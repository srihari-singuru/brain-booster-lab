package com.brainboosterlab.channel.studio;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.stereotype.Component;

/** Deterministic thumbnail composition from approved episode artwork; no image-generation call or extra image credit. */
@Component
class YouTubeThumbnailRenderer {
    static final int WIDTH = 3840, HEIGHT = 2160;
    private static final int DESIGN_WIDTH = 1280, DESIGN_HEIGHT = 720;
    private static final double SCALE = WIDTH / (double) DESIGN_WIDTH;
    private static final long MOBILE_SAFE_BYTES = 1_950_000;

    void render(EpisodeSpec spec, Path episodeDir, String channel, YouTubeUploadPack pack) throws Exception {
        for (int i = 0; i < pack.variants().size(); i++) {
            var option = pack.variants().get(i);
            BufferedImage art = ImageIO.read(episodeDir.resolve("art-" + (option.puzzleNumber() - 1) + ".png").toFile());
            if (art == null) throw new IllegalStateException("Puzzle " + option.puzzleNumber() + " artwork is not readable");
            BufferedImage image = compose(art, channel, option.thumbnailText(), i);
            Path target = episodeDir.resolve("youtube-thumbnail-" + (i + 1) + ".jpg");
            writeMobileFriendlyJpeg(image, target);
            if (!Files.isRegularFile(target) || Files.size(target) == 0) throw new IllegalStateException("The thumbnail image could not be saved");
        }
    }

    static BufferedImage compose(BufferedImage art, String channel, String copy, int variant) {
        BufferedImage out = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.scale(SCALE, SCALE);
            double scale = Math.max((double) DESIGN_WIDTH / art.getWidth(), (double) DESIGN_HEIGHT / art.getHeight());
            int width = (int) Math.ceil(art.getWidth() * scale), height = (int) Math.ceil(art.getHeight() * scale);
            int x = (DESIGN_WIDTH - width) / 2, y = (DESIGN_HEIGHT - height) / 2;
            g.drawImage(art, x, y, width, height, null);
            // Preserve the scene on the right while creating a dependable headline zone on the left.
            g.setPaint(new GradientPaint(0, 0, new Color(7, 15, 42, 226), 820, 0, new Color(7, 15, 42, 0)));
            g.fillRect(0, 0, 900, DESIGN_HEIGHT);
            Color accent = switch (Math.floorMod(variant, 3)) {
                case 1 -> new Color(31, 216, 213); case 2 -> new Color(255, 96, 135); default -> new Color(255, 199, 55);
            };
            g.setColor(accent);
            g.fill(new RoundRectangle2D.Double(65, 75, 12, HEIGHT - 150, 12, 12));
            g.setFont(font(Font.BOLD, 25));
            g.setColor(Color.WHITE);
            g.drawString(channel.toUpperCase(java.util.Locale.ROOT), 104, 107);
            g.setColor(new Color(255, 255, 255, 195));
            g.fill(new RoundRectangle2D.Double(104, 125, 240, 3, 3, 3));
            Font headline = font(Font.BOLD, 72);
            String[] lines = fitLines(copy.toUpperCase(java.util.Locale.ROOT), headline, g.getFontRenderContext(), 810, 72);
            int lineHeight = 84, textHeight = lines.length * lineHeight;
            int top = Math.max(215, Math.min(DESIGN_HEIGHT - textHeight - 80, (DESIGN_HEIGHT - textHeight) / 2 + 30));
            g.setFont(headline);
            // A single restrained dark backplate keeps copy readable on busy art without hiding the scene.
            int widest = 0;
            for (String line : lines) widest = Math.max(widest, g.getFontMetrics().stringWidth(line));
            int plateWidth = Math.min(850, widest + 56);
            g.setColor(new Color(5, 13, 37, 205));
            g.fill(new RoundRectangle2D.Double(104, top - 26, plateWidth, textHeight + 44, 24, 24));
            g.setColor(accent);
            g.fill(new RoundRectangle2D.Double(104, top - 26, 9, textHeight + 44, 9, 9));
            int baseline = top + g.getFontMetrics().getAscent();
            for (String line : lines) {
                g.setColor(new Color(0, 0, 0, 150)); g.drawString(line, 129 + 3, baseline + 4);
                g.setColor(Color.WHITE); g.drawString(line, 129, baseline);
                baseline += lineHeight;
            }
            g.setFont(font(Font.BOLD, 20));
            g.setColor(new Color(255, 255, 255, 238));
            g.drawString("FAMILY PUZZLE CHALLENGE", 106, DESIGN_HEIGHT - 69);
        } finally { g.dispose(); }
        return out;
    }

    private static void writeMobileFriendlyJpeg(BufferedImage image, Path target) throws Exception {
        for (float quality : new float[]{.92f, .84f, .76f}) {
            var writers = ImageIO.getImageWritersByFormatName("jpg");
            if (!writers.hasNext()) throw new IllegalStateException("JPEG image support is unavailable");
            var writer = writers.next();
            try (ImageOutputStream stream = ImageIO.createImageOutputStream(Files.newOutputStream(target))) {
                writer.setOutput(stream);
                ImageWriteParam params = writer.getDefaultWriteParam();
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(quality);
                writer.write(null, new IIOImage(image, null, null), params);
            } finally { writer.dispose(); }
            if (Files.size(target) <= MOBILE_SAFE_BYTES) return;
        }
    }

    private static String[] fitLines(String text, Font font, FontRenderContext frc, int maxWidth, int maxChars) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) normalized = "SOLVE THIS PUZZLE";
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : normalized.split(" ")) {
            if (current.length() > 0 && (font.getStringBounds(current + " " + word, frc).getWidth() > maxWidth
                || current.length() + word.length() + 1 > maxChars)) {
                lines.add(current.toString()); current.setLength(0);
            }
            if (current.length() > 0) current.append(' ');
            current.append(word);
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines.stream().limit(3).toArray(String[]::new);
    }

    private static Font font(int style, float size) {
        try (InputStream in = YouTubeThumbnailRenderer.class.getResourceAsStream("/video-fonts/BowlbyOneSC-Regular.ttf")) {
            if (in != null) return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(style, size);
        } catch (Exception ignored) { }
        return new Font(Font.SANS_SERIF, style, Math.round(size));
    }
}
