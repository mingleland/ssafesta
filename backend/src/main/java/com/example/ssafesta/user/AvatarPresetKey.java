package com.example.ssafesta.user;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** Composite identity for one member-owned avatar preset slot. */
@Embeddable
public class AvatarPresetKey implements Serializable {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "slot", nullable = false)
    private short slot;

    protected AvatarPresetKey() {
    }

    public AvatarPresetKey(Long userId, int slot) {
        this.userId = userId;
        this.slot = (short) slot;
    }

    public Long getUserId() {
        return userId;
    }

    public short getSlot() {
        return slot;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AvatarPresetKey that)) {
            return false;
        }
        return slot == that.slot && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, slot);
    }
}
