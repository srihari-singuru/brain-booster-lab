package com.brainboosterlab.channel.studio;

import java.nio.file.*;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Local, single-process studio. Slow API/FFmpeg work never holds a database transaction open. */
@Service
class StudioService {
    private record Remediation(int index, String notes) {}
    record View(UUID id, String brief, String status, EpisodeSpec spec, StudioAi.Review review,
                EpisodeNarration narration, NarrationAi.Review narrationReview, NarrationGrounding narrationGrounding, EpisodeSpeech speech,
                String lastError, String failedStage, String scriptModel, String responseId, String narrationModel, String narrationResponseId,
                String narrationGroundingModel, String speechModel, String speechVoice, EpisodeSettings settings, StageInstructions stageInstructions, Instant approvedAt, boolean artworkReady,
                List<StudioAi.VisualReview> visualReviews, boolean previewReady, boolean finalReady, boolean speechReady, boolean puzzleReviewOverridden,
                boolean artworkSelectionFinalized, boolean narrationGroundingOverridden, YouTubeUploadPack youtubeUploadPack,
                String youtubeUploadPrompt, String youtubeUploadModel) {}
    private final StudioRepository repository;
    private final StudioAi ai;
    private final NarrationAi narrator;
    private final SpeechAi speaker;
    private final StudioRenderer renderer;
    private final YouTubeMetadataAi youtubeMetadata;
    private final YouTubeThumbnailRenderer youtubeThumbnails;
    private final Path root;
    private final EpisodeSettings defaults;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "brain-booster-studio-worker");
        thread.setDaemon(true);
        return thread;
    });
    @Autowired
    StudioService(StudioRepository repository, StudioAi ai, NarrationAi narrator, SpeechAi speaker, StudioRenderer renderer,
                  YouTubeMetadataAi youtubeMetadata, YouTubeThumbnailRenderer youtubeThumbnails,
                  @Value("${brain-booster.studio.output-dir:outputs/studio}") String output,
                  @Value("${brain-booster.generation.model:}") String textModel,
                  @Value("${brain-booster.artwork.model:}") String imageModel,
                  @Value("${brain-booster.narration.model:}") String narrationModel,
                  @Value("${brain-booster.speech.model:gpt-4o-mini-tts}") String speechModel,
                  @Value("${brain-booster.speech.voice:cedar}") String speechVoice) {
        this.repository = repository; this.ai = ai; this.narrator = narrator; this.speaker = speaker; this.renderer = renderer;
        this.youtubeMetadata = youtubeMetadata; this.youtubeThumbnails = youtubeThumbnails;
        this.root = Path.of(output).toAbsolutePath().normalize();
        String text = fallback(textModel, "gpt-4o-mini");
        this.defaults = new EpisodeSettings("PUZZLE POP", 3, text, fallback(imageModel, "gpt-image-1"),
            fallback(narrationModel, text), fallback(speechModel, "gpt-4o-mini-tts"), fallback(speechVoice, "cedar"), 1.0);
    }

    /** Keeps focused unit tests and local tooling independent of Spring property wiring. */
    StudioService(StudioRepository repository, StudioAi ai, NarrationAi narrator, SpeechAi speaker, StudioRenderer renderer, String output) {
        this(repository, ai, narrator, speaker, renderer, null, null, output, "gpt-4o-mini", "gpt-image-1", "gpt-4o-mini",
            "gpt-4o-mini-tts", "cedar");
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void recoverInterrupted() {
        for (var episode : repository.findAll()) {
            if (active(episode.status)) {
                episode.failedStage = episode.status;
                episode.status = "INTERRUPTED";
                episode.lastError = "Application stopped during work. Saved scripts and artwork are retained; retry the unfinished stage.";
                save(episode);
            }
        }
    }

    private static boolean active(String status) {
        return Set.of("GENERATING", "REGENERATING_PUZZLES", "REGENERATING_PUZZLE", "REVIEWING", "NARRATING", "NARRATION_GROUNDING", "SPEAKING", "PREPARING_ART", "RENDERING", "PREPARING_UPLOAD_PACK").contains(status);
    }

    synchronized View create(String brief) { return create(brief, defaults); }
    synchronized View create(String brief, EpisodeSettings requestedSettings) {
        EpisodeSpec.require(brief != null && !brief.isBlank() && brief.length() <= 4000, "Brief must be 1–4000 characters");
        EpisodeSettings settings = requestedSettings == null ? defaults : requestedSettings;
        settings.validate();
        var episode = new StudioEpisode(brief.trim());
        episode.settingsJson = json.writeValueAsString(settings);
        return view(repository.saveAndFlush(episode));
    }
    /** A legacy or partially written record must not make the entire history page unavailable. */
    List<View> list() { return repository.findAllByOrderByCreatedAtDesc().stream().map(this::safeView).toList(); }
    View get(UUID id) { return safeView(find(id)); }
    String youtubeUploadPrompt(UUID id) {
        var episode = find(id);
        require(youtubeMetadata != null, "The YouTube upload-pack service is unavailable");
        require(episode.approvedAt != null && renderReady(directory(id), "final"),
            "Approve and render the final video before preparing its YouTube upload prompt");
        if (episode.youtubeUploadPrompt != null && !episode.youtubeUploadPrompt.isBlank()) return episode.youtubeUploadPrompt;
        var spec = spec(episode);
        return youtubeMetadata.defaultPrompt(spec, episode.narrationJson == null ? null : narration(episode, spec), settings(episode).channelName());
    }

    /** The brief remains editable only until its first generated script makes it an auditable production record. */
    synchronized View updateBrief(UUID id, String brief) {
        EpisodeSpec.require(brief != null && !brief.isBlank() && brief.length() <= 4000, "Brief must be 1–4000 characters");
        var episode = find(id);
        require(episode.specJson == null, "This episode already has a puzzle script. Start a new episode to use a different prompt.");
        episode.brief = brief.trim();
        save(episode);
        return view(episode);
    }

    /**
     * Slow AI and FFmpeg work runs independently from the browser request. The saved status is
     * returned immediately so the UI can poll it and safely survive a refresh or a closed tab.
     */
    synchronized View startGenerate(UUID id) { return start(id, "GENERATING", () -> generate(id)); }
    synchronized View startReview(UUID id) { return start(id, "REVIEWING", () -> review(id)); }
    /** Replaces every independently rejected concept in one new version; the source episode is never overwritten. */
    synchronized View startFailedReviewPuzzlesRegeneration(UUID id) {
        var source = find(id);
        require(source.approvedAt == null, "Approved episode is immutable; create a revision");
        var original = spec(source);
        var failures = reviewFailures(source, original);
        var revision = find(revise(id, original).id());
        // Retain the source findings while regeneration is in-flight or if it fails.
        // This keeps the explicit failed-puzzle recovery action available on refresh.
        revision.reviewJson = source.reviewJson;
        revision.stageInstructionsJson = source.stageInstructionsJson;
        stage(revision, "REGENERATING_PUZZLES");
        launch(revision, () -> regenerateReviewedPuzzles(id, revision.id, failures));
        return view(revision);
    }
    /** Replaces one selected puzzle in a new revision; Review remains a separate manual stage. */
    synchronized View startPuzzleRegeneration(UUID id, int puzzleNumber) {
        var source = find(id);
        require(source.approvedAt == null, "Approved episode is immutable; create a revision");
        require(!active(source.status), "This episode is already working in the background");
        var original = spec(source);
        require(puzzleNumber >= 1 && puzzleNumber <= original.puzzles().size(), "Choose a valid puzzle number");
        var revision = find(revise(id, original).id());
        revision.reviewJson = source.reviewJson;
        revision.stageInstructionsJson = source.stageInstructionsJson;
        stage(revision, "REGENERATING_PUZZLE");
        launch(revision, () -> regenerateSelectedPuzzle(id, revision.id, puzzleNumber - 1));
        return view(revision);
    }
    synchronized View continueWithReviewWarnings(UUID id) {
        var episode = find(id);
        require(episode.approvedAt == null, "Approved episode is immutable; create a revision");
        var spec = spec(episode);
        require(reviewAllowsWarningOverride(episode, spec),
            "The reviewer disagreed about an answer or returned an incomplete review. Resolve that script issue before continuing.");
        episode.puzzleReviewOverridden = true;
        stage(episode, "SCRIPT_REVIEW");
        return view(episode);
    }
    synchronized View startNarration(UUID id) { return start(id, "NARRATING", () -> narration(id)); }
    synchronized View startGroundNarration(UUID id) { return start(id, "NARRATION_GROUNDING", () -> groundNarration(id)); }
    /** Repairs all artwork rejected by the visual artwork check, in a new safe revision. */
    synchronized View startFailedArtworkRegeneration(UUID id) {
        var source = find(id);
        require(source.approvedAt == null, "Approved episode is immutable; create a revision");
        var original = spec(source);
        var failures = artworkFailures(source, original);
        var revision = find(revise(id, original).id());
        inheritForArtworkRemediation(source, revision);
        stage(revision, "PREPARING_ART");
        launch(revision, () -> regenerateArtworkFailures(revision.id, failures));
        return view(revision);
    }
    /** Regenerates one chosen artwork in a new revision; unchanged art/reviews are copied locally. */
    synchronized View startArtworkRegeneration(UUID id, int puzzleNumber) {
        var source = find(id);
        require(source.approvedAt == null, "Approved episode is immutable; create a revision");
        require(!active(source.status), "This episode is already working in the background");
        var original = spec(source);
        require(puzzleNumber >= 1 && puzzleNumber <= original.puzzles().size(), "Choose a valid puzzle number");
        require(reviewPasses(source, original), "Complete or resolve the puzzle review before regenerating artwork");
        var revision = find(revise(id, original).id());
        inheritForArtworkRemediation(source, revision);
        int index = puzzleNumber - 1;
        stage(revision, "PREPARING_ART");
        launch(revision, () -> regenerateArtworkItem(revision.id, index));
        return view(revision);
    }
    /** Repairs all artwork whose final frame failed narration grounding, in a new safe revision. */
    synchronized View startFailedGroundingArtworkRegeneration(UUID id) {
        var source = find(id);
        require(source.approvedAt == null, "Approved episode is immutable; create a revision");
        var original = spec(source);
        var failures = groundingArtworkFailures(source, original);
        var revision = find(revise(id, original).id());
        inheritForArtworkRemediation(source, revision);
        stage(revision, "PREPARING_ART");
        launch(revision, () -> regenerateArtworkFailures(revision.id, failures));
        return view(revision);
    }
    /** Uses the grounding editor's already-produced corrected script, so this is a local, no-credit action. */
    synchronized View applyGroundedNarrationCorrection(UUID id) {
        var episode = find(id);
        require(episode.approvedAt == null, "Approved episode is immutable; create a revision");
        var spec = spec(episode);
        var grounding = narrationGrounding(episode, spec);
        require(grounding.findings().stream().allMatch(f -> f.visualClueConfirmed() && f.optionOnly()),
            "Regenerate the affected artwork first; the grounding editor could not confirm every visual clue.");
        require(grounding.findings().stream().anyMatch(f -> !f.narrationMatchesFrame()),
            "The saved grounding already matches the narration to every frame.");
        var corrected = new NarrationGrounding(grounding.narration(), grounding.findings().stream()
            .map(f -> new NarrationGrounding.Finding(f.puzzleNumber(), true, true, true, f.notes())).toList());
        episode.narrationGroundingJson = json.writeValueAsString(corrected);
        episode.narrationGroundingOverridden = false;
        episode.narrationJson = json.writeValueAsString(corrected.narration());
        episode.speechJson = null; episode.speechModel = null; episode.speechVoice = null;
        try {
            var production = settings(episode);
            if (production.equals(defaults)) renderer.writeNarration(spec, corrected.narration(), directory(id));
            else renderer.writeNarration(spec, corrected.narration(), directory(id), production.channelName());
            stage(episode, "ART_REVIEW");
            return view(episode);
        } catch (Exception ex) { return failed(episode, ex); }
    }
    synchronized View continueWithGroundingWarnings(UUID id) {
        var episode = find(id);
        require(episode.approvedAt == null, "Approved episode is immutable; create a revision");
        var spec = spec(episode);
        require(groundingAllowsWarningOverride(episode, spec),
            "Grounding is incomplete or uses names instead of OPTION letters. Fix those safety-critical issues before continuing.");
        var grounding = narrationGrounding(episode, spec);
        var production = settings(episode);
        episode.narrationGroundingOverridden = true;
        episode.narrationJson = json.writeValueAsString(grounding.narration());
        episode.speechJson = null; episode.speechModel = null; episode.speechVoice = null;
        try {
            if (production.equals(defaults)) renderer.writeNarration(spec, grounding.narration(), directory(id));
            else renderer.writeNarration(spec, grounding.narration(), directory(id), production.channelName());
        } catch (Exception ex) { return failed(episode, ex); }
        stage(episode, "ART_REVIEW");
        return view(episode);
    }
    synchronized View startArtwork(UUID id) { return start(id, "PREPARING_ART", () -> artwork(id)); }
    synchronized View selectArtworkPuzzles(UUID id, List<Integer> requestedPuzzleNumbers) {
        var source = find(id);
        require(source.approvedAt == null, "Approved episode is immutable; create a revision");
        require(source.narrationJson == null, "Select the puzzles before narration starts; create a revision to change a narrated episode");
        var original = spec(source);
        var sourceView = view(source);
        require(sourceView.artworkReady(), "Prepare the complete artwork before choosing puzzles");
        var positions = selectedPuzzlePositions(requestedPuzzleNumbers, original.puzzles().size());
        require(sourceView.visualReviews().size() == original.puzzles().size(),
            "The visual checks are incomplete. Retry the artwork check before choosing puzzles.");
        for (int position : positions) require(sourceView.visualReviews().get(position).acceptable(),
            "Puzzle " + (position + 1) + " did not pass the visual check. Regenerate its artwork or select only puzzles that passed.");
        var selectedPuzzles = positions.stream().map(i -> original.puzzles().get(i)).toList();
        var selectedSpec = new EpisodeSpec(original.title(), selectedPuzzles);
        selectedSpec.validate();

        var sourceSettings = settings(source);
        var revision = new StudioEpisode("Artwork selection of " + id + ": " + source.brief);
        revision.settingsJson = json.writeValueAsString(new EpisodeSettings(sourceSettings.channelName(), selectedPuzzles.size(),
            sourceSettings.textModel(), sourceSettings.imageModel(), sourceSettings.narrationModel(), sourceSettings.speechModel(),
            sourceSettings.speechVoice(), sourceSettings.speechSpeed()));
        revision.specJson = json.writeValueAsString(selectedSpec);
        revision.reviewJson = json.writeValueAsString(selectedReview(source, positions));
        revision.puzzleReviewOverridden = source.puzzleReviewOverridden;
        revision.artworkSelectionFinalized = true;
        revision.scriptModel = "artwork-selection (source: " + source.scriptModel + ")";
        revision.responseId = source.responseId;
        revision.status = "ART_REVIEW";
        repository.saveAndFlush(revision);
        try {
            Path sourceDir = directory(source.id), targetDir = directory(revision.id);
            Files.createDirectories(targetDir);
            for (int targetIndex = 0; targetIndex < positions.size(); targetIndex++) {
                int sourceIndex = positions.get(targetIndex);
                copyIfPresent(sourceDir.resolve("art-" + sourceIndex + ".png"), targetDir.resolve("art-" + targetIndex + ".png"));
                copyIfPresent(sourceDir.resolve("provenance-" + sourceIndex + ".json"), targetDir.resolve("provenance-" + targetIndex + ".json"));
                copyIfPresent(sourceDir.resolve("overlay-" + sourceIndex + ".json"), targetDir.resolve("overlay-" + targetIndex + ".json"));
            }
            renderer.previews(selectedSpec, targetDir, sourceSettings.channelName());
            for (int targetIndex = 0; targetIndex < positions.size(); targetIndex++) {
                int sourceIndex = positions.get(targetIndex);
                copyIfPresent(sourceDir.resolve("visual-review-" + sourceIndex + ".json"), targetDir.resolve("visual-review-" + targetIndex + ".json"));
                Files.writeString(targetDir.resolve("visual-review-" + targetIndex + ".sha256"), digest(targetDir.resolve("question-" + targetIndex + ".png")));
            }
            return view(revision);
        } catch (Exception ex) { return failed(revision, ex); }
    }
    synchronized View startSpeech(UUID id) { return start(id, "SPEAKING", () -> speech(id)); }
    synchronized View startRender(UUID id, boolean draft) { return start(id, "RENDERING", () -> render(id, draft)); }
    synchronized View startYouTubeUploadPack(UUID id, String prompt) {
        var episode = find(id);
        require(youtubeMetadata != null && youtubeThumbnails != null, "The YouTube upload-pack service is unavailable");
        String cleanPrompt = YouTubeMetadataAi.sanitizePrompt(prompt);
        require(prompt != null && cleanPrompt.length() <= YouTubeMetadataAi.MAX_PROMPT_CHARS,
            "Upload prompt is over 16000 characters after hidden formatting characters are removed. Shorten the prompt before retrying.");
        require(renderReady(directory(id), "final") && episode.approvedAt != null,
            "Approve and render the final video before preparing its YouTube upload details");
        require(!active(episode.status), "This episode is already working in the background");
        episode.youtubeUploadPrompt = cleanPrompt.isBlank() ? youtubeMetadata.defaultPrompt(spec(episode),
            episode.narrationJson == null ? null : narration(episode, spec(episode)), settings(episode).channelName()) : cleanPrompt.trim();
        episode.youtubeUploadPackJson = null;
        episode.youtubeUploadModel = null;
        save(episode);
        stage(episode, "PREPARING_UPLOAD_PACK");
        return launch(episode, () -> prepareYouTubeUploadPack(id));
    }

    private View prepareYouTubeUploadPack(UUID id) {
        var episode = find(id);
        try {
            var spec = spec(episode);
            EpisodeNarration narration = episode.narrationJson == null ? null : narration(episode, spec);
            var production = settings(episode);
            var pack = youtubeMetadata.generate(spec, narration, production.channelName(), production.textModel(), episode.youtubeUploadPrompt);
            pack.validate(spec);
            youtubeThumbnails.render(spec, directory(id), production.channelName(), pack);
            episode.youtubeUploadPackJson = json.writeValueAsString(pack);
            episode.youtubeUploadModel = youtubeMetadata.model(production.textModel());
            stage(episode, "UPLOAD_PACK_READY");
            return view(episode);
        } catch (Exception ex) { return failed(episode, ex); }
    }
    synchronized View startHighlight(UUID id, List<SceneOverlay.Region> clues) { return start(id, "PREPARING_ART", () -> highlight(id, clues)); }
    synchronized View startRestyle(UUID id, List<SceneOverlay.Region> clues) { return start(id, "PREPARING_ART", () -> restyle(id, clues)); }

    /** Creates an editable local copy of delivered artwork without regenerating or uploading images. */
    synchronized View visualRevision(UUID id) { return restyle(id); }

    private View start(UUID id, String status, Runnable action) {
        var episode = find(id);
        require(!active(episode.status), "This episode is already working in the background");
        stage(episode, status);
        return launch(episode, action);
    }
    /** Starts already-persisted work and always settles the visible episode state on worker failure. */
    private View launch(StudioEpisode episode, Runnable action) {
        try {
            worker.execute(() -> {
                try {
                    action.run();
                } catch (Exception exception) {
                    // Individual stages deliberately validate their preconditions before their
                    // own try/catch blocks. The worker boundary must still settle the saved
                    // state if one of those checks (or a future stage) throws first.
                    recordWorkerFailure(episode.id, exception);
                }
            });
        } catch (RuntimeException exception) {
            return failed(episode, exception);
        }
        return view(episode);
    }

    private synchronized void recordWorkerFailure(UUID id, Exception exception) {
        try {
            var episode = find(id);
            if (active(episode.status)) failed(episode, exception);
        } catch (Exception ignored) {
            // This is a final safety net. A server restart will recover any still-active record.
        }
    }

    @PreDestroy
    void stopWorker() { worker.shutdownNow(); }

    synchronized View settings(UUID id, EpisodeSettings requestedSettings) {
        requestedSettings.validate();
        var episode = find(id);
        require(episode.approvedAt == null, "Approved episodes are immutable; create a revision to change their settings");
        if (episode.specJson != null) require(settings(episode).puzzleCount() == requestedSettings.puzzleCount(),
            "Puzzle count belongs to the script. Create a new episode to change it after script generation.");
        episode.settingsJson = json.writeValueAsString(requestedSettings);
        save(episode);
        return view(episode);
    }

    synchronized View stageInstructions(UUID id, StageInstructions requested) {
        var episode = find(id);
        require(!active(episode.status), "This episode is already working in the background");
        require(episode.approvedAt == null, "Approved episodes are immutable; create a settings version to change directions");
        StageInstructions instructions = requested == null ? StageInstructions.EMPTY : requested;
        instructions.validate();
        episode.stageInstructionsJson = json.writeValueAsString(instructions);
        save(episode);
        return view(episode);
    }

    /** Creates an exact script copy for changing a completed episode without overwriting its approved assets. */
    synchronized View settingsRevision(UUID id, EpisodeSettings requestedSettings) {
        requestedSettings.validate();
        var source = find(id);
        var sourceSpec = spec(source);
        require(sourceSpec.puzzles().size() == requestedSettings.puzzleCount(),
            "A settings revision keeps its existing puzzle count. Start a new episode to change the count.");
        var revision = new StudioEpisode("Settings revision of " + id + ": " + source.brief);
        revision.specJson = source.specJson;
        revision.settingsJson = json.writeValueAsString(requestedSettings);
        revision.scriptModel = "settings-revision (source: " + source.scriptModel + ")";
        revision.status = "SCRIPT_REVIEW";
        return view(repository.saveAndFlush(revision));
    }

    synchronized View revise(UUID id, EpisodeSpec replacement) {
        replacement.validate();
        var source = find(id);
        var revision = new StudioEpisode("Revision of " + id + ": " + source.brief);
        var sourceSettings = settings(source);
        revision.settingsJson = json.writeValueAsString(new EpisodeSettings(sourceSettings.channelName(), replacement.puzzles().size(),
            sourceSettings.textModel(), sourceSettings.imageModel(), sourceSettings.narrationModel(), sourceSettings.speechModel(),
            sourceSettings.speechVoice(), sourceSettings.speechSpeed()));
        revision.specJson = json.writeValueAsString(replacement);
        revision.scriptModel = "editor-revision (source: " + source.scriptModel + ")";
        revision.status = "SCRIPT_REVIEW";
        // Reuse only exactly unchanged puzzles at the same position. Changed clues get fresh artwork.
        if (source.specJson != null) {
            var original = json.readValue(source.specJson, EpisodeSpec.class);
            try {
                for (int i = 0; i < replacement.puzzles().size(); i++) {
                    if (!original.puzzles().get(i).equals(replacement.puzzles().get(i))) continue;
                    Path sourceDir = directory(id), targetDir = directory(revision.id);
                    if (!Files.isRegularFile(sourceDir.resolve("art-" + i + ".png"))) continue;
                    Files.createDirectories(targetDir);
                    final int index = i;
                    try (var files = Files.list(sourceDir)) {
                        for (Path file : files.filter(f -> f.getFileName().toString().equals("art-" + index + ".png")
                            || f.getFileName().toString().equals("provenance-" + index + ".json")
                            || f.getFileName().toString().equals("overlay-" + index + ".json")
                            || f.getFileName().toString().equals("artwork-conformance-" + index + ".json")
                            || f.getFileName().toString().matches("visual-(?:check|inspection)-" + index + "-[0-9a-f]{64}\\.json")).toList())
                            Files.copy(file, targetDir.resolve(file.getFileName()));
                    }
                    Files.writeString(targetDir.resolve("reused-" + i + ".txt"), "Unchanged artwork reused from episode " + id);
                }
            } catch (java.io.IOException ex) { throw new IllegalStateException("Unable to prepare revision assets; original episode is unchanged", ex); }
        }
        // Original script/assets/approval are untouched. Every revision needs fresh checks and approval.
        return view(repository.saveAndFlush(revision));
    }

    private void regenerateReviewedPuzzles(UUID sourceId, UUID revisionId, List<Remediation> failures) {
        StudioEpisode revision = find(revisionId);
        StudioEpisode source = find(sourceId);
        var original = spec(source);
        try {
            var production = settings(revision);
            var puzzles = new ArrayList<>(original.puzzles());
            var changed = new LinkedHashSet<Integer>();
            String priorTitles = recentPuzzleTitles(revisionId);
            String model = null, responseId = null;
            for (var failure : failures) {
                String rejectedPuzzle = json.writeValueAsString(original.puzzles().get(failure.index()));
                String alreadyInEpisode = java.util.stream.IntStream.range(0, original.puzzles().size())
                    .filter(i -> i != failure.index())
                    .mapToObj(i -> "PUZZLE " + (i + 1) + " CONCEPT TO AVOID: " + puzzleHistoryLine(original.puzzles().get(i)))
                    .collect(java.util.stream.Collectors.joining("\n"));
                String direction = stageInstructions(source).forAction("generate") + "\n\nREMEDIATION: Replace only puzzle " + (failure.index() + 1)
                    + ". It failed an independent review. Keep the brisk, story-led visual mini-mystery format: a fresh, family-safe situation, "
                    + "a direct question, plausible candidates, and one or two linked, clearly pictured clues that make the answer feel earned. "
                    + "Keep the deduction medium and fair for a ten-second solve; do not add arbitrary rules or complexity. "
                    + "Make the new situation, setting, clue object, and question distinct from the rejected puzzle and concepts already in this episode. "
                    + "Rejected puzzle: " + rejectedPuzzle + "\nOther concepts already in this episode that MUST NOT be repeated:\n" + alreadyInEpisode
                    + "\nReviewer note: " + trimForPrompt(failure.notes(), 1000);
                var draft = ai.generate(source.brief, 1, production.textModel(), direction, priorTitles);
                puzzles.set(failure.index(), draft.spec().puzzles().getFirst());
                changed.add(failure.index());
                priorTitles += "- TITLE: " + draft.spec().puzzles().getFirst().title() + " | QUESTION: " + draft.spec().puzzles().getFirst().question() + '\n';
                model = draft.model(); responseId = draft.responseId();
            }
            var replacement = new EpisodeSpec(original.title(), List.copyOf(puzzles));
            replacement.validateNewChoices();
            revision.specJson = json.writeValueAsString(replacement);
            // Regeneration is its own paid action. The operator explicitly starts Review next.
            revision.reviewJson = null;
            revision.puzzleReviewOverridden = false;
            revision.scriptModel = model; revision.responseId = responseId;
            for (int index : changed) clearArtworkForPuzzle(directory(revisionId), index);
            stage(revision, "SCRIPT_REVIEW");
        } catch (Exception ex) { failed(revision, ex); }
    }

    private void regenerateSelectedPuzzle(UUID sourceId, UUID revisionId, int index) {
        StudioEpisode source = find(sourceId), revision = find(revisionId);
        var original = spec(source);
        try {
            var production = settings(revision);
            var puzzles = new ArrayList<>(original.puzzles());
            var history = recentPuzzleTitles(revisionId) + "\nCURRENT SOURCE EPISODE — do not reuse this selected puzzle or its premise:\n"
                + puzzleHistoryLine(original.puzzles().get(index));
            String direction = stageInstructions(source).forAction("generate") + "\n\nCREATOR REQUEST: Replace puzzle " + (index + 1)
                + " only. Make a genuinely different family challenge; do not reuse this premise. The creator will run Review manually afterward.";
            var draft = ai.generate(source.brief, 1, production.textModel(), direction, history);
            puzzles.set(index, draft.spec().puzzles().getFirst());
            var replacement = new EpisodeSpec(original.title(), List.copyOf(puzzles));
            replacement.validateNewChoices();
            revision.specJson = json.writeValueAsString(replacement);
            revision.reviewJson = null;
            revision.puzzleReviewOverridden = false;
            revision.scriptModel = draft.model(); revision.responseId = draft.responseId();
            revision.narrationJson = null; revision.narrationReviewJson = null; revision.narrationGroundingJson = null;
            revision.narrationModel = null; revision.narrationResponseId = null; revision.narrationGroundingModel = null;
            revision.narrationGroundingResponseId = null; revision.narrationGroundingOverridden = false;
            revision.speechJson = null; revision.speechModel = null; revision.speechVoice = null;
            clearArtworkForPuzzle(directory(revisionId), index);
            stage(revision, "SCRIPT_REVIEW");
        } catch (Exception ex) { failed(revision, ex); }
    }

    private void regenerateArtworkItem(UUID revisionId, int index) {
        StudioEpisode revision = find(revisionId);
        try {
            var spec = spec(revision);
            clearArtworkForPuzzle(directory(revisionId), index);
            artwork(revisionId); // Fresh image plus the normal conformance and blind visual checks.
        } catch (Exception ex) { failed(revision, ex); }
    }

    static String puzzleHistoryLine(EpisodeSpec.Puzzle puzzle) {
        String candidates = puzzle.choices() == null ? "" : puzzle.choices().stream()
            .map(choice -> choice.id() + ": " + bounded(choice.label(), 30) + " — " + bounded(choice.statement(), 55))
            .collect(java.util.stream.Collectors.joining("; "));
        // Titles and questions alone were not enough to prevent visually different
        // versions of the same underlying puzzle. Include a bounded clue/scene and
        // candidate fingerprint in the prompt history, while keeping its total size
        // governed by recentPuzzleTitles' existing 9k-character cap.
        return "KIND: " + bounded(puzzle.kind(), 20)
            + " | TITLE: " + bounded(puzzle.title(), 48)
            + " | PREMISE: " + bounded(puzzle.setup(), 120)
            + " | QUESTION: " + bounded(puzzle.question(), 90)
            + " | CANDIDATES: " + bounded(candidates, 260)
            + " | REVEAL LOGIC: " + bounded(puzzle.explanation(), 100)
            + " | VISUAL CLUE/SCENE MECHANISM: " + bounded(puzzle.sceneDescription(), 360);
    }

    private static String bounded(String value, int max) {
        if (value == null) return "";
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= max ? compact : compact.substring(0, max - 1) + "…";
    }

    private void inheritForArtworkRemediation(StudioEpisode source, StudioEpisode revision) {
        revision.reviewJson = source.reviewJson;
        revision.puzzleReviewOverridden = source.puzzleReviewOverridden;
        revision.artworkSelectionFinalized = source.artworkSelectionFinalized;
        revision.narrationJson = source.narrationJson;
        revision.narrationReviewJson = source.narrationReviewJson;
        revision.narrationModel = source.narrationModel;
        revision.narrationResponseId = source.narrationResponseId;
        revision.stageInstructionsJson = source.stageInstructionsJson;
        revision.narrationGroundingJson = null; revision.narrationGroundingModel = null; revision.narrationGroundingResponseId = null;
        revision.narrationGroundingOverridden = false;
        revision.speechJson = null; revision.speechModel = null; revision.speechVoice = null;
        try {
            Path sourceDir = directory(source.id), targetDir = directory(revision.id);
            Files.createDirectories(targetDir);
            for (int i = 0; i < spec(source).puzzles().size(); i++) copyArtworkEvidence(sourceDir, targetDir, i);
        } catch (Exception ex) { throw new IllegalStateException("Unable to preserve unchanged artwork checks in the recovery version", ex); }
        save(revision);
    }

    private void regenerateArtworkFailures(UUID revisionId, List<Remediation> failures) {
        StudioEpisode revision = find(revisionId);
        try {
            var spec = spec(revision);
            var production = settings(revision);
            Path dir = directory(revisionId);
            for (var failure : failures) {
                clearArtworkDerivatives(dir, failure.index());
                ai.repairArtwork(spec.puzzles().get(failure.index()), dir, failure.index(), production.imageModel(),
                    stageInstructions(revision).forAction("artwork"), "A final visual review rejected this image. Correct the visual evidence precisely: " + trimForPrompt(failure.notes(), 1200));
            }
            // The normal preparation path preserves every unaffected asset and re-runs only the
            // cleared visual checks for the changed frames.
            artwork(revisionId);
        } catch (Exception ex) { failed(revision, ex); }
    }

    /** A rendering-only revision: no paid calls, no content changes, no inherited visual approval. */
    synchronized View restyle(UUID id) {
        return restyle(id, null);
    }

    synchronized View restyle(UUID id, List<SceneOverlay.Region> clues) {
        var source = find(id);
        var original = spec(source);
        if (clues != null) {
            EpisodeSpec.require(clues.size() == original.puzzles().size() && clues.stream().allMatch(Objects::nonNull), "Provide one clue region for every puzzle");
            clues.forEach(SceneOverlay.Region::validate);
        }
        for (int i = 0; i < original.puzzles().size(); i++) require(Files.isRegularFile(directory(id).resolve("art-" + i + ".png")),
            "Restyling needs all three saved artworks for a three-puzzle episode, or every artwork in a larger episode; prepare missing artwork first");
        var revision = find(revise(id, original).id());
        // Concept review is valid only because the entire specification is unchanged.
        revision.reviewJson = source.reviewJson;
        revision.scriptModel = source.scriptModel;
        revision.responseId = source.responseId;
        revision.narrationJson = source.narrationJson;
        revision.narrationReviewJson = source.narrationReviewJson;
        revision.narrationModel = source.narrationModel;
        revision.narrationResponseId = source.narrationResponseId;
        stage(revision, "PREPARING_ART");
        try {
            if (clues != null) for (int i = 0; i < original.puzzles().size(); i++) {
                var overlay = new SceneOverlay(SceneOverlay.hash(directory(revision.id).resolve("art-" + i + ".png")),
                    original.puzzles().get(i).answerId(), clues.get(i));
                Files.writeString(directory(revision.id).resolve("overlay-" + i + ".json"), json.writeValueAsString(overlay));
            }
            var production = settings(revision);
            if (production.equals(defaults)) renderer.previews(original, directory(revision.id));
            else renderer.previews(original, directory(revision.id), production.channelName());
            if (revision.narrationJson != null) {
                if (production.equals(defaults)) renderer.writeNarration(original, narration(revision, original), directory(revision.id));
                else renderer.writeNarration(original, narration(revision, original), directory(revision.id), production.channelName());
            }
            stage(revision, "ART_REVIEW");
            return view(revision);
        } catch (Exception ex) { return failed(revision, ex); }
    }

    // Serialize mutations so duplicate clicks cannot launch duplicate paid requests in this local app.
    synchronized View generate(UUID id) {
        StudioEpisode e = find(id);
        var production = settings(e);
        EpisodeSpec partial = e.specJson == null ? null : spec(e);
        int completed = partial == null ? 0 : partial.puzzles().size();
        require(completed < production.puzzleCount(), "This episode already has its complete puzzle script. Create a revision to change it.");
        boolean recovery = e.lastError != null && (e.lastError.startsWith("OpenAI returned an incomplete structured puzzle response")
            || e.lastError.contains("OpenAIInvalidDataException"));
        stage(e, "GENERATING");
        try {
            String direction = stageInstructions(e).forAction("generate");
            String history = recentPuzzleTitles(e.id);
            if (partial != null) for (EpisodeSpec.Puzzle puzzle : partial.puzzles())
                history += "- CURRENT EPISODE (already saved; do not repeat): " + puzzleHistoryLine(puzzle) + '\n';
            String title = partial == null ? "Fresh Family Puzzle Collection" : partial.title();
            for (int number = completed + 1; number <= production.puzzleCount(); number++) {
                String slotDirection = "\n\nEPISODE POSITION " + number + " OF " + production.puzzleCount()
                    + ". Write this as a brisk, story-led visual challenge, not an abstract worksheet. Before drafting, compare "
                    + "the story, clue and reveal against the supplied history. Choose a genuinely fresh situation and avoid "
                    + "repeating the immediately previous question shape or clue mechanism. Vary the kind of story across this "
                    + "episode, but do not force a strange rule or complicated deduction just to appear novel. Keep the clue "
                    + "clear, fair, visible and satisfying in a ten-second solve.";
                var draft = recovery && number == completed + 1
                    ? ai.generateRecovery(e.brief, 1, production.textModel(), direction + slotDirection, history)
                    : ai.generate(e.brief, 1, production.textModel(), direction + slotDirection, history);
                EpisodeSpec.Puzzle puzzle = draft.spec().puzzles().getFirst();
                var puzzles = new ArrayList<>(partial == null ? List.<EpisodeSpec.Puzzle>of() : partial.puzzles());
                puzzles.add(puzzle);
                var candidate = new EpisodeSpec(title, List.copyOf(puzzles));
                try { candidate.validateNewChoices(); }
                catch (Exception ex) {
                    throw new IllegalArgumentException("Puzzle " + number + " did not pass local format checks: " + ex.getMessage()
                        + ". " + completed + " of " + production.puzzleCount() + " valid puzzles are saved; choose Continue to retry only the unfinished portion.", ex);
                }
                partial = candidate;
                e.specJson = json.writeValueAsString(candidate);
                e.scriptModel = draft.model(); e.responseId = draft.responseId();
                save(e); // Preserve each valid paid result before spending on the next puzzle.
                completed++;
                history += "- CURRENT EPISODE (already saved; do not repeat): " + puzzleHistoryLine(puzzle) + '\n';
                recovery = false;
            }
            e.reviewJson = null;
            e.puzzleReviewOverridden = false;
            // Puzzle generation must not spend again on Review or silently replace rejected ideas.
            // Those are separate, creator-clicked stages with separate progress and cost visibility.
            stage(e, "SCRIPT_REVIEW");
            return view(e);
        } catch (Exception ex) { return failed(e, ex); }
    }

    synchronized View review(UUID id) {
        StudioEpisode e = find(id);
        require(e.approvedAt == null, "Approved episode is immutable; create a revision");
        var spec = spec(e);
        require(spec.puzzles().size() == settings(e).puzzleCount(), "Finish generating all configured puzzles before review.");
        stage(e, "REVIEWING");
        try {
            var production = settings(e);
            var review = ai.review(spec, production.textModel(), stageInstructions(e).forAction("review"));
            e.reviewJson = json.writeValueAsString(review);
            stage(e, review.passes(spec) ? "SCRIPT_REVIEW" : "CHANGES_NEEDED");
            return view(e);
        } catch (Exception ex) { return failed(e, ex); }
    }

    synchronized View narration(UUID id) {
        StudioEpisode e = find(id);
        require(e.approvedAt == null, "Approved episode is immutable; create a revision");
        var spec = spec(e);
        require(reviewPasses(e, spec), "Independent reasoning review must pass before narration");
        stage(e, "NARRATING");
        try {
            var production = settings(e);
            String direction = stageInstructions(e).forAction("narration");
            var draft = direction.isBlank()
                ? (production.equals(defaults) ? narrator.write(spec) : narrator.write(spec, production.narrationModel()))
                : narrator.write(spec, production.narrationModel(), direction);
            draft.narration().validate(spec);
            e.narrationJson = json.writeValueAsString(draft.narration());
            e.narrationModel = draft.model(); e.narrationResponseId = draft.responseId();
            e.narrationGroundingJson = null; e.narrationGroundingModel = null; e.narrationGroundingResponseId = null;
            e.narrationGroundingOverridden = false;
            e.speechJson = null; e.speechModel = null; e.speechVoice = null;
            var review = direction.isBlank()
                ? (production.equals(defaults) ? narrator.review(spec, draft.narration()) : narrator.review(spec, draft.narration(), production.narrationModel()))
                : narrator.review(spec, draft.narration(), production.narrationModel(), direction);
            e.narrationReviewJson = json.writeValueAsString(review);
            if (production.equals(defaults)) renderer.writeNarration(spec, draft.narration(), directory(id));
            else renderer.writeNarration(spec, draft.narration(), directory(id), production.channelName());
            stage(e, review.passes(spec) ? "NARRATION_REVIEW" : "CHANGES_NEEDED");
            return view(e);
        } catch (Exception ex) { return failed(e, ex); }
    }

    synchronized View groundNarration(UUID id) {
        StudioEpisode e = find(id);
        require(e.approvedAt == null, "Approved episode is immutable; create a revision");
        var spec = spec(e);
        var narration = narration(e, spec);
        Path dir = directory(id);
        List<Path> frames = java.util.stream.IntStream.range(0, spec.puzzles().size())
            .mapToObj(i -> dir.resolve("question-" + i + ".png")).toList();
        require(frames.stream().allMatch(Files::isRegularFile), "Prepare completed question frames before grounding narration");
        stage(e, "NARRATION_GROUNDING");
        try {
            var production = settings(e);
            var draft = narrator.ground(spec, narration, frames, production.narrationModel(), stageInstructions(e).forAction("ground-narration"));
            draft.grounding().validate(spec);
            e.narrationGroundingJson = json.writeValueAsString(draft.grounding());
            e.narrationGroundingModel = draft.model(); e.narrationGroundingResponseId = draft.responseId();
            e.narrationGroundingOverridden = false;
            if (draft.grounding().passes(spec)) {
                e.narrationJson = json.writeValueAsString(draft.grounding().narration());
                e.speechJson = null; e.speechModel = null; e.speechVoice = null;
                if (production.equals(defaults)) renderer.writeNarration(spec, draft.grounding().narration(), dir);
                else renderer.writeNarration(spec, draft.grounding().narration(), dir, production.channelName());
                stage(e, "ART_REVIEW");
            } else stage(e, "CHANGES_NEEDED");
            return view(e);
        } catch (Exception ex) { return failed(e, ex); }
    }

    synchronized View artwork(UUID id) {
        StudioEpisode e = find(id);
        require(e.approvedAt == null, "Approved artwork is immutable; create a revision");
        var spec = spec(e);
        require(reviewPasses(e, spec), "Independent reasoning review must pass before spending on artwork");
        stage(e, "PREPARING_ART");
        try {
            Path dir = directory(id); Files.createDirectories(dir);
            var production = settings(e);
            for (int i = 0; i < spec.puzzles().size(); i++) {
                // A creator click authorizes one image request per puzzle. If the local
                // conformance check finds a concrete mismatch, make at most one targeted
                // repair request immediately rather than forcing the creator into a dead end.
                Path artwork = dir.resolve("art-" + i + ".png");
                Path conformanceReport = dir.resolve("artwork-conformance-" + i + ".json");
                var puzzle = spec.puzzles().get(i);
                if (!Files.isRegularFile(artwork))
                    ai.artwork(puzzle, dir, i, production.imageModel(), stageInstructions(e).forAction("artwork"));
                // Old saved art is checked once after this upgrade too. Passing work is
                // retained; only a concrete mismatch may use the bounded repair image.
                if (!Files.isRegularFile(conformanceReport)) {
                    var conformance = production.equals(defaults)
                        ? ai.reviewArtwork(artwork, puzzle, null)
                        : ai.reviewArtwork(artwork, puzzle, production.textModel());
                    if (conformance == null) throw new IllegalStateException("Artwork conformance check returned no result");
                    if (!conformance.acceptable()) {
                        ai.repairArtwork(puzzle, dir, i, production.imageModel(), stageInstructions(e).forAction("artwork"), conformance.repairBrief());
                        conformance = production.equals(defaults)
                            ? ai.reviewArtwork(artwork, puzzle, null)
                            : ai.reviewArtwork(artwork, puzzle, production.textModel());
                        if (conformance == null) throw new IllegalStateException("Artwork repair check returned no result");
                    }
                    Files.writeString(conformanceReport, json.writeValueAsString(conformance));
                }
            }
            renderer.previews(spec, dir, production.channelName());
            for (int i = 0; i < spec.puzzles().size(); i++) {
                Path report = dir.resolve("visual-review-" + i + ".json");
                String hash = digest(dir.resolve("question-" + i + ".png"));
                var puzzle = spec.puzzles().get(i);
                Path cached = dir.resolve("visual-inspection-" + i + "-" + hash + ".json");
                StudioAi.VisualReview visualReview;
                StudioAi.VisualInspection inspection = null;
                if ("visual".equals(puzzle.kind())) {
                    if (!Files.exists(cached)) Files.writeString(cached, json.writeValueAsString(
                        production.equals(defaults) ? ai.inspectVisualFrame(dir.resolve("question-" + i + ".png"), puzzle, null)
                            : ai.inspectVisualFrame(dir.resolve("question-" + i + ".png"), puzzle, production.textModel())));
                    inspection = json.readValue(Files.readString(cached), StudioAi.VisualInspection.class);
                    visualReview = inspection.review();
                    if (!visualReview.acceptable()) {
                        // One bounded, targeted repair can rescue a poor first image without
                        // requiring a manual fail → regenerate → review loop.
                        ai.repairArtwork(puzzle, dir, i, production.imageModel(), stageInstructions(e).forAction("artwork"),
                            "The blind family-viewer check rejected the rendered frame. Make the decisive clue clearer, fully visible, and unambiguous. Keep the same puzzle answer and candidate order. Reviewer notes: "
                                + trimForPrompt(visualReview.notes(), 1200));
                        var conformance = production.equals(defaults)
                            ? ai.reviewArtwork(dir.resolve("art-" + i + ".png"), puzzle, null)
                            : ai.reviewArtwork(dir.resolve("art-" + i + ".png"), puzzle, production.textModel());
                        if (conformance == null) throw new IllegalStateException("Artwork repair check returned no result");
                        Files.writeString(dir.resolve("artwork-conformance-" + i + ".json"), json.writeValueAsString(conformance));
                        clearArtworkDerivatives(dir, i);
                        if (!conformance.acceptable()) {
                            inspection = null;
                            visualReview = new StudioAi.VisualReview(false, "The one automatic image repair still failed the conformance check: " + conformance.notes());
                            renderer.previews(spec, dir, production.channelName());
                            hash = digest(dir.resolve("question-" + i + ".png"));
                        } else {
                            renderer.previews(spec, dir, production.channelName());
                            hash = digest(dir.resolve("question-" + i + ".png"));
                            cached = dir.resolve("visual-inspection-" + i + "-" + hash + ".json");
                            Files.writeString(cached, json.writeValueAsString(
                                production.equals(defaults) ? ai.inspectVisualFrame(dir.resolve("question-" + i + ".png"), puzzle, null)
                                    : ai.inspectVisualFrame(dir.resolve("question-" + i + ".png"), puzzle, production.textModel())));
                            inspection = json.readValue(Files.readString(cached), StudioAi.VisualInspection.class);
                            visualReview = inspection.review();
                        }
                    }
                } else {
                    if (!Files.exists(cached)) Files.writeString(cached, json.writeValueAsString(
                        production.equals(defaults) ? ai.reviewFrame(dir.resolve("question-" + i + ".png"), puzzle)
                            : ai.reviewFrame(dir.resolve("question-" + i + ".png"), puzzle, production.textModel())));
                    visualReview = json.readValue(Files.readString(cached), StudioAi.VisualReview.class);
                }
                Files.writeString(report, json.writeValueAsString(visualReview));
                Files.writeString(dir.resolve("visual-review-" + i + ".sha256"), hash);
                Path overlayFile = dir.resolve("overlay-" + i + ".json");
                if (Files.isRegularFile(overlayFile)) SceneOverlay.read(dir, i, spec.puzzles().get(i));
                else if (inspection != null && inspection.answerMatches()) {
                    // The same blind inspection supplies the reveal geometry; no second
                    // high-detail vision request is needed for every puzzle.
                    var clue = inspection.clue();
                    clue.onArtwork();
                    var overlay = new SceneOverlay(SceneOverlay.hash(dir.resolve("art-" + i + ".png")),
                        puzzle.answerId(), clue.onArtwork());
                    Files.writeString(overlayFile, json.writeValueAsString(overlay));
                } else if (inspection == null) {
                    var clue = production.equals(defaults)
                        ? ai.locateClue(dir.resolve("question-" + i + ".png"), puzzle, null)
                        : ai.locateClue(dir.resolve("question-" + i + ".png"), puzzle, production.textModel());
                    var overlay = new SceneOverlay(SceneOverlay.hash(dir.resolve("art-" + i + ".png")),
                        puzzle.answerId(), clue.onArtwork());
                    Files.writeString(overlayFile, json.writeValueAsString(overlay));
                }
            }
            // Question frames remain clean; answer frames are rebuilt with the precise clue ring.
            renderer.previews(spec, dir, production.channelName());
            // Artwork and narration grounding are deliberately separate manual gates. The
            // operator needs a chance to inspect every image before spending on grounding.
            stage(e, "ART_REVIEW");
            return view(e);
        } catch (Exception ex) { return failed(e, ex); }
    }

    /** Stores operator-selected clue circles locally, then refreshes only the deterministic frame assets. */
    synchronized View highlight(UUID id, List<SceneOverlay.Region> clues) {
        StudioEpisode e = find(id);
        require(e.approvedAt == null, "Approved episode is immutable; create a revision to change it");
        var spec = spec(e);
        require(view(e).artworkReady(), "Prepare artwork before placing reveal highlights");
        require(clues != null && clues.size() == spec.puzzles().size(), "Place one reveal highlight for every puzzle");
        clues.forEach(SceneOverlay.Region::validate);
        stage(e, "PREPARING_ART");
        try {
            Path dir = directory(id);
            for (int i = 0; i < spec.puzzles().size(); i++) {
                var overlay = new SceneOverlay(SceneOverlay.hash(dir.resolve("art-" + i + ".png")),
                    spec.puzzles().get(i).answerId(), clues.get(i));
                Files.writeString(dir.resolve("overlay-" + i + ".json"), json.writeValueAsString(overlay));
            }
            renderer.previews(spec, dir, settings(e).channelName());
            // New reveal treatment must be watched again; source art, narration and voice stay intact.
            invalidateRenders(dir);
            stage(e, "ART_REVIEW");
            return view(e);
        } catch (Exception ex) { return failed(e, ex); }
    }


    synchronized View speech(UUID id) {
        StudioEpisode e = find(id);
        require(e.approvedAt == null, "Approved episode is immutable; create a revision");
        var spec = spec(e);
        require(view(e).artworkReady(), "Prepare all artwork before generating speech");
        require(narrationGroundingPasses(e, spec), "Ground narration against the completed artwork before generating speech");
        var narration = narration(e, spec);
        // If every local clip exists, a retry can recover their measured timings without
        // spending on the Speech API again. The renderer now follows those timings directly.
        boolean recoverExisting = "FAILED".equals(e.status) && existingSpeechClips(id);
        stage(e, "SPEAKING");
        try {
            var production = settings(e);
            var profile = new SpeechAi.Profile(production.speechModel(), production.speechVoice(), production.speechSpeed());
            var draft = recoverExisting ? speaker.recoverExisting(spec, directory(id)) : speaker.speak(spec, narration, directory(id), profile, stageInstructions(e).forAction("speech"));
            draft.speech().validate(spec);
            renderer.writeSpeechTrack(spec, draft.speech(), directory(id));
            // A new voice track changes the local timing plan. Never leave an older preview
            // available as though it matched the regenerated local audio.
            invalidateRenders(directory(id));
            e.speechJson = json.writeValueAsString(draft.speech());
            e.speechModel = draft.speech().model(); e.speechVoice = draft.speech().voice();
            stage(e, "ART_REVIEW");
            return view(e);
        } catch (Exception ex) { return failed(e, ex); }
    }

    synchronized View approve(UUID id) {
        StudioEpisode e = find(id);
        require(e.approvedAt == null, "Already approved");
        var spec = spec(e);
        require(reviewPasses(e, spec), "The reasoning review must pass");
        var view = view(e);
        require(view.artworkReady(), "Prepare all artwork before approval");
        require(e.artworkSelectionFinalized || (view.visualReviews().size() == spec.puzzles().size()
                && view.visualReviews().stream().allMatch(StudioAi.VisualReview::acceptable)),
            "Choose the final puzzle set after artwork, or resolve the visual review findings before approval");
        if (e.narrationJson != null) require(view.speechReady(),
            "Generate and listen to the AI voice before approving this narrated episode");
        e.approvedAt = Instant.now(); stage(e, "APPROVED");
        return view(e);
    }

    synchronized View render(UUID id, boolean draft) {
        StudioEpisode e = find(id);
        var spec = spec(e);
        require(view(e).artworkReady(), "Prepare all artwork first");
        require(draft || e.approvedAt != null, "Human approval is required for a final video; use draft preview first");
        EpisodeSpeech speech = e.speechJson == null ? null : speech(e, spec);
        if (e.narrationJson != null) require(speechReady(e, spec),
            "Generate the AI voice before rendering this narrated episode");
        stage(e, "RENDERING");
        try {
            // Sound effects and pacing are deterministic local production work. Rebuild from saved clips
            // so a preview reflects renderer updates without making another Speech API request.
            if (speech != null && e.narrationJson != null) {
                var production = settings(e);
                var paced = speaker.normalizeExisting(spec, narration(e, spec), directory(id),
                    new SpeechAi.Profile(production.speechModel(), production.speechVoice(), production.speechSpeed()));
                speech = paced.speech();
                e.speechJson = json.writeValueAsString(speech);
                e.speechModel = speech.model(); e.speechVoice = speech.voice();
                save(e);
                renderer.writeSpeechTrack(spec, speech, directory(id));
            }
            renderer.render(spec, directory(id), draft, speech, settings(e).channelName());
            stage(e, draft ? (e.approvedAt == null ? "ART_REVIEW" : "APPROVED") : "RENDERED");
            return view(e);
        } catch (Exception ex) { return failed(e, ex); }
    }

    Path media(UUID id, String filename) {
        find(id);
        require(filename.matches("(?:art|question|reveal)-[0-9]+\\.png|(?:provenance|visual-review)-[0-9]+\\.json|(?:preview|final)(?:-puzzle-[0-9]+)?\\.mp4|youtube-thumbnail-[1-3]\\.jpg|narration\\.txt|speech\\.m4a|ai-voice-disclosure\\.txt"),
            "Unknown media asset");
        Path path = directory(id).resolve(filename);
        if (!Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset is not ready");
        return path;
    }
    private Path directory(UUID id) { return root.resolve(id.toString()); }
    private StudioEpisode find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Episode not found"));
    }
    private EpisodeSpec spec(StudioEpisode e) {
        require(e.specJson != null, "Generate the script first");
        var spec = json.readValue(e.specJson, EpisodeSpec.class); spec.validate(); return spec;
    }
    private EpisodeSettings settings(StudioEpisode e) {
        if (e.settingsJson == null || e.settingsJson.isBlank()) {
            int count = e.specJson == null ? defaults.puzzleCount() : spec(e).puzzles().size();
            return new EpisodeSettings(defaults.channelName(), count, defaults.textModel(), defaults.imageModel(),
                defaults.narrationModel(), defaults.speechModel(), defaults.speechVoice(), defaults.speechSpeed());
        }
        var settings = json.readValue(e.settingsJson, EpisodeSettings.class);
        settings.validate();
        return settings;
    }
    private StageInstructions stageInstructions(StudioEpisode e) {
        if (e.stageInstructionsJson == null || e.stageInstructionsJson.isBlank()) return StageInstructions.EMPTY;
        var instructions = json.readValue(e.stageInstructionsJson, StageInstructions.class);
        instructions.validate();
        return instructions;
    }
    private boolean reviewPasses(StudioEpisode e, EpisodeSpec spec) {
        return e.puzzleReviewOverridden || (e.reviewJson != null && json.readValue(e.reviewJson, StudioAi.Review.class).passes(spec));
    }
    /** A creator may accept quality warnings, never a reviewer disagreement about the actual answer. */
    private boolean reviewAllowsWarningOverride(StudioEpisode e, EpisodeSpec spec) {
        if (e.reviewJson == null) return false;
        var findings = json.readValue(e.reviewJson, StudioAi.Review.class).findings();
        if (findings == null || findings.size() != spec.puzzles().size()) return false;
        for (int i = 0; i < findings.size(); i++) {
            var finding = findings.get(i);
            if (finding == null || finding.puzzleNumber() != i + 1
                || !spec.puzzles().get(i).answerId().equals(finding.independentlySolvedAnswerId())) return false;
        }
        return true;
    }
    private List<Remediation> reviewFailures(StudioEpisode episode, EpisodeSpec spec) {
        require(episode.reviewJson != null, "Run the puzzle review before regenerating a failed puzzle");
        var review = json.readValue(episode.reviewJson, StudioAi.Review.class);
        require(review.findings() != null && review.findings().size() == spec.puzzles().size(),
            "The saved review is incomplete; run it again before regenerating a puzzle");
        var failures = new ArrayList<Remediation>();
        for (int index = 0; index < review.findings().size(); index++) {
            var finding = review.findings().get(index);
            require(finding != null && finding.puzzleNumber() == index + 1, "The saved review is incomplete; run it again before regenerating a puzzle");
            if (!finding.fair() || !spec.puzzles().get(index).answerId().equals(finding.independentlySolvedAnswerId()))
                failures.add(new Remediation(index, finding.notes()));
        }
        require(!failures.isEmpty(), "The independent review did not reject any puzzles");
        return List.copyOf(failures);
    }
    private List<Remediation> groundingArtworkFailures(StudioEpisode episode, EpisodeSpec spec) {
        var grounding = narrationGrounding(episode, spec);
        var failures = new ArrayList<Remediation>();
        for (int index = 0; index < grounding.findings().size(); index++) {
            var finding = grounding.findings().get(index);
            if (!finding.visualClueConfirmed()) failures.add(new Remediation(index, finding.notes()));
        }
        require(!failures.isEmpty(), "Grounding confirmed every visual clue; no artwork repair is needed");
        return List.copyOf(failures);
    }
    private List<Remediation> artworkFailures(StudioEpisode episode, EpisodeSpec spec) {
        Path dir = directory(episode.id);
        var failures = new ArrayList<Remediation>();
        for (int index = 0; index < spec.puzzles().size(); index++) {
            try {
                Path report = dir.resolve("visual-review-" + index + ".json");
                Path hash = dir.resolve("visual-review-" + index + ".sha256");
                require(Files.isRegularFile(report) && Files.isRegularFile(hash), "Complete the artwork visual checks before regenerating failed artwork");
                require(Files.readString(hash).equals(digest(dir.resolve("question-" + index + ".png"))),
                    "The artwork changed after visual review; run artwork preparation again first");
                var review = json.readValue(Files.readString(report), StudioAi.VisualReview.class);
                if (!review.acceptable()) failures.add(new Remediation(index, review.notes()));
            } catch (Exception ex) { throw new IllegalStateException("Unable to read the artwork visual checks", ex); }
        }
        require(!failures.isEmpty(), "The artwork visual checks did not reject any puzzles");
        return List.copyOf(failures);
    }
    private List<Integer> selectedPuzzlePositions(List<Integer> requestedNumbers, int total) {
        require(requestedNumbers != null && !requestedNumbers.isEmpty(), "Choose at least one puzzle to continue");
        var unique = new TreeSet<Integer>();
        for (Integer number : requestedNumbers) {
            require(number != null && number >= 1 && number <= total, "Choose only puzzle numbers from 1 to " + total);
            require(unique.add(number), "Choose each puzzle only once");
        }
        return unique.stream().map(number -> number - 1).toList();
    }
    private StudioAi.Review selectedReview(StudioEpisode source, List<Integer> positions) {
        require(source.reviewJson != null, "Run the puzzle review before choosing puzzles");
        var sourceReview = json.readValue(source.reviewJson, StudioAi.Review.class);
        require(sourceReview.findings() != null, "The saved puzzle review is incomplete; run it again before choosing puzzles");
        var findings = new ArrayList<StudioAi.Finding>();
        for (int targetIndex = 0; targetIndex < positions.size(); targetIndex++) {
            int sourceIndex = positions.get(targetIndex);
            require(sourceReview.findings().size() > sourceIndex && sourceReview.findings().get(sourceIndex) != null,
                "The saved puzzle review is incomplete; run it again before choosing puzzles");
            var sourceFinding = sourceReview.findings().get(sourceIndex);
            findings.add(new StudioAi.Finding(targetIndex + 1, sourceFinding.independentlySolvedAnswerId(), sourceFinding.fair(), sourceFinding.notes()));
        }
        return new StudioAi.Review(List.copyOf(findings));
    }
    private static void copyIfPresent(Path source, Path target) throws java.io.IOException {
        if (Files.isRegularFile(source)) Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
    /** Visual approvals are copied only into an artwork-only recovery; ordinary revisions require fresh review. */
    private static void copyArtworkEvidence(Path sourceDir, Path targetDir, int index) throws java.io.IOException {
        copyIfPresent(sourceDir.resolve("visual-review-" + index + ".json"), targetDir.resolve("visual-review-" + index + ".json"));
        copyIfPresent(sourceDir.resolve("visual-review-" + index + ".sha256"), targetDir.resolve("visual-review-" + index + ".sha256"));
        try (var files = Files.list(sourceDir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().matches("visual-(?:check|inspection)-" + index + "-[0-9a-f]{64}\\.json")).toList())
                Files.copy(file, targetDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }
    /** Removes only derived assets for a changed puzzle; all other saved work in a revision survives. */
    private static void clearArtworkForPuzzle(Path dir, int index) throws java.io.IOException {
        clearArtworkDerivatives(dir, index);
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().matches("art-" + index + "(?:-.*)?\\.png")
                || f.getFileName().toString().equals("reused-" + index + ".txt")).toList()) Files.deleteIfExists(file);
        }
    }
    private static void clearArtworkDerivatives(Path dir, int index) throws java.io.IOException {
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(f -> {
                String name = f.getFileName().toString();
                return name.equals("provenance-" + index + ".json") || name.equals("overlay-" + index + ".json")
                    || name.equals("artwork-conformance-" + index + ".json") || name.equals("question-" + index + ".png")
                    || name.equals("reveal-" + index + ".png") || name.equals("visual-review-" + index + ".json")
                    || name.equals("visual-review-" + index + ".sha256") || name.equals("speech-question-" + index + ".wav")
                    || name.equals("speech-timer-" + index + ".wav") || name.equals("speech-reveal-" + index + ".wav")
                    || name.matches("visual-(?:check|inspection)-" + index + "-[0-9a-f]{64}\\.json");
            }).toList()) Files.deleteIfExists(file);
        }
    }
    private static String trimForPrompt(String value, int max) {
        if (value == null || value.isBlank()) return "No additional reviewer note was returned.";
        String clean = value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
    private EpisodeNarration narration(StudioEpisode e, EpisodeSpec spec) {
        require(e.narrationJson != null, "Generate narration first");
        var narration = json.readValue(e.narrationJson, EpisodeNarration.class);
        narration.validate(spec);
        return narration;
    }
    private EpisodeSpeech speech(StudioEpisode e, EpisodeSpec spec) {
        require(e.speechJson != null, "Generate the AI voice first");
        var speech = json.readValue(e.speechJson, EpisodeSpeech.class);
        speech.validate(spec);
        return speech;
    }
    private boolean narrationGroundingPasses(StudioEpisode e, EpisodeSpec spec) {
        return e.narrationGroundingOverridden || (e.narrationGroundingJson != null
            && json.readValue(e.narrationGroundingJson, NarrationGrounding.class).passes(spec));
    }
    private NarrationGrounding narrationGrounding(StudioEpisode e, EpisodeSpec spec) {
        require(e.narrationGroundingJson != null, "Ground narration before continuing with warnings");
        var grounding = json.readValue(e.narrationGroundingJson, NarrationGrounding.class);
        grounding.validate(spec);
        return grounding;
    }
    /** Warnings may be consciously accepted, but incomplete findings or narration that breaks OPTION-only delivery cannot. */
    private boolean groundingAllowsWarningOverride(StudioEpisode e, EpisodeSpec spec) {
        try {
            var grounding = narrationGrounding(e, spec);
            return grounding.findings().stream().allMatch(NarrationGrounding.Finding::optionOnly);
        } catch (Exception ignored) { return false; }
    }
    private boolean existingSpeechClips(UUID id) {
        Path dir = directory(id);
        return java.util.stream.IntStream.range(0, spec(find(id)).puzzles().size()).allMatch(i -> Files.isRegularFile(dir.resolve("speech-question-" + i + ".wav"))
            && Files.isRegularFile(dir.resolve("speech-timer-" + i + ".wav")) && Files.isRegularFile(dir.resolve("speech-reveal-" + i + ".wav")));
    }
    private boolean speechReady(StudioEpisode e, EpisodeSpec spec) {
        try { speech(e, spec); return Files.isRegularFile(directory(e.id).resolve("speech.m4a")); }
        catch (Exception ignored) { return false; }
    }
    /** Derived renders are disposable. Their sources—artwork, narration and WAV clips—are retained. */
    private static void invalidateRenders(Path dir) throws java.io.IOException {
        for (String prefix : List.of("preview", "final")) {
            Files.deleteIfExists(dir.resolve(prefix + ".mp4"));
            Files.deleteIfExists(dir.resolve(prefix + "-render-version.txt"));
        }
    }
    private static boolean renderReady(Path dir, String prefix) {
        try {
            return Files.isRegularFile(dir.resolve(prefix + ".mp4"))
                && StudioRenderer.RENDER_VERSION.equals(Files.readString(dir.resolve(prefix + "-render-version.txt")).trim());
        } catch (Exception ignored) { return false; }
    }
    private void save(StudioEpisode e) { e.version = repository.saveAndFlush(e).version; }
    private void stage(StudioEpisode e, String status) { e.status = status; e.lastError = null; e.failedStage = null; save(e); }
    private View failed(StudioEpisode e, Exception exception) {
        String failedStage = e.status;
        e.failedStage = failedStage;
        e.status = "FAILED";
        // Never persist raw HTTP bodies/headers, which can contain credentials or signed URLs.
        String type = exception.getClass().getSimpleName();
        e.lastError = timedOut(exception) || "OpenAIIoException".equals(type)
            ? "This stage exceeded its request deadline. No partial response was accepted; retry manually when ready."
            : "OpenAIInvalidDataException".equals(type)
            ? e.specJson != null && "REVIEWING".equals(failedStage)
                ? "OpenAI returned an incomplete structured review response. Your generated puzzles are saved; no puzzles were automatically regenerated. Run Review manually when ready."
                : e.specJson != null
                    ? "OpenAI returned an incomplete response during this stage. Your saved puzzles and previous results are retained. Retry manually when ready."
                    : "OpenAI returned an incomplete structured puzzle response. No complete puzzle set was saved; retry generation manually when ready."
            : exception instanceof org.springframework.web.server.ResponseStatusException response && response.getReason() != null
            ? response.getReason()
            : exception instanceof IllegalArgumentException ? exception.getMessage()
            : "Stage failed (" + type + "). Check model access, API credits and local services, then retry this stage. Existing assets are retained.";
        save(e); return view(e);
    }
    private View view(StudioEpisode e) {
        Path dir = directory(e.id);
        boolean ready = true;
        try { ready = StudioRenderer.layoutVersion(spec(e)).equals(Files.readString(dir.resolve("layout-version.txt"))); }
        catch (Exception ex) { ready = false; }
        var reviews = new ArrayList<StudioAi.VisualReview>();
        int puzzleCount = e.specJson == null ? settings(e).puzzleCount() : spec(e).puzzles().size();
        for (int i = 0; i < puzzleCount; i++) {
            boolean hasArtwork = Files.isRegularFile(dir.resolve("question-" + i + ".png"))
                && Files.isRegularFile(dir.resolve("art-" + i + ".png"));
            ready &= hasArtwork;
            Path report = dir.resolve("visual-review-" + i + ".json");
            if (Files.isRegularFile(report)) {
                try {
                    String hash = Files.readString(dir.resolve("visual-review-" + i + ".sha256"));
                    if (!hash.equals(digest(dir.resolve("question-" + i + ".png"))))
                        throw new IllegalStateException("Frame changed after review");
                    reviews.add(json.readValue(Files.readString(report), StudioAi.VisualReview.class));
                }
                catch (Exception ex) { reviews.add(new StudioAi.VisualReview(false, "Unreadable visual review; inspection required.")); }
            } else if (hasArtwork) reviews.add(new StudioAi.VisualReview(false, "Visual check is missing; repair or recheck this artwork before selecting it."));
        }
        EpisodeSpeech speech = null;
        try { if (e.speechJson != null) speech = speech(e, e.specJson == null ? null : spec(e)); }
        catch (Exception ignored) { speech = null; }
        boolean speechReady = speech != null && Files.isRegularFile(dir.resolve("speech.m4a"));
        return new View(e.id, e.brief, e.status, e.specJson == null ? null : json.readValue(e.specJson, EpisodeSpec.class),
            e.reviewJson == null ? null : json.readValue(e.reviewJson, StudioAi.Review.class),
            e.narrationJson == null ? null : json.readValue(e.narrationJson, EpisodeNarration.class),
            e.narrationReviewJson == null ? null : json.readValue(e.narrationReviewJson, NarrationAi.Review.class),
            e.narrationGroundingJson == null ? null : json.readValue(e.narrationGroundingJson, NarrationGrounding.class), speech, e.lastError, e.failedStage,
            e.scriptModel, e.responseId, e.narrationModel, e.narrationResponseId, e.narrationGroundingModel, e.speechModel, e.speechVoice, settings(e), stageInstructions(e),
            e.approvedAt, ready, reviews, renderReady(dir, "preview"), renderReady(dir, "final"), speechReady, e.puzzleReviewOverridden,
            e.artworkSelectionFinalized, e.narrationGroundingOverridden,
            e.youtubeUploadPackJson == null ? null : json.readValue(e.youtubeUploadPackJson, YouTubeUploadPack.class),
            e.youtubeUploadPrompt, e.youtubeUploadModel);
    }
    private View safeView(StudioEpisode episode) {
        try { return view(episode); }
        catch (Exception ignored) {
            EpisodeSettings savedSettings;
            try { savedSettings = settings(episode); }
            catch (Exception alsoIgnored) { savedSettings = defaults; }
            return new View(episode.id, episode.brief, "FAILED", null, null, null, null, null, null,
                "This older saved episode no longer matches the current puzzle format. Its files are retained, but create a new episode to continue.", episode.failedStage,
                episode.scriptModel, episode.responseId, episode.narrationModel, episode.narrationResponseId,
                episode.narrationGroundingModel, episode.speechModel, episode.speechVoice, savedSettings, StageInstructions.EMPTY, episode.approvedAt,
                false, List.of(), false, false, false, false, false, false, null, episode.youtubeUploadPrompt, episode.youtubeUploadModel);
        }
    }

    /** Recent local puzzle concepts provide durable no-repeat context without exporting any artwork or audio. */
    private String recentPuzzleTitles(UUID currentEpisodeId) {
        List<StudioEpisode> episodes = repository.findAllByOrderByCreatedAtDesc();
        if (episodes == null || episodes.isEmpty()) return "";
        StringBuilder titles = new StringBuilder();
        Set<String> seen = new HashSet<>();
        int count = 0;
        for (StudioEpisode prior : episodes) {
            if (count >= 50 || prior.id.equals(currentEpisodeId) || prior.specJson == null) continue;
            try {
                EpisodeSpec archived = json.readValue(prior.specJson, EpisodeSpec.class);
                for (EpisodeSpec.Puzzle puzzle : archived.puzzles()) {
                    String history = puzzleHistoryLine(puzzle).replaceAll("\\s+", " ").trim();
                    String key = (puzzle.title() + "|" + puzzle.question()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
                    if (history.isBlank() || !seen.add(key) || count >= 50 || titles.length() + history.length() + 3 > 9000) continue;
                    titles.append("- ").append(history).append('\n');
                    count++;
                }
            } catch (Exception ignored) {
                // A legacy/corrupt record must never block a fresh local generation.
            }
        }
        return titles.toString();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    private static boolean timedOut(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause())
            if (current.getClass().getSimpleName().contains("Timeout")) return true;
        return false;
    }
    private static String fallback(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
    private static String digest(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
