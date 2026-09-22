package com.brainboosterlab.channel.studio;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class SpeechPacingTest {
    @Test void acceptsOneNaturalSelectedSpeedWithoutPostSynthesisTimeStretching() {
        assertThatCode(() -> new SpeechAi.Profile("gpt-4o-mini-tts", "cedar", 1.05).validate()).doesNotThrowAnyException();
        assertThatThrownBy(() -> new SpeechAi.Profile("gpt-4o-mini-tts", "cedar", 1.30).validate())
            .hasMessageContaining("0.75 and 1.25");
    }
}
