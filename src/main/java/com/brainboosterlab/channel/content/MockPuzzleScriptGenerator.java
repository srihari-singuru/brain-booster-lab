package com.brainboosterlab.channel.content;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "brain-booster.generation.mode", havingValue = "mock", matchIfMissing = true)
class MockPuzzleScriptGenerator implements PuzzleScriptGenerator {

    @Override
    public GeneratedScript generate(ContentJob job) {
        String script = "TITLE: " + job.getTitle() + "\n"
                + "HOOK: Can you spot the clue before the timer ends?\n"
                + "PUZZLE: " + (job.getPrompt() == null ? "Find the hidden object in the scene." : job.getPrompt()) + "\n"
                + "PAUSE: Give viewers ten seconds to solve it.\n"
                + "ANSWER: Reveal the clue and explain the observation that solves it.\n"
                + "CTA: Comment your score and try the next Brain Booster Lab challenge!";
        return new GeneratedScript(script, "mock", "mock-" + job.getId(), null, null);
    }
}
