package com.brainboosterlab.channel.content;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import javax.imageio.ImageIO;

import com.openai.client.OpenAIClient;
import com.openai.models.images.ImageGenerateParams;
import com.openai.models.images.Image;
import com.openai.models.images.ImagesResponse;
import com.brainboosterlab.channel.OpenAiClientFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "brain-booster.artwork.mode", havingValue = "live")
class OpenAiPuzzleArtworkGenerator implements PuzzleArtworkGenerator {

    private final OpenAIClient client;
    private final ArtworkProperties properties;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    OpenAiPuzzleArtworkGenerator(ArtworkProperties properties) {
        if (properties.model() == null || properties.model().isBlank()) {
            throw new IllegalStateException("OPENAI_IMAGE_MODEL must be set when ARTWORK_MODE=live");
        }
        this.client = OpenAiClientFactory.create(Duration.ofSeconds(120));
        this.properties = properties;
    }

    @Override
    public BufferedImage generate(ContentJob job) {
        String prompt = "Create a polished premium 16:9 family illustration that depicts the exact story and visual challenge in the generated script below. "
                + "Use crisp expressive linework, layered depth, warm cinematic light, tactile materials, rich harmonious color, and clear focal hierarchy. "
                + "Do not add a stock detective room, robot, cast, props, or clue unless the supplied story explicitly calls for them. "
                + "Show exactly the story's candidates and place its decisive evidence visibly and naturally in the scene. "
                + "The clue must causally support the exact event asked about: the visible trace/contact must appear on the surface and in the place the action would affect. "
                + "Do not substitute a similar-looking color or pattern for proof, and do not add an unrelated clue. "
                + "Do not add words, letters, logos, watermarks, UI, borders, or copyrighted characters. "
                + "Keep all candidates and proof inside a wide central safe area for 16:9 video. "
                + "Creative brief: " + (job.getPrompt() == null ? "Find one hidden object in the scene." : job.getPrompt())
                + " Generated puzzle script: " + (job.getScriptText() == null ? "" : job.getScriptText());
        ImageGenerateParams params = ImageGenerateParams.builder()
                .model(properties.model())
                .prompt(prompt)
                .size("1536x1024")
                .quality(ImageGenerateParams.Quality.HIGH)
                .n(1)
                .build();
        ImagesResponse response = client.images().generate(params);
        Image image = response.data()
                .flatMap(images -> images.stream().findFirst())
                .orElseThrow(() -> new IllegalStateException("OpenAI image response did not include image data"));
        try {
            if (image.b64Json().isPresent()) {
                return decodeBytes(Base64.getDecoder().decode(image.b64Json().get()));
            }
            if (image.url().isPresent()) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(image.url().get()))
                        .timeout(Duration.ofSeconds(60))
                        .GET()
                        .build();
                HttpResponse<byte[]> downloaded = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (downloaded.statusCode() / 100 != 2) {
                    throw new IllegalStateException("OpenAI image URL returned HTTP " + downloaded.statusCode());
                }
                return decodeBytes(downloaded.body());
            }
            throw new IllegalStateException("OpenAI image response did not include base64 data or a URL");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("OpenAI image response was not valid base64", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to decode OpenAI artwork", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI artwork download was interrupted", exception);
        }
    }

    private BufferedImage decodeBytes(byte[] bytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IllegalStateException("OpenAI image response could not be decoded");
        }
        return image;
    }
}
