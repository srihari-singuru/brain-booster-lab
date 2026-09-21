package com.brainboosterlab.channel.studio;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.*;
import com.openai.models.images.ImageGenerateParams;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class StudioAi {
    record Draft(EpisodeSpec spec, String model, String responseId) {}
    public record Finding(int puzzleNumber, String independentlySolvedAnswerId, boolean fair, String notes) {}
    public record Review(List<Finding> findings) {
        boolean passes(EpisodeSpec spec) {
            if (findings == null || findings.size() != spec.puzzles().size()) return false;
            for (int i = 0; i < spec.puzzles().size(); i++) {
                Finding f = findings.get(i);
                if (f == null || f.puzzleNumber() != i + 1 || !f.fair()
                    || !spec.puzzles().get(i).answerId().equals(f.independentlySolvedAnswerId())) return false;
            }
            return true;
        }
    }
    public record VisualReview(boolean acceptable, String notes) {}
    /** A clue rectangle returned relative to the full final 1920×1080 question frame. */
    public record ClueLocation(double x, double y, double width, double height, String notes) {
        SceneOverlay.Region onArtwork() {
            // The complete 16:9 artwork is rendered inside this fixed safe scene area.
            double left = (x * 1920 - 160) / 1600;
            double top = (y * 1080 - 156) / 900;
            double wide = width * 1920 / 1600;
            double high = height * 1080 / 900;
            if (!Double.isFinite(left + top + wide + high)) throw new IllegalArgumentException("Clue location is not finite");
            wide = Math.max(.06, Math.min(.55, wide)); high = Math.max(.06, Math.min(.55, high));
            left = Math.max(.01, Math.min(.99 - wide, left)); top = Math.max(.01, Math.min(.99 - high, top));
            var region = new SceneOverlay.Region(left, top, wide, high);
            region.validate();
            return region;
        }
    }
    record Provenance(String model, String size, String quality, Instant generatedAt, String prompt, boolean placeholder) {}
    private final String generationMode;
    private final String artworkMode;
    final String model;
    final String imageModel;
    private final OpenAIClient client;
    private final JsonMapper json = JsonMapper.builder().build();

    StudioAi(@Value("${brain-booster.generation.mode:mock}") String generationMode,
             @Value("${brain-booster.artwork.mode:mock}") String artworkMode,
             @Value("${brain-booster.generation.model:}") String model,
             @Value("${brain-booster.artwork.model:}") String imageModel) {
        this.generationMode = generationMode;
        this.artworkMode = artworkMode;
        this.model = model;
        this.imageModel = imageModel;
        this.client = ("live".equals(generationMode) || "live".equals(artworkMode))
            ? OpenAIOkHttpClient.builder().fromEnv().maxRetries(0).build() : null;
    }

    Draft generate(String brief) {
        return generate(brief, 3, model);
    }

    Draft generate(String brief, int puzzleCount, String requestedModel) {
        return generate(brief, puzzleCount, requestedModel, "");
    }

    Draft generate(String brief, int puzzleCount, String requestedModel, String operatorDirection) {
        EpisodeSpec.require(puzzleCount >= 1 && puzzleCount <= 10, "Choose between 1 and 10 puzzles");
        String activeModel = requestedModel == null || requestedModel.isBlank() ? model : requestedModel.trim();
        if (!"live".equals(generationMode)) return new Draft(PilotFixtures.kids(), "local-fixture", "none");
        String prompt = """
            Create %d original illustrated mini-mysteries for a family channel, ages roughly 6–10
            watching with family. Set kind="visual" for ALL THREE. Aim for an achievable visual aha:
            one observation, one simple inference, one clear answer among A/B/C. First puzzle is a
            welcoming confidence-builder; later clues slightly less obvious but never tiny or tricky.
            NO arithmetic, number patterns, time calculations, truth tables, long alibis or schoolwork.
            NO tiny hidden-object searches. Examples: three friends drinking cocoa, one friendly ghost
            has no shadow; one friend wears a homemade cardboard robot costume; one toy animal has a
            winding key. Friendly fantasy, never frightening or implying real people are nonhuman.
            Never use skin color, disability, body differences or cultural appearance as an alien clue.
            A mechanical/prosthetic limb does not prove someone is nonhuman. Use an unmistakable
            handmade costume clue instead when ordinary robot-looking body parts would be ambiguous.
            Avoid trivially obvious category differences. For a cardboard robot costume puzzle, show
            THREE similar robot outfits; only one has an exposed corrugated edge. Do not put only
            one child in a costume beside two ordinary shirts. The visible detail should reward looking.
            If using ghosts/shadows/reflections, facts must contain ONE short explicitly fictional
            world rule, e.g. 'In this magic cafe, only ghosts have no shadow.' Do not present folklore
            as science. Avoid mirrors unless the geometry and mapping can be completely unambiguous.
            All subjects equally inviting, no obviously monstrous correct answer. The clue is large
            enough to see on a phone, not so huge it spoils the moment. Avoid alternate explanations.
            question: max10 words AND60 characters. facts: zero or one line, max65 characters;
            only an essential story rule, never a paragraph or solution. setup: spoken introduction
            for FUTURE narration, max155 characters; not displayed as a paragraph in the video.
            choices: exactly A,B,C in left/center/right order; label a name/color max24 characters;
            statement max100 characters describing the subject's appearance for production, NOT a
            spoken alibi or caption. Do NOT reveal the clue in the label. explanation: a warm,
            concrete reveal, max14 words AND85 characters. title max48, episode title max65.
            sceneDescription: max1600 characters. Specify exactly three subjects in the left,
            middle and right thirds, full clue visibility, exact clue and which subject owns it,
            correct ordinary counterparts on the other two, no confusing props or extra subjects.
            NO text or badges in sceneDescription: the application adds all A/B/C labels outside the art.
            The artwork itself MUST carry the evidence; the written answer is not proof that the
            image succeeded. One image is reused unchanged during question and answer.
            Keep the refined 2D illustrated style and rich teal/amber/coral palette, with appealing
            people and everyday settings. For thumbnail readability, use a vivid but natural jewel
            palette, luminous key light, strong color separation between subjects, crisp silhouettes,
            and a clean focal clue. The result should feel energetic at small phone size without
            neon skin, plastic 3D rendering or visual noise. Do not copy channel characters or designs.
            thinkSeconds10–15.
            Original creative brief follows:
            """.formatted(puzzleCount) + brief + operatorSuffix(operatorDirection);
        var response = client.responses().create(ResponseCreateParams.builder().model(activeModel).input(prompt)
            .store(false).reasoning(Reasoning.builder().effort(ReasoningEffort.HIGH).build())
            .maxOutputTokens(12000).text(EpisodeSpec.class).build());
        EpisodeSpec spec = response.output().stream().flatMap(i -> i.message().stream())
            .flatMap(m -> m.content().stream()).flatMap(c -> c.outputText().stream()).findFirst()
            .orElseThrow(() -> new IllegalStateException("No complete structured episode returned"));
        // Persist the paid structured response before local/independent validation in the service.
        EpisodeSpec.require(spec.puzzles().size() == puzzleCount, "The script returned the wrong number of puzzles; retry generation");
        return new Draft(spec, activeModel, response.id());
    }

    Review review(EpisodeSpec spec) {
        return review(spec, model);
    }

    Review review(EpisodeSpec spec, String requestedModel) {
        return review(spec, requestedModel, "");
    }

    Review review(EpisodeSpec spec, String requestedModel, String operatorDirection) {
        String activeModel = requestedModel == null || requestedModel.isBlank() ? model : requestedModel.trim();
        if (!"live".equals(generationMode)) return new Review(java.util.stream.IntStream.range(0,spec.puzzles().size())
            .mapToObj(i -> new Finding(i+1,spec.puzzles().get(i).answerId(),true,
                "Offline fixture only; NOT an independent AI review.")).toList());
        // Do not give the critic the proposed answers or explanations: solve independently first.
        var questions = spec.puzzles().stream().map(p -> java.util.Map.of(
            "setup", p.setup(), "question", p.question(), "facts", p.facts(), "choices", p.choices(),
            "scenePlan", "visual".equals(p.kind()) ? p.sceneDescription() : "Not applicable")).toList();
        String prompt = "Independently solve these " + spec.puzzles().size() + " reasoning puzzles in order. For each return puzzleNumber in order starting at 1, "
            + "independentlySolvedAnswerId A/B/C (or NONE if ambiguous), fair boolean, and notes explaining proof "
            + "and why alternatives fail. Reject ambiguity, unstated necessary facts, harmful stereotypes, "
            + "claims that lying proves guilt and tiny object hunts. For visual mini-mysteries, solve from the planned "
            + "scene, checking one visible clue and one simple inference appropriate to ages 6–10; reject homework-like "
            + "reasoning, unsafe stereotypes and unstated supernatural rules. This is only a concept check; an actual-image "
            + "blind visual check follows later. Judge family suitability. "
            + "This is an adversarial review, not a request to endorse. Questions: " + json.writeValueAsString(questions)
            + operatorSuffix(operatorDirection);
        var response = client.responses().create(ResponseCreateParams.builder().model(activeModel).input(prompt)
            .store(false).reasoning(Reasoning.builder().effort(ReasoningEffort.HIGH).build())
            .maxOutputTokens(10000).text(Review.class).build());
        return response.output().stream().flatMap(i -> i.message().stream()).flatMap(m -> m.content().stream())
            .flatMap(c -> c.outputText().stream()).findFirst().orElseThrow();
    }

    void artwork(EpisodeSpec.Puzzle puzzle, Path directory, int index) throws Exception {
        artwork(puzzle, directory, index, imageModel);
    }

    void artwork(EpisodeSpec.Puzzle puzzle, Path directory, int index, String requestedImageModel) throws Exception {
        artwork(puzzle, directory, index, requestedImageModel, "");
    }

    void artwork(EpisodeSpec.Puzzle puzzle, Path directory, int index, String requestedImageModel, String operatorDirection) throws Exception {
        String activeImageModel = requestedImageModel == null || requestedImageModel.isBlank() ? imageModel : requestedImageModel.trim();
        String prompt = """
            Create one premium 16:9 landscape editorial illustration for an original family reasoning show.
            ART BIBLE v2: sophisticated clean 2D mystery-comic illustration; controlled ink contours with
            varied line weight, flat-to-soft cel shading, believable anatomy, naturally expressive faces,
            detailed but uncluttered backgrounds. Use a vivid jewel-toned palette (electric teal,
            royal blue, warm amber, coral, magenta accents), luminous key light and strong
            foreground/background separation. Colors should remain natural on children's screens,
            never muddy, gray, pastel-washed or neon skin.
            Audience: families and curious adults, not preschool. No 3D plastic, clip-art, chibi characters,
            decorative puzzle shapes, airbrushed stock art or exaggerated guilty expressions.
            Compose as a wide establishing frame, fill the canvas with the actual scene. Put important
            heads and objects in the middle vertical band (35–75% of canvas height), not on the edges.
            Top 28% and bottom 22% are background scenery reserved for application text overlays.
            Confident readable silhouettes; medium-wide camera framing, no extreme face closeups.
            Leave lettering to the application: NO text, numbers, labels, speech bubbles, titles, border,
            logo or watermark anywhere. No answer highlights. Do not show concealed contents.
            Exact scene brief:
            """ + puzzle.sceneDescription();
        if ("visual".equals(puzzle.kind())) prompt = """
            Use case: illustration-story. Create a premium 16:9 children's visual mini-mystery.
            Keep the polished 2D mystery-comic style: natural anatomy, refined ink contours, rich teal,
            warm amber and coral, atmospheric light, charming people and detailed but calm scenery.
            Exactly THREE main subjects, one in each equal left/center/right third. No other people.
            Full bodies and any floor/shadow evidence fully inside the image. No cropping of clues.
            Do not reserve large text panels: the app puts a short question and A/B/C outside the art.
            Evidence has to be genuinely visible and coherent, not simply described in a prompt.
            One clear visual clue, easy-to-medium for ages 6–10, readable at phone size. Other two
            subjects must clearly lack that anomaly. Preserve equal expressions so faces don't give
            the answer away. Friendly make-believe, never scary. No text, labels, numbers, logos,
            watermarks, arrows, rings or answer highlights. No plastic 3D or preschool clip-art.
            The following scene plan is authoritative, especially its shadows/reflections/robot details:
            """ + puzzle.sceneDescription();
        prompt += "\nFinal lettering constraint: do NOT draw A/B/C badges or any text, even if the scene brief mentions them. The application alone adds those labels."
            + operatorSuffix(operatorDirection);
        Path artwork = directory.resolve("art-" + index + ".png");
        if (Files.exists(artwork)) return; // Paid output is immutable and reused on retries/renders.
        if (!"live".equals(artworkMode)) {
            var placeholder = new BufferedImage(1536, 864, BufferedImage.TYPE_INT_RGB);
            var g = placeholder.createGraphics();
            g.setColor(new Color(24, 39, 57)); g.fillRect(0, 0, 1536, 864);
            g.setColor(Color.WHITE); g.setFont(new Font("SansSerif", Font.BOLD, 42));
            g.drawString("OFFLINE LAYOUT TEST — NOT FINAL ARTWORK", 150, 420); g.dispose();
            ImageIO.write(placeholder, "png", artwork.toFile());
        } else {
            var result = client.images().generate(ImageGenerateParams.builder().model(activeImageModel)
                .prompt(prompt).size("1536x864").quality(ImageGenerateParams.Quality.HIGH).n(1).build());
            var generated = result.data().flatMap(d -> d.stream().findFirst()).orElseThrow();
            byte[] bytes = Base64.getDecoder().decode(generated.b64Json()
                .orElseThrow(() -> new IllegalStateException("Image response omitted base64 artwork; no asset saved")));
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            if (decoded == null) throw new IllegalStateException("Image response is not a readable image");
            Path pending = directory.resolve("art-" + index + ".pending.png");
            ImageIO.write(decoded, "png", pending.toFile());
            Files.move(pending, artwork, StandardCopyOption.ATOMIC_MOVE);
        }
        Files.writeString(directory.resolve("provenance-" + index + ".json"), json.writeValueAsString(
            new Provenance("live".equals(artworkMode) ? activeImageModel : "offline-placeholder", "1536x864", "high",
                Instant.now(), prompt, !"live".equals(artworkMode))));
    }

    VisualReview reviewFrame(Path frame, EpisodeSpec.Puzzle puzzle) throws Exception {
        return reviewFrame(frame, puzzle, model);
    }

    VisualReview reviewFrame(Path frame, EpisodeSpec.Puzzle puzzle, String requestedModel) throws Exception {
        String activeModel = requestedModel == null || requestedModel.isBlank() ? model : requestedModel.trim();
        if (!"live".equals(generationMode)) return new VisualReview(false, "Offline mode: human visual review required.");
        if ("visual".equals(puzzle.kind())) return reviewVisualMystery(frame, puzzle, activeModel);
        String prompt = "Review this final question frame for a family reasoning video. Assess readable complete text, "
            + "natural image proportions, coherent anatomy, no obvious graphic defects, no accidental answer reveal, "
            + "and correct left/center/right mapping of any depicted A/B/C subjects. Text must not cover faces. Return acceptable=false "
            + "for material defects. Do not mistake contextual artwork for evidence; written facts are authoritative. "
            + "Do not rubber-stamp. Intended puzzle: " + json.writeValueAsString(puzzle);
        var input = EasyInputMessage.builder().role(EasyInputMessage.Role.USER)
            .contentOfResponseInputMessageContentList(List.of(
                ResponseInputContent.ofInputText(ResponseInputText.builder().text(prompt).build()),
                ResponseInputContent.ofInputImage(ResponseInputImage.builder().detail(ResponseInputImage.Detail.HIGH)
                    .imageUrl("data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(frame))).build())))
            .build();
        var response = client.responses().create(ResponseCreateParams.builder().model(activeModel)
            .inputOfResponse(List.of(ResponseInputItem.ofEasyInputMessage(input))).store(false)
            .maxOutputTokens(6000).text(VisualReview.class).build());
        return response.output().stream().flatMap(i -> i.message().stream()).flatMap(m -> m.content().stream())
            .flatMap(c -> c.outputText().stream()).findFirst().orElseThrow();
    }

    /** Finds the real visible clue so answer frames can reveal it with a precise animated ring. */
    ClueLocation locateClue(Path questionFrame, EpisodeSpec.Puzzle puzzle, String requestedModel) throws Exception {
        String activeModel = requestedModel == null || requestedModel.isBlank() ? model : requestedModel.trim();
        if (!"live".equals(generationMode)) throw new IllegalStateException("Live clue analysis is required for automatic reveal highlights");
        String prompt = """
            Inspect this final 1920x1080 family puzzle frame. The correct answer is OPTION %s.
            Locate the single decisive visual clue that proves it. Return x, y, width and height normalized 0–1 against
            the ENTIRE 1920x1080 frame, tightly enclosing the visual evidence inside the illustrated scene. Never select
            a letter badge, title, timer, border, or decoration. The region must be suitable for a bright animated circle
            during the answer reveal; it should not cover unrelated people or objects. If the clue is a missing shadow or
            reflection, frame that relevant ground or mirror area. Use only visible pixels. Include a brief notes field.
            Puzzle definition: %s
            """.formatted(puzzle.answerId(), json.writeValueAsString(puzzle));
        var input = EasyInputMessage.builder().role(EasyInputMessage.Role.USER)
            .contentOfResponseInputMessageContentList(List.of(
                ResponseInputContent.ofInputText(ResponseInputText.builder().text(prompt).build()),
                ResponseInputContent.ofInputImage(ResponseInputImage.builder().detail(ResponseInputImage.Detail.HIGH)
                    .imageUrl("data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(questionFrame))).build())))
            .build();
        var response = client.responses().create(ResponseCreateParams.builder().model(activeModel)
            .inputOfResponse(List.of(ResponseInputItem.ofEasyInputMessage(input))).store(false)
            .maxOutputTokens(1000).text(ClueLocation.class).build());
        var location = response.output().stream().flatMap(i -> i.message().stream()).flatMap(m -> m.content().stream())
            .flatMap(c -> c.outputText().stream()).findFirst().orElseThrow();
        location.onArtwork();
        return location;
    }

    public record VisualSolution(String answerId, boolean clearForKids, String observedClue, String issues) {}
    private VisualReview reviewVisualMystery(Path frame, EpisodeSpec.Puzzle puzzle, String activeModel) throws Exception {
        // Blind: no intended answer, explanation or scene prompt. The delivered pixels must prove the clue.
        var message = EasyInputMessage.builder().role(EasyInputMessage.Role.USER)
            .contentOfResponseInputMessageContentList(List.of(
                ResponseInputContent.ofInputText(ResponseInputText.builder().text(
                    "Solve this visual mini-mystery from this image alone. Return answerId A/B/C or NONE if uncertain, "
                    + "clearForKids boolean for ages6–10, observedClue describing only pixels actually visible, and issues. "
                    + "Read the short on-screen fictional rule if present. All three options must be visible; no tiny, "
                    + "ambiguous, cropped or obscured clue. For missing-shadow/reflection puzzles inspect all three "
                    + "counterparts and lighting carefully; do not assume a missing shadow just because a ghost is mentioned. "
                    + "Reject if multiple answers fit. Do not infer a robot from ordinary clothing or disability. "
                    + "Also reject obvious anatomy defects, covered faces, excessive text or a prematurely highlighted answer.").build()),
                ResponseInputContent.ofInputImage(ResponseInputImage.builder().detail(ResponseInputImage.Detail.HIGH)
                    .imageUrl("data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(frame))).build())))
            .build();
        var result = client.responses().create(ResponseCreateParams.builder().model(activeModel)
            .inputOfResponse(List.of(ResponseInputItem.ofEasyInputMessage(message))).store(false)
            .maxOutputTokens(7000).text(VisualSolution.class).build());
        var solution = result.output().stream().flatMap(i -> i.message().stream()).flatMap(m -> m.content().stream())
            .flatMap(c -> c.outputText().stream()).findFirst().orElseThrow();
        return new VisualReview(solution.clearForKids() && puzzle.answerId().equals(solution.answerId()),
            "Blind image solver chose " + solution.answerId() + ". Visible clue: " + solution.observedClue() + " " + solution.issues());
    }

    private static String operatorSuffix(String direction) {
        return direction == null || direction.isBlank() ? "" : "\n\nOperator direction for this stage (honor it unless it conflicts with safety or required output format):\n" + direction.trim();
    }
}
