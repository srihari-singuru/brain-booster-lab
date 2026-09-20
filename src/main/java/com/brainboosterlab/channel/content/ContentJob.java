package com.brainboosterlab.channel.content;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "content_jobs")
public class ContentJob {

    @Id
    private UUID id;

    @Column(nullable = false, length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ContentJobStatus status;

    @Column(columnDefinition = "text")
    private String prompt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "script_text", columnDefinition = "text")
    private String scriptText;

    @Column(name = "generation_model", length = 120)
    private String generationModel;

    @Column(name = "generation_response_id", length = 120)
    private String generationResponseId;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    protected ContentJob() {
    }

    private ContentJob(String title, String prompt) {
        this.id = UUID.randomUUID();
        this.title = title;
        this.prompt = prompt;
        this.status = ContentJobStatus.DRAFT;
    }

    public static ContentJob draft(String title, String prompt) {
        return new ContentJob(title, prompt);
    }

    public void approve() {
        if (status != ContentJobStatus.DRAFT) {
            throw new IllegalStateException("Only draft content jobs can be approved");
        }
        status = ContentJobStatus.APPROVED;
    }

    public void startGenerating() {
        if (status != ContentJobStatus.APPROVED) {
            throw new IllegalStateException("Only approved content jobs can be generated");
        }
        status = ContentJobStatus.GENERATING;
    }

    public void markGenerated(GeneratedScript generated) {
        if (status != ContentJobStatus.GENERATING) {
            throw new IllegalStateException("Content job is not being generated");
        }
        scriptText = generated.scriptText();
        generationModel = generated.model();
        generationResponseId = generated.responseId();
        inputTokens = generated.inputTokens();
        outputTokens = generated.outputTokens();
        status = ContentJobStatus.READY;
    }

    public void markFailed(String message) {
        scriptText = "Generation failed: " + message;
        generationModel = "error";
        status = ContentJobStatus.FAILED;
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getPrompt() {
        return prompt;
    }

    public ContentJobStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public String getScriptText() {
        return scriptText;
    }

    public String getGenerationModel() {
        return generationModel;
    }

    public String getGenerationResponseId() {
        return generationResponseId;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
