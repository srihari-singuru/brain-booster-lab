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

    FfmpegVideoRenderer(
            @Value("${brain-booster.render.ffmpeg-path:ffmpeg}") String ffmpegPath,
            @Value("${brain-booster.render.output-dir:outputs/rendered}") String outputDirectory
    ) {
        this.ffmpegPath = ffmpegPath;
        this.outputDirectory = Path.of(outputDirectory);
    }

    @Override
    public RenderResult render(ContentJob job) {
        Path workDirectory = null;
        try {
            Files.createDirectories(outputDirectory);
            Path artifact = outputDirectory.resolve(job.getId() + ".mp4").toAbsolutePath();
            workDirectory = Files.createTempDirectory(outputDirectory, "frames-" + job.getId() + "-");
            PuzzleText puzzle = PuzzleText.from(job);
            List<Frame> frames = createFrames(workDirectory, puzzle);
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

    private List<Frame> createFrames(Path workDirectory, PuzzleText puzzle) throws IOException {
        List<Frame> frames = new ArrayList<>();
        frames.add(writeFrame(workDirectory, "01-title.png", image -> drawTitle(image, puzzle)));
        for (int seconds = 5; seconds >= 1; seconds--) {
            int countdown = seconds;
            frames.add(writeFrame(workDirectory, "0" + (7 - seconds) + "-puzzle-" + seconds + ".png",
                    image -> drawPuzzle(image, puzzle, countdown, false)));
        }
        frames.add(writeFrame(workDirectory, "07-answer.png", image -> drawPuzzle(image, puzzle, 0, true)));
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
        drawCentered(g, puzzle.hook(), 960, 540, 42, Font.PLAIN, CREAM, 1500);
        drawCentered(g, "GET READY • FIND THE HIDDEN STAR", 960, 760, 34, Font.BOLD, YELLOW, 1500);
        drawProgress(g, 0.08);
    }

    private void drawPuzzle(Graphics2D g, PuzzleText puzzle, int countdown, boolean reveal) {
        background(g, new GradientPaint(0, 0, new Color(27, 49, 105), WIDTH, HEIGHT, NAVY));
        drawPill(g, reveal ? "ANSWER REVEAL" : "FIND THE HIDDEN STAR", 96, 68, 650, 64,
                reveal ? YELLOW : CYAN, NAVY);
        drawCentered(g, reveal ? puzzle.answer() : puzzle.puzzle(), 960, 172, 34, Font.BOLD, CREAM, 1640);
        drawPuzzleBoard(g, reveal);
        if (!reveal) {
            drawCountdown(g, countdown);
            drawCentered(g, "Look closely — one tile is different!", 960, 1015, 30, Font.PLAIN, CREAM, 1500);
        } else {
            drawPill(g, "THE STAR WAS HIDING IN TILE 7", 565, 910, 790, 68, YELLOW, NAVY);
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

    private void drawPuzzleBoard(Graphics2D g, boolean reveal) {
        int boardX = 330;
        int boardY = 255;
        int tile = 205;
        int gap = 28;
        Color[] colors = {PINK, YELLOW, CYAN, new Color(151, 112, 245)};
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 4; col++) {
                int index = row * 4 + col + 1;
                int x = boardX + col * (tile + gap);
                int y = boardY + row * (tile + gap);
                g.setColor(new Color(255, 255, 255, 24));
                g.fillRoundRect(x, y, tile, tile, 34, 34);
                g.setColor(colors[(index - 1) % colors.length]);
                if (index == 7) {
                    drawStar(g, x + tile / 2, y + tile / 2, 67, YELLOW);
                    if (reveal) {
                        g.setColor(YELLOW);
                        g.setStroke(new BasicStroke(10));
                        g.drawRoundRect(x - 9, y - 9, tile + 18, tile + 18, 44, 44);
                    }
                } else if (index % 3 == 0) {
                    g.fillOval(x + 58, y + 58, 90, 90);
                    g.setColor(new Color(255, 255, 255, 80));
                    g.fillOval(x + 80, y + 76, 22, 22);
                } else if (index % 3 == 1) {
                    g.fillRoundRect(x + 58, y + 58, 90, 90, 22, 22);
                    g.setColor(new Color(255, 255, 255, 85));
                    g.fillOval(x + 78, y + 76, 22, 22);
                } else {
                    Path2D triangle = new Path2D.Double();
                    triangle.moveTo(x + 103, y + 48);
                    triangle.lineTo(x + 157, y + 151);
                    triangle.lineTo(x + 49, y + 151);
                    triangle.closePath();
                    g.fill(triangle);
                    g.setColor(new Color(255, 255, 255, 85));
                    g.fillOval(x + 92, y + 94, 22, 22);
                }
            }
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

    private record PuzzleText(String title, String hook, String puzzle, String answer, String cta) {
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
            return script.lines()
                    .filter(line -> line.startsWith(key + ":"))
                    .map(line -> line.substring(key.length() + 1).trim())
                    .findFirst()
                    .filter(value -> !value.isBlank())
                    .orElse(fallback);
        }
    }
}
