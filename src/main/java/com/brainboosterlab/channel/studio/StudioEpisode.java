package com.brainboosterlab.channel.studio;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "studio_episodes")
class StudioEpisode {
    @Id UUID id;
    @Version long version;
    @Column(columnDefinition = "text", nullable = false) String brief;
    @Column(nullable = false) String status;
    @Column(columnDefinition = "text") String specJson;
    @Column(columnDefinition = "text") String reviewJson;
    @Column(columnDefinition = "text") String narrationJson;
    @Column(columnDefinition = "text") String narrationReviewJson;
    @Column(columnDefinition = "text") String narrationGroundingJson;
    @Column(columnDefinition = "text") String speechJson;
    @Column(columnDefinition = "text") String settingsJson;
    @Column(columnDefinition = "text") String stageInstructionsJson;
    @Column(columnDefinition = "text") String lastError;
    String failedStage;
    String scriptModel;
    String responseId;
    String narrationModel;
    String narrationResponseId;
    String narrationGroundingModel;
    String narrationGroundingResponseId;
    String speechModel;
    String speechVoice;
    @Column(nullable = false) Instant createdAt;
    Instant approvedAt;
    protected StudioEpisode() {}
    StudioEpisode(String brief) {
        id = UUID.randomUUID();
        this.brief = brief;
        status = "DRAFT";
        createdAt = Instant.now();
    }
}
