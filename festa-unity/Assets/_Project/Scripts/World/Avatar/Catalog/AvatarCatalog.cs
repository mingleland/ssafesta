using System;
using System.Collections.Generic;
using System.Linq;
using UnityEngine;

namespace Festa.Avatar
{
    [Serializable] public struct AvatarPaletteColor { public byte id; public Color color; }

    [CreateAssetMenu(menuName = "Festa/Avatar/Catalog")]
    public sealed class AvatarCatalog : ScriptableObject
    {
        public GameObject maleBody;
        public GameObject femaleBody;
        public RuntimeAnimatorController animatorController;
        public AvatarItemDefinition[] items = Array.Empty<AvatarItemDefinition>();
        public AvatarPaletteColor[] palette = Array.Empty<AvatarPaletteColor>();

        public IEnumerable<AvatarItemDefinition> GetItems(AvatarPartCategory category, AvatarGender gender) =>
            items.Where(x => x && x.category == category && (x.gender == AvatarGender.Both || x.gender == gender));
        public AvatarItemDefinition Get(int id) => id == 0 ? null : items.FirstOrDefault(x => x && x.itemId == id);
        public Color GetColor(byte id) => palette.FirstOrDefault(x => x.id == id).color;
        public AvatarItemDefinition Default(AvatarPartCategory category, AvatarGender gender) =>
            GetItems(category, gender).FirstOrDefault(x => x.isDefault) ?? GetItems(category, gender).FirstOrDefault();
        public AvatarItemDefinition ResolveHat(int familyId, HairGroup group) => items.FirstOrDefault(x => x && x.category == AvatarPartCategory.Hat && x.familyId == familyId && (x.requiredHairGroup == group || x.requiredHairGroup == HairGroup.None));

        public AvatarConfig CreateDefault(AvatarGender gender)
        {
            var c = new AvatarConfig { gender = gender, skinColorId = 1, hairColorId = 6, irisColorId = 8, eyebrowColorId = 6, lipsColorId = 10, topColorId = 12, bottomColorId = 15 };
            foreach (var category in new[] { AvatarPartCategory.Head, AvatarPartCategory.Hair, AvatarPartCategory.Top, AvatarPartCategory.Bottom, AvatarPartCategory.Shoes })
                c.SetItem(category, Default(category, gender)?.itemId ?? 0);
            return c;
        }
    }
}
