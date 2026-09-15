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

        /// <summary>
        /// 못 박을 자리. <b>원점이 아니라 씬에 저작된 값이다.</b>
        ///
        /// <para>전에는 무조건 <c>Vector3.zero</c> 로 되돌렸다. 그러면 에디터에서 시각물을 조금 옮겨
        /// 자세를 맞춰 둬도 <b>플레이하는 순간 그 조정이 통째로 사라진다</b> — "에디터에서 맞췄는데
        /// 런타임이 다르다" 의 원인이다 (2026-09-13 사용자 지적, 자는 직원 배치).
        /// 막으려던 것은 <b>클립의 루트 이동</b>이지 사람이 맞춰 둔 오프셋이 아니다.</para>
        /// </summary>
        Vector3 _pinLocalPos;
        Quaternion _pinLocalRot;

        /// <summary>
        /// 저작 위치를 대신할 **한시적인 자세**. 켜져 있는 동안만 이 값으로 못 박는다.
        ///
        /// <para>클립마다 몸이 루트에서 떨어져 있는 거리가 달라, 자세를 바꾸면 같은 루트에서도
        /// 몸이 딴 자리에 선다. 게다가 돌려세우면 몸이 시각물 원점을 중심으로 돌아 자리가 또 바뀐다 —
        /// 위치와 회전을 따로 주면 서로를 무너뜨리므로 <b>한 쌍으로</b> 받는다.
        /// 트랜스폼에 직접 쓰면 이 컴포넌트의 LateUpdate 와 순서 싸움이 나므로 통로는 여기 하나다.</para>
        /// </summary>
        bool _hasOverride;
        Vector3 _overridePos;
        Quaternion _overrideRot;

        /// <summary>한시적인 자세를 건다(시각물의 부모 기준 로컬). 거둘 때는 <see cref="ClearPose"/>.</summary>
        public void SetPose(Vector3 localPos, Quaternion localRot)
        {
            _hasOverride = true;
            _overridePos = localPos;
            _overrideRot = localRot;
        }

        /// <summary>한시적인 자세를 거두고 씬에 저작된 값으로 돌아간다.</summary>
        public void ClearPose() => _hasOverride = false;

        void Awake()
        {
            if (_visual == null)
            {
                var anim = GetComponentInChildren<Animator>(true);
                _visual = anim != null ? anim.transform : (transform.childCount > 0 ? transform.GetChild(0) : null);
            }
            if (_visual != null)
            {
                _pinLocalPos = _visual.localPosition;
                _pinLocalRot = _visual.localRotation;
            }
        }

        void LateUpdate()
        {
            if (_visual == null) return;
            _visual.localPosition = _hasOverride ? _overridePos : _pinLocalPos;
            _visual.localRotation = _hasOverride ? _overrideRot : _pinLocalRot;
        }
    }
}
