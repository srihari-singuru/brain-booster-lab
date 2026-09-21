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
import java.time.Duration;
import java.util.ArrayList;
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
            ? OpenAIOkHttpClient.builder().fromEnv().timeout(Duration.ofSeconds(120)).maxRetries(0).build() : null;
    }

    Draft generate(String brief) {
        return generate(brief, 3, model);
    }

    Draft generate(String brief, int puzzleCount, String requestedModel) {
        return generate(brief, puzzleCount, requestedModel, "");
    }

    Draft generate(String brief, int puzzleCount, String requestedModel, String operatorDirection) {
        return generate(brief, puzzleCount, requestedModel, operatorDirection, "");
    }

    /** Recent local titles make novelty an informed constraint without exporting puzzle details. */
    Draft generate(String brief, int puzzleCount, String requestedModel, String operatorDirection, String recentPuzzleTitles) {
        return generate(brief, puzzleCount, requestedModel, operatorDirection, recentPuzzleTitles, false);
    }

    /** A creator-triggered retry after a schema decoding failure uses a smaller response contract. */
    Draft generateRecovery(String brief, int puzzleCount, String requestedModel, String operatorDirection, String recentPuzzleTitles) {
        return generate(brief, puzzleCount, requestedModel, operatorDirection, recentPuzzleTitles, true);
    }

    private Draft generate(String brief, int puzzleCount, String requestedModel, String operatorDirection,
                           String recentPuzzleTitles, boolean recovery) {
        EpisodeSpec.require(puzzleCount >= 1 && puzzleCount <= 10, "Choose between 1 and 10 puzzles");
        String activeModel = requestedModel == null || requestedModel.isBlank() ? model : requestedModel.trim();
        if (!"live".equals(generationMode)) return new Draft(PilotFixtures.kids(), "local-fixture", "none");
        // Astra can take too long to return a large strict-schema response with rich
        // scenes. Generate independent puzzles sequentially instead: every
        // request has a small, complete contract, while the accumulated titles still
        // protect against repeated premises within the episode.
        if (puzzleCount > 1) {
            var puzzles = new ArrayList<EpisodeSpec.Puzzle>();
            var usedAnswers = new ArrayList<String>();
            String noRepeat = recentPuzzleTitles == null ? "" : recentPuzzleTitles.trim();
            String lastResponseId = "";
            for (int number = 1; number <= puzzleCount; number++) {
                Draft one = generate(brief, 1, activeModel, operatorDirection, noRepeat, recovery);
                EpisodeSpec.Puzzle puzzle = one.spec().puzzles().getFirst();
                puzzles.add(puzzle);
                lastResponseId = one.responseId();
                usedAnswers.add(puzzle.answerId());
                noRepeat = (noRepeat.isBlank() ? "" : noRepeat + "\n")
                    + "TITLE: " + puzzle.title()
                    + " | QUESTION: " + puzzle.question()
                    + " | CLUE/REVEAL: " + puzzle.explanation()
                    + " | CORRECT OPTION: " + puzzle.answerId()
                    + "\nCURRENT EPISODE ANSWER DISTRIBUTION: already used " + String.join(", ", usedAnswers)
                    + ". Choose a different correct OPTION letter when 3+ choices make that possible.";
            }
            return new Draft(new EpisodeSpec("Fresh Family Puzzle Collection", List.copyOf(puzzles)), activeModel, lastResponseId);
        }
        String prompt = """
            Create %d original illustrated visual mini-mysteries for a family channel: children ages 6–18
            solving lively challenges with parents. Set kind="visual" for EVERY puzzle. Each is a satisfying
            family challenge: not an instant giveaway, but fair to solve during one eight-second look by comparing
            the whole scene, noticing one meaningful detail, and making one simple inference. The first puzzle
            is not a warm-up; every puzzle should have the same enjoyable, medium challenge level. Never use
            arithmetic, number patterns, time calculations, truth tables, long alibis, schoolwork, or tiny
            hidden-object searches.

            VARIETY IS A HARD REQUIREMENT. Treat this episode as a fresh collection, not a variation of one
            stock riddle. Every puzzle must use a distinctly different setting, story premise, visual mechanism,
            clue type, and answer rationale. Do not repeat a mechanism within the episode. Do not default to
            cardboard robots, winding keys, missing shadows, reflections, disguised ghosts, or any familiar
            example from prior output. Those themes are allowed only when the creative brief specifically calls
            for them, and then at most once in an episode. Draw from a broad rotating mix: playful everyday
            mishaps, imaginative science, cozy mysteries, animal adventures, light fantasy, harmless kid-friendly
            monsters, games, travel, food, clubs, nature, festivals, inventions, and make-believe worlds. Make
            the premise, physical evidence, and decisive observation new each time. Do not reuse a title,
            question shape, setting, clue mechanism, or reveal wording across puzzles.

            Use 3, 4, or 5 candidates when it serves the scene; vary the candidate count across this episode
            when there is more than one puzzle. Choices must be consecutive OPTION letters starting at A
            (A/B/C, A/B/C/D, or A/B/C/D/E). All candidates must be equally plausible at first glance and the
            correct one must be proven by the picture, not by a suspicious expression or obvious category mismatch.
            Spread correct OPTION letters across an episode: do not repeatedly make the same position correct when
            other valid options are available. When the no-repeat list contains CURRENT EPISODE ANSWER DISTRIBUTION,
            choose a different correct letter from that list whenever possible.
            The clue must be large enough to see on a phone but subtle enough to reward a second look. Avoid
            alternate explanations. Friendly fantasy and monsters are welcome, but never frightening, cruel, or
            implying real people are nonhuman. Never use skin color, disability, body differences, or cultural
            appearance as evidence. A mechanical/prosthetic limb does not prove someone is nonhuman. If a story
            uses magic, ghosts, shadows, or reflections, include one short explicit fictional world rule only
            when it is genuinely necessary; never present folklore as science.

            question: max10 words AND60 characters. facts: zero or one line, max65 characters;
            only an essential story rule, never a paragraph or solution. setup: spoken introduction
            for FUTURE narration, max155 characters; not displayed as a paragraph in the video.
            choices: three to five consecutive choices A through C, D, or E in left-to-right order; label a name/color max24 characters;
            statement max100 characters describing the subject's appearance for production, NOT a
            spoken alibi or caption. Do NOT reveal the clue in the label. explanation: a warm,
            concrete reveal, max14 words AND85 characters. title max48, episode title max65.
            sceneDescription: max%s characters. Specify exactly the chosen three-to-five candidate subjects,
            arranged left-to-right in matching OPTION order, with every candidate and the full clue visible.
            State the exact clue and which candidate owns it, plus clear ordinary counterparts. Do not add
            confusing extra candidate-like people or props. NO text or badges in sceneDescription: the application
            adds all OPTION labels outside the art.
            The artwork itself MUST carry the evidence; the written answer is not proof that the
            image succeeded. One image is reused unchanged during question and answer.
            Keep the refined 2D illustrated style and rich teal/amber/coral palette, with appealing
            people and everyday settings. For thumbnail readability, use a vivid but natural jewel
            palette, luminous key light, strong color separation between subjects, crisp silhouettes,
            and a clean focal clue. The result should feel energetic at small phone size without
            neon skin, plastic 3D rendering or visual noise. Do not copy channel characters or designs.
            thinkSeconds: 8–15; use 8 for every visual puzzle.
            Original creative brief follows:
            """.formatted(puzzleCount, recovery ? "750" : (puzzleCount >= 4 ? "900" : "1200")) + brief + recentTitleSuffix(recentPuzzleTitles)
            + (recovery ? "\nRECOVERY MODE: A prior response could not be decoded. Return the complete schema only. Keep every field concise, especially sceneDescription; do not omit any puzzle or use markdown.\n" : "")
            + operatorSuffix(operatorDirection);
        var response = client.responses().create(ResponseCreateParams.builder().model(activeModel).input(prompt)
            // Creative drafting needs breadth, not a large reasoning budget. This
            // keeps multi-puzzle requests comfortably within the UI deadline.
            .store(false).reasoning(Reasoning.builder().effort(ReasoningEffort.LOW).build())
            .maxOutputTokens(puzzleCount >= 4 ? 7500 : 6500).text(EpisodeSpec.class).build());
        EpisodeSpec spec = response.output().stream().flatMap(i -> i.message().stream())
            .flatMap(m -> m.content().stream()).flatMap(c -> c.outputText().stream()).findFirst()
            .orElseThrow(() -> new IllegalStateException("No complete structured episode returned"));
        spec = normalizeOptionIds(spec);
        // A model can occasionally over-produce despite the strict schema. In a
        // one-puzzle batch, keep exactly its first complete puzzle so the caller's
        // requested count remains authoritative; later batches receive its title,
        // clue, and answer position as no-repeat context.
        EpisodeSpec.require(spec.puzzles() != null && !spec.puzzles().isEmpty(), "The script returned no puzzles; retry generation");
        if (puzzleCount == 1 && spec.puzzles().size() != 1)
            spec = new EpisodeSpec(spec.title(), List.of(spec.puzzles().getFirst()));
        // Persist the paid structured response before local/independent validation in the service.
        EpisodeSpec.require(spec.puzzles().size() == puzzleCount, "The script returned the wrong number of puzzles; retry generation");
        return new Draft(spec, activeModel, response.id());
    }

    /** Models occasionally spell identifiers as "OPTION C"; storage uses canonical A–E IDs. */
    private static EpisodeSpec normalizeOptionIds(EpisodeSpec source) {
        if (source.puzzles() == null) return source;
        var puzzles = new ArrayList<EpisodeSpec.Puzzle>();
        for (EpisodeSpec.Puzzle puzzle : source.puzzles()) {
            if (puzzle == null || puzzle.choices() == null) { puzzles.add(puzzle); continue; }
            var choices = new ArrayList<EpisodeSpec.Choice>();
            for (int i = 0; i < puzzle.choices().size(); i++) {
                EpisodeSpec.Choice choice = puzzle.choices().get(i);
                choices.add(new EpisodeSpec.Choice(String.valueOf((char) ('A' + i)), choice.label(), choice.statement()));
            }
            puzzles.add(new EpisodeSpec.Puzzle(puzzle.kind(), puzzle.title(), puzzle.setup(), puzzle.question(), puzzle.facts(),
                List.copyOf(choices), optionId(puzzle.answerId()), puzzle.explanation(), puzzle.sceneDescription(), puzzle.thinkSeconds()));
        }
        return new EpisodeSpec(source.title(), List.copyOf(puzzles));
    }

    private static String optionId(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (int i = value.length() - 1; i >= 0; i--) {
            char candidate = value.charAt(i);
            if (candidate >= 'A' && candidate <= 'E') return String.valueOf(candidate);
        }
        return raw;
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
        String prompt = "You are an independent, adversarial editor for family visual challenges. Independently solve these "
            + spec.puzzles().size() + " puzzles in order. For each return puzzleNumber in order starting at 1, "
            + "independentlySolvedAnswerId matching one supplied OPTION letter (or NONE if ambiguous), fair boolean, and notes explaining the proof "
            + "and why every alternative fails. Judge for children ages 6–18 solving with parents: each puzzle must be a satisfying "
            + "medium challenge, not an instant giveaway and not a frustrating hunt. It must be fairly solvable from one meaningful, phone-visible "
            + "visual observation and one simple inference during an eight-second look. "
            + "Reject ambiguity, unstated necessary facts, harmful stereotypes, claims that lying proves guilt, arithmetic, number patterns, "
            + "time calculations, truth tables, long alibis, schoolwork, and tiny object hunts. Verify every puzzle has 3–5 consecutive supplied "
            + "OPTION letters, equally plausible candidates, and a correct answer proven by the planned picture rather than expression, appearance, or category difference. "
            + "Audit the complete collection for variety: reject an episode if its puzzles repeat a setting, premise, visual mechanism, clue type, "
            + "question shape, or reveal logic, or if it falls back on stock robot/cardboard, winding-key, missing-shadow, reflection, or disguised-ghost patterns without an explicit brief reason. "
            + "Friendly monsters and fantasy are welcome only when age-appropriate and supported by an explicit fictional rule where needed. "
            + "This is only a concept check; an actual-image blind visual check follows later. Do not rubber-stamp. "
            + "This is an adversarial review, not a request to endorse. Questions: " + json.writeValueAsString(questions)
            + operatorSuffix(operatorDirection);
        var response = client.responses().create(ResponseCreateParams.builder().model(activeModel).input(prompt)
            .store(false).reasoning(Reasoning.builder().effort(ReasoningEffort.MEDIUM).build())
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
            Use case: illustration-story. Create a premium 16:9 family visual challenge for children, teens,
            and parents. Keep the polished 2D mystery-comic style: natural anatomy, refined ink contours, rich teal,
            warm amber and coral, atmospheric light, engaging characters, and detailed but calm scenery. It must feel
            vibrant and intelligent, never preschool, babyish, gloomy, generic, or like stock clip-art.
            Exactly the three-to-five candidate subjects specified in the scene plan, arranged left-to-right in
            matching OPTION order. No other candidate-like people.
            Full bodies and any floor/shadow evidence fully inside the image. No cropping of clues.
            Do not reserve large text panels: the app puts a short question and OPTION badges outside the art.
            Evidence has to be genuinely visible and coherent, not simply described in a prompt.
            One clear visual clue, medium challenge for family viewers ages 6–18, readable at phone size. Every other
            candidate must clearly lack that anomaly. The clue should reward comparison and a second look, not be an
            instant giveaway or a tiny hunt. Preserve equally plausible expressions so faces do not give the answer
            away. Friendly make-believe, including harmless monsters or fantasy, is welcome; never scary. No text, labels, numbers, logos,
            watermarks, arrows, rings or answer highlights. No plastic 3D or preschool clip-art.
            The following scene plan is authoritative, especially its stated physical evidence:
            """ + puzzle.sceneDescription();
        prompt += "\nFinal lettering constraint: do NOT draw OPTION badges or any text, even if the scene brief mentions them. The application alone adds those labels."
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
            + "and correct left-to-right mapping of every depicted OPTION subject. Text must not cover faces. Return acceptable=false "
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
                    "Solve this visual mini-mystery from this image alone. Return the matching visible OPTION letter or NONE if uncertain, "
                    + "clearForKids boolean for family viewers ages 6–18: true only when it is a fair medium challenge, not an instant giveaway. "
                    + "observedClue must describe only pixels actually visible, and issues must identify any defect. "
                    + "Read the short on-screen fictional rule if present. Every option must be visible; no tiny, "
                    + "ambiguous, cropped or obscured clue. For missing-shadow/reflection puzzles inspect every "
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
    private static String recentTitleSuffix(String titles) {
        return titles == null || titles.isBlank() ? "" : "\n\nRECENT PUZZLE TITLES — HARD NO-REPEAT LIST:\n"
            + "Do not repeat or lightly rephrase a title below. Make the premise, setting, clue mechanism, question shape, and reveal logic genuinely different from this recent collection.\n"
            + titles.trim();
    }
}
