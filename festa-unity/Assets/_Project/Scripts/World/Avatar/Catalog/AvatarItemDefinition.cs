using System;
using UnityEngine;

namespace Festa.Avatar
{
    [CreateAssetMenu(menuName = "Festa/Avatar/Item")]
    public sealed class AvatarItemDefinition : ScriptableObject
    {
        public int itemId;
        public string displayName;
        public AvatarPartCategory category;
        public AvatarGender gender;
        public SkinnedMeshRenderer[] meshes = Array.Empty<SkinnedMeshRenderer>();
        public GameObject[] objectPrefabs = Array.Empty<GameObject>();
        public HumanBodyBones[] targetBones = Array.Empty<HumanBodyBones>();
        public int[] hiddenBodyParts = Array.Empty<int>();
        [Tooltip("다른 착용 파츠의 숨김 설정보다 우선해 표시할 신체 파트")]
        public int[] forcedVisibleBodyParts = Array.Empty<int>();
        [Tooltip("이 아이템에서 화면 변화가 확인된 의상 색상 영역 비트(A=1, B=2, C=4)")]
        public byte garmentColorAreaMask = 0x7;
        public Sprite thumbnail;

        /// <summary>
        /// 카테고리의 대표 항목 — 기본 아바타에서 우선 고른다.
        ///
        /// <para><b>잠금 여부와는 무관하다.</b> 잠금은 서버 <c>owned</c> 가 정한다
        /// (<see cref="AvatarOwnership"/>). 다만 BE 가 무료 파츠 시드를 이 값으로 뽑았으므로
        /// (GitLab #120 §2-1 의 "Unity <c>isDefault</c> 12종"), 이 필드를 바꾸면 무료 세트와
        /// 어긋난다 — 바꿀 때는 BE 에 알려야 한다.</para>
        /// </summary>
        public bool isDefault;

        public HairGroup hairGroup;
        [Tooltip("모자 UI ID. 헤어별 변형들이 같은 값을 공유한다.")]
        public int familyId;
        public HairGroup requiredHairGroup;
    }
}
