package com.brainboosterlab.channel.studio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Studio-chosen creative constraints for one case. Independent one-case requests otherwise converge on the
 * model's single most likely idea, so structure (clue logic, question shape, cast, hook, suspect count, and
 * answer position) is varied by code rather than by asking the model to "be different".
 * Plans are seeded by the episode id, so retrying an unfinished slot reuses the same blueprint.
 */
record CaseBlueprint(Format format, String world, String cast, String hook, int suspects, String answerId, boolean twist) {
    record Format(String name, String question, String clue) {}

    static final List<Format> FORMATS = List.of(
        new Format("CARRIED TRACE", "who caused it, e.g. \"Who knocked over the flour?\"",
            "material from the scene (powder, pollen, soot, glitter, sand, jam) sits on only the culprit, exactly where "
                + "the action would touch. Decoys carry a similar material in a different color or in a spot the action would not touch."),
        new Format("THE TRAIL", "who went there, e.g. \"Who walked through the garden?\"",
            "prints, tracks, drips, or a crumb path lead away from the scene. The trail's specific shape matches only the "
                + "culprit's sole, paw, wheel, or leaking item. Decoys have similar feet, paws, or wheels with a different shape."),
        new Format("LEFT BEHIND", "who was there, e.g. \"Who was in the treehouse?\"",
            "the culprit dropped something of theirs at the scene (a button, bead, feather, ribbon, or sticker) and is "
                + "visibly missing exactly that piece. Decoys have similar items that are all complete."),
        new Format("SHADOW OR REFLECTION", "who is hiding it, e.g. \"Who has the golden egg?\"",
            "a mirror, window, puddle, shiny pot, or shadow shows what one suspect hides or is doing, while every "
                + "suspect looks innocent when seen directly."),
        new Format("STILL FRESH", "who was there most recently, e.g. \"Who just ate the ice cream?\"",
            "the state of things shows time without any clock: melting only on one, steam rising from one cup, "
                + "footprints still shiny wet, a swing still moving, smoke from a just-blown candle. Decoys show older, finished states."),
        new Format("BROKEN ALIBI", "whose story is false, e.g. \"Whose story does not match?\"",
            "facts states the one claim every suspect makes (e.g. \"Everyone says they were swimming.\"). Exactly one "
                + "suspect's look proves the claim false (dry hair and a dry towel). Decoys match the claim."),
        new Format("THE ONLY TRUE STORY", "who is honest, e.g. \"Who is telling the truth?\"",
            "facts states a helpful claim every suspect makes (e.g. \"Everyone says they fed the fish.\"). Only one "
                + "suspect shows real proof. The others show tempting but wrong proof. The answer is the honest suspect."),
        new Format("THE FAKE", "who is not real, e.g. \"Who is the fake pilot?\"",
            "one suspect pretends to be something (pilot, knight, chef, doctor, wizard, statue, famous singer). One "
                + "visible detail gives them away: a toy tool, an upside-down map, cardboard armor, a price tag still on."),
        new Format("CAUSE AND EFFECT", "whose thing caused it, e.g. \"Whose fan blew the cards away?\"",
            "something happened by simple physics (wind, water, a magnet, a rope, a ramp, a balloon). Only the "
                + "culprit's object is visibly connected to the effect or pointing the right way. Decoys have similar objects that are not."),
        new Format("FROM OUTSIDE", "who just came in, e.g. \"Who just came in from the snow?\"",
            "weather or place traces show who just arrived from a certain place: melting snow on a hat, a dripping "
                + "umbrella, sand spilling from shoes, leaves in hair. Decoys show traces from somewhere else or old, dry traces."),
        new Format("THE PERFECT FIT", "whose thing matches, e.g. \"Whose key opened the box?\"",
            "a broken piece, torn edge, cut shape, bite mark, or key outline at the scene matches only one suspect's item. "
                + "Decoys hold similar items with a clearly different shape."),
        new Format("THE WITNESS", "who saw it, e.g. \"Who saw what happened?\"",
            "the answer is a witness, not a culprit: only one suspect faces the scene or holds binoculars or a "
                + "telescope aimed at it. The others face away or their view is blocked by a visible object."),
        new Format("THE RULE", "who fits or breaks a rule, e.g. \"Which one is the real ghost?\"",
            "facts states one clear fictional or magic rule (e.g. \"Real ghosts have no shadow.\"). The picture shows "
                + "exactly one suspect fitting or breaking it. Everything else about the suspects looks similar."),
        new Format("HIDDEN IN PLAIN SIGHT", "who has it, e.g. \"Who took the crown?\"",
            "the missing thing is on or with the culprit but disguised: inside a hat, part of a costume, tucked in a "
                + "bouquet, poking from a pocket. Decoys hold similar-shaped harmless things."),
        new Format("THE ANIMAL KNOWS", "who the animal points to, e.g. \"Who has the dog's treats?\"",
            "an animal reacts to only one suspect (sniffing a pocket, staring at a bag, following falling seeds), and "
                + "the reason is visible on that suspect. The animal ignores the decoys.")
    );

    static final List<String> WORLDS = List.of(
        "a bakery", "a dinosaur museum at night", "a school science fair", "a forest campsite", "a zoo kitchen",
        "a space station", "a pirate ship", "a talent show backstage", "a sports day field", "a snowy mountain cabin",
        "a castle kitchen", "a rainforest research camp", "an underwater hotel", "a steam train carriage",
        "a circus tent", "a toy factory", "a farm barn", "a birthday party", "a beach boardwalk",
        "a wizard school classroom", "a robot workshop", "a hot air balloon festival", "an ice rink",
        "a flower show greenhouse", "a cooking contest TV studio", "a friendly haunted house", "a desert market",
        "an airport gate", "an aquarium", "a bowling alley", "an art gallery", "a movie set", "a royal parade",
        "a carnival", "a treehouse club", "a moon base", "a jungle river boat", "a fire station open day",
        "a pet show", "a music concert"
    );

    static final List<String> CASTS = List.of(
        "kids from the same class", "grown-up workers with clear jobs (chef, gardener, mail carrier, painter)",
        "pets and farm animals", "zoo animals", "robots and machines", "friendly monsters",
        "fairy-tale characters (knight, dragon, wizard, princess)", "a family (grandma, dad, cousin, aunt)",
        "players from a sports team", "pirates and a parrot", "a space crew with a friendly alien",
        "circus performers", "friendly dinosaurs", "a mix of kids, grown-ups, and one pet"
    );

    static final List<String> HOOKS = List.of(
        "a short quote from the upset owner, e.g. \"My golden egg is gone!\"",
        "a sudden sound word, e.g. \"CRASH!\" or \"SPLASH!\"",
        "an impossible-seeming fact, e.g. the door was locked all night",
        "a ticking clock, e.g. the show starts in five minutes",
        "a funny, surprising sight, e.g. a cow wearing the chef's hat",
        "a direct question to the viewer, e.g. \"Can you keep a secret?\"",
        "a breaking-news headline, e.g. \"Breaking news from the zoo!\""
    );

    static CaseBlueprint forSlot(UUID seed, int slot, int total) {
        return plan(seed, total).get(slot);
    }

    static List<CaseBlueprint> plan(UUID seed, int count) {
        var random = new Random(seed.getMostSignificantBits() ^ seed.getLeastSignificantBits());
        var formats = cycle(FORMATS, count, random);
        var worlds = cycle(WORLDS, count, random);
        var casts = cycle(CASTS, count, random);
        var hooks = cycle(HOOKS, count, random);
        var usage = new int[4];
        String previousAnswer = "";
        var plan = new ArrayList<CaseBlueprint>();
        for (int i = 0; i < count; i++) {
            int suspects = random.nextInt(10) < 4 ? 4 : 3;
            String answer = balancedAnswer(usage, suspects, previousAnswer, random);
            previousAnswer = answer;
            plan.add(new CaseBlueprint(formats.get(i), worlds.get(i), casts.get(i), hooks.get(i), suspects, answer,
                random.nextInt(4) == 0));
        }
        return List.copyOf(plan);
    }

    /** Least-used letter first, never the previous letter, random tie-break: fair spread with no visible rotation. */
    private static String balancedAnswer(int[] usage, int suspects, String previous, Random random) {
        var candidates = new ArrayList<Integer>();
        int fewest = Integer.MAX_VALUE;
        for (int i = 0; i < suspects; i++) {
            if (String.valueOf((char) ('A' + i)).equals(previous)) continue;
            if (usage[i] < fewest) { fewest = usage[i]; candidates.clear(); }
            if (usage[i] == fewest) candidates.add(i);
        }
        int chosen = candidates.get(random.nextInt(candidates.size()));
        usage[chosen]++;
        return String.valueOf((char) ('A' + chosen));
    }

    /** Uses every option before any repeats, and never places the same option twice in a row. */
    private static <T> List<T> cycle(List<T> options, int count, Random random) {
        var result = new ArrayList<T>();
        while (result.size() < count) {
            var round = new ArrayList<>(options);
            Collections.shuffle(round, random);
            if (!result.isEmpty() && round.size() > 1 && round.getFirst().equals(result.getLast()))
                Collections.swap(round, 0, 1 + random.nextInt(round.size() - 1));
            result.addAll(round);
        }
        return List.copyOf(result.subList(0, count));
    }

    String direction() {
        return "\n\nCASE BLUEPRINT — chosen by the studio so every case in the channel feels different. Follow it exactly; "
            + "it overrides any case-type choice elsewhere in these instructions.\n"
            + "- Case format: " + format.name() + ". Question shape: " + format.question() + ". Clue logic: " + format.clue() + "\n"
            + "- World: " + world + ". Make the setting part of the story and the clue, not just background.\n"
            + "- Suspects: exactly " + suspects + " suspects, drawn from " + cast + ". Give each one a distinct, vivid role "
            + "that is easy to see in the picture. Never call them \"helpers\".\n"
            + "- Correct answer: OPTION " + answerId + ". Build the case so the suspect in left-to-right position "
            + answerId + " is the answer.\n"
            + "- Hook: open the setup with " + hook + ".\n"
            + (twist ? "- Twist: make the answer the suspect viewers would least expect (the smallest, the quietest, the pet, "
                + "or the one helping), while the most suspicious-looking suspect is innocent. Keep it completely fair.\n" : "");
    }
}
