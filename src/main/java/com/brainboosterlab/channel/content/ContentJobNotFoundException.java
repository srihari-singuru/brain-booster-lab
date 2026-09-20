package com.brainboosterlab.channel.content;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class ContentJobNotFoundException extends RuntimeException {

    public ContentJobNotFoundException(UUID id) {
        super("Content job not found: " + id);
    }
}
