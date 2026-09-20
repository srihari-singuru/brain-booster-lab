package com.brainboosterlab.channel.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContentJobServiceTest {

    @Mock
    private ContentJobRepository repository;

    @Mock
    private PuzzleScriptGenerator generator;

    @InjectMocks
    private ContentJobService service;

    @Test
    void createsDraftJob() {
        when(repository.save(any(ContentJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ContentJob job = service.create(new ContentJobRequest("  Find the Hidden Key  ", "A ten-second puzzle"));

        assertThat(job.getTitle()).isEqualTo("Find the Hidden Key");
        assertThat(job.getStatus()).isEqualTo(ContentJobStatus.DRAFT);
        verify(repository).save(any(ContentJob.class));
    }

    @Test
    void approvesDraftJob() {
        UUID id = UUID.randomUUID();
        ContentJob job = ContentJob.draft("A clue", null);
        when(repository.findById(id)).thenReturn(Optional.of(job));
        when(repository.save(job)).thenReturn(job);

        ContentJob approved = service.approve(id);

        assertThat(approved.getStatus()).isEqualTo(ContentJobStatus.APPROVED);
        verify(repository).save(job);
    }

    @Test
    void rejectsUnknownJob() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(id))
                .isInstanceOf(ContentJobNotFoundException.class);
    }

    @Test
    void generatesApprovedJobAndMarksItReady() {
        UUID id = UUID.randomUUID();
        ContentJob job = ContentJob.draft("A clue", "Find the star");
        job.approve();
        when(repository.findById(id)).thenReturn(Optional.of(job));
        when(repository.save(job)).thenReturn(job);
        when(generator.generate(job)).thenReturn(new GeneratedScript("TITLE: A clue", "mock", "mock-response", null, null));

        ContentJob generated = service.generate(id);

        assertThat(generated.getStatus()).isEqualTo(ContentJobStatus.READY);
        assertThat(generated.getScriptText()).contains("TITLE: A clue");
        verify(generator).generate(job);
    }
}
