package com.brainboosterlab.channel.content;

import com.openai.client.OpenAIClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.brainboosterlab.channel.OpenAiClientFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "brain-booster.generation.mode", havingValue = "live")
class OpenAiPuzzleScriptGenerator implements PuzzleScriptGenerator {

    private final OpenAIClient client;
    private final String model;

    OpenAiPuzzleScriptGenerator(GenerationProperties properties) {
        if (properties.model() == null || properties.model().isBlank()) {
            throw new IllegalStateException("OPENAI_MODEL must be set when GENERATION_MODE=live");
        }
        this.client = OpenAiClientFactory.create(java.time.Duration.ofSeconds(120));
        this.model = properties.model();
    }

    @Override
    public GeneratedScript generate(ContentJob job) {
        String input = "Create one original, premium family-friendly Puzzle Pop visual mini-mystery.\n"
                + "Title: " + job.getTitle() + "\n"
                + "Creative brief: " + (job.getPrompt() == null ? "Tell a short, playful story that leads to one fair visual mystery." : job.getPrompt()) + "\n"
                + "Use this exact plain-text structure, with one field per line and no markdown: TITLE:, HOOK:, PUZZLE:, PAUSE:, ANSWER:, CTA:.\n"
                + "Write a brisk story setup, one direct question, three or four plausible OPTION choices (A/B/C or A/B/C/D),"
                + " a fair medium-difficulty inference for children 6–18 solving with parents, and a satisfying answer reveal."
                + " The thinking round is exactly ten seconds; story speech length is separate and can follow its measured voice duration."
                + " The visible clue must causally prove the exact action asked about: its mark/contact must be on the surface and in the place the action would actually affect."
                + " A matching color or pattern alone is not proof. State any simple fantasy rule and show it in the scene."
                + " Use simple spoken English and vivid, safe, varied stories. Never repeat or lightly rephrase the prior puzzle history or reference transcript."
                + " Keep every field concise, exciting, and easy to understand. Avoid violence, frightening imagery, impossible trick questions, copyrighted characters, and claims such as '99% fail'.";
        ResponseCreateParams params = ResponseCreateParams.builder()
                .model(model)
                .input(input)
                .build();
        Response response = client.responses().create(params);
        String text = response.output().stream()
                .flatMap(item -> item.message().stream())
                .flatMap(message -> message.content().stream())
                .flatMap(content -> content.outputText().stream())
                .map(outputText -> outputText.text())
                .reduce("", String::concat);
        return new GeneratedScript(text, model, response.id(), null, null);
    }
}
