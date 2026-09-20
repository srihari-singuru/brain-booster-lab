package com.brainboosterlab.channel.content;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Keyless fallback artwork. It keeps local development useful while preserving
 * the same contract as the OpenAI artwork provider.
 */
@Component
@ConditionalOnProperty(name = "brain-booster.artwork.mode", havingValue = "mock", matchIfMissing = true)
class LocalPuzzleArtworkGenerator implements PuzzleArtworkGenerator {

    private static final int WIDTH = 1536;
    private static final int HEIGHT = 1024;
    private static final Color INK = new Color(31, 35, 67);
    private static final Color CREAM = new Color(255, 248, 232);
    private static final Color PINK = new Color(244, 92, 145);
    private static final Color YELLOW = new Color(255, 211, 66);
    private static final Color CYAN = new Color(62, 220, 214);
    private static final Color PURPLE = new Color(127, 93, 223);

    @Override
    public BufferedImage generate(ContentJob job) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            drawScene(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    private void drawScene(Graphics2D g) {
        g.setPaint(new GradientPaint(0, 0, new Color(116, 199, 255), WIDTH, HEIGHT, new Color(235, 216, 255)));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(new Color(255, 255, 255, 110));
        g.fillOval(112, 102, 180, 70);
        g.fillOval(232, 82, 190, 90);
        g.fillOval(1120, 126, 200, 78);
        g.fillOval(1230, 100, 190, 100);

        g.setColor(YELLOW);
        g.fillOval(1220, 62, 120, 120);
        g.setColor(new Color(255, 255, 255, 150));
        g.fillOval(1250, 92, 28, 28);

        g.setColor(new Color(121, 197, 139));
        g.fillRect(0, 730, WIDTH, 294);
        g.setColor(new Color(93, 163, 112));
        g.fillOval(-120, 655, 460, 230);
        g.fillOval(1170, 660, 480, 240);

        drawTree(g, 145, 460, 1.0);
        drawBookshelf(g, 1185, 322);
        drawDesk(g, 420, 675);
        drawDetective(g, 292, 490, PINK, "M");
        drawDetective(g, 975, 485, PURPLE, "B");
        drawRobot(g, 760, 530);
        drawMagnifyingGlass(g, 680, 650);
        drawNotebook(g, 560, 739);
        drawLamp(g, 900, 604);
    }

    private void drawTree(Graphics2D g, int x, int y, double scale) {
        g.setColor(new Color(119, 76, 50));
        g.fillRoundRect(x + 80, y + 150, 55, 260, 28, 28);
        g.setColor(new Color(60, 173, 112));
        g.fillOval(x - 15, y + 70, 180, 165);
        g.setColor(new Color(83, 191, 129));
        g.fillOval(x + 75, y, 170, 180);
        g.fillOval(x - 60, y + 14, 170, 170);
        g.setColor(YELLOW);
        g.fillOval(x + 40, y + 88, 24, 24);
    }

    private void drawBookshelf(Graphics2D g, int x, int y) {
        g.setColor(new Color(129, 79, 61));
        g.fillRoundRect(x, y, 230, 420, 22, 22);
        g.setColor(new Color(248, 198, 126));
        g.fillRect(x + 24, y + 25, 182, 10);
        g.fillRect(x + 24, y + 140, 182, 10);
        g.fillRect(x + 24, y + 255, 182, 10);
        g.fillRect(x + 24, y + 370, 182, 10);
        Color[] books = {PINK, CYAN, YELLOW, PURPLE};
        for (int shelf = 0; shelf < 3; shelf++) {
            for (int i = 0; i < 4; i++) {
                g.setColor(books[(shelf + i) % books.length]);
                g.fillRoundRect(x + 35 + i * 38, y + 48 + shelf * 115, 28, 80, 8, 8);
            }
        }
        g.setColor(CYAN);
        g.fillOval(x + 90, y + 315, 50, 50);
    }

    private void drawDesk(Graphics2D g, int x, int y) {
        g.setColor(new Color(136, 82, 56));
        g.fillRoundRect(x, y, 700, 120, 24, 24);
        g.setColor(new Color(107, 61, 46));
        g.fillRoundRect(x + 45, y + 90, 45, 230, 18, 18);
        g.fillRoundRect(x + 610, y + 90, 45, 230, 18, 18);
        g.setColor(new Color(250, 219, 170));
        g.fillRoundRect(x + 110, y + 20, 480, 20, 10, 10);
    }

    private void drawDetective(Graphics2D g, int x, int y, Color shirt, String badge) {
        g.setColor(shirt);
        g.fillRoundRect(x, y + 160, 180, 240, 70, 70);
        g.setColor(new Color(255, 207, 167));
        g.fillOval(x + 35, y, 110, 125);
        g.setColor(new Color(71, 45, 40));
        g.fillOval(x + 33, y - 12, 115, 50);
        g.setColor(INK);
        g.fillOval(x + 68, y + 58, 10, 14);
        g.fillOval(x + 105, y + 58, 10, 14);
        g.setColor(CREAM);
        g.fillRoundRect(x + 68, y + 86, 50, 12, 8, 8);
        g.setColor(YELLOW);
        g.fillOval(x + 76, y + 197, 36, 36);
        g.setColor(INK);
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.drawString(badge, x + 87, y + 223);
        g.setColor(shirt.darker());
        g.fillRoundRect(x + 5, y + 380, 62, 22, 10, 10);
        g.fillRoundRect(x + 113, y + 380, 62, 22, 10, 10);
    }

    private void drawRobot(Graphics2D g, int x, int y) {
        g.setColor(CYAN);
        g.fillRoundRect(x, y, 150, 170, 30, 30);
        g.setColor(INK);
        g.fillOval(x + 33, y + 55, 24, 24);
        g.fillOval(x + 94, y + 55, 24, 24);
        g.setColor(PINK);
        g.fillRoundRect(x + 44, y + 112, 62, 16, 8, 8);
        g.setColor(INK);
        g.setStroke(new BasicStroke(8));
        g.drawLine(x + 75, y, x + 75, y - 35);
        g.setColor(YELLOW);
        g.fillOval(x + 63, y - 52, 24, 24);
        g.setColor(PURPLE);
        g.fillRoundRect(x - 22, y + 62, 24, 66, 12, 12);
        g.fillRoundRect(x + 148, y + 62, 24, 66, 12, 12);
    }

    private void drawMagnifyingGlass(Graphics2D g, int x, int y) {
        g.setColor(CREAM);
        g.fillOval(x, y, 100, 100);
        g.setColor(PURPLE);
        g.setStroke(new BasicStroke(18));
        g.drawOval(x + 10, y + 10, 80, 80);
        g.drawLine(x + 82, y + 82, x + 145, y + 145);
        g.setColor(new Color(255, 255, 255, 100));
        g.fillOval(x + 35, y + 25, 22, 22);
    }

    private void drawNotebook(Graphics2D g, int x, int y) {
        g.setColor(CREAM);
        g.fillRoundRect(x, y, 170, 120, 12, 12);
        g.setColor(PINK);
        g.fillRect(x + 16, y + 18, 12, 84);
        g.setColor(INK);
        g.setStroke(new BasicStroke(5));
        g.drawLine(x + 50, y + 38, x + 145, y + 38);
        g.drawLine(x + 50, y + 65, x + 130, y + 65);
        g.drawLine(x + 50, y + 92, x + 150, y + 92);
    }

    private void drawLamp(Graphics2D g, int x, int y) {
        g.setColor(YELLOW);
        Path2D shade = new Path2D.Double();
        shade.moveTo(x, y);
        shade.lineTo(x + 130, y);
        shade.lineTo(x + 105, y + 95);
        shade.lineTo(x + 25, y + 95);
        shade.closePath();
        g.fill(shade);
        g.setColor(INK);
        g.setStroke(new BasicStroke(10));
        g.drawLine(x + 65, y + 95, x + 65, y + 172);
        g.drawLine(x + 20, y + 172, x + 110, y + 172);
    }
}
