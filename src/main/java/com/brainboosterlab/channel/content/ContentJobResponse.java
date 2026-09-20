package com.brainboosterlab.channel.content;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ContentJobResponse(
        UUID id,
        String title,
        String prompt,
        ContentJobStatus status,
        String scriptText,
        String generationModel,
        String generationResponseId,
        Integer inputTokens,
        Integer outputTokens,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    static ContentJobResponse from(ContentJob job) {
        return new ContentJobResponse(
                job.getId(), job.getTitle(), job.getPrompt(), job.getStatus(),
                job.getScriptText(), job.getGenerationModel(), job.getGenerationResponseId(),
                job.getInputTokens(), job.getOutputTokens(),
                job.getCreatedAt(), job.getUpdatedAt()
        );
    }
}
