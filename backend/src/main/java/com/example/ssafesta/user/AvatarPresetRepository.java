package com.example.ssafesta.user;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Loads only the current member's reusable avatar appearances, in stable UI order. */
public interface AvatarPresetRepository extends JpaRepository<AvatarPreset, AvatarPresetKey> {
    List<AvatarPreset> findAllByKeyUserIdOrderByKeySlotAsc(Long userId);
}
