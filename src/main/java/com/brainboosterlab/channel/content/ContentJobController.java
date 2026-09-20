package com.brainboosterlab.channel.content;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/content-jobs")
public class ContentJobController {

    private final ContentJobService service;

    public ContentJobController(ContentJobService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ContentJobResponse> create(@Valid @RequestBody ContentJobRequest request) {
        ContentJobResponse response = ContentJobResponse.from(service.create(request));
        return ResponseEntity.created(URI.create("/api/v1/content-jobs/" + response.id())).body(response);
    }

    @GetMapping
    List<ContentJobResponse> list() {
        return service.list().stream().map(ContentJobResponse::from).toList();
    }

    @PostMapping("/{id}/approve")
    ContentJobResponse approve(@PathVariable UUID id) {
        return ContentJobResponse.from(service.approve(id));
    }
}
