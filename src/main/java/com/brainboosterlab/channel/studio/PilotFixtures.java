package com.brainboosterlab.channel.studio;
import java.util.List;
import static com.brainboosterlab.channel.studio.EpisodeSpec.*;

final class PilotFixtures {
    private PilotFixtures() {}
    static EpisodeSpec kids() {
        return new EpisodeSpec("Three little mysteries", List.of(
            new Puzzle("visual", "The Magic Cafe", "Three friends are drinking cocoa. One is a friendly ghost!",
                "Who is the friendly ghost?", List.of("In this magic cafe, only ghosts have no shadow."),
                List.of(new Choice("A","Maya","Teal coat"),new Choice("B","Leo","Amber coat"),new Choice("C","Zara","Coral coat")),
                "B", "Leo has no shadow! Did you spot it?",
                "Exactly three full-body friends holding cocoa mugs on a sunlit cafe terrace. A left in teal and C right in coral cast clear shadows on a spacious plain floor. B center in amber has absolutely no shadow. Everyone otherwise looks equally ordinary and friendly. All feet and shadows clearly visible.",12),
            new Puzzle("visual", "The Cardboard Costume", "Three friends made shiny robot outfits. One used a cardboard box. Which one?",
                "Which robot outfit is made of cardboard?", List.of(),
                List.of(new Choice("A","Maya","Teal shirt"),new Choice("B","Leo","Amber shirt"),new Choice("C","Zara","Coral shirt")),
                "C", "Zara's open flap shows the cardboard inside!",
                "Exactly three friends in similar shiny silver robot torso outfits, A left teal sleeves, B center amber sleeves, C right coral sleeves, waving in a park. C alone has an open flap exposing brown corrugated cardboard. A and B have smooth silver fabric-and-foam panels without cardboard. All silhouettes and decorations similar; ordinary human hands and faces. No text or labels.",12),
            new Puzzle("visual", "The Toy Cat", "Three cats are waiting for a cuddle. One is a wind-up toy!",
                "Which cat is a wind-up toy?", List.of(),
                List.of(new Choice("A","Milo","Ginger cat"),new Choice("B","Luna","Gray cat"),new Choice("C","Pip","Black-and-white cat")),
                "A", "Milo has a little winding key on his back!",
                "Exactly three equally appealing cats sitting in left, middle and right thirds on a garden bench. Left ginger cat A has one clearly visible brass wind-up key attached to its back; no other toy clues. Middle gray cat B and right black-and-white cat C have ordinary uninterrupted fur, backs clearly visible. All have natural proportions and friendly faces.",12)
        ));
    }
    static EpisodeSpec sample() {
        return new EpisodeSpec("Three cases. One sharp mind.", List.of(
            new Puzzle("deduction", "The Observatory Visit", "Three visitors describe their afternoon at a hilltop observatory.",
                "Whose account contradicts the opening hours?",
                List.of("The telescope room was locked from 2 pm to 4 pm.", "Nobody entered that room during the closure."),
                List.of(new Choice("A", "Maya", "At 3 pm, I sketched the gardens outside."),
                    new Choice("B", "Leo", "At 3 pm, I looked through the telescope inside."),
                    new Choice("C", "Zara", "At 4:30 pm, I entered the telescope room.")),
                "B", "Leo says he was inside at 3 pm, during the closure. Maya stayed outside; Zara arrived after reopening. Only Leo's account conflicts with the facts.",
                "Three distinct adult visitors, Maya in teal on the left, Leo in coral at center, Zara in purple on the right. Waist-up in an elegant hilltop observatory garden at golden hour, telescope dome behind them. All equally calm and credible, no guilty expressions.", 15),
            new Puzzle("logic", "The Trophy Lockers", "A school trophy is in exactly one of three lockers. Each locker has a statement.",
                "Which locker holds the trophy?", List.of("Exactly ONE of the three statements is true."),
                List.of(new Choice("A", "Left locker", "The trophy is in B."), new Choice("B", "Middle locker", "The trophy is not in B."),
                    new Choice("C", "Right locker", "The trophy is not in A.")), "A",
                "A and B's statements are opposites, so one is always true. C's statement must be false. The trophy is in A. If it were in B or C, two statements would be true.",
                "Exactly three equally sized closed lockers, left teal, center coral, right purple, at an elegant academy science fair. Straight-on symmetrical composition. All shut, no trophy visible, no letters or numbers. Three friendly students very small in distant background, no extra lockers.", 20),
            new Puzzle("sequence", "The Clockwork Greenhouse", "A greenhouse controller uses the same rule at every step to set its next watering time.",
                "Which number comes next?", List.of("Rule: double the current number, then add 1.", "Sequence: 2, 5, 11, 23, ?"),
                List.of(new Choice("A", "45", "Double 23, then subtract 1."), new Choice("B", "46", "Double 23."), new Choice("C", "47", "Double 23, then add 1.")),
                "C", "23 doubled is 46; adding 1 gives 47. The same rule gives 2 → 5 → 11 → 23. A subtracts instead of adding; B misses the final +1.",
                "A confident adult botanical engineer on the left, sophisticated brass irrigation controller at center, lush sunlit glasshouse and hanging plants. Wide inviting editorial scene, unmarked controller with blank dark display. No digits, equations or symbols generated in the artwork.", 12)
        ));
    }
}
