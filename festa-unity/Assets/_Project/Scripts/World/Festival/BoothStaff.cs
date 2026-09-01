using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 축제 부스 앞에 서 있는 **직원 NPC**. 부스 입장은 이제 부스 구조물이 아니라
    /// 이 직원에게 말을 걸어야 한다 (S15P21A604-355).
    ///
    /// <para><b>왜 직원인가.</b> 전에는 부스 구조물 전체가 판정 대상이라 어디에 서든 입장이 됐고,
    /// "부스에 들어간다" 는 행동에 상대가 없었다. 사람이 서 있으면 어디로 가야 하는지가
    /// 눈에 보이고, 엑스포에서 부스를 방문하는 실제 동작과도 맞는다.</para>
    ///
    /// <para><b>플레이어와 같은 아바타 파이프라인을 쓴다.</b> 별도 NPC 모델을 들이면 스케일·
    /// 셰이더·머티리얼이 또 갈라진다 — 오늘 세계 척도가 둘로 갈려 겪은 문제와 같은 종류다
    /// (T-235). 카탈로그로 조립하고 플레이어와 같은 목표 키로 맞춘다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class BoothStaff : MonoBehaviour
    {
        [Tooltip("아바타 조립에 쓸 모듈 카탈로그")]
        [SerializeField] Festa.Avatar.AvatarCatalog _catalog;

        [Tooltip("직원 복장(Outfit) 아이템 id. 0 이면 카탈로그 기본값.")]
        [SerializeField] int _outfitItemId;

        [Tooltip("시각 높이(월드 유닛). 플레이어와 같은 값이라야 나란히 섰을 때 비례가 맞는다.")]
        [SerializeField] float _targetVisualHeight = 22.375f;

        GameObject _visual;

        void Start() => Build();

        void Build()
        {
            if (_catalog == null)
            {
                // 조용히 빈 오브젝트로 남기지 않는다 — 직원이 없으면 부스에 들어갈 방법이 사라진다.
                Debug.LogError($"[BoothStaff] {name}: AvatarCatalog 미할당 — 직원을 세우지 못한다.");
                return;
            }

            var config = _catalog.CreateDefault(Festa.Avatar.AvatarGender.Male);
            if (_outfitItemId != 0) config.SetItem(Festa.Avatar.AvatarPartCategory.Outfit, _outfitItemId);

            _visual = new GameObject("StaffVisual");
            _visual.transform.SetParent(transform, false);
            var assembler = _visual.AddComponent<Festa.Avatar.AvatarAssembler>();
            assembler.Catalog = _catalog;
            assembler.Apply(config);
            if (!string.IsNullOrEmpty(assembler.LastError))
                Debug.LogError($"[BoothStaff] {name}: {assembler.LastError}");

            FitHeight();
        }

        /// <summary>
        /// 조립 결과의 실제 높이를 재서 목표 키로 스케일한다. 상수 배율을 박으면 카탈로그
        /// 아이템이 바뀔 때 조용히 어긋난다 — 플레이어 쪽과 같은 방식이다.
        /// </summary>
        void FitHeight()
        {
            var renderers = _visual.GetComponentsInChildren<SkinnedMeshRenderer>(true);
            if (renderers.Length == 0 || _targetVisualHeight <= 0f) return;

            var bounds = renderers[0].bounds;
            for (int i = 1; i < renderers.Length; i++) bounds.Encapsulate(renderers[i].bounds);
            if (bounds.size.y < 0.001f) return;

            _visual.transform.localScale *= _targetVisualHeight / bounds.size.y;

            // 발을 루트 높이에 맞춘다 — 스케일 뒤에 다시 재야 한다(스케일 전 값으로 맞추면 뜬다).
            renderers = _visual.GetComponentsInChildren<SkinnedMeshRenderer>(true);
            bounds = renderers[0].bounds;
            for (int i = 1; i < renderers.Length; i++) bounds.Encapsulate(renderers[i].bounds);
            _visual.transform.position += Vector3.up * (transform.position.y - bounds.min.y);
        }
    }
}
