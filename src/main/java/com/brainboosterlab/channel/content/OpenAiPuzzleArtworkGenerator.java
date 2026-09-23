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
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.images.ImageGenerateParams;
import com.openai.models.images.Image;
import com.openai.models.images.ImagesResponse;
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
        this.client = OpenAIOkHttpClient.builder().fromEnv().timeout(Duration.ofSeconds(120)).maxRetries(0).build();
        this.properties = properties;
    }

    @Override
    public BufferedImage generate(ContentJob job) {
        String prompt = "Create a polished premium family editorial illustration for a Puzzle Pop visual puzzle. "
                + "Use the visual quality of a high-end animated feature key frame: confident composition, crisp expressive linework, "
                + "layered depth, cinematic soft lighting, tactile materials, rich but harmonious color, and clear focal hierarchy. "
                + "Show a welcoming family-friendly mystery room with two distinct friendly detectives, a small helper robot, "
                + "a desk, bookshelf, magnifying glass, notebook, lamp, and several believable props. "
                + "The puzzle clue must be a single, clearly drawable object that is present exactly once and is subtly integrated "
                + "into the scene so it is fair but not immediately obvious. Follow the PUZZLE and ANSWER fields below as ground truth. "
                + "Do not add words, letters, logos, watermarks, UI, borders, or copyrighted characters. "
                + "Keep the important action in the central safe area for a 16:9 video crop. "
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
