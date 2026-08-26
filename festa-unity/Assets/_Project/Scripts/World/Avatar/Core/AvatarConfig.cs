using System;
using UnityEngine;

namespace Festa.Avatar
{
    public enum AvatarGender : byte { Male, Female, Both }
    public enum AvatarPartCategory : byte { Head, Hair, Hat, Glasses, Top, Bottom, Outfit, Shoes }
    // Keep existing numeric values stable for network/backward compatibility.
    public enum AvatarColorSlot : byte { Skin, Hair, Iris, Eyebrow, Lips, Top, Bottom, Sclera, Pupil }
    public enum AvatarGarmentColorSlot : byte { A1, A2, B1, B2, C1, C2 }
    public enum HairGroup : byte { None, Buzzcut, Short, Medium, Long, Tied }

    [Serializable]
    public struct AvatarGarmentColors
    {
        public Color32 a1, a2, b1, b2, c1, c2;

        public Color32 Get(AvatarGarmentColorSlot slot) => slot switch
        {
            AvatarGarmentColorSlot.A1 => a1, AvatarGarmentColorSlot.A2 => a2,
            AvatarGarmentColorSlot.B1 => b1, AvatarGarmentColorSlot.B2 => b2,
            AvatarGarmentColorSlot.C1 => c1, AvatarGarmentColorSlot.C2 => c2, _ => default
        };

        public void Set(AvatarGarmentColorSlot slot, Color32 value)
        {
            switch (slot)
            {
                case AvatarGarmentColorSlot.A1: a1 = value; break; case AvatarGarmentColorSlot.A2: a2 = value; break;
                case AvatarGarmentColorSlot.B1: b1 = value; break; case AvatarGarmentColorSlot.B2: b2 = value; break;
                case AvatarGarmentColorSlot.C1: c1 = value; break; case AvatarGarmentColorSlot.C2: c2 = value; break;
            }
        }
    }

    [Serializable]
    public struct AvatarConfig
    {
        public AvatarGender gender;
        public int headId, hairId, hatId, glassesId, topId, bottomId, outfitId, shoesId;
        public byte skinColorId, hairColorId, irisColorId, eyebrowColorId, lipsColorId, topColorId, bottomColorId, scleraColorId, pupilColorId;
        public byte garmentColorVersion;
        public Color32 skinColor, hairColor, irisColor, eyebrowColor, lipsColor, topColor, bottomColor, scleraColor, pupilColor;
        public AvatarGarmentColors topGarmentColors, bottomGarmentColors, outfitGarmentColors, shoesGarmentColors, hatGarmentColors, glassesGarmentColors;

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
            AvatarColorSlot.Bottom => bottomColorId, AvatarColorSlot.Sclera => scleraColorId,
            AvatarColorSlot.Pupil => pupilColorId, _ => 0
        };

        public void SetColor(AvatarColorSlot slot, byte value)
        {
            switch (slot)
            {
                case AvatarColorSlot.Skin: skinColorId = value; break; case AvatarColorSlot.Hair: hairColorId = value; break;
                case AvatarColorSlot.Iris: irisColorId = value; break; case AvatarColorSlot.Eyebrow: eyebrowColorId = value; break;
                case AvatarColorSlot.Lips: lipsColorId = value; break; case AvatarColorSlot.Top: topColorId = value; break;
                case AvatarColorSlot.Bottom: bottomColorId = value; break; case AvatarColorSlot.Sclera: scleraColorId = value; break;
                case AvatarColorSlot.Pupil: pupilColorId = value; break;
            }
        }

        public Color GetColor(AvatarColorSlot slot, AvatarCatalog catalog)
        {
            Color32 precise = slot switch
            {
                AvatarColorSlot.Skin => skinColor, AvatarColorSlot.Hair => hairColor,
                AvatarColorSlot.Iris => irisColor, AvatarColorSlot.Eyebrow => eyebrowColor,
                AvatarColorSlot.Lips => lipsColor, AvatarColorSlot.Top => topColor,
                AvatarColorSlot.Bottom => bottomColor, AvatarColorSlot.Sclera => scleraColor,
                AvatarColorSlot.Pupil => pupilColor, _ => default
            };
            if (precise.a > 0) return precise;
            if (slot == AvatarColorSlot.Sclera && scleraColorId == 0) return Color.white;
            if (slot == AvatarColorSlot.Pupil && pupilColorId == 0) return new Color32(20, 16, 18, 255);

            // A network payload can be restored before its palette asset is
            // available (or can contain an older palette id). Never hand the
            // transparent default Color to a runtime avatar shader: it renders
            // the entire corresponding mesh as a black silhouette in WebGL.
            var paletteColor = catalog != null ? catalog.GetColor(GetColor(slot)) : default;
            return paletteColor.a > 0 ? paletteColor : DefaultColor(slot);
        }

        static Color DefaultColor(AvatarColorSlot slot) => slot switch
        {
            AvatarColorSlot.Skin => new Color32(255, 204, 176, 255),
            AvatarColorSlot.Hair => new Color32(48, 32, 28, 255),
            AvatarColorSlot.Iris => new Color32(45, 92, 145, 255),
            AvatarColorSlot.Eyebrow => new Color32(52, 34, 28, 255),
            AvatarColorSlot.Lips => new Color32(170, 88, 102, 255),
            AvatarColorSlot.Top => new Color32(35, 56, 98, 255),
            AvatarColorSlot.Bottom => new Color32(48, 50, 58, 255),
            AvatarColorSlot.Sclera => Color.white,
            AvatarColorSlot.Pupil => new Color32(20, 16, 18, 255),
            _ => Color.white
        };

        public void SetColor(AvatarColorSlot slot, Color value)
        {
            var precise = (Color32)value; precise.a = 255;
            switch (slot)
            {
                case AvatarColorSlot.Skin: skinColor = precise; break; case AvatarColorSlot.Hair: hairColor = precise; break;
                case AvatarColorSlot.Iris: irisColor = precise; break; case AvatarColorSlot.Eyebrow: eyebrowColor = precise; break;
                case AvatarColorSlot.Lips: lipsColor = precise; break; case AvatarColorSlot.Top: topColor = precise; break;
                case AvatarColorSlot.Bottom: bottomColor = precise; break; case AvatarColorSlot.Sclera: scleraColor = precise; break;
                case AvatarColorSlot.Pupil: pupilColor = precise; break;
            }
        }

        public Color32 GetGarmentColor(AvatarPartCategory category, AvatarGarmentColorSlot slot) => category switch
        {
            AvatarPartCategory.Top => topGarmentColors.Get(slot),
            AvatarPartCategory.Bottom => bottomGarmentColors.Get(slot),
            AvatarPartCategory.Outfit => outfitGarmentColors.Get(slot),
            AvatarPartCategory.Shoes => shoesGarmentColors.Get(slot),
            AvatarPartCategory.Hat => hatGarmentColors.Get(slot),
            AvatarPartCategory.Glasses => glassesGarmentColors.Get(slot),
            _ => default
        };

        public void SetGarmentColor(AvatarPartCategory category, AvatarGarmentColorSlot slot, Color value)
        {
            var precise = (Color32)value; precise.a = 255;
            switch (category)
            {
                case AvatarPartCategory.Top: topGarmentColors.Set(slot, precise); break;
                case AvatarPartCategory.Bottom: bottomGarmentColors.Set(slot, precise); break;
                case AvatarPartCategory.Outfit: outfitGarmentColors.Set(slot, precise); break;
                case AvatarPartCategory.Shoes: shoesGarmentColors.Set(slot, precise); break;
                case AvatarPartCategory.Hat: hatGarmentColors.Set(slot, precise); break;
                case AvatarPartCategory.Glasses: glassesGarmentColors.Set(slot, precise); break;
            }
        }

        public Color32 GetGarmentColor(int index)
        {
            var category = index < 6 ? AvatarPartCategory.Top : index < 12 ? AvatarPartCategory.Bottom : index < 18 ? AvatarPartCategory.Outfit : index < 24 ? AvatarPartCategory.Shoes : index < 30 ? AvatarPartCategory.Hat : AvatarPartCategory.Glasses;
            return GetGarmentColor(category, (AvatarGarmentColorSlot)(index % 6));
        }

        public void SetGarmentColor(int index, Color32 value)
        {
            var category = index < 6 ? AvatarPartCategory.Top : index < 12 ? AvatarPartCategory.Bottom : index < 18 ? AvatarPartCategory.Outfit : index < 24 ? AvatarPartCategory.Shoes : index < 30 ? AvatarPartCategory.Hat : AvatarPartCategory.Glasses;
            var slot = (AvatarGarmentColorSlot)(index % 6);
            switch (category)
            {
                case AvatarPartCategory.Top: topGarmentColors.Set(slot, value); break;
                case AvatarPartCategory.Bottom: bottomGarmentColors.Set(slot, value); break;
                case AvatarPartCategory.Outfit: outfitGarmentColors.Set(slot, value); break;
                case AvatarPartCategory.Shoes: shoesGarmentColors.Set(slot, value); break;
                case AvatarPartCategory.Hat: hatGarmentColors.Set(slot, value); break;
                case AvatarPartCategory.Glasses: glassesGarmentColors.Set(slot, value); break;
            }
        }
    }
}
