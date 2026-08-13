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
        public Sprite thumbnail;
        public bool isDefault;
        public HairGroup hairGroup;
        [Tooltip("모자 UI ID. 헤어별 변형들이 같은 값을 공유한다.")]
        public int familyId;
        public HairGroup requiredHairGroup;
    }
}
