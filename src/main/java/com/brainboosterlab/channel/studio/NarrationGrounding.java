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
