package com.brainboosterlab.channel.studio;

import com.openai.client.OpenAIClient;
import com.brainboosterlab.channel.OpenAiClientFactory;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.*;
import java.nio.file.Files;
import java.nio.file.Path;
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
        this.client = "live".equals(mode) ? OpenAiClientFactory.create(java.time.Duration.ofSeconds(120)) : null;
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
                Draft one = write(onePuzzle, activeModel, operatorDirection + varietyHint(i));
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
            You are the head writer for Puzzle Pop, an energetic family detective-mystery channel for children
            ages 6–18 solving alongside parents. Write natural spoken narration for the supplied episode
            specification. This is voice-over, not on-screen copy. The viewer is the detective. Make each case feel
            like a tiny, gripping whodunit in one polished show: a punchy hook in the first sentence (what just
            happened and why it matters), a quick nod to the suspects, the detective question, a crisp challenge to
            solve it in ten seconds, then a triumphant, satisfying reveal. VERY SIMPLE ENGLISH comes first: every
            line must be easy for a young child or a beginner English learner, while the story still feels exciting. Keep sentence length and word counts remarkably even between puzzles so one steady voice speed feels natural across the full episode. Vary the storytelling rhythm and wording; never sound babyish, classroom-like, or generic.

            Use ONLY facts, choices, answer and explanation in the specification. Never invent visual evidence,
            character traits, extra suspects, danger, or a second case rule. Do not use fake statistics or stock
            pressure such as 'only geniuses' or '99 percent fail', shame, panic, or repeated 'are you ready'. Avoid overusing stock phrases such as
            'look closely'; never promise that the viewer can see an unclear clue. Address viewers warmly, inclusively, and with genuine
            excitement rather than pressure.

            VERY SIMPLE, BEGINNER-FRIENDLY ENGLISH IS REQUIRED. A six-year-old or a person who is just starting to
            learn English must understand every word on the first listen. Follow every rule:
            - Short sentences: 3–9 words each, one idea per sentence. Use more short sentences, never one long one.
              A tiny exclamation such as "Oh no!" or "Wow!" is fine.
            - Only very common everyday words (the first 1,000 words a learner meets). If a simpler word exists, use
              it: "lying" not "fibbing", "took" not "swiped", "fake" not "impostor", "the one who did it" not
              "culprit", "look" not "observe", "wet" not "soaked", "happy" not "thrilled", "gone" not "vanished".
            - Simple grammar only: present tense or simple past, active voice, subject then verb then object.
              No "which", "whom", "although", "however", "whereas", "meanwhile", or other joining words. No
              "would have", "must have been", passive voice, or clauses inside clauses.
            - No idioms, slang, metaphors, puns, rhymes, sarcasm, or phrasal verbs that learners find hard
              ("own up", "pull off", "get away with"). Say things plainly: "Someone ate the cake."
            - Name things with plain, concrete words: "the cake", "blue paint", "wet shoes". Colors, numbers,
              body parts, and everyday objects are ideal.
            - Punctuation: only full stops, question marks, exclamation marks, and simple commas. No colons,
              semicolons, dashes, brackets, ellipses, or quotation marks.
            - Okay detective words: case, suspect, clue, detective, mystery, lying, fake. Avoid "deduce",
              "candidate", "evidence", "alibi", "culprit", "impostor", "conclusion", "mechanism", "suspicious".
            - Retell facts from the specification in simpler words when its wording is hard. Never add new facts.
            - Repeating the on-screen question exactly is good. It helps learners connect the voice and the screen.
            EXAMPLES OF THE RIGHT LEVEL (do not copy these stories):
              questionLeadIn (29 words): "Oh no! The birthday cake is gone. The party starts in five minutes.
              Mom is very sad. There are three suspects. Look at the picture. Who ate the cake?"
              timerCue (5 words): "You have ten seconds. Go!"
              revealExplanation (18 words): "It was Suspect B! Look at B's hands. They have blue frosting. The cake
              has blue frosting too."
            The picture supplies the challenge; the voice must make the case very easy to follow.

            Structure rules:
            - episodeOpening: one fresh welcome, 4–28 words. It will be used in a future opening, not today’s video.
            - for each puzzle, questionLeadIn: 24–32 words, designed for a lively, natural roughly ten-to-fourteen-second
              delivery at the chosen voice speed. Its measured voice duration controls the question-story screen; it
              is NOT the thinking countdown. Open with the hook (what just happened), add a quick beat of stakes or
              suspense, introduce the suspects, and ask the on-screen question. Every case must open differently: rotate
              between a sound word, a quote from someone in the story, a question to the viewer, a news headline, a
              funny sight, and a ticking clock. Introduce the suspects a new way each time (by their roles, or "Four
              friends were there."); never reuse the same sentence in an episode. WORD COUNT IS CHECKED
              BY CODE: aim for 27–30 words, which is usually 5–7 short sentences. Short sentences make it easy to fall
              under 24 words, and anything under 24 or over 32 is rejected, so add one more short sentence rather than
              making sentences longer. Count every word before returning. Do not add new facts. Never name, label, or hint at the answer or decisive clue. Keep the words very
              simple and the pace even across puzzles.
            - timerCue: 5–7 very simple words, and a different one for every case in the episode, for example: "You have ten seconds. Go!", "Ten seconds, detective. Start now!", "Can you solve it in ten seconds?", "Your ten seconds start right now!", "Find the clue in ten seconds!", "Ten seconds on the clock. Go!", "Think fast! You have ten seconds.", "Quick, detective! Ten seconds. Go!". It must invite viewers to take exactly TEN seconds. Its measured voice duration plays while the timer holds at 10; the separate fixed ten-second countdown begins immediately after it. Do not count aloud.
            - revealExplanation: 15–25 words; aim for 18–22 and count them. Its measured voice duration controls the answer screen. Avoid colons, semicolons, ellipses, or dramatic pauses. State the correct choice and the exact
              visible clue/rule that proves it in 3–4 very short, simple sentences that reward the viewer's thinking.
            - episodeClosing: one fresh 4–28-word sign-off for a future ending.
            - Never use a character name, choice label, or descriptive choice name anywhere in narration. Refer to a suspect only by its supplied OPTION letter (A through D), for example "Suspect B".
            - The lead-in and timer cue must never expose an option letter, the answer, or the decisive clue.
            - The reveal must begin by naming the right OPTION letter (for example "It was Suspect B!"), then say the visible proof in plain words. It may add one short sentence about why a tempting red herring was innocent.
            - No headings, timestamps, stage directions, sound effects, markdown, or text intended to appear on art.

            PRE-SUBMISSION ACCEPTANCE CHECK — silently check every puzzle beat before returning it. The lead-in
            and timer must not name or telegraph the answer, an OPTION letter, a choice name, or the decisive clue.
            The reveal must use only the correct OPTION letter and must state only the exact proof already supplied
            in the puzzle specification. Keep the required word ranges, especially a concise 15–25-word answer, natural voice pacing, the fixed ten-second countdown, and
            VERY SIMPLE beginner English: check every sentence is 3–9 words, every word is common and plain, and there
            is no idiom, hard word, joining word, colon, dash, or long clause. Also keep family-safe warmth, and a similar spoken density across every puzzle. If a line fails one of
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
        try {
            narration.validate(spec);
            return new Draft(narration, activeModel, response.id());
        } catch (IllegalArgumentException invalid) {
            // Very short beginner-English sentences can miss a word-count slot. The creator's click
            // authorizes one targeted repair of the paid draft rather than discarding it.
            return repairWordCounts(spec, narration, invalid.getMessage(), activeModel, operatorDirection);
        }
    }

    private Draft repairWordCounts(EpisodeSpec spec, EpisodeNarration draft, String problem, String activeModel, String operatorDirection) {
        String prompt = """
            You are fixing a Puzzle Pop narration draft that failed a local check: %s
            Word counts are measured by code as words separated by spaces. Current counts and required ranges:
            %s
            Return the complete corrected structured object. Change ONLY the lines marked NEEDS FIX; copy every other
            line exactly. To lengthen a line, add one more very short, simple sentence (3–9 words) that retells a fact
            already in the specification; to shorten it, remove a sentence. Land in the middle of each range.
            Keep the same VERY SIMPLE beginner English: only common everyday words, no idioms, no hard words, no colons,
            semicolons, dashes, or ellipses. Refer to suspects only by OPTION letter, for example "Suspect B". The lead-in
            and timer cue must never reveal the answer, an OPTION letter, or the decisive clue. Count every word before
            returning.
            Episode specification: %s
            Draft narration: %s
            """.formatted(problem, wordCountReport(draft), json.writeValueAsString(spec), json.writeValueAsString(draft))
            + operatorSuffix(operatorDirection);
        var response = client.responses().create(ResponseCreateParams.builder().model(activeModel).input(prompt)
            .store(false).reasoning(Reasoning.builder().effort(ReasoningEffort.LOW).build())
            .maxOutputTokens(5000).text(EpisodeNarration.class).build());
        EpisodeNarration repaired = response.output().stream().flatMap(item -> item.message().stream())
            .flatMap(message -> message.content().stream()).flatMap(content -> content.outputText().stream()).findFirst()
            .orElseThrow(() -> new IllegalStateException("No complete structured narration repair returned"));
        try {
            repaired.validate(spec);
        } catch (IllegalArgumentException stillInvalid) {
            throw new IllegalArgumentException(stillInvalid.getMessage()
                + ". One automatic word-count repair was already tried; run Narration again.", stillInvalid);
        }
        return new Draft(repaired, activeModel, response.id());
    }

    static String wordCountReport(EpisodeNarration narration) {
        var report = new StringBuilder();
        report.append(countLine("episodeOpening", narration.episodeOpening(), 4, 28));
        if (narration.puzzles() != null) for (var beat : narration.puzzles()) {
            if (beat == null) continue;
            String prefix = "puzzle " + beat.puzzleNumber() + " ";
            report.append(countLine(prefix + "questionLeadIn", beat.questionLeadIn(), 24, 32))
                .append(countLine(prefix + "timerCue", beat.timerCue(), 5, 7))
                .append(countLine(prefix + "revealExplanation", beat.revealExplanation(), 15, 25));
        }
        report.append(countLine("episodeClosing", narration.episodeClosing(), 4, 28));
        return report.toString();
    }

    private static String countLine(String name, String text, int minimum, int maximum) {
        int words = text == null || text.isBlank() ? 0 : text.trim().split("\\s+").length;
        return "- " + name + ": " + words + " words (allowed " + minimum + "–" + maximum + ")"
            + (words < minimum || words > maximum ? " NEEDS FIX" : "") + "\n";
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
            You are an exacting family-audience script editor for viewers ages 6–18 and parents. Critically review the supplied Puzzle Pop
            detective-case narration against the supplied case specification. Return one finding for each puzzle, in order.
            noAnswerLeak is true only when the question lead-in and timer cue do NOT reveal or strongly telegraph the
            correct option, character/name, answer letter, decisive visual clue, or explanation. storyFitsPuzzle is
            true only when the narration uses no invented evidence and the reveal correctly names the right answer and
            its exact proof, identifying the culprit only by the supplied OPTION letter (A through D), for example "Suspect B". It must reject any use of a choice name or label.
            familySafe is true only for warm, age-appropriate, simple language with no pressure, shame, fake statistics, fear,
            stereotypes or unsafe claims. Playful mystery suspense is welcome; real fear is not. The English must be VERY SIMPLE
            and beginner-friendly: a six-year-old or a new English learner must understand every word on one listen. Set
            familySafe=false for any sentence longer than about 9 words, any uncommon or formal word (for example culprit,
            impostor, fibbing, evidence, alibi, suspicious, vanished, observe), any idiom, slang, metaphor, pun, or hard
            phrasal verb, any passive voice or clause inside a clause, or any colon, semicolon, dash, or ellipsis. Name
            the hard word and give a simpler replacement in notes. timeFits is true only when the story lead-in is 24–32 words, the timer cue is 5–7 words and explicitly says ten seconds, and the reveal is 15–25 words with no colon, semicolon, ellipsis, or dramatic pause. Measured voice duration controls its own question or answer screen; only the countdown is fixed. Be adversarial: reject generic filler, babyish delivery, classroom-like explanation, and ambiguous proof. Put concise actionable feedback
            in notes. timeFits accepts a 24–32-word story lead-in because its actual speech duration determines the
            story-screen length; it is not limited to ten seconds. The silent thinking countdown remains exactly ten
            seconds for visual puzzles. In notes, also flag a flat or generic hook that would not hold a viewer's attention. Do not rubber-stamp.
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
            unambiguous in the frame, and that any red-herring detail on an innocent suspect does not also fit the clue.
            Check that the narration refers to suspects only by the supplied OPTION letter (A through D, e.g. "Suspect B"),
            never a character name or choice label. If the frame supports the puzzle, polish the narration only as needed
            to speak exactly what a family viewer ages 6–18 can fairly infer from the displayed frame. Preserve the
            voice-timed question lead-in and reveal, followed by the fixed ten-second countdown. Preserve the existing
            word-count slots exactly: questionLeadIn 24–32 words, timerCue 5–7 words, and revealExplanation 15–25
            words. Count each line before returning it; do not shorten or expand a line outside its range. Never invent details to repair art.
            Also check the puzzle's causal claim: the depicted trace must plausibly result from the event asked about,
            in the shown location and by the shown contact. Matching appearance alone is not proof. If the source
            puzzle's reasoning is physically unsupported, fail visualClueConfirmed instead of narrating around it.

            The story lead-in is voice-timed: its actual clip length sets the story-screen length. It does not consume
            or replace the separate, fixed ten-second silent thinking countdown. A 24–32-word lead-in is expected.
            If any image does not prove its clue, set that finding’s visualClueConfirmed and narrationMatchesFrame
            false, explain the mismatch, and keep the narration conservative. Each notes field must be one or two short sentences, under 300 characters. Return the complete structured
            NarrationGrounding object with one finding per image in order. Before returning, silently verify that
            the corrected narration refers to suspects only by OPTION letter, makes no claim beyond visible pixels, retains the
            fixed timing, and is safe to send directly to speech when every finding passes. Any line you polish must stay in
            VERY SIMPLE beginner English: 3–9-word sentences, only common everyday words, no idioms, no hard words such as
            culprit, impostor, evidence, or alibi, and no colons, semicolons, dashes, or ellipses.

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
        grounded = grounded.preserveValidSpeechSlots(narration, spec);
        grounded.validate(spec);
        return new GroundedDraft(grounded, activeModel, response.id());
    }

    private static EpisodeNarration fixture(EpisodeSpec spec) {
        var beats = java.util.stream.IntStream.range(0, spec.puzzles().size()).mapToObj(index -> {
            var puzzle = spec.puzzles().get(index);
            return new EpisodeNarration.PuzzleNarration(index + 1,
                "A bright little scene is unfolding, with three lively choices and one clever surprise waiting in the picture. Which option solves this friendly puzzle today?",
                "Take ten seconds to choose your answer.",
                "The answer is OPTION " + puzzle.answerId() + ". Follow the clear clue in the scene; it explains why this choice fits the puzzle and the other choices do not.");
        }).toList();
        return new EpisodeNarration("Welcome to Puzzle Pop, where every small clue can spark a brilliant idea.",
            beats, "Wonderful thinking today. Keep noticing the little details, and come back for another cheerful puzzle.");
    }

    private static final List<String> OPENING_STYLES = List.of("a sound word such as \"CRASH!\" or \"SPLASH!\"",
        "a short quote from someone in the story", "a question to the viewer", "a news headline such as \"Breaking news!\"",
        "a funny sight", "a ticking clock such as \"The show starts in two minutes.\"");
    private static final List<String> TIMER_CUES = List.of("You have ten seconds. Go!", "Ten seconds, detective. Start now!",
        "Can you solve it in ten seconds?", "Your ten seconds start right now!", "Find the clue in ten seconds!",
        "Ten seconds on the clock. Go!", "Think fast! You have ten seconds.", "Quick, detective! Ten seconds. Go!");

    /** One-case batches cannot see each other, so each gets its own opening style and timer line. */
    static String varietyHint(int index) {
        return "\n\nVARIETY FOR THIS CASE: open the question lead-in with " + OPENING_STYLES.get(index % OPENING_STYLES.size())
            + ", and use exactly this timer cue: \"" + TIMER_CUES.get(index % TIMER_CUES.size()) + "\"";
    }

    private static String operatorSuffix(String direction) {
        return direction == null || direction.isBlank() ? "" : "\n\nOperator direction for this stage (honor it unless it conflicts with safety or required output format):\n" + direction.trim();
    }
}
