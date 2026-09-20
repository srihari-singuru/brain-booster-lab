package com.brainboosterlab.channel.content;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ContentJobService {

    private final ContentJobRepository repository;
    private final PuzzleScriptGenerator generator;
    private final VideoRenderer renderer;

    public ContentJobService(ContentJobRepository repository, PuzzleScriptGenerator generator, VideoRenderer renderer) {
        this.repository = repository;
        this.generator = generator;
        this.renderer = renderer;
    }

    @Transactional
    public ContentJob create(ContentJobRequest request) {
        return repository.save(ContentJob.draft(request.title().trim(), request.prompt()));
    }

    public List<ContentJob> list() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public ContentJob approve(UUID id) {
        ContentJob job = repository.findById(id)
                .orElseThrow(() -> new ContentJobNotFoundException(id));
        job.approve();
        return repository.save(job);
    }

    @Transactional
    public ContentJob generate(UUID id) {
        ContentJob job = repository.findById(id)
                .orElseThrow(() -> new ContentJobNotFoundException(id));
        job.startGenerating();
        repository.save(job);
        try {
            job.markGenerated(generator.generate(job));
            return repository.save(job);
        } catch (RuntimeException exception) {
            return markFailed(job, exception);
        }
    }

    private ContentJob markFailed(ContentJob job, RuntimeException exception) {
        // Keep the failure visible to the workflow owner while allowing a later retry policy.
        job.markFailed(exception.getMessage());
        return repository.save(job);
    }

    @Transactional
    public ContentJob render(UUID id) {
        ContentJob job = repository.findById(id)
                .orElseThrow(() -> new ContentJobNotFoundException(id));
        job.startRendering();
        repository.save(job);
        try {
            job.markRendered(renderer.render(job));
            return repository.save(job);
        } catch (RuntimeException exception) {
            return markFailed(job, exception);
        }
    }
}
