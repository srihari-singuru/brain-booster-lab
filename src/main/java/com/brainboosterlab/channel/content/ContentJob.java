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
