package com.brainboosterlab.channel.studio;
import java.awt.Rectangle;

public final class ImageLayout {
    private ImageLayout() {}
    /** Contain without clipping evidence or changing proportions. */
    public static Rectangle contain(int sourceWidth, int sourceHeight, Rectangle box) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || box.width <= 0 || box.height <= 0)
            throw new IllegalArgumentException("Image and box dimensions must be positive");
        double scale = Math.min((double) box.width / sourceWidth, (double) box.height / sourceHeight);
        int w = Math.max(1, (int) Math.round(sourceWidth * scale));
        int h = Math.max(1, (int) Math.round(sourceHeight * scale));
        return new Rectangle(box.x + (box.width - w) / 2, box.y + (box.height - h) / 2, w, h);
    }

    /** Cover a viewport without changing proportions; callers must choose a safe crop. */
    public static Rectangle cover(int sourceWidth, int sourceHeight, Rectangle box) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || box.width <= 0 || box.height <= 0)
            throw new IllegalArgumentException("Image and box dimensions must be positive");
        double scale = Math.max((double) box.width / sourceWidth, (double) box.height / sourceHeight);
        int w = Math.max(1, (int) Math.round(sourceWidth * scale));
        int h = Math.max(1, (int) Math.round(sourceHeight * scale));
        return new Rectangle(box.x + (box.width - w) / 2, box.y + (box.height - h) / 2, w, h);
    }
}
