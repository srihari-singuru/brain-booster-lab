package com.brainboosterlab.channel.studio;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class SpeechPacingTest {
    @Test void uses_constant_phase_durations_at_the_selected_global_speed() {
        assertThat(SpeechAi.targetSecondsFor("question", 1.00)).isEqualTo(9.0);
        assertThat(SpeechAi.targetSecondsFor("reveal", 1.00)).isEqualTo(8.0);
        assertThat(SpeechAi.targetSecondsFor("timer", 1.00)).isEqualTo(2.5);
        assertThat(SpeechAi.targetSecondsFor("question", 1.10)).isCloseTo(9.0 / 1.10, org.assertj.core.data.Offset.offset(.000001));
        assertThat(SpeechAi.targetSecondsFor("reveal", .90)).isCloseTo(8.0 / .90, org.assertj.core.data.Offset.offset(.000001));
    }
}
