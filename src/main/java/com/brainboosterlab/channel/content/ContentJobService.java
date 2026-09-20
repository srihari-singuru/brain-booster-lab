package com.brainboosterlab.channel.content;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ContentJobService {

    private final ContentJobRepository repository;

    public ContentJobService(ContentJobRepository repository) {
        this.repository = repository;
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
}
