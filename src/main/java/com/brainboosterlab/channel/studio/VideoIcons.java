package com.brainboosterlab.channel.studio;

import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.AlphaComposite;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;

/** Official Google Material icons, bundled locally so rendering never depends on the network. */
final class VideoIcons {
    private static final BufferedImage LIKE = load("like");
    private static final BufferedImage SHARE = load("share");
    private static final BufferedImage SUBSCRIBE = load("subscribe");
    private static final BufferedImage LIKE_COLOR = tint(LIKE, new Color(29, 122, 226));
    private static final BufferedImage SHARE_COLOR = tint(SHARE, new Color(18, 157, 170));
    private static final BufferedImage SUBSCRIBE_COLOR = tint(SUBSCRIBE, new Color(242, 45, 64));

    private VideoIcons() {}

    static void like(Graphics2D g, int x, int y, int size) { draw(g, LIKE_COLOR, x, y, size); }
    static void share(Graphics2D g, int x, int y, int size) { draw(g, SHARE_COLOR, x, y, size); }
    static void subscribe(Graphics2D g, int x, int y, int size) { draw(g, SUBSCRIBE_COLOR, x, y, size); }

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

    private static BufferedImage load(String name) {
        try (InputStream source = VideoIcons.class.getResourceAsStream("/video-icons/" + name + ".png")) {
            if (source == null) throw new IllegalStateException("Missing bundled video icon: " + name);
            BufferedImage image = ImageIO.read(source);
            if (image == null) throw new IllegalStateException("Unreadable bundled video icon: " + name);
            return image;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to load bundled video icon: " + name, ex);
        }
    }
}
