using System;
using UnityEngine;

namespace Festa.Avatar
{
    public enum AvatarGender : byte { Male, Female, Both }
    public enum AvatarPartCategory : byte { Head, Hair, Hat, Glasses, Top, Bottom, Outfit, Shoes }
    public enum AvatarColorSlot : byte { Skin, Hair, Iris, Eyebrow, Lips, Top, Bottom }
    public enum HairGroup : byte { None, Buzzcut, Short, Medium, Long, Tied }

    [Serializable]
    public struct AvatarConfig
    {
        public AvatarGender gender;
        public int headId, hairId, hatId, glassesId, topId, bottomId, outfitId, shoesId;
        public byte skinColorId, hairColorId, irisColorId, eyebrowColorId, lipsColorId, topColorId, bottomColorId;

        public int GetItem(AvatarPartCategory category) => category switch
        {
            AvatarPartCategory.Head => headId, AvatarPartCategory.Hair => hairId,
            AvatarPartCategory.Hat => hatId, AvatarPartCategory.Glasses => glassesId,
            AvatarPartCategory.Top => topId, AvatarPartCategory.Bottom => bottomId,
            AvatarPartCategory.Outfit => outfitId, AvatarPartCategory.Shoes => shoesId, _ => 0
        };

        public void SetItem(AvatarPartCategory category, int value)
        {
            switch (category)
            {
                case AvatarPartCategory.Head: headId = value; break; case AvatarPartCategory.Hair: hairId = value; break;
                case AvatarPartCategory.Hat: hatId = value; break; case AvatarPartCategory.Glasses: glassesId = value; break;
                case AvatarPartCategory.Top: topId = value; outfitId = 0; break;
                case AvatarPartCategory.Bottom: bottomId = value; outfitId = 0; break;
                case AvatarPartCategory.Outfit: outfitId = value; if (value != 0) { topId = 0; bottomId = 0; } break;
                case AvatarPartCategory.Shoes: shoesId = value; break;
            }
        }

        public byte GetColor(AvatarColorSlot slot) => slot switch
        {
            AvatarColorSlot.Skin => skinColorId, AvatarColorSlot.Hair => hairColorId,
            AvatarColorSlot.Iris => irisColorId, AvatarColorSlot.Eyebrow => eyebrowColorId,
            AvatarColorSlot.Lips => lipsColorId, AvatarColorSlot.Top => topColorId,
            AvatarColorSlot.Bottom => bottomColorId, _ => 0
        };

        public void SetColor(AvatarColorSlot slot, byte value)
        {
            switch (slot)
            {
                case AvatarColorSlot.Skin: skinColorId = value; break; case AvatarColorSlot.Hair: hairColorId = value; break;
                case AvatarColorSlot.Iris: irisColorId = value; break; case AvatarColorSlot.Eyebrow: eyebrowColorId = value; break;
                case AvatarColorSlot.Lips: lipsColorId = value; break; case AvatarColorSlot.Top: topColorId = value; break;
                case AvatarColorSlot.Bottom: bottomColorId = value; break;
            }
        }
    }
}
