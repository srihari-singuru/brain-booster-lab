package com.brainboosterlab.channel.content;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import java.time.Duration;
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
        this.client = OpenAIOkHttpClient.builder().fromEnv().timeout(Duration.ofSeconds(120)).maxRetries(0).build();
        this.model = properties.model();
    }

    @Override
    public GeneratedScript generate(ContentJob job) {
        String input = "Create a premium family-friendly Puzzle Pop visual puzzle script.\n"
                + "Title: " + job.getTitle() + "\n"
                + "Creative brief: " + (job.getPrompt() == null ? "Use a visual detective riddle." : job.getPrompt()) + "\n"
                + "Use this exact plain-text structure, with one field per line and no markdown: TITLE:, HOOK:, PUZZLE:, PAUSE:, ANSWER:, CTA:.\n"
                + "Design one fair visual challenge that can be solved from the artwork in five seconds."
                + " The PUZZLE must name one concrete clue object and where to look."
                + " The ANSWER must describe that same object and exact location, with no new object or location."
                + " Keep every field concise, exciting, and easy to read on a phone."
                + " Avoid violence, frightening imagery, impossible trick questions, copyrighted characters, and claims such as '99% fail'.";
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
