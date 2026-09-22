package com.brainboosterlab.channel.studio;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.Locale;
import static com.brainboosterlab.channel.studio.StudioRenderer.*;

/** Branded puzzle board with deterministic lettering, never generated or stretched text. */
final class KidsFrameRenderer {
    private static final Color YELLOW = new Color(255, 222, 39);
    private static final Color OUTLINE = new Color(15, 23, 35);
    private static final Color CORRECT = new Color(113, 244, 153);
    /* A large, edge-to-edge picture window makes the artwork the star of every frame. */
    static final Rectangle ART_STAGE = new Rectangle(160, 145, 1600, 900);
    private static final Rectangle ART_PANEL = new Rectangle(144, 129, 1632, 932);

    static Rectangle artBox(int width, int height) {
        // Keep the complete scene visible in its 16:9 stage rather than cropping puzzle evidence.
        if (width <= 0 || height <= 0 || Math.abs((double) width / height - 16.0 / 9) > .005)
            throw new IllegalArgumentException("Puzzle-board artwork must be 16:9; stretching could hide a clue.");
        return new Rectangle(ART_STAGE);
    }

    static Rectangle badgeBox(int index, int count) {
        return badgeBox(index, count, ART_STAGE);
    }

    static Rectangle badgeBox(int index, int count, Rectangle imageBox) {
        // Artwork generation reserves a clean strip above each left-to-right subject.
        // A single letter marker is readable on a phone without hiding the clue below.
        int centerX = imageBox.x + (2 * index + 1) * imageBox.width / (2 * count);
        return new Rectangle(centerX - 50, imageBox.y + 52, 100, 100);
    }

    static BufferedImage frame(EpisodeSpec.Puzzle p, BufferedImage art, int index, String phase, int countdown, boolean draft) {
        return frame(p, art, index, phase, countdown, draft, null, 1.2);
    }

    static BufferedImage frame(EpisodeSpec.Puzzle p, BufferedImage art, int index, String phase, int countdown, boolean draft,
                               SceneOverlay overlay, double revealTime) {
        return frame(p, art, index, phase, countdown, draft, overlay, revealTime, "BRAIN BOOSTER LAB", 3);
    }

    static BufferedImage frame(EpisodeSpec.Puzzle p, BufferedImage art, int index, String phase, int countdown, boolean draft,
                               SceneOverlay overlay, double revealTime, String channelName, int puzzleCount) {
        var canvas = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            boolean reveal = "reveal".equals(phase);
            drawBoard(g, art, reveal, revealTime);
            var stage = artBox(art.getWidth(), art.getHeight());
            var fit = ImageLayout.contain(art.getWidth(), art.getHeight(), stage);
            g.setColor(new Color(8, 31, 70, 120)); g.fillRoundRect(ART_PANEL.x - 8, ART_PANEL.y + 8, ART_PANEL.width + 16, ART_PANEL.height + 16, 34, 34);
            g.setColor(Color.WHITE); g.fillRoundRect(ART_PANEL.x, ART_PANEL.y, ART_PANEL.width, ART_PANEL.height, 30, 30);
            Shape clip = g.getClip();
            g.clip(new Rectangle(fit));
            g.drawImage(art, fit.x, fit.y, fit.width, fit.height, null);
            g.setClip(clip);
            g.setColor(new Color(12, 32, 66)); g.setStroke(new BasicStroke(4));
            g.drawRoundRect(ART_PANEL.x, ART_PANEL.y, ART_PANEL.width, ART_PANEL.height, 30, 30);

            headlineCentered(g, reveal ? "ANSWER " + p.answerId() + "!" : p.question().toUpperCase(Locale.ROOT),
                new Rectangle(205, 18, 1510, 112), 92, 42, reveal ? YELLOW : Color.WHITE, 8);
            drawPuzzleNumber(g, index + 1);

            for (int i = 0; i < p.choices().size(); i++) {
                var choice = p.choices().get(i);
                boolean correct = reveal && choice.id().equals(p.answerId());
                var b = badgeBox(i, p.choices().size(), fit);
                g.setColor(new Color(6, 24, 50, 132)); g.fillOval(b.x + 5, b.y + 7, b.width, b.height);
                if (correct) {
                    double pulse = revealTime < 1.2 ? 8 * Math.sin(Math.PI * Math.min(1, revealTime / 1.2)) : 0;
                    g.setColor(CORRECT); g.setStroke(new BasicStroke(6));
                    g.drawOval((int)(b.x - 8 - pulse), (int)(b.y - 8 - pulse),
                        (int)(b.width + 16 + 2 * pulse), (int)(b.height + 16 + 2 * pulse));
                }
                g.setColor(correct ? CORRECT : YELLOW); g.fillOval(b.x, b.y, b.width, b.height);
                g.setColor(OUTLINE); g.setStroke(new BasicStroke(5)); g.drawOval(b.x, b.y, b.width, b.height);
                centered(g, choice.id(), b, 62, OUTLINE);
            }
            if (reveal && overlay != null) {
                int answerIndex = 0;
                for (int i = 0; i < p.choices().size(); i++) if (p.choices().get(i).id().equals(p.answerId())) answerIndex = i;
                drawClue(g, keepClueOnStage(overlay.clue().bounds(fit), stage), revealTime,
                    badgeBox(answerIndex, p.choices().size(), fit));
            }
            if (countdown > 0 && !reveal) {
                var timer = new Rectangle(1768, 25, 102, 102);
                g.setColor(OUTLINE); g.fillOval(timer.x, timer.y, timer.width, timer.height);
                g.setColor(Color.WHITE); g.setStroke(new BasicStroke(4)); g.drawOval(timer.x, timer.y, timer.width, timer.height);
                centered(g, Integer.toString(countdown), timer, 53, YELLOW);
                g.setColor(YELLOW); g.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawArc(timer.x - 7, timer.y - 7, 116, 116, 90,
                    -(int)(360.0 * countdown / VISUAL_QUESTION_SECONDS));
            }
            drawSideBrand(g, 66, 540, true, channelName);
            drawSideBrand(g, 1854, 540, false, channelName);
            drawPlayBadge(g, 32, 728);
            drawPlayBadge(g, 1818, 728);
            if (draft) headline(g, "DRAFT", new Rectangle(30, 1020, 100, 28), 22, 18, Color.WHITE, 3);
        } finally { g.dispose(); }
        return canvas;
    }

    private static void drawBoard(Graphics2D g, BufferedImage art, boolean reveal, double seconds) {
        BoardPalette palette = paletteFor(art, reveal);
        Color left = palette.left(), right = palette.right();
        g.setPaint(new GradientPaint(0, 0, left, WIDTH, HEIGHT, right));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        double drift = Math.sin(seconds * 2.2) * 18;
        g.setColor(new Color(255, 255, 255, 33));
        for (int row = 0; row < 7; row++) for (int column = 0; column < 11; column++) {
            int x = (int)(60 + column * 185 + (row % 2) * 70 + drift);
            int y = 45 + row * 157;
            Polygon diamond = new Polygon(new int[]{x, x + 24, x, x - 24}, new int[]{y - 24, y, y + 24, y}, 4);
            g.fillPolygon(diamond);
        }
        g.setColor(new Color(255, 255, 255, 62)); g.setStroke(new BasicStroke(4));
        g.drawRoundRect(18, 18, WIDTH - 36, HEIGHT - 36, 30, 30);
    }

    /** Use the artwork's most common lively hue so each puzzle has its own branded board. */
    private static BoardPalette paletteFor(BufferedImage art, boolean reveal) {
        int[] hues = new int[24];
        for (int y = 0; y < art.getHeight(); y += 24) for (int x = 0; x < art.getWidth(); x += 24) {
            int rgb = art.getRGB(x, y);
            float[] hsb = Color.RGBtoHSB((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, null);
            if (hsb[1] >= .22f && hsb[2] >= .25f) hues[Math.min(23, (int)(hsb[0] * 24))] += 1 + (int)(hsb[1] * 3);
        }
        int winner = 13;
        for (int i = 1; i < hues.length; i++) if (hues[i] > hues[winner]) winner = i;
        float hue = (winner + .5f) / hues.length;
        float second = (hue + (reveal ? .10f : .055f)) % 1f;
        float brightness = reveal ? .69f : .80f;
        return new BoardPalette(
            new Color(Color.HSBtoRGB(hue, reveal ? .56f : .60f, brightness)),
            new Color(Color.HSBtoRGB(second, reveal ? .62f : .66f, Math.min(.90f, brightness + .04f))));
    }

    private record BoardPalette(Color left, Color right) {}

    private static void drawPuzzleNumber(Graphics2D g, int number) {
        int cx = 98, cy = 74, outer = 64, inner = 49, spikes = 14;
        int[] xs = new int[spikes * 2], ys = new int[spikes * 2];
        for (int i = 0; i < xs.length; i++) {
            double angle = -Math.PI / 2 + i * Math.PI / spikes;
            int radius = i % 2 == 0 ? outer : inner;
            xs[i] = cx + (int)Math.round(Math.cos(angle) * radius);
            ys[i] = cy + (int)Math.round(Math.sin(angle) * radius);
        }
        g.setColor(new Color(14, 31, 60)); g.setStroke(new BasicStroke(8, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawPolygon(xs, ys, xs.length);
        g.setColor(YELLOW); g.fillPolygon(xs, ys, xs.length);
        g.setColor(new Color(14, 31, 60)); g.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawPolygon(xs, ys, xs.length);
        centered(g, Integer.toString(number), new Rectangle(cx - 42, cy - 42, 84, 84), 66, OUTLINE);
    }

    private static void drawSideBrand(Graphics2D g, int x, int centerY, boolean left, String channelName) {
        AffineTransform transform = g.getTransform();
        try {
            g.rotate(left ? -Math.PI / 2 : Math.PI / 2, x, centerY);
            String name = channelName.toUpperCase(Locale.ROOT);
            g.setFont(VideoTypography.display(30));
            int width = g.getFontMetrics().stringWidth(name);
            int baseline = centerY + (g.getFontMetrics().getAscent() - g.getFontMetrics().getDescent()) / 2;
            g.setColor(new Color(10, 47, 94, 120)); g.drawString(name, x - width / 2 + 3, baseline + 3);
            g.setColor(new Color(255, 255, 255, 235)); g.drawString(name, x - width / 2, baseline);
        } finally { g.setTransform(transform); }
    }

    /** A locally bundled, official Google Material subscription icon—not a YouTube affiliation badge. */
    private static void drawPlayBadge(Graphics2D g, int x, int y) {
        g.setColor(new Color(9, 39, 79, 80)); g.fillOval(x + 3, y + 5, 60, 60);
        g.setColor(new Color(255, 255, 255, 235)); g.fillOval(x, y, 60, 60);
        VideoIcons.subscribe(g, x + 14, y + 14, 32);
    }

    /** Never let the animated ring be clipped at the lower edge of a full-width scene stage. */
    static java.awt.geom.Rectangle2D keepClueOnStage(java.awt.geom.Rectangle2D clue, Rectangle stage) {
        double margin = 42;
        double width = Math.min(clue.getWidth(), stage.getWidth() - 2 * margin);
        double height = Math.min(clue.getHeight(), stage.getHeight() - 2 * margin);
        double x = Math.max(stage.getX() + margin, Math.min(clue.getX(), stage.getMaxX() - margin - width));
        double y = Math.max(stage.getY() + margin, Math.min(clue.getY(), stage.getMaxY() - margin - height));
        return new java.awt.geom.Rectangle2D.Double(x, y, width, height);
    }

    private static void drawClue(Graphics2D g, java.awt.geom.Rectangle2D b, double time, Rectangle badge) {
        double progress = Math.max(0, Math.min(1, time / .8));
        if (progress == 0) return;
        double pulse = 1 + .10 * Math.sin(Math.PI * Math.min(1, time / 1.2));
        double cx = b.getCenterX(), cy = b.getCenterY();
        double diameter = Math.max(b.getWidth(), b.getHeight()) * 1.22 * pulse;
        var ring = new java.awt.geom.Rectangle2D.Double(cx - diameter / 2, cy - diameter / 2, diameter, diameter);
        // A generous, circular double halo reads instantly on a phone while leaving the clue pixels unobscured.
        g.setColor(new Color(255, 221, 56, 105)); g.setStroke(new BasicStroke(36, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new java.awt.geom.Ellipse2D.Double(ring.getX(), ring.getY(), ring.getWidth(), ring.getHeight()));
        var arc = new java.awt.geom.Arc2D.Double(ring, 90, -360 * progress, java.awt.geom.Arc2D.OPEN);
        g.setStroke(new BasicStroke(24, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(12, 20, 34, 220)); g.draw(arc);
        g.setStroke(new BasicStroke(12, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(YELLOW); g.draw(arc);
    }

    /** Fit glyphs by choosing a font size, never by distorting their aspect ratio. */
    static void headline(Graphics2D g, String text, Rectangle box, int maximum, int minimum, Color fill, float stroke) {
        for (int size = maximum; size >= minimum; size--) {
            var font = VideoTypography.display(size);
            Shape glyphs = font.createGlyphVector(g.getFontRenderContext(), text).getOutline();
            var bounds = glyphs.getBounds2D();
            if (bounds.getWidth() + stroke > box.width || bounds.getHeight() + stroke > box.height) continue;
            var placed = AffineTransform.getTranslateInstance(box.x + stroke / 2 - bounds.getX(),
                box.y + stroke / 2 - bounds.getY()).createTransformedShape(glyphs);
            g.setColor(OUTLINE); g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(placed); g.setColor(fill); g.fill(placed);
            return;
        }
        throw new IllegalArgumentException("Overlay cannot fit legibly without distortion: " + text);
    }

    static void headlineCentered(Graphics2D g, String text, Rectangle box, int maximum, int minimum, Color fill, float stroke) {
        for (int size = maximum; size >= minimum; size--) {
            var font = VideoTypography.display(size);
            Shape glyphs = font.createGlyphVector(g.getFontRenderContext(), text).getOutline();
            var bounds = glyphs.getBounds2D();
            if (bounds.getWidth() + stroke > box.width || bounds.getHeight() + stroke > box.height) continue;
            double x = box.x + (box.width - bounds.getWidth()) / 2 - bounds.getX();
            double y = box.y + (box.height - bounds.getHeight()) / 2 - bounds.getY();
            var placed = AffineTransform.getTranslateInstance(x, y).createTransformedShape(glyphs);
            g.setColor(OUTLINE); g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(placed); g.setColor(fill); g.fill(placed);
            return;
        }
        throw new IllegalArgumentException("Centered overlay cannot fit legibly without distortion: " + text);
    }

    private static void centered(Graphics2D g, String text, Rectangle box, int size, Color color) {
        var font = VideoTypography.display(size);
        var glyphs = font.createGlyphVector(g.getFontRenderContext(), text).getOutline();
        var bounds = glyphs.getBounds2D();
        g.setColor(color);
        g.fill(AffineTransform.getTranslateInstance(box.getCenterX() - bounds.getCenterX(),
            box.getCenterY() - bounds.getCenterY()).createTransformedShape(glyphs));
    }
}
