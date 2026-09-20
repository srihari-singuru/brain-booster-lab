package com.brainboosterlab.channel;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class ProjectStatusController {

    @GetMapping("/status")
    Map<String, String> status() {
        return Map.of(
                "service", "brain-booster-lab",
                "state", "ready",
                "architecture", "local-first"
        );
    }
}
