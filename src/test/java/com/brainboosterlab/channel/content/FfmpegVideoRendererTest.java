package com.brainboosterlab.channel.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FfmpegVideoRendererTest {

    @Test
    void parsesLiveModelHeadingsWithOrWithoutColons() {
        ContentJob job = ContentJob.draft("Fallback title", "Fallback prompt");
        job.approve();
        job.startGenerating();
        job.markGenerated(new GeneratedScript("""
                TITLE
                The Clockwork Library

                HOOK:
                Can you find the one object that does not belong?

                PUZZLE
                Find the tiny brass key on the middle bookshelf.

                ANSWER:
                The tiny brass key is tucked behind the blue book on the middle shelf.

                CTA
                Share your score with a friend.
                """, "test", "test-response", null, null));

        FfmpegVideoRenderer.PuzzleText puzzle = FfmpegVideoRenderer.PuzzleText.from(job);

        assertThat(puzzle.title()).isEqualTo("The Clockwork Library");
        assertThat(puzzle.hook()).contains("one object");
        assertThat(puzzle.puzzle()).contains("tiny brass key");
        assertThat(puzzle.answer()).contains("blue book");
        assertThat(puzzle.cta()).contains("Share your score");
    }
}
