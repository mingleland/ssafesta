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
        public bool isDefault;
        public HairGroup hairGroup;
        [Tooltip("모자 UI ID. 헤어별 변형들이 같은 값을 공유한다.")]
        public int familyId;
        public HairGroup requiredHairGroup;
    }
}
