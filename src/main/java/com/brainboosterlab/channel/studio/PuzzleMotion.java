package com.brainboosterlab.channel.studio;

import java.awt.*;
import java.awt.image.BufferedImage;

final class PuzzleMotion {
    static final int WIDTH = 1920, HEIGHT = 1080;
    static final int FPS = 30, REVEAL_TICKS = 36, TRANSITION_TICKS = 90, SPEECH_GAP_TICKS = 15;
    enum Cta { COMMENT, LIKE, OUTRO }

    /** Place only a couple of calls to action in a long episode, not on every transition. */
    static Cta ctaAfter(int puzzleCount, int transitionIndex) {
        int commentIndex = Math.min(1, Math.max(0, puzzleCount - 2));
        if (transitionIndex == commentIndex && puzzleCount >= 2) return Cta.COMMENT;
        if (puzzleCount >= 5 && transitionIndex == 3) return Cta.LIKE;
        return null;
    }

    /** A colourful interlude; one large CTA is used only at the chosen points. */
    static BufferedImage transition(BufferedImage before, BufferedImage after, double progress) {
        return transition(before, after, progress, null);
    }

    static BufferedImage transition(BufferedImage before, BufferedImage after, double progress, Cta cta) {
        if (progress <= 0) return before;
        if (progress >= 1) return after;
        BufferedImage image = background(progress);
        var g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (cta == null) {
                drawPill(g, "NEXT PUZZLE", 64, 498, 1792, 114, 66, Color.WHITE, new Color(255, 213, 50));
            } else {
                drawCta(g, cta, progress, false);
            }
        } finally { g.dispose(); }
        return image;
    }

    static BufferedImage outro(double progress) {
        BufferedImage image = background(progress);
        var g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            drawCta(g, Cta.OUTRO, progress, true);
        } finally { g.dispose(); }
        return image;
    }

    /** Opens on the supplied channel banner, then resolves into a welcome card featuring the logo. */
    static BufferedImage intro(double progress, BufferedImage logo, BufferedImage banner) {
        double p = Math.max(0, Math.min(1, progress));
        double reveal = smoothstep((p - .18) / .12);
        BufferedImage image = background(p);
        var g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setComposite(AlphaComposite.SrcOver.derive((float) (1 - reveal)));
            drawContained(g, banner, 0, 0, WIDTH, HEIGHT);

            g.setComposite(AlphaComposite.SrcOver.derive((float) reveal));
            int lift = (int) Math.round((1 - reveal) * 28);
            int cardX = 250, cardY = 55 + lift, cardW = 1420, cardH = 970;
            g.setColor(new Color(5, 17, 58, 225)); g.fillRoundRect(cardX + 10, cardY + 14, cardW, cardH, 76, 76);
            g.setColor(new Color(24, 204, 224, 220)); g.setStroke(new BasicStroke(5));
            g.drawRoundRect(cardX, cardY, cardW, cardH, 76, 76);

            int logoSize = 520;
            int logoY = cardY + 34;
            double logoScale = .94 + .06 * reveal;
            int animatedSize = (int) Math.round(logoSize * logoScale);
            g.drawImage(logo, (WIDTH - animatedSize) / 2, logoY + (logoSize - animatedSize) / 2,
                animatedSize, animatedSize, null);

            g.setFont(VideoTypography.display(48)); g.setColor(Color.WHITE);
            String welcome = "WELCOME, PUZZLE SOLVERS!";
            FontMetrics welcomeMetrics = g.getFontMetrics();
            g.drawString(welcome, (WIDTH - welcomeMetrics.stringWidth(welcome)) / 2, cardY + 650);

            drawPill(g, "LET'S SOLVE SOME FUN PUZZLES TOGETHER!", cardX + 90, cardY + 690,
                cardW - 180, 104, 43, new Color(255, 207, 38), new Color(8, 24, 72));
            g.setFont(VideoTypography.display(30)); g.setColor(new Color(235, 244, 255));
            String tagline = "LOOK CLOSELY  •  THINK TOGETHER  •  HAVE FUN!";
            FontMetrics taglineMetrics = g.getFontMetrics();
            g.drawString(tagline, (WIDTH - taglineMetrics.stringWidth(tagline)) / 2, cardY + 875);
        } finally { g.dispose(); }
        return image;
    }

    private static void drawContained(Graphics2D g, BufferedImage source, int x, int y, int width, int height) {
        double scale = Math.min(width / (double) source.getWidth(), height / (double) source.getHeight());
        int drawWidth = (int) Math.round(source.getWidth() * scale);
        int drawHeight = (int) Math.round(source.getHeight() * scale);
        g.drawImage(source, x + (width - drawWidth) / 2, y + (height - drawHeight) / 2, drawWidth, drawHeight, null);
    }

    private static double smoothstep(double value) {
        double clamped = Math.max(0, Math.min(1, value));
        return clamped * clamped * (3 - 2 * clamped);
    }

    private static BufferedImage background(double progress) {
        var image = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, new Color(20, 35, 126), 1920, 1080, new Color(199, 26, 121)));
            g.fillRect(0, 0, 1920, 1080);
            double eased = progress * progress * (3 - 2 * progress);
            g.setColor(new Color(18, 207, 218, 125)); g.fillOval((int)(-420 + eased * 240), 85, 760, 760);
            g.setColor(new Color(255, 213, 50, 135)); g.fillOval((int)(1580 - eased * 240), 430, 760, 760);
            g.setColor(new Color(255, 104, 178, 110)); g.fillOval((int)(650 + Math.sin(eased * Math.PI) * 160), -350, 700, 700);
            g.setColor(new Color(255, 255, 255, 180));
            for (int i = 0; i < 16; i++) {
                int x = (int)((90 + i * 139 + eased * 240) % 1920);
                int y = 110 + (i * 97) % 820;
                g.fillOval(x, y, 10, 10);
            }
        } finally { g.dispose(); }
        return image;
    }

    private static void drawCta(Graphics2D g, Cta cta, double progress, boolean outro) {
        int lift = (int) Math.round((1 - progress) * 34);
        int cardX = 270, cardY = 170 + lift, cardW = 1380, cardH = 740;
        g.setColor(new Color(8, 21, 67, 145)); g.fillRoundRect(cardX + 9, cardY + 14, cardW, cardH, 72, 72);
        g.setColor(new Color(255, 255, 255, 244)); g.fillRoundRect(cardX, cardY, cardW, cardH, 72, 72);
        int iconSize = 220, iconX = 850, iconY = cardY + 70;
        switch (cta) {
            case COMMENT -> { VideoIcons.comment(g, iconX, iconY, iconSize); drawPill(g, "TELL US YOUR PICK!", cardX + 90, cardY + 390, cardW - 180, 100, 55, new Color(31, 87, 189), Color.WHITE); }
            case LIKE -> { VideoIcons.like(g, iconX, iconY, iconSize); drawPill(g, "SOLVED IT? TAP LIKE!", cardX + 90, cardY + 390, cardW - 180, 100, 55, new Color(31, 87, 189), Color.WHITE); }
            case OUTRO -> {
                VideoIcons.subscribe(g, iconX - 115, iconY + 15, 190);
                VideoIcons.share(g, iconX + 140, iconY + 15, 190);
                drawPill(g, "SUBSCRIBE + SHARE", cardX + 90, cardY + 390, cardW - 180, 100, 55, new Color(31, 87, 189), Color.WHITE);
            }
        }
        String subline = switch (cta) {
            case COMMENT -> "WHICH CLUE GAVE IT AWAY?";
            case LIKE -> "CELEBRATE YOUR SHARP EYES!";
            case OUTRO -> "MORE FAMILY PUZZLES ARE COMING!";
        };
        g.setFont(VideoTypography.display(34)); g.setColor(new Color(45, 54, 75));
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(subline, (1920 - metrics.stringWidth(subline)) / 2, cardY + 590);
        if (outro) {
            g.setFont(VideoTypography.display(25)); g.setColor(new Color(31, 87, 189));
            String brand = "PUZZLE POP";
            g.drawString(brand, (1920 - g.getFontMetrics().stringWidth(brand)) / 2, cardY + 665);
        }
    }

    private static void drawPill(Graphics2D g, String label, int x, int y, int width, int height, int fontSize, Color background, Color foreground) {
        g.setColor(new Color(13, 25, 38, 25)); g.fillRoundRect(x + 2, y + 6, width, height, height / 2, height / 2);
        g.setColor(background); g.fillRoundRect(x, y, width, height, height / 2, height / 2);
        g.setFont(VideoTypography.display(fontSize)); g.setColor(foreground);
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(label, x + (width - metrics.stringWidth(label)) / 2, y + (height - metrics.getHeight()) / 2 + metrics.getAscent());
    }
}
