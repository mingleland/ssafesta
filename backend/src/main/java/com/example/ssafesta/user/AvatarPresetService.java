package com.example.ssafesta.user;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.inventory.InventoryService;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns validation and persistence of the three member-scoped avatar preset slots. */
@Service
public class AvatarPresetService {

    static final int MIN_SLOT = 1;
    static final int MAX_SLOT = 3;

    private final AvatarPresetRepository presets;
    private final AvatarCodePolicy avatarCodePolicy;
    private final InventoryService inventory;

    public AvatarPresetService(AvatarPresetRepository presets, AvatarCodePolicy avatarCodePolicy,
                               InventoryService inventory) {
        this.presets = presets;
        this.avatarCodePolicy = avatarCodePolicy;
        this.inventory = inventory;
    }

    @Transactional(readOnly = true)
    public List<AvatarPreset> list(Long userId) {
        return presets.findAllByKeyUserIdOrderByKeySlotAsc(userId);
    }

    @Transactional
    public AvatarPreset save(Long userId, int slot, String avatarCode) {
        validateSlot(slot);
        avatarCodePolicy.validate(avatarCode);
        inventory.requireOwned(userId, AvatarWornItems.parse(avatarCode));

        AvatarPresetKey key = new AvatarPresetKey(userId, slot);
        AvatarPreset preset = presets.findById(key).orElseGet(() -> new AvatarPreset(userId, slot, avatarCode));
        if (!Objects.equals(preset.getAvatarCode(), avatarCode)) {
            preset.replaceAvatarCode(avatarCode);
        }
        return presets.save(preset);
    }

    @Transactional
    public void delete(Long userId, int slot) {
        validateSlot(slot);
        presets.deleteById(new AvatarPresetKey(userId, slot));
    }

    private static void validateSlot(int slot) {
        if (slot < MIN_SLOT || slot > MAX_SLOT) {
            throw ApiException.fieldInvalid("slot", "프리셋 슬롯은 1부터 3 사이여야 합니다.");
        }
    }
}
