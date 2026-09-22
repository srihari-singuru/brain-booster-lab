package com.brainboosterlab.channel.studio;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;

/** Official Google Material icons, bundled locally so rendering never depends on the network. */
final class VideoIcons {
    private static final BufferedImage LIKE = load("like");
    private static final BufferedImage SHARE = load("share");
    private static final BufferedImage SUBSCRIBE = load("subscribe");

    private VideoIcons() {}

    static void like(Graphics2D g, int x, int y, int size) { draw(g, LIKE, x, y, size); }
    static void share(Graphics2D g, int x, int y, int size) { draw(g, SHARE, x, y, size); }
    static void subscribe(Graphics2D g, int x, int y, int size) { draw(g, SUBSCRIBE, x, y, size); }

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
