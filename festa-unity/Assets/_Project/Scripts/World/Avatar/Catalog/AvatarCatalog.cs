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

        // ── 런타임 재질 템플릿 (T-213) ──────────────────────────────
        // 조립기는 헤어·액세서리 재질을 런타임에 만든다. 이전에는 Shader.Find("URP/Lit") 로
        // 만들었는데, 빌드 셰이더 스트리핑이 그 셰이더를 잘라내면 재질이 "존재하지만
        // 그려지지 않는" 상태가 된다 — 에디터에서는 전 셰이더가 살아 있어 절대 재현되지 않는다.
        // 에셋으로 참조된 재질은 그 키워드 상태의 배리언트가 빌드에 반드시 포함되므로,
        // 템플릿을 복제하는 방식으로 바꿨다. 비어 있으면 Shader.Find 로 폴백한다(에디터 안전망).
        [Tooltip("URP Lit 불투명 — 노멀맵 없는 헤어·액세서리용")]
        public Material litOpaqueTemplate;
        [Tooltip("URP Lit 불투명 + _NORMALMAP — 노멀맵 있는 헤어·액세서리용")]
        public Material litOpaqueNormalTemplate;
        public AvatarItemDefinition[] items = Array.Empty<AvatarItemDefinition>();
        public AvatarPaletteColor[] palette = Array.Empty<AvatarPaletteColor>();

        public IEnumerable<AvatarItemDefinition> GetItems(AvatarPartCategory category, AvatarGender gender) =>
            items.Where(x => x && x.category == category && (x.gender == AvatarGender.Both || x.gender == gender));
        /// <summary>
        /// 지금 착용할 수 있는 것만 — 기본 제공이거나 소유한 항목이다 (S15P21A604-355).
        ///
        /// <para>목록 UI 는 <see cref="GetItems"/> 로 <b>잠긴 것까지</b> 받아 자물쇠를 붙여
        /// 보여준다. 무엇을 얻을 수 있는지 보이지 않으면 잠금이 의미가 없기 때문이다.
        /// 반대로 기본 아바타·무작위처럼 <b>대신 골라 주는</b> 자리에서는 이 목록을 써야 한다 —
        /// 잠긴 옷을 입혀 놓고 저장하면 서버 검증에서 막힌다.</para>
        /// </summary>
        public IEnumerable<AvatarItemDefinition> GetUnlockedItems(AvatarPartCategory category, AvatarGender gender) =>
            GetItems(category, gender).Where(AvatarOwnership.IsUnlocked);

        /// <summary>
        /// 목록 UI 에 그릴 항목 — <b>서버 카탈로그에 있는 것만</b> (GitLab #120 §2-1).
        ///
        /// <para>응답에 없는 파츠는 서버 시드 미등록이라, 그려 두면 고를 수는 있어도 저장이
        /// <c>409 AVATAR_ITEM_NOT_OWNED</c> 로 거부된다. 고를 수 있는데 저장이 안 되는 것이
        /// 애초에 안 보이는 것보다 나쁘다. 보유 정보를 받기 전에는 숨기지 않는다 —
        /// 무엇이 등록됐는지 모르는 상태에서 감추면 목록이 통째로 비어 보인다.</para>
        /// </summary>
        public IEnumerable<AvatarItemDefinition> GetCatalogedItems(AvatarPartCategory category, AvatarGender gender) =>
            GetItems(category, gender).Where(AvatarOwnership.IsInCatalog);

        /// <summary>
        /// <b>대신 골라 줄 때</b> 쓰는 후보 — 기본 아바타·무작위가 이것을 쓴다.
        ///
        /// <para>판정이 준비되면 해제된 것만, 아직이면 전체다
        /// (<see cref="AvatarOwnership.JudgementReady"/> 주석 참조). 잠금으로 후보를 좁히는
        /// 것은 판정을 신뢰할 수 있을 때만 의미가 있고, 그 전에 좁히면 후보가 0개가 되어
        /// 알몸이 된다.</para>
        /// </summary>
        public IEnumerable<AvatarItemDefinition> GetSelectableItems(AvatarPartCategory category, AvatarGender gender) =>
            AvatarOwnership.JudgementReady ? GetUnlockedItems(category, gender) : GetItems(category, gender);

        public AvatarItemDefinition Get(int id) => id == 0 ? null : items.FirstOrDefault(x => x && x.itemId == id);
        public Color GetColor(byte id) => palette.FirstOrDefault(x => x.id == id).color;

        /// <summary>
        /// 기본으로 입힐 항목. <b>잠긴 것을 고르지 않는다.</b>
        ///
        /// <para>해제된 것이 하나도 없으면 잠긴 것 중에서라도 고른다 — 아무것도 못 입혀
        /// 알몸으로 두는 것보다는 낫고, 그 상태는 데이터가 잘못됐다는 뜻이라 로그로 드러낸다.</para>
        /// </summary>
        public AvatarItemDefinition Default(AvatarPartCategory category, AvatarGender gender)
        {
            var unlocked = GetSelectableItems(category, gender).ToArray();
            if (unlocked.Length > 0)
                return unlocked.FirstOrDefault(x => x.isDefault) ?? unlocked[0];

            var any = GetItems(category, gender).ToArray();
            if (any.Length == 0) return null;
            UnityEngine.Debug.LogWarning(
                $"[AvatarCatalog] {category}/{gender} 에 기본 제공 항목이 하나도 없다 — 잠긴 항목으로 대체한다.");
            return any.FirstOrDefault(x => x.isDefault) ?? any[0];
        }
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
            var hair=GetSelectableItems(AvatarPartCategory.Hair,gender).FirstOrDefault(x=>x.hairGroup==HairGroup.Long)??Default(AvatarPartCategory.Hair,gender);
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
