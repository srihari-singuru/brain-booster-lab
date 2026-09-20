package com.brainboosterlab.channel.content;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ContentJobResponse(
        UUID id,
        String title,
        String prompt,
        ContentJobStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    static ContentJobResponse from(ContentJob job) {
        return new ContentJobResponse(
                job.getId(), job.getTitle(), job.getPrompt(), job.getStatus(),
                job.getCreatedAt(), job.getUpdatedAt()
        );
    }
}
