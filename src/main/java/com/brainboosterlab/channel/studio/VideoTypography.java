package com.brainboosterlab.channel.studio;

import java.awt.Font;
import java.io.InputStream;

/** Self-hosted display type keeps the rendered video identical on every local machine. */
final class VideoTypography {
    private static final Font BOWLBY = load();

    private VideoTypography() {}

    static Font display(float size) { return BOWLBY.deriveFont(Font.PLAIN, size); }

    private static Font load() {
        try (InputStream source = VideoTypography.class.getResourceAsStream("/video-fonts/BowlbyOneSC-Regular.ttf")) {
            if (source == null) throw new IllegalStateException("Missing bundled display font");
            return Font.createFont(Font.TRUETYPE_FONT, source);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to load the bundled video display font", ex);
        }
    }
}
