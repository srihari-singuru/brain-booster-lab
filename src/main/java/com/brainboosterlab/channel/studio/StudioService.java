package com.brainboosterlab.channel.studio;

import java.nio.file.*;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Local, single-process studio. Slow API/FFmpeg work never holds a database transaction open. */
@Service
class StudioService {
    record View(UUID id, String brief, String status, EpisodeSpec spec, StudioAi.Review review,
                EpisodeNarration narration, NarrationAi.Review narrationReview, NarrationGrounding narrationGrounding, EpisodeSpeech speech,
                String lastError, String scriptModel, String responseId, String narrationModel, String narrationResponseId,
                String narrationGroundingModel, String speechModel, String speechVoice, Instant approvedAt, boolean artworkReady,
                List<StudioAi.VisualReview> visualReviews, boolean previewReady, boolean finalReady, boolean speechReady) {}
    private final StudioRepository repository;
    private final StudioAi ai;
    private final NarrationAi narrator;
    private final SpeechAi speaker;
    private final StudioRenderer renderer;
    private final Path root;
    private final JsonMapper json = JsonMapper.builder().build();
    StudioService(StudioRepository repository, StudioAi ai, NarrationAi narrator, SpeechAi speaker, StudioRenderer renderer,
                  @Value("${brain-booster.studio.output-dir:outputs/studio}") String output) {
        this.repository = repository; this.ai = ai; this.narrator = narrator; this.speaker = speaker; this.renderer = renderer;
        this.root = Path.of(output).toAbsolutePath().normalize();
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void recoverInterrupted() {
        for (var episode : repository.findAll()) {
            if (Set.of("GENERATING", "REVIEWING", "NARRATING", "NARRATION_GROUNDING", "SPEAKING", "PREPARING_ART", "RENDERING").contains(episode.status)) {
                episode.status = "INTERRUPTED";
                episode.lastError = "Application stopped during work. Saved scripts and artwork are retained; retry the unfinished stage.";
                save(episode);
            }
        }
    }

    synchronized View create(String brief) {
        EpisodeSpec.require(brief != null && !brief.isBlank() && brief.length() <= 4000, "Brief must be 1–4000 characters");
        return view(repository.saveAndFlush(new StudioEpisode(brief.trim())));
    }
    List<View> list() { return repository.findAllByOrderByCreatedAtDesc().stream().map(this::view).toList(); }
    View get(UUID id) { return view(find(id)); }

    synchronized View revise(UUID id, EpisodeSpec replacement) {
        replacement.validate();
        var source = find(id);
        var revision = new StudioEpisode("Revision of " + id + ": " + source.brief);
        revision.specJson = json.writeValueAsString(replacement);
        revision.scriptModel = "editor-revision (source: " + source.scriptModel + ")";
        revision.status = "SCRIPT_REVIEW";
        // Reuse only exactly unchanged puzzles at the same position. Changed clues get fresh artwork.
        if (source.specJson != null) {
            var original = json.readValue(source.specJson, EpisodeSpec.class);
            try {
                for (int i = 0; i < 3; i++) {
                    if (!original.puzzles().get(i).equals(replacement.puzzles().get(i))) continue;
                    Path sourceDir = directory(id), targetDir = directory(revision.id);
                    if (!Files.isRegularFile(sourceDir.resolve("art-" + i + ".png"))) continue;
                    Files.createDirectories(targetDir);
                    final int index = i;
                    try (var files = Files.list(sourceDir)) {
                        for (Path file : files.filter(f -> f.getFileName().toString().equals("art-" + index + ".png")
                            || f.getFileName().toString().equals("provenance-" + index + ".json")
                            || f.getFileName().toString().equals("overlay-" + index + ".json")
                            || f.getFileName().toString().matches("visual-check-" + index + "-[0-9a-f]{64}\\.json")).toList())
                            Files.copy(file, targetDir.resolve(file.getFileName()));
                    }
                    Files.writeString(targetDir.resolve("reused-" + i + ".txt"), "Unchanged artwork reused from episode " + id);
                }
            } catch (java.io.IOException ex) { throw new IllegalStateException("Unable to prepare revision assets; original episode is unchanged", ex); }
        }
        // Original script/assets/approval are untouched. Every revision needs fresh checks and approval.
        return view(repository.saveAndFlush(revision));
    }

    /** A rendering-only revision: no paid calls, no content changes, no inherited visual approval. */
    synchronized View restyle(UUID id) {
        return restyle(id, null);
    }

    synchronized View restyle(UUID id, List<SceneOverlay.Region> clues) {
        var source = find(id);
        var original = spec(source);
        if (clues != null) {
            EpisodeSpec.require(clues.size() == 3 && clues.stream().allMatch(Objects::nonNull), "Provide exactly three clue regions");
            clues.forEach(SceneOverlay.Region::validate);
        }
        for (int i = 0; i < 3; i++) require(Files.isRegularFile(directory(id).resolve("art-" + i + ".png")),
            "Restyling needs all three saved artworks; prepare missing artwork first");
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
            if (clues != null) for (int i = 0; i < 3; i++) {
                var overlay = new SceneOverlay(SceneOverlay.hash(directory(revision.id).resolve("art-" + i + ".png")),
                    original.puzzles().get(i).answerId(), clues.get(i));
                Files.writeString(directory(revision.id).resolve("overlay-" + i + ".json"), json.writeValueAsString(overlay));
            }
            renderer.previews(original, directory(revision.id));
            if (revision.narrationJson != null) renderer.writeNarration(original, narration(revision, original), directory(revision.id));
            stage(revision, "ART_REVIEW");
            return view(revision);
        } catch (Exception ex) { return failed(revision, ex); }
    }

    // Serialize mutations so duplicate clicks cannot launch duplicate paid requests in this local app.
    synchronized View generate(UUID id) {
        StudioEpisode e = find(id);
        require(e.specJson == null, "This episode already has a script. Create a revision to change it.");
        stage(e, "GENERATING");
        try {
            var draft = ai.generate(e.brief);
            e.specJson = json.writeValueAsString(draft.spec());
            e.scriptModel = draft.model(); e.responseId = draft.responseId();
            stage(e, "SCRIPT_REVIEW"); // Preserve the paid script even if the independent review fails.
            View reviewed = review(id);
            return reviewed.review() != null && reviewed.review().passes(draft.spec()) ? narration(id) : reviewed;
        } catch (Exception ex) { return failed(e, ex); }
    }

    synchronized View review(UUID id) {
        StudioEpisode e = find(id);
        require(e.approvedAt == null, "Approved episode is immutable; create a revision");
        var spec = spec(e);
        stage(e, "REVIEWING");
        try {
            var review = ai.review(spec);
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
            var draft = narrator.write(spec);
            draft.narration().validate(spec);
            e.narrationJson = json.writeValueAsString(draft.narration());
            e.narrationModel = draft.model(); e.narrationResponseId = draft.responseId();
            e.narrationGroundingJson = null; e.narrationGroundingModel = null; e.narrationGroundingResponseId = null;
            e.speechJson = null; e.speechModel = null; e.speechVoice = null;
            var review = narrator.review(spec, draft.narration());
            e.narrationReviewJson = json.writeValueAsString(review);
            renderer.writeNarration(spec, draft.narration(), directory(id));
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
        List<Path> frames = java.util.stream.IntStream.range(0, 3)
            .mapToObj(i -> dir.resolve("question-" + i + ".png")).toList();
        require(frames.stream().allMatch(Files::isRegularFile), "Prepare completed question frames before grounding narration");
        stage(e, "NARRATION_GROUNDING");
        try {
            var draft = narrator.ground(spec, narration, frames);
            draft.grounding().validate(spec);
            e.narrationGroundingJson = json.writeValueAsString(draft.grounding());
            e.narrationGroundingModel = draft.model(); e.narrationGroundingResponseId = draft.responseId();
            if (draft.grounding().passes(spec)) {
                e.narrationJson = json.writeValueAsString(draft.grounding().narration());
                e.speechJson = null; e.speechModel = null; e.speechVoice = null;
                renderer.writeNarration(spec, draft.grounding().narration(), dir);
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
            for (int i = 0; i < 3; i++) ai.artwork(spec.puzzles().get(i), dir, i);
            renderer.previews(spec, dir);
            for (int i = 0; i < 3; i++) {
                Path report = dir.resolve("visual-review-" + i + ".json");
                String hash = digest(dir.resolve("question-" + i + ".png"));
                Path cached = dir.resolve("visual-check-" + i + "-" + hash + ".json");
                if (!Files.exists(cached)) Files.writeString(cached, json.writeValueAsString(
                    ai.reviewFrame(dir.resolve("question-" + i + ".png"), spec.puzzles().get(i))));
                Files.writeString(report, Files.readString(cached));
                Files.writeString(dir.resolve("visual-review-" + i + ".sha256"), hash);
            }
            if (e.narrationJson != null) return groundNarration(id);
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
        boolean recoverExisting = "FAILED".equals(e.status) && existingSpeechClips(id);
        stage(e, "SPEAKING");
        try {
            var draft = recoverExisting ? speaker.recoverExisting(spec, directory(id)) : speaker.speak(spec, narration, directory(id));
            draft.speech().validate(spec);
            renderer.writeSpeechTrack(spec, draft.speech(), directory(id));
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
        require(view.artworkReady(), "Prepare all three artworks before approval");
        require(view.visualReviews().size() == 3 && view.visualReviews().stream().allMatch(StudioAi.VisualReview::acceptable),
            "Resolve the visual review findings before approval");
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
                var paced = speaker.normalizeExisting(spec, narration(e, spec), directory(id));
                speech = paced.speech();
                e.speechJson = json.writeValueAsString(speech);
                e.speechModel = speech.model(); e.speechVoice = speech.voice();
                save(e);
                renderer.writeSpeechTrack(spec, speech, directory(id));
            }
            renderer.render(spec, directory(id), draft, speech);
            stage(e, draft ? (e.approvedAt == null ? "ART_REVIEW" : "APPROVED") : "RENDERED");
            return view(e);
        } catch (Exception ex) { return failed(e, ex); }
    }

    Path media(UUID id, String filename) {
        find(id);
        require(filename.matches("(?:art|question|reveal)-[0-2]\\.png|(?:provenance|visual-review)-[0-2]\\.json|(?:preview|final)\\.mp4|narration\\.txt|speech\\.m4a|ai-voice-disclosure\\.txt"),
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
    private boolean reviewPasses(StudioEpisode e, EpisodeSpec spec) {
        return e.reviewJson != null && json.readValue(e.reviewJson, StudioAi.Review.class).passes(spec);
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
        return e.narrationGroundingJson != null
            && json.readValue(e.narrationGroundingJson, NarrationGrounding.class).passes(spec);
    }
    private boolean existingSpeechClips(UUID id) {
        Path dir = directory(id);
        return java.util.stream.IntStream.range(0, 3).allMatch(i -> Files.isRegularFile(dir.resolve("speech-question-" + i + ".wav"))
            && Files.isRegularFile(dir.resolve("speech-timer-" + i + ".wav")) && Files.isRegularFile(dir.resolve("speech-reveal-" + i + ".wav")));
    }
    private boolean speechReady(StudioEpisode e, EpisodeSpec spec) {
        try { speech(e, spec); return Files.isRegularFile(directory(e.id).resolve("speech.m4a")); }
        catch (Exception ignored) { return false; }
    }
    private void save(StudioEpisode e) { e.version = repository.saveAndFlush(e).version; }
    private void stage(StudioEpisode e, String status) { e.status = status; e.lastError = null; save(e); }
    private View failed(StudioEpisode e, Exception exception) {
        e.status = "FAILED";
        // Never persist raw HTTP bodies/headers, which can contain credentials or signed URLs.
        e.lastError = exception instanceof IllegalArgumentException ? exception.getMessage()
            : "Stage failed (" + exception.getClass().getSimpleName() + "). Check model access, API credits and local services, then retry this stage. Existing assets are retained.";
        save(e); return view(e);
    }
    private View view(StudioEpisode e) {
        Path dir = directory(e.id);
        boolean ready = true;
        try { ready = StudioRenderer.layoutVersion(spec(e)).equals(Files.readString(dir.resolve("layout-version.txt"))); }
        catch (Exception ex) { ready = false; }
        var reviews = new ArrayList<StudioAi.VisualReview>();
        for (int i = 0; i < 3; i++) {
            ready &= Files.isRegularFile(dir.resolve("question-" + i + ".png")) && Files.isRegularFile(dir.resolve("art-" + i + ".png"));
            Path report = dir.resolve("visual-review-" + i + ".json");
            if (Files.isRegularFile(report)) {
                try {
                    String hash = Files.readString(dir.resolve("visual-review-" + i + ".sha256"));
                    if (!hash.equals(digest(dir.resolve("question-" + i + ".png"))))
                        throw new IllegalStateException("Frame changed after review");
                    reviews.add(json.readValue(Files.readString(report), StudioAi.VisualReview.class));
                }
                catch (Exception ex) { reviews.add(new StudioAi.VisualReview(false, "Unreadable visual review; inspection required.")); }
            }
        }
        EpisodeSpeech speech = null;
        try { if (e.speechJson != null) speech = speech(e, e.specJson == null ? null : spec(e)); }
        catch (Exception ignored) { speech = null; }
        boolean speechReady = speech != null && Files.isRegularFile(dir.resolve("speech.m4a"));
        return new View(e.id, e.brief, e.status, e.specJson == null ? null : json.readValue(e.specJson, EpisodeSpec.class),
            e.reviewJson == null ? null : json.readValue(e.reviewJson, StudioAi.Review.class),
            e.narrationJson == null ? null : json.readValue(e.narrationJson, EpisodeNarration.class),
            e.narrationReviewJson == null ? null : json.readValue(e.narrationReviewJson, NarrationAi.Review.class),
            e.narrationGroundingJson == null ? null : json.readValue(e.narrationGroundingJson, NarrationGrounding.class), speech, e.lastError,
            e.scriptModel, e.responseId, e.narrationModel, e.narrationResponseId, e.narrationGroundingModel, e.speechModel, e.speechVoice,
            e.approvedAt, ready, reviews, Files.isRegularFile(dir.resolve("preview.mp4")), Files.isRegularFile(dir.resolve("final.mp4")), speechReady);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
    private static String digest(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
