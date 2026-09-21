package com.brainboosterlab.channel.studio;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v2/episodes")
class StudioController {
    record Brief(@NotBlank @Size(max = 4000) String brief) {}
    record RestyleOptions(List<SceneOverlay.Region> clueRegions) {}
    private final StudioService studio;
    StudioController(StudioService studio) { this.studio = studio; }
    @GetMapping List<StudioService.View> list() { return studio.list(); }
    @GetMapping("/{id}") StudioService.View get(@PathVariable UUID id) { return studio.get(id); }
    // JSON-only mutations reject cross-site HTML form submissions; no CORS access is granted.
    @PostMapping(consumes = "application/json") StudioService.View create(@Valid @RequestBody Brief brief) { return studio.create(brief.brief()); }
    @PostMapping(value = "/{id}/generate", consumes = "application/json") StudioService.View generate(@PathVariable UUID id) { return studio.generate(id); }
    @PostMapping(value = "/{id}/review", consumes = "application/json") StudioService.View review(@PathVariable UUID id) { return studio.review(id); }
    @PostMapping(value = "/{id}/narration", consumes = "application/json") StudioService.View narration(@PathVariable UUID id) { return studio.narration(id); }
    @PostMapping(value = "/{id}/ground-narration", consumes = "application/json") StudioService.View groundNarration(@PathVariable UUID id) { return studio.groundNarration(id); }
    @PostMapping(value = "/{id}/speech", consumes = "application/json") StudioService.View speech(@PathVariable UUID id) { return studio.speech(id); }
    @PostMapping(value = "/{id}/revise", consumes = "application/json") StudioService.View revise(@PathVariable UUID id, @RequestBody EpisodeSpec spec) { return studio.revise(id, spec); }
    @PostMapping(value = "/{id}/restyle", consumes = "application/json") StudioService.View restyle(@PathVariable UUID id,
        @RequestBody(required = false) RestyleOptions options) { return studio.restyle(id, options == null ? null : options.clueRegions()); }
    @PostMapping(value = "/{id}/artwork", consumes = "application/json") StudioService.View artwork(@PathVariable UUID id) { return studio.artwork(id); }
    @PostMapping(value = "/{id}/approve", consumes = "application/json") StudioService.View approve(@PathVariable UUID id) { return studio.approve(id); }
    @PostMapping(value = "/{id}/preview", consumes = "application/json") StudioService.View preview(@PathVariable UUID id) { return studio.render(id, true); }
    @PostMapping(value = "/{id}/render", consumes = "application/json") StudioService.View render(@PathVariable UUID id) { return studio.render(id, false); }
    @GetMapping("/{id}/media/{name}")
    ResponseEntity<FileSystemResource> media(@PathVariable UUID id, @PathVariable String name) {
        var file = studio.media(id, name);
        String type = name.endsWith(".mp4") ? "video/mp4" : name.endsWith(".m4a") ? "audio/mp4" : name.endsWith(".png") ? "image/png" : name.endsWith(".txt") ? "text/plain;charset=UTF-8" : "application/json";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(type))
            .header("X-Content-Type-Options", "nosniff").body(new FileSystemResource(file));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
    }
}
