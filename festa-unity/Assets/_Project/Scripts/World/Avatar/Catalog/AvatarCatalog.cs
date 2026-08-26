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
        public AvatarItemDefinition ResolveHat(int familyId, HairGroup group)
        {
            var variants = items.Where(x => x && x.category == AvatarPartCategory.Hat && x.familyId == familyId).ToArray();
            return variants.FirstOrDefault(x => x.requiredHairGroup == group)
                ?? variants.FirstOrDefault(x => x.requiredHairGroup == HairGroup.None)
                ?? variants.FirstOrDefault();
        }

        public AvatarConfig CreateDefault(AvatarGender gender)
        {
            var c = new AvatarConfig { gender = gender, skinColorId = 1, hairColorId = 6, irisColorId = 8, eyebrowColorId = 6, lipsColorId = 10, topColorId = 12, bottomColorId = 15, scleraColorId = 16, pupilColorId = 6, garmentColorVersion = 1 };
            c.SetItem(AvatarPartCategory.Head,Default(AvatarPartCategory.Head,gender)?.itemId??0);
            var hair=GetItems(AvatarPartCategory.Hair,gender).FirstOrDefault(x=>x.hairGroup==HairGroup.Long)??Default(AvatarPartCategory.Hair,gender);
            c.SetItem(AvatarPartCategory.Hair,hair?hair.itemId:0);
            var top=Default(AvatarPartCategory.Top,gender);
            var bottom=Default(AvatarPartCategory.Bottom,gender);
            c.SetItem(AvatarPartCategory.Top,top?top.itemId:0);
            c.SetItem(AvatarPartCategory.Bottom,bottom?bottom.itemId:0);
            c.SetItem(AvatarPartCategory.Outfit,0);
            c.SetItem(AvatarPartCategory.Shoes,Default(AvatarPartCategory.Shoes,gender)?.itemId??0);
            return c;
        }
    }
}
