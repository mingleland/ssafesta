package com.example.ssafesta.user;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/** Stores one reusable full avatar appearance for a member and a numbered preset slot. */
@Entity
@Table(name = "avatar_presets")
public class AvatarPreset {

    @EmbeddedId
    private AvatarPresetKey key;

    @Column(name = "avatar_code", nullable = false, columnDefinition = "text")
    private String avatarCode;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AvatarPreset() {
    }

    public AvatarPreset(Long userId, int slot, String avatarCode) {
        this.key = new AvatarPresetKey(userId, slot);
        this.avatarCode = avatarCode;
        this.updatedAt = Instant.now();
    }

    public int getSlot() {
        return key.getSlot();
    }

    public String getAvatarCode() {
        return avatarCode;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Replaces the opaque encoding verbatim, just like the account's current appearance. */
    public void replaceAvatarCode(String avatarCode) {
        this.avatarCode = avatarCode;
        this.updatedAt = Instant.now();
    }
}
