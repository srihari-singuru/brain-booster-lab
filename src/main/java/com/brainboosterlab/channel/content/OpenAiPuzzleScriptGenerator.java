package com.brainboosterlab.channel.content;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
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
        this.client = OpenAIOkHttpClient.fromEnv();
        this.model = properties.model();
    }

    @Override
    public GeneratedScript generate(ContentJob job) {
        String input = "Create a family-friendly Brain Booster Lab puzzle video script.\n"
                + "Title: " + job.getTitle() + "\n"
                + "Creative brief: " + (job.getPrompt() == null ? "Use a visual detective riddle." : job.getPrompt()) + "\n"
                + "Use this exact structure: TITLE, HOOK, PUZZLE, PAUSE, ANSWER, CTA."
                + " Avoid violence, frightening imagery, and copyrighted characters.";
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
