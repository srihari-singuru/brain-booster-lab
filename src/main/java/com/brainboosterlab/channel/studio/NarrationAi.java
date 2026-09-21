package com.brainboosterlab.channel.studio;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.*;
import java.nio.file.Files;
import java.nio.file.Path;
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
        this.client = "live".equals(mode) ? OpenAIOkHttpClient.builder().fromEnv().maxRetries(0).build() : null;
    }

    Draft write(EpisodeSpec spec) {
        if (!"live".equals(mode)) return new Draft(fixture(spec), "local-fixture", "none");
        String prompt = """
            You are the senior story writer for Brain Booster Lab, a warm family visual-puzzle channel for ages 6–10.
            Write natural spoken narration for the supplied episode specification. This is voice-over, not on-screen
            copy. Make each puzzle feel like a tiny moment in one playful show: calm curiosity, a vivid but brief
            situation, a clean invitation to notice the clue, then a satisfying and kind reveal. Vary the wording.

            Use ONLY facts, choices, answer and explanation in the specification. Never invent visual evidence,
            character traits, extra suspects, danger, or a second puzzle rule. Do not use stock pressure such as
            'only geniuses', shame, panic, or repeated 'are you ready'. Do not say 'find something', 'look closely',
            or promise that the viewer can see an unclear clue. Address viewers gently and inclusively.

            Structure rules:
            - episodeOpening: one fresh welcome, 4–28 words. It will be used in a future opening, not today’s video.
            - for each puzzle, questionLeadIn: 18–34 words, designed for about ten seconds. Set a mini-story and ask
              the on-screen question naturally, but DO NOT name, label, or hint at the answer or clue.
            - timerCue: 3–10 words. It plays exactly when the fixed ten-second silent timer begins. Do not count aloud.
            - revealExplanation: 18–34 words, designed for about ten seconds. State the correct choice and the exact
              visible clue/rule that proves it, warmly and plainly.
            - episodeClosing: one fresh 4–28-word sign-off for a future ending.
            - Never use a character name, choice label, or descriptive choice name anywhere in narration. Refer to a choice only as OPTION A, OPTION B, or OPTION C.
            - The lead-in and timer cue must never expose an option letter, the answer, or the decisive clue.
            - The reveal must begin with the correct OPTION letter, then unpack the visible proof with enough cozy, story-like detail to reward a careful guess.
            - No headings, timestamps, stage directions, sound effects, markdown, or text intended to appear on art.

            Return the required structured object. Episode specification:
            """ + json.writeValueAsString(spec);
        var response = client.responses().create(ResponseCreateParams.builder().model(model).input(prompt)
            .store(false).reasoning(Reasoning.builder().effort(ReasoningEffort.HIGH).build())
            .maxOutputTokens(7000).text(EpisodeNarration.class).build());
        EpisodeNarration narration = response.output().stream().flatMap(item -> item.message().stream())
            .flatMap(message -> message.content().stream()).flatMap(content -> content.outputText().stream()).findFirst()
            .orElseThrow(() -> new IllegalStateException("No complete structured narration returned"));
        narration.validate(spec);
        return new Draft(narration, model, response.id());
    }

    Review review(EpisodeSpec spec, EpisodeNarration narration) {
        if (!"live".equals(mode)) return new Review(java.util.stream.IntStream.range(0, 3)
            .mapToObj(i -> new Finding(i + 1, true, true, true, true,
                "Offline fixture only; NOT an independent AI narration review.")).toList());
        String prompt = """
            You are an exacting child-audience script editor. Critically review the supplied Brain Booster Lab
            narration against the supplied puzzle specification. Return one finding for each puzzle, in order.
            noAnswerLeak is true only when the question lead-in and timer cue do NOT reveal or strongly telegraph the
            correct option, character/name, answer letter, decisive visual clue, or explanation. storyFitsPuzzle is
            true only when the narration uses no invented evidence and the reveal correctly names the right answer and
            its exact proof, identifying the answer as OPTION A, OPTION B, or OPTION C only. It must reject any use of a choice name or label. timeFits is true only when lead-in is 18–34 words, timer cue 3–10, and reveal 18–34.
            familySafe is true only for warm, age-appropriate language with no pressure, shame, fear, stereotypes or
            unsafe claims. Be adversarial: reject generic filler and ambiguous proof. Put concise actionable feedback
            in notes. Do not rubber-stamp.
            Specification: """ + json.writeValueAsString(spec) + "\nNarration: " + json.writeValueAsString(narration);
        var response = client.responses().create(ResponseCreateParams.builder().model(model).input(prompt)
            .store(false).reasoning(Reasoning.builder().effort(ReasoningEffort.HIGH).build())
            .maxOutputTokens(7000).text(Review.class).build());
        return response.output().stream().flatMap(item -> item.message().stream())
            .flatMap(message -> message.content().stream()).flatMap(content -> content.outputText().stream()).findFirst()
            .orElseThrow(() -> new IllegalStateException("No complete structured narration review returned"));
    }
    record GroundedDraft(NarrationGrounding grounding, String model, String responseId) {}

    GroundedDraft ground(EpisodeSpec spec, EpisodeNarration narration, List<Path> frames) throws Exception {
        EpisodeSpec.require(frames != null && frames.size() == 3, "Grounding needs three completed question frames");
        if (!"live".equals(mode)) {
            var findings = java.util.stream.IntStream.range(0, 3)
                .mapToObj(i -> new NarrationGrounding.Finding(i + 1, false, false, true,
                    "Offline fixture only; human visual narration review is required."))
                .toList();
            return new GroundedDraft(new NarrationGrounding(narration, findings), "local-fixture", "none");
        }
        String prompt = """
            You are Brain Booster Lab’s final visual-continuity editor. The three attached images are completed
            QUESTION frames in puzzle order one, two, three. Compare pixels in each frame against its puzzle
            specification and its proposed narration. Do not trust the written scene plan when it contradicts a frame.

            For every puzzle, verify that the correct option and decisive clue are truly visible, readable, and
            unambiguous in the frame. Check that the narration names only OPTION A, OPTION B, or OPTION C, never
            a character name or choice label. If the frame supports the puzzle, polish the narration only as needed
            to speak exactly what a child can fairly infer from the displayed frame. Preserve the warm 10-second
            lead-in, fixed 10-second timer cue, and 10-second reveal structure. Never invent details to repair art.

            If any image does not prove its clue, set that finding’s visualClueConfirmed and narrationMatchesFrame
            false, explain the mismatch, and keep the narration conservative. Each notes field must be one or two short sentences, under 300 characters. Return the complete structured
            NarrationGrounding object with exactly three findings in image order.

            Puzzle specification: """ + json.writeValueAsString(spec) + "\nProposed narration: " + json.writeValueAsString(narration);
        var message = EasyInputMessage.builder().role(EasyInputMessage.Role.USER)
            .contentOfResponseInputMessageContentList(List.of(
                ResponseInputContent.ofInputText(ResponseInputText.builder().text(prompt).build()),
                ResponseInputContent.ofInputImage(ResponseInputImage.builder().detail(ResponseInputImage.Detail.HIGH)
                    .imageUrl("data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(frames.get(0)))).build()),
                ResponseInputContent.ofInputImage(ResponseInputImage.builder().detail(ResponseInputImage.Detail.HIGH)
                    .imageUrl("data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(frames.get(1)))).build()),
                ResponseInputContent.ofInputImage(ResponseInputImage.builder().detail(ResponseInputImage.Detail.HIGH)
                    .imageUrl("data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(frames.get(2)))).build())))
            .build();
        var response = client.responses().create(ResponseCreateParams.builder().model(model)
            .inputOfResponse(List.of(ResponseInputItem.ofEasyInputMessage(message))).store(false)
            .reasoning(Reasoning.builder().effort(ReasoningEffort.HIGH).build())
            .maxOutputTokens(10000).text(NarrationGrounding.class).build());
        NarrationGrounding grounded = NarrationGrounding.normalize(response.output().stream().flatMap(item -> item.message().stream())
            .flatMap(item -> item.content().stream()).flatMap(item -> item.outputText().stream()).findFirst()
            .orElseThrow(() -> new IllegalStateException("No complete structured narration grounding returned")), spec.puzzles().size());
        grounded.validate(spec);
        return new GroundedDraft(grounded, model, response.id());
    }

    private static EpisodeNarration fixture(EpisodeSpec spec) {
        var beats = java.util.stream.IntStream.range(0, 3).mapToObj(index -> {
            var puzzle = spec.puzzles().get(index);
            return new EpisodeNarration.PuzzleNarration(index + 1,
                "A bright little scene is unfolding, with three lively choices and one clever surprise waiting in the picture. Which option solves this friendly puzzle today?",
                "Your ten seconds start now.",
                "The answer is OPTION " + puzzle.answerId() + ". Follow the clear clue in the scene; it explains why this choice fits the puzzle and the other choices do not.");
        }).toList();
        return new EpisodeNarration("Welcome to Brain Booster Lab, where every small clue can spark a brilliant idea.",
            beats, "Wonderful thinking today. Keep noticing the little details, and come back for another cheerful puzzle.");
    }
}
