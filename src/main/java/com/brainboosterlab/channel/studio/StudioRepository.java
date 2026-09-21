package com.brainboosterlab.channel.studio;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
interface StudioRepository extends JpaRepository<StudioEpisode, UUID> {
    List<StudioEpisode> findAllByOrderByCreatedAtDesc();
}
