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

        [Tooltip("직원 복장(Outfit) 아이템 id. 0 이면 미적용.")]
        [SerializeField] int _outfitItemId;

        [Tooltip("직원 상의(Top) 아이템 id. 정장은 Outfit 이 아니라 Top 에 있다. 0 이면 미적용.")]
        [SerializeField] int _topItemId;

        [Tooltip("직원 하의(Bottom) 아이템 id. 0 이면 카탈로그 기본값.")]
        [SerializeField] int _bottomItemId;

        [Tooltip("직원을 비추는 필 라이트 세기(월드). 0 이면 만들지 않는다.")]
        [SerializeField] float _fillLightIntensity = 220f;

        [Tooltip("필 라이트 도달 거리(월드).")]
        [SerializeField] float _fillLightRange = 34f;

        [Tooltip("시각 높이(월드 유닛). 플레이어와 같은 값이라야 나란히 섰을 때 비례가 맞는다.")]
        [SerializeField] float _targetVisualHeight = 22.375f;

        [Tooltip("아이들 애니메이터. 없으면 바인드 포즈로 굳어 '화난 듯 뻣뻣하게' 서 있는다.")]
        [SerializeField] RuntimeAnimatorController _animatorController;

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
            if (_topItemId != 0) config.SetItem(Festa.Avatar.AvatarPartCategory.Top, _topItemId);
            if (_bottomItemId != 0) config.SetItem(Festa.Avatar.AvatarPartCategory.Bottom, _bottomItemId);

            _visual = new GameObject("StaffVisual");
            _visual.transform.SetParent(transform, false);
            var assembler = _visual.AddComponent<Festa.Avatar.AvatarAssembler>();
            assembler.Catalog = _catalog;
            assembler.Apply(config);
            if (!string.IsNullOrEmpty(assembler.LastError))
                Debug.LogError($"[BoothStaff] {name}: {assembler.LastError}");

            FitHeight();
            SetupAnimator();
            AddFillLight();
        }

        /// <summary>
        /// 직원 얼굴·상체를 비추는 작은 필 라이트.
        ///
        /// <para>부스 조명은 천장 쪽에서 내려오므로 사람 얼굴에는 그늘이 진다 — 야간 축제존에서
        /// 직원이 "잘 안 보인다" 는 보고의 원인이다 (S15P21A604-355). 앞·위에서 약하게 채운다.</para>
        ///
        /// <para>도달 거리를 작게 둔다. 렌더 경로가 Forward+ 라 광원 수 자체는 문제가 아니지만,
        /// 범위가 넓으면 클러스터마다 계산 대상이 늘어 비용만 커진다.</para>
        /// </summary>
        void AddFillLight()
        {
            if (_fillLightIntensity <= 0f) return;

            var go = new GameObject("StaffFillLight");
            go.transform.SetParent(transform, false);
            // 앞쪽 위 — 얼굴을 향한다
            go.transform.localPosition = new Vector3(0f, _targetVisualHeight * 0.95f, _targetVisualHeight * 0.32f);

            var light = go.AddComponent<Light>();
            light.type = LightType.Point;
            light.color = new Color(1f, 0.95f, 0.86f);
            light.intensity = _fillLightIntensity;
            light.range = _fillLightRange;
            light.shadows = LightShadows.None;   // 필 라이트가 그림자를 만들면 이중 그림자가 생긴다
        }

        /// <summary>
        /// 조립된 외형에 애니메이터를 물린다.
        ///
        /// <para>컨트롤러가 없으면 휴머노이드가 **바인드 포즈로 굳는다** — 팔을 벌린 채 경직된
        /// 자세라 "화난 것처럼 서 있다" 로 보인다 (S15P21A604-355 보고). 플레이어와 같은
        /// 컨트롤러를 그대로 쓴다: 아이들 상태가 이미 있고, 따로 만들면 또 갈라진다.</para>
        /// </summary>
        void SetupAnimator()
        {
            var animator = _visual.GetComponentInChildren<Animator>();
            if (animator == null)
            {
                Debug.LogWarning($"[BoothStaff] {name}: 조립 결과에 Animator 가 없다 — 포즈가 굳는다.");
                return;
            }

            // 직원은 제자리에 선다. 루트 모션이 켜져 있으면 클립이 NPC 를 부스 밖으로 끌고 나간다.
            animator.applyRootMotion = false;
            // **항상 애니메이션한다.** CullUpdateTransforms 를 쓰면 컬링 판정이 "안 보인다" 로
            // 나온 순간 본 갱신이 멈춰 **바인드 포즈로 굳는다** — 팔다리를 벌린 채 서 있어
            // "깡패처럼 서 있다" 는 보고를 받았다 (S15P21A604-355). 런타임 조립 직후에는
            // 스킨메시 바운즈가 실제 자세를 따라가지 못해 이 오판이 쉽게 난다.
            // 직원은 12명뿐이라 아끼는 비용보다 자세가 깨지는 손해가 크다.
            animator.cullingMode = AnimatorCullingMode.AlwaysAnimate;

            if (animator.runtimeAnimatorController == null && _animatorController != null)
                animator.runtimeAnimatorController = _animatorController;

            if (animator.runtimeAnimatorController == null)
                Debug.LogWarning($"[BoothStaff] {name}: Animator Controller 미할당 — 바인드 포즈로 굳는다.");
            else
                animator.Rebind();
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
