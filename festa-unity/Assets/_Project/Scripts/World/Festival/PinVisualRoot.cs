using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 자식 시각물의 루트를 매 프레임 제자리에 못 박는다 (S15P21A604-355).
    ///
    /// <para><b>왜 필요한가.</b> 카지노 팩 캐릭터 5종에 같은 대기 클립을 물렸는데, 그중
    /// 3_Actor(링마스터)만 플레이 모드에서 47~52 유닛 튕겨 나갔다. 클립에 루트 이동 커브가
    /// 있고(<c>hasRootCurves</c>), 리그마다 아바타 정의가 달라 같은 커브가 다른 루트 이동으로
    /// 풀린 것이다. <c>applyRootMotion=false</c> 로도 막히지 않았다 — 휴머노이드는 루트 이동을
    /// 클립 임포트 설정("Bake Into Pose")으로 정하는데 그건 벤더 에셋 쪽이라 건드리지 않는다.</para>
    ///
    /// <para><b>에디터와 런타임이 달라 보였던 원인이 이것이다.</b> 에디터에서는 애니메이터가 돌지
    /// 않아 제자리, 플레이하면 클립이 루트를 밀어낸다. 그래서 같은 씬을 두 상태로 보면서 여러
    /// 번 헛고쳤다. 리그가 무엇이든 무조건 원점으로 되돌리면 이 차이가 사라진다.</para>
    ///
    /// <para>Animator 가 LateUpdate 전에 포즈를 쓰므로 LateUpdate 에서 되돌려야 이긴다.
    /// 비용은 트랜스폼 두 개 대입뿐이다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class PinVisualRoot : MonoBehaviour
    {
        [Tooltip("고정할 시각물 루트. 비우면 첫 자식(Animator 를 가진 오브젝트)을 쓴다.")]
        [SerializeField] Transform _visual;

        void Awake()
        {
            if (_visual == null)
            {
                var anim = GetComponentInChildren<Animator>(true);
                _visual = anim != null ? anim.transform : (transform.childCount > 0 ? transform.GetChild(0) : null);
            }
        }

        void LateUpdate()
        {
            if (_visual == null) return;
            _visual.localPosition = Vector3.zero;
            _visual.localRotation = Quaternion.identity;
        }
    }
}
