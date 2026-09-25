package com.brainboosterlab.channel.studio;

import com.openai.client.OpenAIClient;
import com.brainboosterlab.channel.OpenAiClientFactory;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.*;
import com.openai.models.images.ImageGenerateParams;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.Instant;
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
    /** One vision request both solves the delivered frame and locates its visible proof. */
    record VisualInspection(VisualReview review, ClueLocation clue, boolean answerMatches) {}
    /** Checks conformance to the scene plan before the independent blind solver sees the delivered frame. */
    public record ArtworkConformance(boolean acceptable, String repairBrief, String notes) {}
    /** A clue rectangle returned relative to the full final 1920×1080 question frame. */
    public record ClueLocation(double x, double y, double width, double height, String notes) {
        SceneOverlay.Region onArtwork() {
            // The complete 16:9 artwork is rendered in this fixed, unobscured scene area.
            var stage = KidsFrameRenderer.ART_STAGE;
            double left = (x * StudioRenderer.WIDTH - stage.x) / stage.width;
            double top = (y * StudioRenderer.HEIGHT - stage.y) / stage.height;
            double wide = width * StudioRenderer.WIDTH / stage.width;
            double high = height * StudioRenderer.HEIGHT / stage.height;
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
            ? OpenAiClientFactory.create(java.time.Duration.ofSeconds(120)) : null;
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
        EpisodeSpec.require(puzzleCount >= 1 && puzzleCount <= EpisodeSettings.MAX_PUZZLE_COUNT,
            "Choose between 1 and " + EpisodeSettings.MAX_PUZZLE_COUNT + " puzzles");
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
            return new Draft(new EpisodeSpec("Family Detective Case Files", List.copyOf(puzzles)), activeModel, lastResponseId);
        }
        String prompt = """
            Write %d original DETECTIVE CASES for Puzzle Pop, a family YouTube mystery channel watched by children
            ages 6–18 and their parents. Set kind="visual" for every case. Each case is a tiny, bingeable whodunit:
            something surprising has just happened, three or four suspects appear together in one illustrated scene,
            viewers get exactly ten seconds to study the picture and pick the culprit, and the reveal delivers a
            satisfying "how did I miss that?" moment. Model the energy of the best viral picture-riddle and detective
            channels: an instant hook, clear playful stakes, a line-up of believable suspects, one fair visual clue,
            and a reveal that makes families argue happily about who they picked. Never a worksheet or IQ test.

            WHAT MAKES A CASE GO VIRAL — apply every point:
            1. INSTANT HOOK: the first words grab attention and the premise lands at once. Be specific: name the
               object, the place, and who is upset. Generic premises are boring. Use the hook style from the CASE
               BLUEPRINT when one is supplied.
            2. PLAYFUL STAKES: it matters to someone, yet stays light. Vary what is at stake from case to case;
               do not make every case about an event that is about to start.
            3. BELIEVABLE SUSPECTS: three or four distinct suspects, each with a clear visual role and a reason they
               could have done it (everyone was near the scene). Suspects may be children, adults, animals, robots,
               or friendly fantasy creatures. All must look equally plausible at first glance.
            4. RED HERRINGS ON EVERY INNOCENT SUSPECT: each innocent suspect carries a tempting, innocent detail
               that points toward the crime at first glance (also has flour on their hands but no frosting; also
               wears boots, but they are dry; also holds a paintbrush, but in the wrong color). At first glance
               every suspect should look guilty. The decisive clue must cleanly rule out every decoy so exactly one
               answer remains.
            5. ONE FAIR, DECISIVE CLUE THAT NEEDS TWO LINKED OBSERVATIONS: the viewer must connect a detail at the
               scene of the crime with a matching detail on exactly one suspect. Neither detail alone solves it.
               Example: all three suspects have frosting on their hands, but the cake's missing slice has a pink
               layer, and only one suspect's frosting is pink. Or: several paw prints cross the floor, but only one
               trail has a three-toed print, and only one pet has a bandaged paw that makes that shape. Or: everyone
               says they stayed indoors, several carry umbrellas, but only one umbrella is dripping onto the rug.
            6. THE AHA REVEAL: once pointed out, the clue feels obvious and clever, never arbitrary or unfair.
               Viewers should want to rewatch and comment which suspect they picked.

            CASE FORMAT: the studio supplies a CASE BLUEPRINT for each case (format, world, suspect cast, hook style,
            suspect count, answer letter, and sometimes a twist). Follow it exactly. It exists because separate
            requests otherwise drift into the same case again and again. Some formats have a witness or an honest
            suspect as the answer instead of a culprit; always build the case so the blueprint's answer letter is the
            one suspect that answers the question. If no blueprint is supplied, choose a clue format yourself and
            make it different from the history.

            ANTI-REPETITION RULES — the recent history shows these patterns were overused, so avoid them:
            - Do not write setups shaped like "X happened just before the party/show/contest! Three helpers are
              nearby." Never use the words "helpers" or "nearby" for the suspects, and avoid "just before".
            - Do not default to "the missing object is hidden on the culprit" or "a mark matches a pattern" unless
              the blueprint asks for that format.
            - Avoid the overused title endings Secret, Trick, Mystery, Surprise, and Trouble. Vary title shapes:
              "The Case of ...", a question, a punchy headline, or a character's name.
            - Vary the opening words of the setup, the question wording, and the reveal wording across cases.

            SAFETY AND TONE: cozy, funny mysteries, never scary. Allowed "crimes": a missing snack or cake, a swapped or
            vanished trophy, an item borrowed without asking, a harmless prank, a broken vase, an escaped pet, a spoiled
            surprise, a small fib. Never use murder, death, weapons, injury, kidnapping, real danger, bullying, police
            violence, or humiliating body or medical clues. Guilt must come only from visible physical evidence or a
            broken claim — never from facial expression, skin tone, disability, body shape, age, gender, culture,
            clothing style, or appearance. Keep every suspect's expression equally calm or equally surprised.

            DIFFICULTY: MEDIUM-HARD — noticeably harder than a simple spot-the-odd-one-out, yet always fair. Harden
            the case with these techniques:
            - Decoys: every innocent suspect has a tempting detail, so no suspect is the only one with anything unusual.
            - Two links: the answer comes from matching a scene detail to a suspect detail, never one lone oddity.
            - Quiet clue: the decisive detail is not the brightest, largest, or most central thing in the picture,
              and it is not spotlit. It sits naturally in the scene with the same visual weight as the decoys.
            - Comparison: the difference between the real clue and the decoys is specific (a color, a shape, which
              side, wet versus dry, full versus empty) rather than present versus absent.
            It must still be fair: once found, every detail is large, sharp, and readable on a phone, and a sharp
            viewer can solve it within ten seconds. A good target: most families need the full ten seconds, and
            some get it wrong because a decoy fooled them. Never make it hard through tricky English, specialist
            knowledge, arithmetic, clocks, tiny hidden objects, or multi-step logic grids.
            CAUSAL CLUE CHECK: the clue must prove the event asked about, not merely resemble or be associated
            with it. Check the physical chain from action to trace: the right surface must touch, mark, cast,
            carry, or change the right object in the right place and direction. Matching patterns alone do not
            prove that someone sat, touched, carried, opened, or moved something. For fantasy, state one simple
            rule in facts and show it clearly. If you cannot explain the cause in one plain sentence, redesign it.

            USE THE REFERENCE FOR FORMAT ONLY — NEVER REUSE ITS CONTENT. The user supplied a transcript only to
            show the desired brisk, illustrated, story-question-reveal rhythm. Do not copy, paraphrase, remix, or
            make a close variation of its scenes, characters, clues, or answers. Specifically avoid: a ladder fall
            and suspected attacker; expecting twins or baby clothes; divers' oxygen/fins or survival readiness;
            a restaurant bill paid by phone; a stolen dress or fitting room; a sleeping library reader; a fake
            royal guard or wrong shield symbol; club entry wristbands; opening a jar with warm water; forged
            graduation papers; a death or scarf-as-weapon; a cracked branch endangering someone; a torn gift-wrap
            clue; dentures; and fishing rods or biggest-fish clues.

            FRESHNESS: invent a new premise, setting, suspect cast, clue object, question wording, and reveal each
            time. Check the supplied recent/current-episode history before drafting: never repeat or lightly rephrase
            a listed case, its distinctive scene, prop, clue, or solution. Vary the worlds across the episode:
            bakeries, museums, school fairs, campsites, zoos, space stations, pirate ships, talent shows, sports days,
            castles, science labs, farms, and friendly monster towns are options, not a checklist. Let the brief and
            history guide the idea. Novelty means a genuinely new scene and deduction, not a bizarre rule.

            CHALLENGE DESIGN: exactly three or four suspects with consecutive OPTION letters A/B/C or A/B/C/D; never
            five. Suspects stand clearly separated left to right in OPTION order. Use the suspect count and answer
            letter from the CASE BLUEPRINT. Never accuse without visible proof.

            WRITE THE FIELDS THIS WAY:
            title: a punchy case title, max 48 characters, in a fresh shape (see the anti-repetition rules); episode
            title max 65.
            setup: one exciting hook in very simple English, max 155 characters, written as 2–4 short sentences.
            Start with the blueprint's hook style, then say what happened, where, and why it matters, and introduce
            the suspects by their vivid roles (for example "a chef, a clown, and a robot"). Never reveal the clue.
            question: one direct detective question in the blueprint's question shape, max 10 words and 60
            characters, using only very common words. A six-year-old or a beginner
            English learner must understand it at once. Never use hard words such as culprit, impostor, or fibbing.
            facts: zero or one line, max 65 characters, only for a shared claim or one essential fictional rule.
            choices: 3–4 suspects with consecutive OPTION letters from A. label: a short, unique first name, max 24
            characters, never a role word, because narration refers to suspects only by letter. statement: max 100
            characters for the artist: the suspect's role, look, and any red-herring or decisive detail they carry.
            explanation: the reveal in very simple English. CHARACTER COUNT IS CHECKED BY CODE: aim for 50–75
            characters and 9–13 words; anything over 85 characters or 18 words is rejected. Name the right letter
            first, then the two linked details in the fewest plain words, e.g. "Suspect B! B has pink frosting,
            like the cake." Count the characters before returning; if it is long, cut words, not clues.
            sceneDescription: max %s characters. Describe the scene of the case, including visible evidence of what
            happened (the half-eaten cake, the empty trophy stand), then exactly the 3–4 suspects in left-to-right
            OPTION order with role, pose, and specified details. State precisely where the decisive clue is and how
            large it is, and the exact red-herring detail each innocent suspect carries and why it does NOT match.
            Give the decisive detail and the decoys similar size and visual weight. No
            extra suspect-like figures. No text, letters, numbers, labels, badges, or answer marks; the application
            adds option letters separately. Wide composition; keep the clue large, unobstructed, and above the
            bottom 16 percent of the frame.
            thinkSeconds: use exactly 10 for every visual case.

            Use VERY SIMPLE, beginner-friendly English in every viewer-facing field (title, setup, question, facts,
            explanation): short sentences, only common everyday words, simple present or past tense, no idioms, no
            hard words such as culprit, impostor, fibbing, vanished, evidence, or alibi. Keep it warm and exciting,
            like a fun detective telling a young friend what just happened. Avoid filler, formal wording, and generic questions such as "Which one is different?" when a
            specific detective question works.

            BEFORE RETURNING: check that each case has a one-sentence hook and playful stakes; that every innocent
            suspect has a tempting red herring and each one is cleanly ruled out; that the answer needs two linked
            observations and could not be guessed from one odd-looking suspect; that exactly one suspect is provably guilty
            from the planned image; that the clue is visible at phone size; that its physical cause and location
            genuinely follow from the event in the question; and that the reveal explains that proof without adding
            facts. Ask whether the same visual trace could come from an ordinary unrelated action; if so, redesign
            it. Compare all premises and clues against the included history and the transcript exclusion list above.
            Redesign anything copied, confusing, too easy, too obscure, repetitive, or boring. Return only the
            structured data, no commentary.
            Original creative brief follows:
            """.formatted(puzzleCount, recovery ? "900" : (puzzleCount >= 4 ? "1100" : "1400")) + brief + recentTitleSuffix(recentPuzzleTitles)
            + (recovery ? "\nRECOVERY MODE: A prior response could not be decoded. Return the complete schema only. Keep every field concise, especially sceneDescription; do not omit any puzzle or use markdown.\n" : "")
            + operatorSuffix(operatorDirection);
        var response = client.responses().create(ResponseCreateParams.builder().model(activeModel).input(prompt)
            // Creative drafts favor faster, lower-cost exploration; the independent review
            // below remains medium effort before artwork can proceed.
            .store(false).reasoning(Reasoning.builder().effort(ReasoningEffort.LOW).build())
            .maxOutputTokens(4000).text(EpisodeSpec.class).build());
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
        try {
            spec.validateNewChoices();
            return new Draft(spec, activeModel, response.id());
        } catch (IllegalArgumentException invalid) {
            // A single field slightly over its limit should not discard a paid, otherwise good puzzle.
            // The creator's click authorizes one targeted repair; the service never loops.
            if (puzzleCount != 1) throw invalid;
            return repairFormat(spec, invalid.getMessage(), activeModel);
        }
    }

    private Draft repairFormat(EpisodeSpec draft, String problem, String activeModel) {
        String prompt = """
            You are fixing a Puzzle Pop detective case that failed a local format check: %s
            Current lengths and limits (characters unless marked words):
            %s
            Return the complete corrected structured object with exactly this one case. Change ONLY the fields marked
            NEEDS FIX; copy every other field exactly, including the answer, suspects, their order, and the scene. To
            shorten a field, cut words while keeping the same meaning and clue; aim well below each limit. Keep VERY
            SIMPLE beginner English, kind="visual", thinkSeconds=10, and 3–4 choices with consecutive letters from A.
            Count characters and words before returning.
            Case to fix: %s
            """.formatted(problem, formatReport(draft), json.writeValueAsString(draft));
        var response = client.responses().create(ResponseCreateParams.builder().model(activeModel).input(prompt)
            .store(false).reasoning(Reasoning.builder().effort(ReasoningEffort.LOW).build())
            .maxOutputTokens(4000).text(EpisodeSpec.class).build());
        EpisodeSpec repaired = response.output().stream().flatMap(i -> i.message().stream())
            .flatMap(m -> m.content().stream()).flatMap(c -> c.outputText().stream()).findFirst()
            .orElseThrow(() -> new IllegalStateException("No complete structured puzzle repair returned"));
        repaired = normalizeOptionIds(repaired);
        EpisodeSpec.require(repaired.puzzles() != null && !repaired.puzzles().isEmpty(), "The format repair returned no puzzle; retry generation");
        if (repaired.puzzles().size() != 1) repaired = new EpisodeSpec(repaired.title(), List.of(repaired.puzzles().getFirst()));
        try {
            repaired.validateNewChoices();
        } catch (IllegalArgumentException stillInvalid) {
            throw new IllegalArgumentException(stillInvalid.getMessage()
                + ". One automatic format repair was already tried", stillInvalid);
        }
        return new Draft(repaired, activeModel, response.id());
    }

    static String formatReport(EpisodeSpec spec) {
        var report = new StringBuilder();
        report.append(lengthLine("episode title", spec.title(), 65));
        if (spec.puzzles() != null) for (var p : spec.puzzles()) {
            if (p == null) continue;
            report.append(lengthLine("title", p.title(), 48))
                .append(lengthLine("setup", p.setup(), 155))
                .append(lengthLine("question", p.question(), 60))
                .append(wordLine("question", p.question(), 10));
            if (p.facts() != null) for (String fact : p.facts()) report.append(lengthLine("fact", fact, 65));
            if (p.choices() != null) for (var c : p.choices()) {
                if (c == null) continue;
                report.append(lengthLine("choice " + c.id() + " label", c.label(), 24))
                    .append(lengthLine("choice " + c.id() + " statement", c.statement(), 100));
            }
            report.append(lengthLine("explanation", p.explanation(), 85))
                .append(wordLine("explanation", p.explanation(), 18))
                .append(lengthLine("sceneDescription", p.sceneDescription(), 1600));
        }
        return report.toString();
    }

    private static String lengthLine(String name, String value, int max) {
        int length = value == null ? 0 : value.length();
        return "- " + name + ": " + length + " (max " + max + ")"
            + (value == null || value.isBlank() || length > max ? " NEEDS FIX" : "") + "\n";
    }

    private static String wordLine(String name, String value, int max) {
        int words = value == null || value.isBlank() ? 0 : value.trim().split("\\s+").length;
        return "- " + name + ": " + words + " words (max " + max + " words)" + (words > max ? " NEEDS FIX" : "") + "\n";
    }

    /** Models occasionally spell identifiers as "OPTION C"; storage uses canonical A–D IDs. */
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
        String prompt = """
            You are the independent case editor and answer checker for a family detective-mystery YouTube channel.
            Review the supplied cases as a skeptical viewer would, then solve each one without seeing its proposed
            answer or explanation. Return one finding per puzzle, in order, with puzzleNumber starting at 1,
            independentlySolvedAnswerId matching a supplied OPTION letter (or NONE if the scene plan is ambiguous),
            fair boolean, and concise notes (max 220 characters) naming the proof and why the closest alternative fails.

            Match the intended format: a short whodunit with an instant hook and playful stakes; a direct detective
            question; three or four believable suspects; a red herring on every innocent suspect that the decisive clue rules out;
            and a clear, satisfying visual reveal. Judge for children ages 6–18 watching with parents.
            Difficulty should be MEDIUM-HARD and fair in about ten seconds: a real challenge where decoys fool some
            viewers, but never a frustrating hunt. The answer should need two linked observations.
            Usually one small inference links the trace at the scene to the matching trace on one suspect, or a
            broken claim to visible proof. The clue should feel meaningful in the case—not a random mismatch—and be
            easy to explain in simple spoken English. Ask: would this hook make a viewer stop scrolling, can they
            understand the question at once, and will the answer feel earned when the clue is revealed?
            Some cases ask for a witness, the honest suspect, or the fake instead of a culprit; judge them by the same
            fairness rules, and solve for whichever suspect answers the question asked.
            Mark fair=false for a giveaway where the answer is the only suspect with anything unusual, where the
            answer can be picked from one lone oddity without linking two details, or where a red herring is not
            actually ruled out by visible evidence.

            Mark fair=false for multiple defensible answers, missing or unstated facts, implausible cause and
            effect, evidence that could not be shown clearly in one image, tiny hidden-object searches, or an
            answer that depends on expression, stereotype, appearance, specialized knowledge, arithmetic, number
            patterns, time calculations, truth tables, schoolwork, complex English, or guilt that is not proven by
            visible evidence or a broken stated claim. Require exactly three or four consecutive supplied OPTION letters (A/B/C or
            A/B/C/D), distinct plausible candidates, and a scene plan that explicitly depicts the evidence and
            ordinary comparison cases. Check the full physical chain: the action asked about must plausibly create
            the depicted trace on that exact surface, in that exact location and orientation. A matching mark,
            color, shape, or pattern is not proof by itself; reject it if the same mark could come from normal
            unrelated activity or if the trace is on an area the event would not touch. For a fantasy cause,
            require one simple stated rule and a visible demonstration. The correct answer must be uniquely
            supported by the plan, without an unstated assumption.

            Keep the collection fresh and coherent: reject an exact or lightly rephrased premise, repeated scene,
            repeated clue prop, or repeated answer path within this episode. Different basic logic families may
            recur across a larger channel catalogue; do not reject a sound puzzle just because it uses a familiar
            broad reasoning skill, but reject two consecutive cases with the same case type or clue mechanism.
            Reject repetitive generic "Which one is different?" variants. Check that the
            concepts are not copied from the user's reference transcript: no ladder-fall attacker, twins/baby
            outfits, diver gear survival, phone-paid restaurant bill, stolen fitting-room dress, sleeping library
            reader, fake royal shield, club wristband, warm jar lid, forged diploma, death/scarf clue, cracked
            branch danger, torn gift wrap, dentures, or fishing-rod/biggest-fish scene. Keep cases cozy and
            playful: harmless mischief such as a missing snack, a vanished trophy, a prank, a broken vase, or a small
            fib is welcome; reject violence, weapons, injury, death, kidnapping, serious danger, bullying, or
            humiliating body/medical clues.

            Do not demand an arbitrary multi-step logic puzzle to increase difficulty; the right kind of difficulty
            is decoys plus one linked, fair comparison. Do not reject a case because it takes a careful second look.
            This is a concept review only; an actual-image blind visual check follows. Be strict about correctness
            but judge the intended short detective-case format fairly. Questions and scene plans: """
            + json.writeValueAsString(questions)
            + operatorSuffix(operatorDirection);
        var response = client.responses().create(ResponseCreateParams.builder().model(activeModel).input(prompt)
            .store(false).reasoning(Reasoning.builder().effort(ReasoningEffort.MEDIUM).build())
            .maxOutputTokens(5000).text(Review.class).build());
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
        artwork(puzzle, directory, index, requestedImageModel, operatorDirection, false);
    }

    /** One bounded repair pass prevents a first image mismatch from becoming a manual dead end. */
    void repairArtwork(EpisodeSpec.Puzzle puzzle, Path directory, int index, String requestedImageModel,
                       String operatorDirection, String repairBrief) throws Exception {
        String repair = "\n\nREPAIR PASS — correct only the failed visual details below while keeping the canonical scene plan authoritative. "
            + "Do not add text, labels, arrows, circles, or extra candidates.\n" + repairBrief;
        artwork(puzzle, directory, index, requestedImageModel, operatorDirection + repair, true);
    }

    private void artwork(EpisodeSpec.Puzzle puzzle, Path directory, int index, String requestedImageModel,
                         String operatorDirection, boolean replaceExisting) throws Exception {
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
            Compose as a wide establishing frame that fills the canvas with the actual scene. Keep every
            candidate, their hands, feet, and decisive evidence inside a generous central safe area: no cropped
            heads, feet, shadows, reflections, or proof objects. Use a medium-wide camera, not close-ups.
            Leave lettering to the application: NO text, numbers, labels, speech bubbles, titles, border,
            logo or watermark anywhere. No answer highlights. Do not show concealed contents.
            Exact scene brief:
            """ + puzzle.sceneDescription();
        if ("visual".equals(puzzle.kind())) prompt = """
            Use case: illustration-story. Create a premium 16:9 family DETECTIVE CASE illustration for children,
            teens, and parents: the scene of a playful mystery moments after it happened. Keep the polished 2D
            mystery-comic style: natural anatomy, refined ink contours, rich teal, warm amber and coral, a touch of
            cinematic detective lighting (a warm key light and gentle rim light, never dark or scary), engaging
            characters, and detailed but calm scenery. It must feel vibrant, intriguing, and intelligent, never
            preschool, babyish, gloomy, generic, or like stock clip-art. The evidence of what happened (for example
            the half-eaten cake or the empty trophy stand) must be clearly visible in the scene.
            This is a pure, unlabelled scene placed inside a separate application frame. Exactly three or four
            suspects specified in the scene plan must appear left-to-right in matching OPTION order.
            Treat each suspect as a clear visual lane with enough space to distinguish them, while arranging them
            naturally inside a coherent case moment rather than as a sterile police lineup. Leave the bottom 16 percent of
            every candidate lane visually quiet—floor, table edge, or background only—so the application can place one
            OPTION letter below that subject without covering a face or a clue. Keep the decisive clue above this quiet strip.
            No other candidate-like
            people, mannequins, portraits, or background figures that could be mistaken for an option. Show all
            full bodies and any floor/shadow/reflection evidence completely inside the canvas. The important proof
            must be large, sharp, physically coherent, and visible at phone size—not hidden, covered, cropped,
            implied, or merely described in the prompt. Keep all suspects comparable within the case so the
            visible clue fairly supports one answer without making the others absurd or obviously different.
            Draw every red-herring detail the scene plan gives the innocent suspects exactly as described, clearly
            distinguishable from the decisive clue so the answer stays unique.
            One fair, case-relevant visual clue, a medium-hard challenge for family viewers ages 6–18, readable at
            phone size once found. It may be one detail or a compact pair of linked details; keep the culprit's part
            close enough for one answer-highlight circle. Integrate the clue naturally: do not spotlight, center, or
            enlarge it beyond the decoys, and give each red-herring detail similar size and visual weight, so the
            viewer must compare suspects carefully. Never make it a tiny hunt or an instant giveaway.
            Give every suspect an equally calm or equally surprised expression so faces do not give the culprit
            away. Friendly make-believe, including harmless monsters or fantasy, is welcome; never scary. No text, labels, numbers, logos,
            watermarks, arrows, rings or answer highlights. No plastic 3D or preschool clip-art.
            The following scene plan is authoritative, especially its stated physical evidence and candidate order:
            """ + puzzle.sceneDescription();
        prompt += """

            PRE-SUBMISSION ACCEPTANCE CHECK — before delivering the image, verify that every specified candidate
            is present once, in the required left-to-right order, fully visible, and visually comparable; the story
            moment reads clearly; the correct clue is large, sharp, physically coherent, and visible at phone size;
            its location and shape fit the stated action, and the art does not turn a mere resemblance into proof;
            and the quiet bottom option-letter strip remains clear in every candidate lane. The image must pass raw art conformance and a later blind visual solve without
            relying on the written scene plan. If any requirement conflicts, prioritize the exact scene plan and
            visual proof. Do not add text, OPTION badges, arrows, circles, watermarks, logos, or answer hints.
            """
            + operatorSuffix(operatorDirection);
        Path artwork = directory.resolve("art-" + index + ".png");
        if (Files.exists(artwork) && !replaceExisting) return; // Saved successful output is reused on regular retries.
        if (replaceExisting && Files.exists(artwork))
            Files.copy(artwork, directory.resolve("art-" + index + "-attempt-1-rejected.png"), StandardCopyOption.REPLACE_EXISTING);
        if (!"live".equals(artworkMode)) {
            var placeholder = new BufferedImage(1536, 864, BufferedImage.TYPE_INT_RGB);
            var g = placeholder.createGraphics();
            g.setColor(new Color(24, 39, 57)); g.fillRect(0, 0, 1536, 864);
            g.setColor(Color.WHITE); g.setFont(new Font("SansSerif", Font.BOLD, 42));
            g.drawString("OFFLINE LAYOUT TEST — NOT FINAL ARTWORK", 150, 420); g.dispose();
            ImageIO.write(placeholder, "png", artwork.toFile());
        } else {
            var result = client.images().generate(ImageGenerateParams.builder().model(activeImageModel)
                .prompt(prompt).size("1536x864").quality(imageQuality(activeImageModel)).n(1).build());
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
            new Provenance("live".equals(artworkMode) ? activeImageModel : "offline-placeholder", "1536x864", imageQuality(activeImageModel).asString(),
                Instant.now(), prompt, !"live".equals(artworkMode))));
    }

    private static ImageGenerateParams.Quality imageQuality(String model) {
        // Maximum fidelity is reserved for the current premium 2.5 models. Older image
        // models retain their portable high-quality setting rather than receiving an
        // unsupported parameter.
        // HIGH is the production default: it protects visual quality without making
        // every exploratory puzzle pay the premium MAX output-token rate.
        return ImageGenerateParams.Quality.HIGH;
    }

    ArtworkConformance reviewArtwork(Path artwork, EpisodeSpec.Puzzle puzzle, String requestedModel) throws Exception {
        String activeModel = requestedModel == null || requestedModel.isBlank() ? model : requestedModel.trim();
        if (!"live".equals(generationMode)) return new ArtworkConformance(true, "", "Offline mode: no AI conformance check.");
        String prompt = """
            Act as a strict production art director. Compare this raw, unlabelled 16:9 detective-case illustration to the
            canonical scene plan below. This is a CONFORMANCE check, not the final blind puzzle solve.
            Accept only if: the scene reads as the supplied case moment, with the evidence of what happened visible; exactly 3 or 4 suspects are present in the required left-to-right order;
            no confusing extra candidate-like figures exist; all candidates and proof objects are fully visible;
            the stated clue or compact linked clue-pair is visibly real, readable at phone size, and supports one answer;
            its visible location and form are physically consistent with the action the story claims (do not accept
            a mark on an untouched surface or a resemblance that does not establish contact);
            the innocent suspects make the comparison fair, and every red-herring detail in the plan is present but visibly does not
            match the decisive clue; the decisive clue is not spotlit or oversized compared with the decoys; no suspect's facial expression gives the answer away; anatomy, lighting, shadows and
            reflections are coherent; and there is no generated text, badge, logo, arrow, answer marker, or watermark.
            If any condition fails, set acceptable=false and provide a short, concrete repairBrief describing only
            the visual correction needed. Never invent a different puzzle or relax the canonical scene plan.
            Canonical puzzle definition:
            """ + json.writeValueAsString(puzzle);
        var input = EasyInputMessage.builder().role(EasyInputMessage.Role.USER)
            .contentOfResponseInputMessageContentList(List.of(
                ResponseInputContent.ofInputText(ResponseInputText.builder().text(prompt).build()),
                ResponseInputContent.ofInputImage(ResponseInputImage.builder().detail(ResponseInputImage.Detail.HIGH)
                    .imageUrl("data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(artwork))).build())))
            .build();
        var response = client.responses().create(ResponseCreateParams.builder().model(activeModel)
            .inputOfResponse(List.of(ResponseInputItem.ofEasyInputMessage(input))).store(false)
            .maxOutputTokens(900).text(ArtworkConformance.class).build());
        return response.output().stream().flatMap(i -> i.message().stream()).flatMap(m -> m.content().stream())
            .flatMap(c -> c.outputText().stream()).findFirst().orElseThrow();
    }

    /**
     * Blindly solve the delivered frame and return the reveal position in the same vision call.
     * This replaces the former separate review and clue-location calls.
     */
    VisualInspection inspectVisualFrame(Path frame, EpisodeSpec.Puzzle puzzle, String requestedModel) throws Exception {
        String activeModel = requestedModel == null || requestedModel.isBlank() ? model : requestedModel.trim();
        if (!"live".equals(generationMode)) return new VisualInspection(
            new VisualReview(false, "Offline mode: human visual review required."), new ClueLocation(.5, .5, .12, .12, "Offline fixture"), false);
        var message = EasyInputMessage.builder().role(EasyInputMessage.Role.USER)
            .contentOfResponseInputMessageContentList(List.of(
                ResponseInputContent.ofInputText(ResponseInputText.builder().text("""
                    Solve this family detective case from the displayed frame. Read its short question, study the scene and
                    every suspect, then return the visible OPTION letter of the one suspect that answers the question (usually
                    the culprit, but some cases ask for a witness, the honest suspect, or the fake), or NONE if uncertain.
                    clearForKids is true only if there is one fair answer for family viewers ages 6–18. This channel wants
                    medium-hard cases: a clue that needs careful comparison against decoys is GOOD, not a defect, as long as
                    it is clearly visible once found and only one suspect fits.
                    observedClue and issues must describe only visible pixels. A red-herring detail on an innocent suspect is not
                    proof; decide from the evidence that actually links one suspect to the event. Reject multiple fitting answers, tiny or obscured clues,
                    obvious anatomy defects, covered faces, excessive text, or a prematurely highlighted answer. Do not assume a missing
                    shadow or reflection merely because a fantasy character is present. Do not infer a robot from ordinary clothing or disability.
                    Also return x, y, width, and height normalized 0–1 against the ENTIRE 1920×1080 frame: tightly frame the decisive
                    visual proof, or the smallest compact cluster of up to two linked details, never an option card, title, timer,
                    border, or decoration. If uncertain, return 0 for all four coordinates.
                    """).build()),
                ResponseInputContent.ofInputImage(ResponseInputImage.builder().detail(ResponseInputImage.Detail.HIGH)
                    .imageUrl("data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(frame))).build())))
            .build();
        var result = client.responses().create(ResponseCreateParams.builder().model(activeModel)
            .inputOfResponse(List.of(ResponseInputItem.ofEasyInputMessage(message))).store(false)
            .maxOutputTokens(1600).text(VisualInspectionResult.class).build());
        var solved = result.output().stream().flatMap(i -> i.message().stream()).flatMap(m -> m.content().stream())
            .flatMap(c -> c.outputText().stream()).findFirst().orElseThrow();
        boolean answerMatches = puzzle.answerId().equals(solved.answerId());
        var review = new VisualReview(solved.clearForKids() && answerMatches,
            "Blind image solver chose " + solved.answerId() + ". Visible clue: " + solved.observedClue() + " " + solved.issues());
        return new VisualInspection(review, new ClueLocation(solved.x(), solved.y(), solved.width(), solved.height(), solved.notes()), answerMatches);
    }

    private record VisualInspectionResult(String answerId, boolean clearForKids, String observedClue, String issues,
                                          double x, double y, double width, double height, String notes) {}

    VisualReview reviewFrame(Path frame, EpisodeSpec.Puzzle puzzle) throws Exception {
        return reviewFrame(frame, puzzle, model);
    }

    VisualReview reviewFrame(Path frame, EpisodeSpec.Puzzle puzzle, String requestedModel) throws Exception {
        String activeModel = requestedModel == null || requestedModel.isBlank() ? model : requestedModel.trim();
        if (!"live".equals(generationMode)) return new VisualReview(false, "Offline mode: human visual review required.");
        if ("visual".equals(puzzle.kind())) return inspectVisualFrame(frame, puzzle, activeModel).review();
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
            Inspect this final 1920x1080 detective-case frame. The correct answer is OPTION %s.
            Locate the decisive visual clue (or the smallest compact cluster of up to two linked details) that proves it.
            Return x, y, width and height normalized 0–1 against the ENTIRE 1920x1080 frame, tightly enclosing the
            visual evidence inside the illustrated scene. Never select
            a letter badge, title, timer, border, or decoration. The region must be suitable for a bright animated circle
            during the answer reveal; it should not cover unrelated people or objects. If the clue is a missing shadow or
            reflection, frame that relevant ground or mirror area. Never frame a red-herring detail on an innocent
            suspect. Use only visible pixels. Include a brief notes field.
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
                    "Solve this family detective case from the displayed frame. Read its short question and study every suspect, "
                    + "then return the visible OPTION letter of the suspect that answers the question, or NONE if uncertain. A red-herring detail is not proof. "
                    + "clearForKids boolean for family viewers ages 6–18: true only when it is a fair medium-hard challenge, not an instant giveaway; careful comparison against decoys is good, not a defect. "
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
        return titles == null || titles.isBlank() ? "" : "\n\nRECENT PUZZLE HISTORY — NO-COPY LIST (TITLE, PREMISE, QUESTION, CLUE, REVEAL):\n"
            + "Do not repeat or lightly rephrase a listed puzzle, scene, distinctive clue prop, or answer path, including with renamed characters. Broad reasoning skills may recur over time; make this actual story, visual evidence, wording, and reveal genuinely new.\n"
            + titles.trim();
    }
}
