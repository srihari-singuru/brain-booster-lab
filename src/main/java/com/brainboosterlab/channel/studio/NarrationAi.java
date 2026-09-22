package com.brainboosterlab.channel.studio;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Dedicated writer and critical editor for spoken copy; audio generation is intentionally separate. */
@Component
class NarrationAi {
    record Draft(EpisodeNarration narration, String model, String responseId) {}
    public record Finding(int puzzleNumber, boolean noAnswerLeak, boolean storyFitsPuzzle,
                          boolean timeFits, boolean familySafe, String notes) {}
    public record Review(List<Finding> findings) {
        boolean passes(EpisodeSpec spec) {
            if (findings == null || findings.size() != spec.puzzles().size()) return false;
            for (int i = 0; i < findings.size(); i++) {
                Finding finding = findings.get(i);
                if (finding == null || finding.puzzleNumber() != i + 1 || !finding.noAnswerLeak()
                    || !finding.storyFitsPuzzle() || !finding.timeFits() || !finding.familySafe()) return false;
            }
            return true;
        }
    }

    private final String mode;
    private final String model;
    private final OpenAIClient client;
    private final JsonMapper json = JsonMapper.builder().build();

    NarrationAi(@Value("${brain-booster.generation.mode:mock}") String generationMode,
                @Value("${brain-booster.generation.model:}") String generationModel,
                @Value("${brain-booster.narration.mode:}") String narrationMode,
                @Value("${brain-booster.narration.model:}") String narrationModel) {
        this.mode = narrationMode == null || narrationMode.isBlank() ? generationMode : narrationMode;
        this.model = narrationModel == null || narrationModel.isBlank() ? generationModel : narrationModel;
        this.client = "live".equals(mode) ? OpenAIOkHttpClient.builder().fromEnv().timeout(Duration.ofSeconds(120)).maxRetries(0).build() : null;
    }

    Draft write(EpisodeSpec spec) {
        return write(spec, model);
    }

    Draft write(EpisodeSpec spec, String requestedModel) {
        return write(spec, requestedModel, "");
    }

    Draft write(EpisodeSpec spec, String requestedModel, String operatorDirection) {
        String activeModel = requestedModel == null || requestedModel.isBlank() ? model : requestedModel.trim();
        if (!"live".equals(mode)) return new Draft(fixture(spec), "local-fixture", "none");
        // Large strict narration objects have the same latency risk as large puzzle
        // objects. Batch only long episodes; short episodes retain one coherent pass.
        if (spec.puzzles().size() >= 4) {
            var beats = new ArrayList<EpisodeNarration.PuzzleNarration>();
            EpisodeNarration first = null;
            EpisodeNarration last = null;
            String lastResponseId = "";
            for (int i = 0; i < spec.puzzles().size(); i++) {
                EpisodeSpec onePuzzle = new EpisodeSpec(spec.title(), List.of(spec.puzzles().get(i)));
                Draft one = write(onePuzzle, activeModel, operatorDirection);
                EpisodeNarration.PuzzleNarration beat = one.narration().puzzles().getFirst();
                beats.add(new EpisodeNarration.PuzzleNarration(i + 1, beat.questionLeadIn(), beat.timerCue(), beat.revealExplanation()));
                if (first == null) first = one.narration();
                last = one.narration();
                lastResponseId = one.responseId();
            }
            EpisodeNarration combined = new EpisodeNarration(first.episodeOpening(), List.copyOf(beats), last.episodeClosing());
            combined.validate(spec);
            return new Draft(combined, activeModel, lastResponseId);
        }
        String prompt = """
            You are the senior story writer for Brain Booster Lab, an energetic family visual-challenge channel for
            children ages 6–18 solving alongside parents. Write natural spoken narration for the supplied episode
            specification. This is voice-over, not on-screen copy. Make each puzzle feel like a compact, playful
            mystery in one polished show: a vivid situation, building curiosity, a clean invitation to solve, then
            a satisfying reveal. Sound bright and intelligent for older children and parents while remaining clear
            for younger children. Keep sentence length and word counts remarkably even between puzzles so one steady voice speed feels natural across the full episode. Vary the storytelling rhythm and wording; never sound babyish, classroom-like, or generic.

            Use ONLY facts, choices, answer and explanation in the specification. Never invent visual evidence,
            character traits, extra suspects, danger, or a second puzzle rule. Do not use stock pressure such as
            'only geniuses', shame, panic, or repeated 'are you ready'. Do not say 'find something', 'look closely',
            or promise that the viewer can see an unclear clue. Address viewers warmly, inclusively, and with genuine
            excitement rather than pressure.

            SIMPLE SPOKEN ENGLISH IS REQUIRED. Write for a child aged seven to understand on the first
            listen, while still sounding fun for teens and parents. Use familiar everyday words, short
            sentences, and clear action words. Avoid formal words, metaphors, idioms, long descriptions,
            or puzzle words such as "deduce", "candidate", "evidence", "mechanism", or "conclusion".
            The picture supplies the challenge; the voice must make the story easy to follow.

            Structure rules:
            - episodeOpening: one fresh welcome, 4–28 words. It will be used in a future opening, not today’s video.
            - for each puzzle, questionLeadIn: 20–25 words, designed for a fixed ten-second spoken slot at the chosen voice speed. Set a mini-story and ask
              the on-screen question naturally, but DO NOT name, label, or hint at the answer or clue. Build a little
              anticipation without repeating the question word-for-word.
            - timerCue: 5–7 words. It must invite viewers to take exactly EIGHT seconds. It has a fixed three-and-a-half-second slot while the timer holds at 8; the separate fixed eight-second countdown begins immediately after it. Do not count aloud.
            - revealExplanation: 15–18 words, designed for a fixed eight-second spoken slot at the chosen voice speed. Use one or two short, direct sentences; avoid colons, semicolons, ellipses, or dramatic pauses. State the correct choice and the exact
              visible clue/rule that proves it in a lively, natural way that rewards the viewer’s reasoning.
            - episodeClosing: one fresh 4–28-word sign-off for a future ending.
            - Never use a character name, choice label, or descriptive choice name anywhere in narration. Refer to a choice only as its supplied OPTION letter (A through E).
            - The lead-in and timer cue must never expose an option letter, the answer, or the decisive clue.
            - The reveal must begin with the correct OPTION letter, then unpack the visible proof with enough vivid, story-like detail to reward a careful guess.
            - No headings, timestamps, stage directions, sound effects, markdown, or text intended to appear on art.

            PRE-SUBMISSION ACCEPTANCE CHECK — silently check every puzzle beat before returning it. The lead-in
            and timer must not name or telegraph the answer, an OPTION letter, a choice name, or the decisive clue.
            The reveal must use only the correct OPTION letter and must state only the exact proof already supplied
            in the puzzle specification. Keep the required word ranges, especially the concise 15–18-word answer, 10-second question slot, 3.5-second cue, fixed eight-second countdown, 8-second answer slot, simple spoken
            English, family-safe warmth, and a similar spoken density across every puzzle. If a line fails one of
            these checks, rewrite it before returning the structured object. Return narration you expect both the
            independent script editor and final frame-grounding editor to accept without correction.

            Return the required structured object. Episode specification:
            """ + json.writeValueAsString(spec) + operatorSuffix(operatorDirection);
        var response = client.responses().create(ResponseCreateParams.builder().model(activeModel).input(prompt)
            // Draft quickly, then keep the independent narration review and visual grounding at medium effort.
            .store(false).reasoning(Reasoning.builder().effort(ReasoningEffort.LOW).build())
            .maxOutputTokens(5000).text(EpisodeNarration.class).build());
        EpisodeNarration narration = response.output().stream().flatMap(item -> item.message().stream())
            .flatMap(message -> message.content().stream()).flatMap(content -> content.outputText().stream()).findFirst()
            .orElseThrow(() -> new IllegalStateException("No complete structured narration returned"));
        narration.validate(spec);
        return new Draft(narration, activeModel, response.id());
    }

    Review review(EpisodeSpec spec, EpisodeNarration narration) {
        return review(spec, narration, model);
    }

    Review review(EpisodeSpec spec, EpisodeNarration narration, String requestedModel) {
        return review(spec, narration, requestedModel, "");
    }

    Review review(EpisodeSpec spec, EpisodeNarration narration, String requestedModel, String operatorDirection) {
        String activeModel = requestedModel == null || requestedModel.isBlank() ? model : requestedModel.trim();
        if (!"live".equals(mode)) return new Review(java.util.stream.IntStream.range(0, spec.puzzles().size())
            .mapToObj(i -> new Finding(i + 1, true, true, true, true,
                "Offline fixture only; NOT an independent AI narration review.")).toList());
        String prompt = """
            You are an exacting family-audience script editor for viewers ages 6–18 and parents. Critically review the supplied Brain Booster Lab
            narration against the supplied puzzle specification. Return one finding for each puzzle, in order.
            noAnswerLeak is true only when the question lead-in and timer cue do NOT reveal or strongly telegraph the
            correct option, character/name, answer letter, decisive visual clue, or explanation. storyFitsPuzzle is
            true only when the narration uses no invented evidence and the reveal correctly names the right answer and
            its exact proof, identifying the answer only as the supplied OPTION letter (A through E). It must reject any use of a choice name or label.
            familySafe is true only for warm, age-appropriate, simple language with no pressure, shame, fear, stereotypes or
            unsafe claims. A seven-year-old must understand every line on one listen; reject formal vocabulary, idioms, metaphors,
            or long tangled sentences. timeFits is true only when lead-in is 20–25 words for the fixed 10-second slot, timer cue is 5–7 words and explicitly says eight seconds for the fixed 3.5-second cue slot, and reveal is 15–18 words for the fixed 8-second slot with no colon, semicolon, ellipsis, or dramatic pause. Be adversarial: reject generic filler, babyish delivery, classroom-like explanation, and ambiguous proof. Put concise actionable feedback
            in notes. Do not rubber-stamp.
            Specification: """ + json.writeValueAsString(spec) + "\nNarration: " + json.writeValueAsString(narration) + operatorSuffix(operatorDirection);
        var response = client.responses().create(ResponseCreateParams.builder().model(activeModel).input(prompt)
            .store(false).reasoning(Reasoning.builder().effort(ReasoningEffort.MEDIUM).build())
            .maxOutputTokens(2500).text(Review.class).build());
        return response.output().stream().flatMap(item -> item.message().stream())
            .flatMap(message -> message.content().stream()).flatMap(content -> content.outputText().stream()).findFirst()
            .orElseThrow(() -> new IllegalStateException("No complete structured narration review returned"));
    }
    record GroundedDraft(NarrationGrounding grounding, String model, String responseId) {}

    GroundedDraft ground(EpisodeSpec spec, EpisodeNarration narration, List<Path> frames) throws Exception {
        return ground(spec, narration, frames, model);
    }

    GroundedDraft ground(EpisodeSpec spec, EpisodeNarration narration, List<Path> frames, String requestedModel) throws Exception {
        return ground(spec, narration, frames, requestedModel, "");
    }

    GroundedDraft ground(EpisodeSpec spec, EpisodeNarration narration, List<Path> frames, String requestedModel, String operatorDirection) throws Exception {
        String activeModel = requestedModel == null || requestedModel.isBlank() ? model : requestedModel.trim();
        EpisodeSpec.require(frames != null && frames.size() == spec.puzzles().size(), "Grounding needs every completed question frame");
        if (!"live".equals(mode)) {
            var findings = java.util.stream.IntStream.range(0, spec.puzzles().size())
                .mapToObj(i -> new NarrationGrounding.Finding(i + 1, false, false, true,
                    "Offline fixture only; human visual narration review is required."))
                .toList();
            return new GroundedDraft(new NarrationGrounding(narration, findings), "local-fixture", "none");
        }
        String prompt = """
            You are a final visual-continuity editor. The attached images are completed QUESTION frames in puzzle
            order. Compare pixels in each frame against its puzzle
            specification and its proposed narration. Do not trust the written scene plan when it contradicts a frame.

            For every puzzle, verify that the correct option and decisive clue are truly visible, readable, and
            unambiguous in the frame. Check that the narration names only the supplied OPTION letter (A through E), never
            a character name or choice label. If the frame supports the puzzle, polish the narration only as needed
            to speak exactly what a family viewer ages 6–18 can fairly infer from the displayed frame. Preserve the fixed
            10-second question lead-in, 3.5-second timer cue, 8-second countdown, and 8-second reveal structure. Never invent details to repair art.

            If any image does not prove its clue, set that finding’s visualClueConfirmed and narrationMatchesFrame
            false, explain the mismatch, and keep the narration conservative. Each notes field must be one or two short sentences, under 300 characters. Return the complete structured
            NarrationGrounding object with one finding per image in order. Before returning, silently verify that
            the corrected narration names only OPTION letters, makes no claim beyond visible pixels, retains the
            fixed timing and simple language, and is safe to send directly to speech when every finding passes.

            Puzzle specification: """ + json.writeValueAsString(spec) + "\nProposed narration: " + json.writeValueAsString(narration) + operatorSuffix(operatorDirection);
        var content = new java.util.ArrayList<ResponseInputContent>();
        content.add(ResponseInputContent.ofInputText(ResponseInputText.builder().text(prompt).build()));
        for (Path frame : frames) content.add(ResponseInputContent.ofInputImage(ResponseInputImage.builder()
            .detail(ResponseInputImage.Detail.HIGH).imageUrl("data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(frame))).build()));
        var message = EasyInputMessage.builder().role(EasyInputMessage.Role.USER).contentOfResponseInputMessageContentList(content).build();
        var response = client.responses().create(ResponseCreateParams.builder().model(activeModel)
            .inputOfResponse(List.of(ResponseInputItem.ofEasyInputMessage(message))).store(false)
            .reasoning(Reasoning.builder().effort(ReasoningEffort.MEDIUM).build())
            .maxOutputTokens(7000).text(NarrationGrounding.class).build());
        NarrationGrounding grounded = NarrationGrounding.normalize(response.output().stream().flatMap(item -> item.message().stream())
            .flatMap(item -> item.content().stream()).flatMap(item -> item.outputText().stream()).findFirst()
            .orElseThrow(() -> new IllegalStateException("No complete structured narration grounding returned")), spec.puzzles().size());
        grounded.validate(spec);
        return new GroundedDraft(grounded, activeModel, response.id());
    }

    private static EpisodeNarration fixture(EpisodeSpec spec) {
        var beats = java.util.stream.IntStream.range(0, spec.puzzles().size()).mapToObj(index -> {
            var puzzle = spec.puzzles().get(index);
            return new EpisodeNarration.PuzzleNarration(index + 1,
                "A bright little scene is unfolding, with three lively choices and one clever surprise waiting in the picture. Which option solves this friendly puzzle today?",
                "Take eight seconds to choose your answer.",
                "The answer is OPTION " + puzzle.answerId() + ". Follow the clear clue in the scene; it explains why this choice fits the puzzle and the other choices do not.");
        }).toList();
        return new EpisodeNarration("Welcome to Brain Booster Lab, where every small clue can spark a brilliant idea.",
            beats, "Wonderful thinking today. Keep noticing the little details, and come back for another cheerful puzzle.");
    }

    private static String operatorSuffix(String direction) {
        return direction == null || direction.isBlank() ? "" : "\n\nOperator direction for this stage (honor it unless it conflicts with safety or required output format):\n" + direction.trim();
    }
}
