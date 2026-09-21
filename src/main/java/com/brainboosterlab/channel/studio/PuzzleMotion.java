package com.brainboosterlab.channel.studio;

import java.awt.*;
import java.awt.image.BufferedImage;

final class PuzzleMotion {
    static final int FPS = 30, REVEAL_TICKS = 36, TRANSITION_TICKS = 90;
    /** A three-second colour interlude with a short, legible all-caps cue. */
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
        } finally { g.dispose(); }
        return image;
    }
}
