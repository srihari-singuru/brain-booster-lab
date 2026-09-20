package com.brainboosterlab.channel.content;

import jakarta.validation.constraints.NotBlank;

public record ContentJobRequest(
        @NotBlank(message = "title is required") String title,
        String prompt
) {
}
