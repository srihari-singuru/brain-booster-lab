package com.brainboosterlab.channel.content;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;

import javax.imageio.ImageIO;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.images.ImageGenerateParams;
import com.openai.models.images.ImagesResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "brain-booster.artwork.mode", havingValue = "live")
class OpenAiPuzzleArtworkGenerator implements PuzzleArtworkGenerator {

    private final OpenAIClient client;
    private final ArtworkProperties properties;

    OpenAiPuzzleArtworkGenerator(ArtworkProperties properties) {
        if (properties.model() == null || properties.model().isBlank()) {
            throw new IllegalStateException("OPENAI_IMAGE_MODEL must be set when ARTWORK_MODE=live");
        }
        this.client = OpenAIOkHttpClient.fromEnv();
        this.properties = properties;
    }

    @Override
    public BufferedImage generate(ContentJob job) {
        String prompt = "Create a cheerful family-friendly editorial cartoon illustration for a Brain Booster Lab visual puzzle. "
                + "Show a colorful mystery room with two friendly child detectives, a small helper robot, a desk, "
                + "bookshelf, magnifying glass, notebook, lamp, and playful objects. "
                + "Use clean bold outlines, bright blue, yellow, pink, cyan, and purple, with no words, letters, logos, "
                + "or copyrighted characters. Leave enough open space for local video captions. "
                + "Creative brief: " + (job.getPrompt() == null ? "Find a hidden object in the scene." : job.getPrompt());
        ImageGenerateParams params = ImageGenerateParams.builder()
                .model(properties.model())
                .prompt(prompt)
                .size("1536x1024")
                .quality(ImageGenerateParams.Quality.MEDIUM)
                .responseFormat(ImageGenerateParams.ResponseFormat.B64_JSON)
                .n(1)
                .build();
        ImagesResponse response = client.images().generate(params);
        String encoded = response.data()
                .flatMap(images -> images.stream().findFirst())
                .flatMap(image -> image.b64Json())
                .orElseThrow(() -> new IllegalStateException("OpenAI image response did not include base64 image data"));
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
            if (image == null) {
                throw new IllegalStateException("OpenAI image response could not be decoded");
            }
            return image;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("OpenAI image response was not valid base64", exception);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to decode OpenAI artwork", exception);
        }
    }
}
