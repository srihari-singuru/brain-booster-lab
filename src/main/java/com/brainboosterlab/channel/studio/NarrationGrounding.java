package com.brainboosterlab.channel.studio;

import java.util.List;

/** Final visual-continuity result, created from the delivered puzzle frames rather than the prompt alone. */
public record NarrationGrounding(EpisodeNarration narration, List<Finding> findings) {
    public record Finding(int puzzleNumber, boolean visualClueConfirmed, boolean narrationMatchesFrame,
                          boolean optionOnly, String notes) {}

    /** Keeps feedback display-safe without allowing incomplete editor feedback to pass the grounding gate. */
    static NarrationGrounding normalize(NarrationGrounding source, int expectedFindings) {
        List<Finding> supplied = source == null || source.findings() == null ? List.of() : source.findings();
        var normalized = java.util.stream.IntStream.range(0, expectedFindings).mapToObj(index -> {
            Finding finding = index < supplied.size() ? supplied.get(index) : null;
            String notes = finding == null ? null : finding.notes();
            boolean notePresent = notes != null && !notes.isBlank();
            String safeNotes = notePresent ? notes.trim() : "No editor note returned; manual review is required.";
            if (safeNotes.length() > 1000) safeNotes = safeNotes.substring(0, 1000);
            return new Finding(index + 1,
                notePresent && finding != null && finding.visualClueConfirmed(),
                notePresent && finding != null && finding.narrationMatchesFrame(),
                notePresent && finding != null && finding.optionOnly(), safeNotes);
        }).toList();
        return new NarrationGrounding(source == null ? null : source.narration(), normalized);
    }

    /**
     * Grounding may improve wording, but it must not invalidate the reviewed speech slots.
     * If it does, retain the already-reviewed narration and make the affected beats require
     * review instead of failing the entire grounding action with a validation exception.
     */
    NarrationGrounding preserveValidSpeechSlots(EpisodeNarration reviewed, EpisodeSpec spec) {
        if (narration == null) return new NarrationGrounding(reviewed, requireGroundingReview(findings,
            "Grounding returned no narration; the reviewed narration was retained."));
        reviewed.validate(spec);
        if (narration.puzzles() == null || narration.puzzles().size() != spec.puzzles().size())
            return new NarrationGrounding(reviewed, requireGroundingReview(findings,
                "Grounding changed narration outside its speech slot; the reviewed narration was retained."));

        var safeBeats = new java.util.ArrayList<EpisodeNarration.PuzzleNarration>();
        var changed = new java.util.ArrayList<Boolean>();
        for (int i = 0; i < spec.puzzles().size(); i++) {
            var groundedBeat = narration.puzzles().get(i);
            boolean valid = groundedBeat != null;
            if (valid) {
                try {
                    var singlePuzzle = new EpisodeSpec(spec.title(), List.of(spec.puzzles().get(i)));
                    new EpisodeNarration(reviewed.episodeOpening(), List.of(
                        new EpisodeNarration.PuzzleNarration(1, groundedBeat.questionLeadIn(), groundedBeat.timerCue(), groundedBeat.revealExplanation())),
                        reviewed.episodeClosing()).validate(singlePuzzle);
                } catch (IllegalArgumentException invalidGroundedBeat) {
                    valid = false;
                }
            }
            changed.add(!valid);
            safeBeats.add(valid
                ? new EpisodeNarration.PuzzleNarration(i + 1, groundedBeat.questionLeadIn(), groundedBeat.timerCue(), groundedBeat.revealExplanation())
                : reviewed.puzzles().get(i));
        }

        String safeOpening = hasWordsInRange(narration.episodeOpening(), 4, 28)
            ? narration.episodeOpening() : reviewed.episodeOpening();
        String safeClosing = hasWordsInRange(narration.episodeClosing(), 4, 28)
            ? narration.episodeClosing() : reviewed.episodeClosing();
        EpisodeNarration safeNarration = new EpisodeNarration(safeOpening, safeBeats, safeClosing);
        try {
            safeNarration.validate(spec);
        } catch (IllegalArgumentException invalidGroundedNarration) {
            safeNarration = reviewed;
            java.util.Collections.fill(changed, true);
        }
        return new NarrationGrounding(safeNarration, requireGroundingReview(findings, changed,
            "Grounding changed narration outside its speech slot; the reviewed line was retained."));
    }

    private static boolean hasWordsInRange(String text, int minimum, int maximum) {
        if (text == null || text.isBlank()) return false;
        int words = text.trim().split("\\s+").length;
        return words >= minimum && words <= maximum;
    }

    private static List<Finding> requireGroundingReview(List<Finding> findings, String warning) {
        return requireGroundingReview(findings,
            findings == null ? null : java.util.Collections.nCopies(findings.size(), true), warning);
    }

    private static List<Finding> requireGroundingReview(List<Finding> findings, List<Boolean> changed, String warning) {
        if (findings == null) return List.of();
        return java.util.stream.IntStream.range(0, findings.size()).mapToObj(index -> {
            Finding finding = findings.get(index);
            if (finding == null) return null;
            if (changed == null || index >= changed.size() || !changed.get(index)) return finding;
            String notes = finding.notes() == null ? "" : finding.notes().trim();
            String combined = notes.isEmpty() ? warning : notes + " " + warning;
            if (combined.length() > 1000) combined = combined.substring(0, 1000);
            return new Finding(finding.puzzleNumber(), finding.visualClueConfirmed(), false,
                finding.optionOnly(), combined);
        }).toList();
    }

    public void validate(EpisodeSpec spec) {
        narration.validate(spec);
        EpisodeSpec.require(findings != null && findings.size() == spec.puzzles().size(),
            "Narration grounding must inspect every puzzle");
        for (int i = 0; i < findings.size(); i++) {
            Finding finding = findings.get(i);
            EpisodeSpec.require(finding != null && finding.puzzleNumber() == i + 1,
                "Narration grounding findings must be numbered in order");
            EpisodeSpec.require(finding.notes() != null && !finding.notes().isBlank() && finding.notes().length() <= 1000,
                "Narration grounding notes are missing or too long");
        }
    }

    boolean passes(EpisodeSpec spec) {
        try { validate(spec); }
        catch (IllegalArgumentException ignored) { return false; }
        return findings.stream().allMatch(f -> f.visualClueConfirmed() && f.narrationMatchesFrame() && f.optionOnly());
    }
}
