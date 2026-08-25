using Festa.Network;
using Unity.Netcode;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 마인크래프트식 시선·상체 회전 (2026-08-25).
    ///
    /// 문제였던 것 — 이전에는 **이동 방향으로 몸 전체가 돌아갔다.** 그래서
    ///   · 가만히 서서 주위를 둘러볼 수 없고(카메라만 돌고 캐릭터는 박혀 있다),
    ///   · 옆으로 움직이면 몸이 그쪽으로 돌아버려 보고 있던 것을 못 본다.
    /// "묶여 있는 것 같다" 는 체감이 여기서 나온다.
    ///
    /// 마인크래프트가 하는 것:
    ///   1) 서 있을 때 카메라를 돌리면 **고개만** 따라온다. 몸은 그대로다.
    ///   2) 고개가 한계(약 ±70°)를 넘으면 몸이 따라 돈다.
    ///   3 이동하면 몸이 카메라 정면으로 정렬되고, 다리는 실제 이동 방향(8방향)을 재생한다.
    /// 이 컴포넌트는 1·2 의 **시선**을 담당한다 (몸 정렬은 PlayerMovement, 다리는 블렌드 트리).
    ///
    /// 원격 아바타도 같이 보이게 시선 각도를 동기화한다 — 1 바이트다.
    /// 몸이 카메라를 향해 정렬되므로 대개 오프셋이 0 이지만, **서서 둘러보는 동안의
    /// 고개 각도**가 동기화되지 않으면 다른 사람 화면에서는 그 표현이 통째로 사라진다.
    /// </summary>
    [RequireComponent(typeof(NetworkPlayer))]
    public class AvatarLook : NetworkBehaviour
    {
        [Tooltip("몸 대비 고개가 돌아갈 수 있는 최대 각도. 넘으면 PlayerMovement 가 몸을 돌린다.")]
        [SerializeField] float _maxHeadYaw = 70f;
        [Tooltip("고개 상하 범위 (위/아래)")]
        [SerializeField] float _maxHeadPitchUp = 40f;
        [SerializeField] float _maxHeadPitchDown = 30f;
        [Tooltip("고개가 목표를 따라가는 속도 (초당 비율). 낮으면 굼뜨고 높으면 뻣뻣하다.")]
        [SerializeField] float _followLerp = 10f;
        [Tooltip("LookAt 가중치 — 1이면 상체가 크게 따라 돌고, 0.5 정도가 자연스럽다.")]
        [SerializeField] float _lookWeight = 0.65f;
        [SerializeField] float _bodyWeight = 0.25f;
        [SerializeField] float _headWeight = 0.9f;

        /// <summary>보고 있는 방향의 월드 yaw·pitch. Owner 가 쓰고 모두가 읽는다.</summary>
        public NetworkVariable<Vector2> LookAngles = new(
            Vector2.zero, NetworkVariableReadPermission.Everyone, NetworkVariableWritePermission.Owner);

        // 각도가 조금 흔들릴 때마다 보내면 트래픽이 낭비된다 — 눈에 보이는 변화만 보낸다.
        const float SendThresholdDeg = 2.5f;

        PlayerCameraFollow _camera;
        Animator _animator;
        Vector2 _sent;
        Vector2 _current;   // 표현용 보간값

        /// <summary>몸 정렬이 필요한지 — 고개 한계를 넘었는가 (PlayerMovement 가 읽는다).</summary>
        public bool NeedsBodyTurn { get; private set; }

        void Awake() => _camera = GetComponent<PlayerCameraFollow>();

        void LateUpdate()
        {
            var visual = GetComponent<PlayerAvatarVisual>();
            _animator = visual != null ? visual.CurrentAnimator : null;

            var target = IsOwner ? ReadOwnerLook() : LookAngles.Value;

            if (IsOwner)
            {
                NeedsBodyTurn = Mathf.Abs(Mathf.DeltaAngle(transform.eulerAngles.y, target.x)) > _maxHeadYaw;
                if (Mathf.Abs(Mathf.DeltaAngle(_sent.x, target.x)) > SendThresholdDeg ||
                    Mathf.Abs(target.y - _sent.y) > SendThresholdDeg)
                {
                    LookAngles.Value = target;
                    _sent = target;
                }
            }

            // 표현은 항상 보간한다 — 원격은 임계값 때문에 각도가 띄어 오므로 여기서 매끄럽게 된다.
            _current.x = Mathf.LerpAngle(_current.x, target.x, 1f - Mathf.Exp(-_followLerp * Time.deltaTime));
            _current.y = Mathf.Lerp(_current.y, target.y, 1f - Mathf.Exp(-_followLerp * Time.deltaTime));
        }

        Vector2 ReadOwnerLook()
        {
            // 카메라 궤도 방향이 곧 시선이다. PlayerCameraFollow 가 유일한 기준이다.
            if (_camera != null && _camera.TryGetPlanarBasis(out var forward, out _))
            {
                float yaw = Mathf.Atan2(forward.x, forward.z) * Mathf.Rad2Deg;
                float pitch = _camera.CurrentPitch;
                return new Vector2(yaw, pitch);
            }
            return new Vector2(transform.eulerAngles.y, 0f);
        }

        /// <summary>
        /// Animator IK 패스에서 LookAt 을 건다. 뼈를 직접 돌리지 않는 이유 —
        /// 휴머노이드 LookAt 은 목·머리·상체에 가중치를 나눠 자연스럽게 섞어 주고,
        /// 애니메이션 위에 얹히므로 클립을 교체해도 따라온다.
        /// </summary>
        void OnAnimatorIK(int layerIndex)
        {
            if (_animator == null || !_animator.isHuman) return;
            ApplyLook(_animator);
        }

        void ApplyLook(Animator animator)
        {
            // 몸 기준 상대 각도로 제한한다 — 제한하지 않으면 뒤를 볼 때 목이 꺾인다.
            float relYaw = Mathf.Clamp(Mathf.DeltaAngle(transform.eulerAngles.y, _current.x), -_maxHeadYaw, _maxHeadYaw);
            float pitch = Mathf.Clamp(_current.y, -_maxHeadPitchUp, _maxHeadPitchDown);

            var head = animator.GetBoneTransform(HumanBodyBones.Head);
            if (head == null) return;

            var dir = Quaternion.Euler(pitch, transform.eulerAngles.y + relYaw, 0f) * Vector3.forward;
            animator.SetLookAtWeight(_lookWeight, _bodyWeight, _headWeight, 0f, 0.5f);
            animator.SetLookAtPosition(head.position + dir * 5f);
        }
    }
}
