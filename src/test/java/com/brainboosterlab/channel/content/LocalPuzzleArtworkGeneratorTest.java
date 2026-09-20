package com.brainboosterlab.channel.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

class LocalPuzzleArtworkGeneratorTest {

    @Test
    void createsIllustratedArtworkWithoutExternalServices() {
        BufferedImage artwork = new LocalPuzzleArtworkGenerator()
                .generate(ContentJob.draft("A friendly clue", "Find the hidden star"));

        assertThat(artwork.getWidth()).isEqualTo(1536);
        assertThat(artwork.getHeight()).isEqualTo(1024);
        assertThat(artwork.getRGB(768, 512)).isNotEqualTo(0);
    }
}
