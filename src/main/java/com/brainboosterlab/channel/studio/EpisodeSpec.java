package com.brainboosterlab.channel.studio;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Facts and choices are rendered by code, never delegated to image-generated text. */
public record EpisodeSpec(String title, List<Puzzle> puzzles) {
    public record Choice(String id, String label, String statement) {}
    public record Puzzle(String kind, String title, String setup, String question,
                         List<String> facts, List<Choice> choices, String answerId,
                         String explanation, String sceneDescription, int thinkSeconds) {}
    public void validate() {
        text(title, 65, "Episode title");
        require(puzzles != null && puzzles.size() >= 1 && puzzles.size() <= 10,
            "An episode must contain between 1 and 10 puzzles");
        var kinds = new HashSet<String>();
        for (Puzzle p : puzzles) {
            require(p != null, "Puzzle is missing");
            boolean visual = "visual".equals(p.kind());
            require(List.of("deduction", "logic", "sequence", "visual").contains(p.kind()), "Unsupported puzzle kind");
            require(visual || kinds.add(p.kind()), "Use distinct non-visual puzzle kinds");
            text(p.title(), 48, "Puzzle title");
            text(p.setup(), 155, "Setup");
            text(p.question(), visual ? 60 : 90, "Question");
            require(!visual || p.question().trim().split("\\s+").length <= 10, "Visual questions must be ten words or fewer");
            require(visual || !p.question().toLowerCase(Locale.ROOT).matches(".*(find|spot|hidden|odd.one.out).*"),
                    "Use a reasoning question, not an object search");
            require(p.facts() != null && (visual ? p.facts().size() <= 1 : p.facts().size() >= 1 && p.facts().size() <= 3), "Visual puzzles allow at most one short story rule");
            p.facts().forEach(f -> text(f, visual ? 65 : 100, "Fact"));
            require(p.choices() != null && p.choices().size() >= 3 && p.choices().size() <= 5,
                "Use between three and five choices");
            for (int i = 0; i < p.choices().size(); i++) {
                Choice c = p.choices().get(i);
                require(c != null && String.valueOf((char) ('A' + i)).equals(c.id()),
                    "Choices must be ordered consecutively from A");
                text(c.label(), 24, "Choice label");
                text(c.statement(), 100, "Choice statement");
            }
            require(p.answerId() != null && p.answerId().length() == 1
                && p.answerId().charAt(0) >= 'A' && p.answerId().charAt(0) < 'A' + p.choices().size(),
                "Answer must refer to a choice");
            text(p.explanation(), visual ? 85 : 300, "Explanation");
            require(!visual || p.explanation().trim().split("\\s+").length <= 14, "Visual reveals must be fourteen words or fewer");
            text(p.sceneDescription(), 1600, "Scene description");
            require(p.thinkSeconds() >= 8 && p.thinkSeconds() <= (visual ? 15 : 25), "Thinking time outside supported range");
        }
    }
    private static void text(String value, int max, String label) {
        require(value != null && !value.isBlank() && value.length() <= max, label + " is missing or too long (max " + max + ")");
        require(value.codePoints().allMatch(c -> c >= 32 && c != 127), label + " must be a single line");
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
