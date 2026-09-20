package com.brainboosterlab.channel.content;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Renders a complete deterministic prototype video from a generated puzzle script.
 *
 * The artwork is deliberately code-generated so the local workflow remains
 * repeatable and does not require an image-generation service for every render.
 */
@Component
class FfmpegVideoRenderer implements VideoRenderer {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final Color NAVY = new Color(15, 24, 55);
    private static final Color BLUE = new Color(31, 111, 235);
    private static final Color YELLOW = new Color(255, 211, 66);
    private static final Color PINK = new Color(244, 92, 145);
    private static final Color CYAN = new Color(62, 220, 214);
    private static final Color CREAM = new Color(255, 248, 232);

    private final String ffmpegPath;
    private final Path outputDirectory;
    private final PuzzleArtworkGenerator artworkGenerator;

    FfmpegVideoRenderer(
            @Value("${brain-booster.render.ffmpeg-path:ffmpeg}") String ffmpegPath,
            @Value("${brain-booster.render.output-dir:outputs/rendered}") String outputDirectory,
            PuzzleArtworkGenerator artworkGenerator
    ) {
        this.ffmpegPath = ffmpegPath;
        this.outputDirectory = Path.of(outputDirectory);
        this.artworkGenerator = artworkGenerator;
    }

    @Override
    public RenderResult render(ContentJob job) {
        Path workDirectory = null;
        try {
            Files.createDirectories(outputDirectory);
            Path artifact = outputDirectory.resolve(job.getId() + ".mp4").toAbsolutePath();
            workDirectory = Files.createTempDirectory(outputDirectory, "frames-" + job.getId() + "-");
            PuzzleText puzzle = PuzzleText.from(job);
            BufferedImage artwork = artworkGenerator.generate(job);
            List<Frame> frames = createFrames(workDirectory, puzzle, artwork);
            Path concatFile = writeConcatFile(workDirectory, frames);
            List<String> command = List.of(
                    ffmpegPath,
                    "-y",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", concatFile.toString(),
                    "-vf", "fps=30,format=yuv420p",
                    "-c:v", "libx264",
                    "-preset", "medium",
                    "-crf", "22",
                    "-movflags", "+faststart",
                    "-an",
                    "-metadata", "title=" + job.getTitle(),
                    artifact.toString()
            );
            runFfmpeg(command);
            return new RenderResult(artifact.toString(), String.join(" ", command));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to render the puzzle video", exception);
        } finally {
            if (workDirectory != null) {
                deleteWorkDirectory(workDirectory);
            }
        }
    }

    private List<Frame> createFrames(Path workDirectory, PuzzleText puzzle, BufferedImage artwork) throws IOException {
        List<Frame> frames = new ArrayList<>();
        frames.add(writeFrame(workDirectory, "01-title.png", image -> drawTitle(image, puzzle)));
        for (int seconds = 5; seconds >= 1; seconds--) {
            int countdown = seconds;
            frames.add(writeFrame(workDirectory, "0" + (7 - seconds) + "-puzzle-" + seconds + ".png",
                    image -> drawPuzzle(image, puzzle, artwork, countdown, false)));
        }
        frames.add(writeFrame(workDirectory, "07-answer.png", image -> drawPuzzle(image, puzzle, artwork, 0, true)));
        frames.add(writeFrame(workDirectory, "08-cta.png", image -> drawCta(image, puzzle)));
        return frames;
    }

    private Frame writeFrame(Path directory, String filename, ImagePainter painter) throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            painter.paint(graphics);
        } finally {
            graphics.dispose();
        }
        Path path = directory.resolve(filename);
        ImageIO.write(image, "png", path.toFile());
        double durationSeconds = filename.contains("title") ? 2.5
                : filename.contains("puzzle") ? 1.0
                : filename.contains("answer") ? 3.0
                : 2.5;
        return new Frame(path, durationSeconds);
    }

    private Path writeConcatFile(Path directory, List<Frame> frames) throws IOException {
        StringBuilder content = new StringBuilder();
        for (Frame frame : frames) {
            content.append("file '").append(frame.path().toAbsolutePath().toString().replace("'", "'\\''"))
                    .append("'\n")
                    .append("duration ").append(frame.durationSeconds()).append("\n");
        }
        // The concat demuxer needs the final image repeated to honor the last duration.
        Frame last = frames.get(frames.size() - 1);
        content.append("file '").append(last.path().toAbsolutePath().toString().replace("'", "'\\''"))
                .append("'\n");
        Path concatFile = directory.resolve("frames.txt");
        Files.writeString(concatFile, content, StandardCharsets.UTF_8);
        return concatFile;
    }

    private void runFfmpeg(List<String> command) throws IOException {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("FFmpeg exited with " + exitCode + ": " + output);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FFmpeg render was interrupted", exception);
        }
    }

    private void drawTitle(Graphics2D g, PuzzleText puzzle) {
        background(g, new GradientPaint(0, 0, BLUE, WIDTH, HEIGHT, NAVY));
        drawDecorations(g);
        drawPill(g, "BRAIN BOOSTER LAB", 120, 100, 520, 64, YELLOW, NAVY);
        drawCentered(g, puzzle.title(), 960, 390, 86, Font.BOLD, CREAM, 1550);
        drawWrappedCentered(g, puzzle.hook(), 960, 520, 42, Font.PLAIN, CREAM, 1500, 2);
        drawCentered(g, "LOOK CLOSELY • SOLVE THE CLUE", 960, 760, 34, Font.BOLD, YELLOW, 1500);
        drawProgress(g, 0.08);
    }

    private void drawPuzzle(Graphics2D g, PuzzleText puzzle, BufferedImage artwork, int countdown, boolean reveal) {
        background(g, new GradientPaint(0, 0, new Color(27, 49, 105), WIDTH, HEIGHT, NAVY));
        drawPill(g, reveal ? "ANSWER REVEAL" : "YOUR CHALLENGE", 96, 68, 650, 64,
                reveal ? YELLOW : CYAN, NAVY);
        drawWrappedCentered(g, reveal ? puzzle.answer() : puzzle.puzzle(), 830, 160, 32, Font.BOLD, CREAM, 1320, 2);
        drawArtworkScene(g, artwork, reveal);
        if (!reveal) {
            drawCountdown(g, countdown);
            drawCentered(g, "Trust your eyes • the answer is in the scene", 960, 1015, 30, Font.PLAIN, CREAM, 1500);
        } else {
            drawPill(g, "CLUE FOUND • CHECK THE ANSWER ABOVE", 505, 910, 910, 68, YELLOW, NAVY);
            drawCentered(g, "Great eye! Ready for another Brain Booster?", 960, 1015, 30, Font.PLAIN, CREAM, 1500);
        }
    }

    private void drawCta(Graphics2D g, PuzzleText puzzle) {
        background(g, new GradientPaint(0, 0, PINK, WIDTH, HEIGHT, NAVY));
        drawDecorations(g);
        drawPill(g, "BRAIN BOOSTER LAB", 120, 110, 520, 64, YELLOW, NAVY);
        drawCentered(g, "HOW FAST DID YOU FIND IT?", 960, 390, 72, Font.BOLD, CREAM, 1650);
        drawCentered(g, puzzle.cta(), 960, 565, 42, Font.PLAIN, CREAM, 1500);
        drawPill(g, "LIKE • COMMENT • SUBSCRIBE", 610, 745, 700, 72, CYAN, NAVY);
        drawProgress(g, 1.0);
    }

    private void drawArtworkScene(Graphics2D g, BufferedImage artwork, boolean reveal) {
        int x = 150;
        int y = 230;
        int width = 1620;
        int height = 690;
        g.setColor(new Color(8, 15, 38, 180));
        g.fillRoundRect(x - 14, y - 14, width + 28, height + 28, 38, 38);
        g.drawImage(artwork, x, y, width, height, null);
        if (reveal) {
            g.setColor(new Color(255, 211, 66, 90));
            g.setStroke(new BasicStroke(8));
            g.drawRoundRect(x - 2, y - 2, width + 4, height + 4, 24, 24);
        }
    }

    private void drawCountdown(Graphics2D g, int seconds) {
        g.setColor(new Color(15, 24, 55, 235));
        g.fillOval(1570, 62, 220, 220);
        g.setColor(YELLOW);
        g.setStroke(new BasicStroke(12));
        g.drawOval(1583, 75, 194, 194);
        drawCentered(g, Integer.toString(seconds), 1680, 214, 100, Font.BOLD, CREAM, 180);
    }

    private void drawDecorations(Graphics2D g) {
        g.setColor(new Color(255, 255, 255, 30));
        for (int i = 0; i < 18; i++) {
            int x = 70 + ((i * 347) % 1790);
            int y = 80 + ((i * 191) % 900);
            g.fillOval(x, y, 10 + (i % 3) * 6, 10 + (i % 3) * 6);
        }
        drawStar(g, 1640, 800, 56, YELLOW);
        drawStar(g, 260, 830, 38, CYAN);
    }

    private void drawProgress(Graphics2D g, double progress) {
        g.setColor(new Color(255, 255, 255, 70));
        g.fillRoundRect(120, 960, 1680, 16, 8, 8);
        g.setColor(YELLOW);
        g.fillRoundRect(120, 960, (int) (1680 * progress), 16, 8, 8);
    }

    private void background(Graphics2D g, GradientPaint paint) {
        g.setPaint(paint);
        g.fillRect(0, 0, WIDTH, HEIGHT);
    }

    private void drawPill(Graphics2D g, String text, int x, int y, int width, int height, Color fill, Color textColor) {
        g.setColor(fill);
        g.fillRoundRect(x, y, width, height, height, height);
        drawCentered(g, text, x + width / 2, y + height / 2 + 13, 28, Font.BOLD, textColor, width - 34);
    }

    private void drawCentered(Graphics2D g, String text, int centerX, int baseline, int size, int style,
                              Color color, int maxWidth) {
        Font font = new Font("SansSerif", style, size);
        g.setFont(font);
        FontMetrics metrics = g.getFontMetrics();
        String value = text == null || text.isBlank() ? "Brain Booster Lab" : text.trim();
        if (metrics.stringWidth(value) > maxWidth) {
            while (value.length() > 8 && metrics.stringWidth(value + "…") > maxWidth) {
                value = value.substring(0, value.length() - 1);
            }
            value += "…";
        }
        g.setColor(color);
        g.drawString(value, centerX - metrics.stringWidth(value) / 2, baseline);
    }

    private void drawWrappedCentered(Graphics2D g, String text, int centerX, int centerY, int size, int style,
                                     Color color, int maxWidth, int maxLines) {
        Font font = new Font("SansSerif", style, size);
        g.setFont(font);
        FontMetrics metrics = g.getFontMetrics();
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : cleanText(text).split("\\s+")) {
            if (word.isBlank()) {
                continue;
            }
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && metrics.stringWidth(candidate) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        if (lines.isEmpty()) {
            lines.add("Brain Booster Lab");
        }
        if (lines.size() > maxLines) {
            lines = new ArrayList<>(lines.subList(0, maxLines));
            String last = lines.get(maxLines - 1);
            while (metrics.stringWidth(last + "…") > maxWidth && last.length() > 8) {
                last = last.substring(0, last.length() - 1);
            }
            lines.set(maxLines - 1, last + "…");
        }
        int lineHeight = size + 10;
        int firstBaseline = centerY - ((lines.size() - 1) * lineHeight) / 2;
        g.setColor(color);
        for (int index = 0; index < lines.size(); index++) {
            String value = lines.get(index);
            g.drawString(value, centerX - metrics.stringWidth(value) / 2, firstBaseline + index * lineHeight);
        }
    }

    private static String cleanText(String text) {
        return text == null ? "" : text.replaceAll("[*_`#]", "").replaceAll("\\s+", " ").trim();
    }

    private void drawStar(Graphics2D g, int centerX, int centerY, int radius, Color color) {
        Path2D star = new Path2D.Double();
        for (int i = 0; i < 10; i++) {
            double angle = -Math.PI / 2 + i * Math.PI / 5;
            double distance = i % 2 == 0 ? radius : radius * 0.45;
            double x = centerX + Math.cos(angle) * distance;
            double y = centerY + Math.sin(angle) * distance;
            if (i == 0) {
                star.moveTo(x, y);
            } else {
                star.lineTo(x, y);
            }
        }
        star.closePath();
        g.setColor(color);
        g.fill(star);
        g.setColor(new Color(255, 255, 255, 130));
        g.setStroke(new BasicStroke(4));
        g.draw(star);
    }

    private void deleteWorkDirectory(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup; the rendered artifact remains untouched.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup; the rendered artifact remains untouched.
        }
    }

    private record Frame(Path path, double durationSeconds) {
    }

    @FunctionalInterface
    private interface ImagePainter {
        void paint(Graphics2D graphics);
    }

    static record PuzzleText(String title, String hook, String puzzle, String answer, String cta) {
        static PuzzleText from(ContentJob job) {
            String script = job.getScriptText();
            return new PuzzleText(
                    value(script, "TITLE", job.getTitle()),
                    value(script, "HOOK", "Can you spot the clue before the timer ends?"),
                    value(script, "PUZZLE", job.getPrompt()),
                    value(script, "ANSWER", "The hidden star was in tile 7."),
                    value(script, "CTA", "Comment your score and try the next Brain Booster Lab challenge!")
            );
        }

        private static String value(String script, String key, String fallback) {
            if (script == null || script.isBlank()) {
                return fallback;
            }
            StringBuilder result = new StringBuilder();
            boolean capturing = false;
            for (String rawLine : script.lines().toList()) {
                String line = cleanText(rawLine);
                String section = sectionName(line);
                if (section != null) {
                    if (capturing) {
                        break;
                    }
                    if (section.equals(key)) {
                        capturing = true;
                        String remainder = line.substring(key.length()).replaceFirst("^[\\s:–—-]+", "").trim();
                        if (!remainder.isBlank()) {
                            result.append(remainder);
                        }
                    }
                } else if (capturing && !line.isBlank()) {
                    if (result.length() > 0) {
                        result.append(' ');
                    }
                    result.append(line);
                }
            }
            return result.length() == 0 ? fallback : result.toString();
        }

        private static String sectionName(String line) {
            String upper = line.toUpperCase(Locale.ROOT);
            for (String key : List.of("TITLE", "HOOK", "PUZZLE", "PAUSE", "ANSWER", "CTA")) {
                if (upper.equals(key) || upper.startsWith(key + ":") || upper.startsWith(key + " ")
                        || upper.startsWith(key + "-") || upper.startsWith(key + "–")) {
                    return key;
                }
            }
            return null;
        }
    }
}
