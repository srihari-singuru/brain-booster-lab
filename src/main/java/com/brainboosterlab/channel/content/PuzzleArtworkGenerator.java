package com.brainboosterlab.channel.content;

import java.awt.image.BufferedImage;

interface PuzzleArtworkGenerator {

    BufferedImage generate(ContentJob job);
}
