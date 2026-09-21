package com.brainboosterlab.channel.studio;

import java.awt.geom.Rectangle2D;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import tools.jackson.databind.json.JsonMapper;

/** Editorial clue annotation, bound to the exact artwork and answer (not guessed from prose). */
record SceneOverlay(String artworkSha256, String answerId, Region clue) {
    public record Region(double x, double y, double width, double height) {
        void validate() {
            if (!Double.isFinite(x + y + width + height) || x < .01 || y < .01
                || width <= 0 || height <= 0 || x + width > .99 || y + height > .99)
                throw new IllegalArgumentException("Clue region must fit inside the image using normalized 0–1 coordinates");
        }
        Rectangle2D bounds() { return new Rectangle2D.Double(x * 1920, y * 1080, width * 1920, height * 1080); }
        Rectangle2D bounds(java.awt.Rectangle imageBox) {
            return new Rectangle2D.Double(imageBox.x + x * imageBox.width, imageBox.y + y * imageBox.height,
                width * imageBox.width, height * imageBox.height);
        }
    }
    static String hash(Path art) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(art)));
    }
    static SceneOverlay read(Path dir, int index, EpisodeSpec.Puzzle puzzle) throws Exception {
        Path file = dir.resolve("overlay-" + index + ".json");
        if (!Files.exists(file)) return null;
        var overlay = JsonMapper.builder().build().readValue(Files.readString(file), SceneOverlay.class);
        if (overlay.clue == null || !puzzle.answerId().equals(overlay.answerId)
            || !hash(dir.resolve("art-" + index + ".png")).equals(overlay.artworkSha256))
            throw new IllegalArgumentException("Clue annotation does not match this artwork and answer; review its placement");
        overlay.clue.validate();
        return overlay;
    }
}
