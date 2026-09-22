package com.brainboosterlab.channel.studio;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class StudioRenderer {
    /** Bump when a saved video no longer represents the current production contract. */
    static final String RENDER_VERSION = "voice-timed-puzzle-segments-1";
    static final String LAYOUT_VERSION = "reasoning-2";
    static String layoutVersion(EpisodeSpec spec) {
        return spec.puzzles().stream().anyMatch(p -> "visual".equals(p.kind())) ? "kids-thumbnail-10" : LAYOUT_VERSION;
    }
    static final int WIDTH = 1920, HEIGHT = 1080;
    /** The countdown is fixed; question, cue, and reveal visuals take their measured local voice duration. */
    static final int VISUAL_SETUP_SECONDS = 10;
    static final double VISUAL_TIMER_CUE_SECONDS = 3.5;
    static final int VISUAL_QUESTION_SECONDS = 8;
    static final int VISUAL_REVEAL_SECONDS = 8;
    static final Color INK = new Color(13, 25, 38), PAPER = new Color(250, 247, 236), GOLD = new Color(255, 209, 96);
    private final String ffmpeg;
    StudioRenderer(@Value("${brain-booster.render.ffmpeg-path:ffmpeg}") String ffmpeg) { this.ffmpeg = ffmpeg; }

    void previews(EpisodeSpec spec, Path dir) throws Exception { previews(spec, dir, "BRAIN BOOSTER LAB"); }
    void previews(EpisodeSpec spec, Path dir, String channelName) throws Exception {
        for (int i = 0; i < spec.puzzles().size(); i++) {
            BufferedImage art = readArt(dir, i);
            var puzzle = spec.puzzles().get(i);
            var overlay = SceneOverlay.read(dir, i, puzzle);
            ImageIO.write(composed(puzzle, art, i, "question", 0, false, overlay, 1.2, channelName, spec.puzzles().size()), "png", dir.resolve("question-" + i + ".png").toFile());
            ImageIO.write(composed(puzzle, art, i, "reveal", 0, false, overlay, 1.2, channelName, spec.puzzles().size()), "png", dir.resolve("reveal-" + i + ".png").toFile());
        }
        Files.writeString(dir.resolve("layout-version.txt"), layoutVersion(spec));
        // A reviewed writer draft is never replaced by a generic rendering fallback.
        if (!Files.exists(dir.resolve("narration.txt"))) writeNarration(spec, fallbackNarration(spec), dir, channelName);
    }

    void writeNarration(EpisodeSpec spec, EpisodeNarration narration, Path dir) throws Exception { writeNarration(spec, narration, dir, "BRAIN BOOSTER LAB"); }
    void writeNarration(EpisodeSpec spec, EpisodeNarration narration, Path dir, String channelName) throws Exception {
        narration.validate(spec);
        Files.createDirectories(dir);
        StringBuilder script = new StringBuilder(channelName.toUpperCase(java.util.Locale.ROOT) + " — NARRATION SCRIPT\n")
            .append("Speech production is deferred. Review this copy before enabling text-to-speech.\n\n")
            .append("EPISODE OPENING — FUTURE INTRO\n").append(narration.episodeOpening()).append("\n\n");
        for (int i = 0; i < spec.puzzles().size(); i++) {
            var puzzle = spec.puzzles().get(i);
            var beat = narration.puzzles().get(i);
            script.append("PUZZLE ").append(i + 1).append(" — ").append(puzzle.title()).append("\n")
                .append("QUESTION LEAD-IN — VOICE-TIMED\n")
                .append(beat.questionLeadIn()).append("\n")
                .append("TIMER CUE — VOICE-TIMED; THE TIMER HOLDS AT EIGHT, THEN THE FIXED ").append(VISUAL_QUESTION_SECONDS).append("-SECOND COUNTDOWN STARTS\n")
                .append(beat.timerCue()).append("\n")
                .append("SILENT THINKING — EXACTLY ").append(VISUAL_QUESTION_SECONDS).append(" SECONDS\n")
                .append("REVEAL EXPLANATION — VOICE-TIMED\n")
                .append(beat.revealExplanation()).append("\n\n");
        }
        script.append("EPISODE CLOSING — FUTURE OUTRO\n").append(narration.episodeClosing()).append('\n');
        Files.writeString(dir.resolve("narration.txt"), script);
    }

    private static EpisodeNarration fallbackNarration(EpisodeSpec spec) {
        var beats = java.util.stream.IntStream.range(0, spec.puzzles().size()).mapToObj(i -> {
            var p = spec.puzzles().get(i);
            return new EpisodeNarration.PuzzleNarration(i + 1,
                "A cheerful mini mystery is unfolding with three lively choices and one clever surprise in the picture. Which option solves this friendly puzzle today?",
                "Take eight seconds to choose your answer.",
                "The answer is OPTION " + p.answerId() + ". Follow the clearest clue in the scene; it shows why this choice fits the puzzle and the others do not.");
        }).toList();
        return new EpisodeNarration("Welcome to Brain Booster Lab, where every small clue can spark a brilliant idea.", beats,
            "Wonderful thinking today. Keep noticing the little details, and come back for another cheerful puzzle.");
    }

    Path render(EpisodeSpec spec, Path dir, boolean draft) throws Exception { return render(spec, dir, draft, null, "BRAIN BOOSTER LAB"); }
    Path render(EpisodeSpec spec, Path dir, boolean draft, EpisodeSpeech speech) throws Exception { return render(spec, dir, draft, speech, "BRAIN BOOSTER LAB"); }

    /** Renders every puzzle as its own voice-timed review clip, then joins clips with silent transitions. */
    Path render(EpisodeSpec spec, Path dir, boolean draft, EpisodeSpeech speech, String channelName) throws Exception {
        if (speech != null) {
            speech.validate(spec);
            if (!Files.isRegularFile(dir.resolve("puzzle-audio-0.m4a"))) writeSpeechTrack(spec, speech, dir);
        }
        String prefix = draft ? "preview" : "final";
        var segments = new ArrayList<RenderedSegment>();
        for (int i = 0; i < spec.puzzles().size(); i++)
            segments.add(renderPuzzle(spec, dir, draft, speech, channelName, prefix, i));

        Path manifest = dir.resolve(prefix + "-segments.txt");
        StringBuilder files = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            appendAudio(files, segments.get(i).video().getFileName().toString());
            if (i < segments.size() - 1) {
                Path transition = renderTransition(dir, prefix, i, segments.get(i).last(), segments.get(i + 1).first(), speech != null);
                appendAudio(files, transition.getFileName().toString());
            }
        }
        Files.writeString(manifest, files);
        Path result = dir.resolve(prefix + ".mp4"), pending = dir.resolve(prefix + ".pending.mp4");
        var command = new ArrayList<String>(List.of(ffmpeg, "-y", "-v", "warning", "-f", "concat", "-safe", "0", "-i", manifest.toString(),
            "-c:v", "libx264", "-preset", "fast", "-crf", "19"));
        if (speech != null) command.addAll(List.of("-c:a", "aac", "-b:a", "192k")); else command.add("-an");
        command.addAll(List.of("-movflags", "+faststart", pending.toString()));
        runVideo(command, dir.resolve(prefix + "-ffmpeg.log"));
        Files.move(pending, result, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.writeString(dir.resolve(prefix + "-render-version.txt"), RENDER_VERSION);
        return result;
    }

    private RenderedSegment renderPuzzle(EpisodeSpec spec, Path dir, boolean draft, EpisodeSpeech speech, String channelName, String prefix, int index) throws Exception {
        var puzzle = spec.puzzles().get(index); var art = readArt(dir, index); var overlay = SceneOverlay.read(dir, index, puzzle);
        boolean visual = "visual".equals(puzzle.kind());
        EpisodeSpeech.PuzzleSpeech track = speech == null ? null : speech.puzzles().get(index);
        double setup = track == null ? (visual ? VISUAL_SETUP_SECONDS : 8) : track.questionSeconds();
        double cue = track == null ? (visual ? VISUAL_TIMER_CUE_SECONDS : 0) : track.timerCueSeconds();
        double thinking = visual ? VISUAL_QUESTION_SECONDS : puzzle.thinkSeconds();
        double reveal = track == null ? (visual ? VISUAL_REVEAL_SECONDS : 12) : track.revealSeconds();
        Path frames = dir.resolve(prefix + "-puzzle-" + (index + 1) + "-frames"); Files.createDirectories(frames);
        var sequence = new FrameSequence(frames); BufferedImage first = null, last = null;
        for (var phase : List.of(new TimedPhase("setup", setup), new TimedPhase("cue", cue), new TimedPhase("question", thinking), new TimedPhase("reveal", reveal))) {
            if (phase.seconds() <= 0) continue;
            int totalTicks = ticks(phase.seconds());
            if (visual && phase.name().equals("reveal")) {
                int animated = Math.min(PuzzleMotion.REVEAL_TICKS, totalTicks);
                for (int tick = 0; tick < animated; tick++) {
                    last = composed(puzzle, art, index, phase.name(), 0, draft, overlay, (double) tick / PuzzleMotion.FPS, channelName, spec.puzzles().size());
                    if (first == null) first = last; sequence.add(last, 1);
                }
                for (int tick = animated; tick < totalTicks; tick += 15) {
                    last = composed(puzzle, art, index, phase.name(), 0, draft, overlay, (double) tick / PuzzleMotion.FPS, channelName, spec.puzzles().size());
                    if (first == null) first = last; sequence.add(last, Math.min(15, totalTicks - tick));
                }
            } else if (visual) {
                for (int tick = 0; tick < totalTicks; tick += 15) {
                    int countdown = phase.name().equals("cue") ? VISUAL_QUESTION_SECONDS : phase.name().equals("question") ? Math.max(1, VISUAL_QUESTION_SECONDS - tick / PuzzleMotion.FPS) : 0;
                    last = composed(puzzle, art, index, phase.name(), countdown, draft, overlay, (double) tick / PuzzleMotion.FPS, channelName, spec.puzzles().size());
                    if (first == null) first = last; sequence.add(last, Math.min(15, totalTicks - tick));
                }
            } else {
                last = composed(puzzle, art, index, phase.name(), phase.name().equals("question") ? (int) Math.ceil(phase.seconds()) : 0, draft, overlay, 1.2, channelName, spec.puzzles().size());
                if (first == null) first = last; sequence.add(last, totalTicks);
            }
        }
        sequence.finish();
        Path result = dir.resolve(prefix + "-puzzle-" + (index + 1) + ".mp4"), pending = dir.resolve(prefix + "-puzzle-" + (index + 1) + ".pending.mp4");
        Path audio = speech == null ? null : dir.resolve("puzzle-audio-" + index + ".m4a");
        encodeFrames(frames, audio, sequence.ticks / (double) PuzzleMotion.FPS, pending, dir.resolve(prefix + "-puzzle-" + (index + 1) + "-ffmpeg.log"));
        Files.move(pending, result, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new RenderedSegment(result, first, last);
    }

    private Path renderTransition(Path dir, String prefix, int index, BufferedImage before, BufferedImage after, boolean withAudio) throws Exception {
        Path frames = dir.resolve(prefix + "-transition-" + (index + 1) + "-frames"); Files.createDirectories(frames);
        var sequence = new FrameSequence(frames);
        for (int tick = 1; tick <= PuzzleMotion.TRANSITION_TICKS; tick++) sequence.add(PuzzleMotion.transition(before, after, (double) tick / PuzzleMotion.TRANSITION_TICKS), 1);
        sequence.finish();
        Path result = dir.resolve(prefix + "-transition-" + (index + 1) + ".mp4"), pending = dir.resolve(prefix + "-transition-" + (index + 1) + ".pending.mp4");
        encodeFrames(frames, withAudio ? dir.resolve("speech-transition-" + index + ".wav") : null, sequence.ticks / (double) PuzzleMotion.FPS, pending, dir.resolve(prefix + "-transition-" + (index + 1) + "-ffmpeg.log"));
        Files.move(pending, result, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return result;
    }

    private void encodeFrames(Path frames, Path audio, double durationSeconds, Path pending, Path log) throws Exception {
        var command = new ArrayList<String>(List.of(ffmpeg, "-y", "-v", "warning", "-f", "concat", "-safe", "0", "-i", frames.resolve("frames.txt").toString()));
        if (audio != null) command.addAll(List.of("-i", audio.toString()));
        command.addAll(List.of("-vf", "fps=30,format=yuv420p", "-c:v", "libx264", "-preset", "fast", "-crf", "19", "-t", Double.toString(durationSeconds)));
        if (audio != null) command.addAll(List.of("-map", "0:v:0", "-map", "1:a:0", "-c:a", "aac", "-b:a", "192k", "-shortest")); else command.add("-an");
        command.addAll(List.of("-movflags", "+faststart", pending.toString()));
        runVideo(command, log);
    }

    private static int ticks(double seconds) { return Math.max(1, (int) Math.round(seconds * PuzzleMotion.FPS)); }
    private record TimedPhase(String name, double seconds) {}
    private record RenderedSegment(Path video, BufferedImage first, BufferedImage last) {}

    void writeSpeechTrack(EpisodeSpec spec, EpisodeSpeech speech, Path dir) throws Exception {
        speech.validate(spec);
        Files.createDirectories(dir);
        StringBuilder episodeManifest = new StringBuilder();
        for (int i = 0; i < spec.puzzles().size(); i++) {
            requireAudio(dir, "speech-question-" + i + ".wav");
            requireAudio(dir, "speech-timer-" + i + ".wav");
            requireAudio(dir, "speech-reveal-" + i + ".wav");
            StringBuilder puzzleManifest = new StringBuilder();
            appendAudio(puzzleManifest, "speech-question-" + i + ".wav");
            appendAudio(puzzleManifest, "speech-timer-" + i + ".wav");
            String ticks = "speech-ticks-" + i + ".wav";
            writeTicking(dir.resolve(ticks), dir);
            appendAudio(puzzleManifest, ticks);
            appendAudio(puzzleManifest, "speech-reveal-" + i + ".wav");
            Path puzzleList = dir.resolve("puzzle-audio-" + i + ".txt"), puzzlePending = dir.resolve("puzzle-audio-" + i + ".pending.m4a"), puzzle = dir.resolve("puzzle-audio-" + i + ".m4a");
            Files.writeString(puzzleList, puzzleManifest);
            runFfmpeg(List.of(ffmpeg, "-y", "-v", "warning", "-f", "concat", "-safe", "0", "-i", puzzleList.toString(), "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", puzzlePending.toString()), dir);
            Files.move(puzzlePending, puzzle, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            appendAudio(episodeManifest, puzzle.getFileName().toString());
            if (i < spec.puzzles().size() - 1) {
                String transition = "speech-transition-" + i + ".wav";
                writeSilence(dir.resolve(transition), PuzzleMotion.TRANSITION_TICKS / (double) PuzzleMotion.FPS, dir);
                appendAudio(episodeManifest, transition);
            }
        }
        Files.writeString(dir.resolve("speech-audio.txt"), episodeManifest);
        Path pending = dir.resolve("speech.pending.m4a");
        runFfmpeg(List.of(ffmpeg, "-y", "-v", "warning", "-f", "concat", "-safe", "0", "-i",
            dir.resolve("speech-audio.txt").toString(), "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", pending.toString()), dir);
        Files.move(pending, dir.resolve("speech.m4a"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.writeString(dir.resolve("ai-voice-disclosure.txt"),
            "This episode uses an AI-generated OpenAI text-to-speech voice. Include this disclosure in the YouTube description before publishing.\n");
    }

    private static void appendAudio(StringBuilder manifest, String filename) {
        manifest.append("file '").append(filename).append("'\n");
    }

    private static void requireAudio(Path dir, String filename) {
        if (!Files.isRegularFile(dir.resolve(filename)))
            throw new IllegalStateException("Missing local speech clip " + filename + "; regenerate the AI voice");
    }

    private void writeTicking(Path target, Path dir) throws Exception {
        // One gentle tick for every second of the exact on-screen thinking period.
        runFfmpeg(List.of(ffmpeg, "-y", "-v", "warning", "-f", "lavfi", "-i",
            "aevalsrc=if(lt(mod(t\\,1)\\,.055)\\,.13*sin(2*PI*1200*t)\\,0):s=24000:d=" + VISUAL_QUESTION_SECONDS,
            "-c:a", "pcm_s16le", target.toString()), dir);
    }

    private void writeSilence(Path target, double seconds, Path dir) throws Exception {
        runFfmpeg(List.of(ffmpeg, "-y", "-v", "warning", "-f", "lavfi", "-i", "anullsrc=r=24000:cl=mono",
            "-t", String.format(java.util.Locale.ROOT, "%.6f", seconds), "-c:a", "pcm_s16le", target.toString()), dir);
    }

    private void runFfmpeg(List<String> command, Path dir) throws Exception {
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(dir.resolve("speech-ffmpeg.log").toFile()).start();
        try {
            if (!process.waitFor(2, TimeUnit.MINUTES)) { process.destroyForcibly(); throw new IllegalStateException("FFmpeg timed out while assembling speech"); }
        } catch (InterruptedException ex) {
            process.destroyForcibly(); Thread.currentThread().interrupt(); throw ex;
        }
        if (process.exitValue() != 0) throw new IllegalStateException("FFmpeg could not assemble the local speech track; inspect speech-ffmpeg.log");
    }

    private void runVideo(List<String> command, Path log) throws Exception {
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(10, TimeUnit.MINUTES)) { process.destroyForcibly(); throw new IllegalStateException("FFmpeg timed out; retained frames can be inspected"); }
        } catch (InterruptedException ex) {
            process.destroyForcibly(); Thread.currentThread().interrupt(); throw ex;
        }
        if (process.exitValue() != 0) throw new IllegalStateException("FFmpeg failed; inspect the local FFmpeg log");
    }

    private BufferedImage readArt(Path dir, int i) throws Exception {
        BufferedImage art = ImageIO.read(dir.resolve("art-" + i + ".png").toFile());
        if (art == null) throw new IllegalStateException("Unreadable artwork " + (i + 1));
        return vivid(art);
    }

    /** A restrained thumbnail-grade lift; the original art file remains unchanged and available. */
    private static BufferedImage vivid(BufferedImage source) {
        var result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            int rgb = source.getRGB(x, y);
            float[] hsb = Color.RGBtoHSB((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, null);
            hsb[1] = Math.min(1f, hsb[1] * 1.12f + .015f);
            hsb[2] = Math.min(1f, Math.max(0f, (hsb[2] - .5f) * 1.05f + .5f + .01f));
            result.setRGB(x, y, Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]));
        }
        return result;
    }

    private BufferedImage composed(EpisodeSpec.Puzzle p, BufferedImage art, int index, String phase, int countdown,
                                   boolean draft, SceneOverlay overlay, double time, String channelName, int puzzleCount) {
        return "visual".equals(p.kind()) ? KidsFrameRenderer.frame(p, art, index, phase, countdown, draft, overlay, time, channelName, puzzleCount)
            : frame(p, art, index, phase, countdown, draft);
    }

    private static final class FrameSequence {
        final Path dir;
        final StringBuilder concat = new StringBuilder();
        int number, ticks;
        String last;
        FrameSequence(Path dir) { this.dir = dir; }
        void add(BufferedImage image, int durationTicks) throws Exception {
            last = String.format(java.util.Locale.ROOT, "frame-%04d.png", number++);
            ImageIO.write(image, "png", dir.resolve(last).toFile());
            concat.append("file '").append(last).append("'\noption framerate 30\nduration ")
                .append(String.format(java.util.Locale.ROOT, "%.9f", durationTicks / 30.0)).append('\n');
            ticks += durationTicks;
        }
        void finish() throws Exception {
            concat.append("file '").append(last).append("'\noption framerate 30\n");
            Files.writeString(dir.resolve("frames.txt"), concat);
        }
    }

    BufferedImage frame(EpisodeSpec.Puzzle p, BufferedImage art, int index, String phase, int countdown, boolean draft) {
        if ("visual".equals(p.kind())) return KidsFrameRenderer.frame(p, art, index, phase, countdown, draft);
        BufferedImage canvas = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setColor(INK); g.fillRect(0, 0, WIDTH, HEIGHT);
            Rectangle fit = ImageLayout.contain(art.getWidth(), art.getHeight(), new Rectangle(0, 0, WIDTH, HEIGHT));
            // Reframe contextual artwork below the evidence header, preserving its proportions.
            // The lower background extends off-canvas; the complete original remains in the studio.
            g.drawImage(art, fit.x, fit.y + 160, fit.width, fit.height, null);
            panel(g, 0, 0, WIDTH, 124, new Color(13, 25, 38, 242));
            text(g, "BRAIN BOOSTER LAB  /  CASE " + (index + 1), 38, 16, 1400, 36, 25, GOLD, Font.BOLD);
            text(g, p.question(), 38, 54, 1720, 58, 45, PAPER, Font.BOLD);
            if (draft) text(g, "DRAFT", 1740, 16, 145, 36, 25, GOLD, Font.BOLD);

            if (phase.equals("setup")) {
                panel(g, 140, 330, 1640, 410, new Color(13, 25, 38, 240));
                text(g, p.title(), 180, 360, 1560, 90, 62, GOLD, Font.BOLD);
                text(g, p.setup(), 180, 474, 1560, 180, 48, PAPER, Font.PLAIN);
                text(g, "READ THE EVIDENCE. MAKE YOUR CASE.", 180, 670, 1560, 42, 30, GOLD, Font.BOLD);
            } else {
                int factHeight = p.facts().size() * 50 + 24;
                panel(g, 0, 124, WIDTH, factHeight, new Color(13, 25, 38, 226));
                for (int j = 0; j < p.facts().size(); j++)
                    text(g, p.facts().get(j), 38, 132 + j * 50, 1844, 48, 34, PAPER, Font.PLAIN);
                if (phase.equals("reveal")) {
                    panel(g, 210, 430, 1500, 350, new Color(13, 25, 38, 241));
                    text(g, "ANSWER " + p.answerId() + "  /  HERE'S WHY", 250, 456, 1420, 60, 44, GOLD, Font.BOLD);
                    text(g, p.explanation(), 250, 535, 1420, 213, 39, PAPER, Font.PLAIN);
                } else if (countdown > 0) {
                    panel(g, 1764, 725, 116, 102, new Color(13, 25, 38, 235));
                    text(g, String.valueOf(countdown), 1784, 741, 85, 74, 57, GOLD, Font.BOLD);
                }
                for (int c = 0; c < 3; c++) {
                    var choice = p.choices().get(c);
                    int x = 32 + c * 628;
                    boolean correct = phase.equals("reveal") && choice.id().equals(p.answerId());
                    panel(g, x, 850, 600, 188, correct ? new Color(255, 209, 96, 250) : new Color(13, 25, 38, 242));
                    Color foreground = correct ? INK : PAPER;
                    text(g, choice.id() + "  " + choice.label(), x + 24, 866, 552, 44, 32, foreground, Font.BOLD);
                    text(g, choice.statement(), x + 24, 920, 552, 102, 30, foreground, Font.PLAIN);
                }
            }
            panel(g, 0, 1048, WIDTH, 32, INK);
            text(g, phase.equals("reveal") ? "Reasoning beats guessing. What was your key clue?"
                : "Read every fact. Choose A, B or C. Pause if you need more time.", 38, 1050, 1844, 27, 22, PAPER, Font.PLAIN);
        } finally { g.dispose(); }
        return canvas;
    }

    private static void panel(Graphics2D g, int x, int y, int w, int h, Color color) {
        g.setColor(color); g.fillRect(x, y, w, h);
    }

    /** Wrap and shrink within a readable range; fail rather than silently delete puzzle evidence. */
    static void text(Graphics2D g, String value, int x, int y, int width, int height, int size, Color color, int style) {
        for (int fontSize = size; fontSize >= Math.min(size, 24); fontSize--) {
            g.setFont(new Font("SansSerif", style, fontSize));
            var metrics = g.getFontMetrics();
            var lines = new ArrayList<String>();
            String line = "";
            boolean fits = true;
            for (String word : value.split("\\s+")) {
                if (metrics.stringWidth(word) > width) { fits = false; break; }
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (metrics.stringWidth(candidate) > width) { lines.add(line); line = word; }
                else line = candidate;
            }
            if (!line.isEmpty()) lines.add(line);
            int lineHeight = metrics.getHeight();
            if (!fits || lines.size() * lineHeight > height) continue;
            g.setColor(color);
            for (int i = 0; i < lines.size(); i++) g.drawString(lines.get(i), x, y + metrics.getAscent() + i * lineHeight);
            return;
        }
        throw new IllegalArgumentException("Text cannot fit without losing readability: " + value);
    }
}
