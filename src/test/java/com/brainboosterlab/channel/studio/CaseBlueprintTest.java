package com.brainboosterlab.channel.studio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CaseBlueprintTest {
    @Test void planIsStableForRetriesAndDiffersBetweenEpisodes() {
        UUID episode = UUID.randomUUID();
        assertThat(CaseBlueprint.plan(episode, 20)).isEqualTo(CaseBlueprint.plan(episode, 20));
        assertThat(CaseBlueprint.forSlot(episode, 7, 20)).isEqualTo(CaseBlueprint.plan(episode, 20).get(7));
        assertThat(CaseBlueprint.plan(UUID.randomUUID(), 20)).isNotEqualTo(CaseBlueprint.plan(episode, 20));
    }

    @Test void everyFormatIsUsedBeforeAnyRepeatAndNeverTwiceInARow() {
        for (int run = 0; run < 50; run++) {
            var plan = CaseBlueprint.plan(UUID.randomUUID(), 20);
            int formats = CaseBlueprint.FORMATS.size();
            assertThat(new HashSet<>(plan.subList(0, formats).stream().map(CaseBlueprint::format).toList())).hasSize(formats);
            for (int i = 1; i < plan.size(); i++) {
                assertThat(plan.get(i).format()).isNotEqualTo(plan.get(i - 1).format());
                assertThat(plan.get(i).world()).isNotEqualTo(plan.get(i - 1).world());
                assertThat(plan.get(i).hook()).isNotEqualTo(plan.get(i - 1).hook());
            }
        }
    }

    @Test void answerLettersFitTheSuspectCountAndDoNotFormARotation() {
        for (int run = 0; run < 50; run++) {
            var plan = CaseBlueprint.plan(UUID.randomUUID(), 20);
            for (int i = 0; i < plan.size(); i++) {
                var blueprint = plan.get(i);
                assertThat(blueprint.suspects()).isBetween(3, 4);
                assertThat(blueprint.answerId().charAt(0) - 'A').isBetween(0, blueprint.suspects() - 1);
                if (i > 0) assertThat(blueprint.answerId()).isNotEqualTo(plan.get(i - 1).answerId());
            }
            long a = plan.stream().filter(b -> b.answerId().equals("A")).count();
            long b = plan.stream().filter(p -> p.answerId().equals("B")).count();
            long c = plan.stream().filter(p -> p.answerId().equals("C")).count();
            assertThat(Math.max(a, Math.max(b, c)) - Math.min(a, Math.min(b, c))).isLessThanOrEqualTo(3);
        }
    }

    @Test void directionStatesTheBlueprintForTheModel() {
        var blueprint = CaseBlueprint.forSlot(UUID.randomUUID(), 0, 3);
        assertThat(blueprint.direction()).contains("CASE BLUEPRINT", blueprint.format().name(), blueprint.world(),
            "exactly " + blueprint.suspects() + " suspects", "OPTION " + blueprint.answerId(), "Never call them \"helpers\"");
    }

    @Test void everyTimerCueFitsItsSpeechSlot() {
        for (int i = 0; i < 8; i++) {
            String hint = NarrationAi.varietyHint(i);
            String cue = hint.substring(hint.lastIndexOf(": \"") + 3, hint.length() - 1);
            assertThat(cue.toLowerCase()).contains("ten seconds");
            assertThat(cue.trim().split("\\s+").length).isBetween(5, 7);
        }
        assertThat(NarrationAi.varietyHint(0)).isNotEqualTo(NarrationAi.varietyHint(1));
    }
}
