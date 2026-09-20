package com.brainboosterlab.channel.content;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentJobRepository extends JpaRepository<ContentJob, UUID> {

    List<ContentJob> findAllByOrderByCreatedAtDesc();
}
