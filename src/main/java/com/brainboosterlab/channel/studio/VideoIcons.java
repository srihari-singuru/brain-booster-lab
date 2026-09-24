package com.brainboosterlab.channel.studio;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import javax.imageio.ImageIO;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

/** Crisp, locally bundled icons for video-rendered calls to action. */
final class VideoIcons {
    private static final int RASTER_SIZE = 512;
    private static final BufferedImage LIKE = tint(loadSvg("like"), new Color(29, 122, 226));
    private static final BufferedImage SHARE = tint(loadSvg("share"), new Color(18, 157, 170));
    private static final BufferedImage COMMENT = tint(loadSvg("comment"), new Color(37, 110, 226));
    private static final BufferedImage YOUTUBE = loadPng("youtube");

    private VideoIcons() {}

    static void like(Graphics2D g, int x, int y, int size) { draw(g, LIKE, x, y, size); }
    static void share(Graphics2D g, int x, int y, int size) { draw(g, SHARE, x, y, size); }
    static void comment(Graphics2D g, int x, int y, int size) { draw(g, COMMENT, x, y, size); }

    /** Draw the official full-colour YouTube play mark without stretching or recolouring it. */
    static void subscribe(Graphics2D g, int x, int y, int size) { youtubeMark(g, x, y, size, size); }

    private static BufferedImage tint(BufferedImage source, Color color) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.drawImage(source, 0, 0, null);
            g.setComposite(AlphaComposite.SrcIn);
            g.setColor(color);
            g.fillRect(0, 0, result.getWidth(), result.getHeight());
        } finally { g.dispose(); }
        return result;
    }

    private static void draw(Graphics2D g, BufferedImage icon, int x, int y, int size) {
        g.drawImage(icon, x, y, size, size, null);
    }

    static void youtubeMark(Graphics2D g, int x, int y, int boxWidth, int boxHeight) {
        double scale = Math.min((double) boxWidth / YOUTUBE.getWidth(), (double) boxHeight / YOUTUBE.getHeight());
        int drawWidth = Math.max(1, (int) Math.round(YOUTUBE.getWidth() * scale));
        int drawHeight = Math.max(1, (int) Math.round(YOUTUBE.getHeight() * scale));
        g.drawImage(YOUTUBE, x + (boxWidth - drawWidth) / 2, y + (boxHeight - drawHeight) / 2, drawWidth, drawHeight, null);
    }

    private static BufferedImage loadSvg(String name) {
        String resource = "/video-icons/" + name + ".svg";
        try (InputStream source = VideoIcons.class.getResourceAsStream(resource);
             ByteArrayOutputStream raster = new ByteArrayOutputStream()) {
            if (source == null) throw new IllegalStateException("Missing bundled video icon: " + resource);
            PNGTranscoder transcoder = new PNGTranscoder();
            transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) RASTER_SIZE);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) RASTER_SIZE);
            transcoder.transcode(new TranscoderInput(source), new TranscoderOutput(raster));
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(raster.toByteArray()));
            if (image == null) throw new IllegalStateException("Unable to rasterize bundled video icon: " + resource);
            return image;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to load bundled video icon: " + resource, ex);
        }
    }

    private static BufferedImage loadPng(String name) {
        String resource = "/video-icons/" + name + ".png";
        try (InputStream source = VideoIcons.class.getResourceAsStream(resource)) {
            if (source == null) throw new IllegalStateException("Missing bundled video icon: " + resource);
            BufferedImage image = ImageIO.read(source);
            if (image == null) throw new IllegalStateException("Unreadable bundled video icon: " + resource);
            return image;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to load bundled video icon: " + resource, ex);
        }
    }
}
