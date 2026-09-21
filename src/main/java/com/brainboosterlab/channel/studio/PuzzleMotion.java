package com.brainboosterlab.channel.studio;

import java.awt.*;
import java.awt.image.BufferedImage;

final class PuzzleMotion {
    static final int FPS = 30, REVEAL_TICKS = 36, TRANSITION_TICKS = 60;
    /** A two-second colour interlude with a short, legible all-caps cue and subtle channel calls to action. */
    static BufferedImage transition(BufferedImage before, BufferedImage after, double progress) {
        if (progress <= 0) return before;
        if (progress >= 1) return after;
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
            String title = "NEXT PUZZLE";
            g.setFont(new Font("Arial Black", Font.BOLD, 72));
            int width = g.getFontMetrics().stringWidth(title);
            int x = (1920 - width) / 2, y = 575;
            g.setColor(new Color(12, 24, 66, 220));
            for (int dx = -5; dx <= 5; dx += 5) for (int dy = -5; dy <= 5; dy += 5) g.drawString(title, x + dx, y + dy);
            g.setColor(Color.WHITE); g.drawString(title, x, y);
            drawLike(g, 565, 675, eased);
            drawShare(g, 865, 675, eased);
            drawSubscribe(g, 1165, 675, eased);
        } finally { g.dispose(); }
        return image;
    }

    private static void pill(Graphics2D g, int x, int y, int width, String label, double progress) {
        int lift = (int) Math.round((1 - progress) * 28);
        g.setColor(new Color(8, 21, 67, 180)); g.fillRoundRect(x, y + lift, width, 70, 35, 35);
        g.setColor(new Color(255, 255, 255, 225)); g.setStroke(new BasicStroke(2)); g.drawRoundRect(x, y + lift, width, 70, 35, 35);
        g.setFont(new Font("Arial", Font.BOLD, 26)); g.setColor(Color.WHITE); g.drawString(label, x + 74, y + lift + 45);
    }

    private static void drawLike(Graphics2D g, int x, int y, double progress) {
        pill(g, x, y, 220, "LIKE", progress);
        int lift = (int) Math.round((1 - progress) * 28); g.setColor(new Color(255, 218, 48));
        g.fillRoundRect(x + 28, y + lift + 29, 24, 24, 8, 8); g.fillRoundRect(x + 45, y + lift + 18, 13, 35, 7, 7);
        g.fillRoundRect(x + 53, y + lift + 21, 15, 24, 7, 7);
    }

    private static void drawShare(Graphics2D g, int x, int y, double progress) {
        pill(g, x, y, 230, "SHARE", progress);
        int lift = (int) Math.round((1 - progress) * 28); g.setColor(new Color(70, 234, 220)); g.setStroke(new BasicStroke(4));
        g.drawLine(x + 35, y + lift + 42, x + 54, y + lift + 27); g.drawLine(x + 35, y + lift + 42, x + 56, y + lift + 52);
        g.fillOval(x + 26, y + lift + 33, 18, 18); g.fillOval(x + 47, y + lift + 18, 18, 18); g.fillOval(x + 49, y + lift + 43, 18, 18);
    }

    private static void drawSubscribe(Graphics2D g, int x, int y, double progress) {
        pill(g, x, y, 270, "SUBSCRIBE", progress);
        int lift = (int) Math.round((1 - progress) * 28); g.setColor(new Color(255, 80, 86)); g.fillRoundRect(x + 25, y + lift + 20, 38, 32, 9, 9);
        g.setColor(Color.WHITE); Polygon play = new Polygon(new int[]{x + 40, x + 40, x + 53}, new int[]{y + lift + 27, y + lift + 45, y + lift + 36}, 3); g.fillPolygon(play);
    }
}
