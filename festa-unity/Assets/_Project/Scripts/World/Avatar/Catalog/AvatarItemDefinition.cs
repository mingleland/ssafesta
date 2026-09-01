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

        /// <summary>
        /// 기본 제공 여부. <b>false 면 잠긴 항목</b>이라 소유 목록에 없으면 착용할 수 없다.
        ///
        /// <para>소유 정보의 정본은 서버다 — 어떤 아이템을 가졌는지는 백엔드가 내려주고
        /// Unity 는 읽기만 한다(헌법 16조). 이 필드는 <b>서버 응답이 없어도 누구나 쓸 수 있는
        /// 최소 세트</b>를 표시하는 용도다. 계약이 정해지기 전까지는 소유 목록이 비어 있고,
        /// 그때는 이 필드가 곧 사용 가능 여부가 된다.</para>
        ///
        /// <para>기본값이 false 인 것은 의도적이다 — 새 아이템을 추가하면 잠긴 상태로 들어와,
        /// 해제를 깜빡해서 유료 항목이 공짜로 풀리는 방향으로는 틀리지 않는다.</para>
        /// </summary>
        [Tooltip("기본 제공(잠기지 않음). 체크하지 않으면 소유해야 착용할 수 있다.")]
        public bool isDefaultUnlocked;
        public HairGroup hairGroup;
        [Tooltip("모자 UI ID. 헤어별 변형들이 같은 값을 공유한다.")]
        public int familyId;
        public HairGroup requiredHairGroup;
    }
}
