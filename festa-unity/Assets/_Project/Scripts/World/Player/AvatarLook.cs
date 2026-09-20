using Festa.Network;
using Unity.Netcode;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 마인크래프트식 시선 — 카메라를 돌리면 **고개와 상체만** 따라간다. 몸은 그대로다.
    ///
    /// ⚠ IK 로 하지 않는 이유 (T-205) — `OnAnimatorIK` 는 **Animator 와 같은 GameObject 의
    /// 컴포넌트에서만** 호출된다. 이 컴포넌트는 플레이어 루트에 있고 Animator 는 조립된
    /// 아바타(자식)에 있어서 콜백이 아예 불리지 않았다. 대신 `LateUpdate` 에서 **목·머리·
    /// 상체 본을 직접 회전**한다 — 애니메이션 평가 뒤라 안전하고 각도 분배를 통제할 수 있다.
    ///
    /// 2026-08-25 2차 — 첫 구현은 **한계(±90°)에서 확 꺾였고, 뛸 때 과하게 비틀렸다.**
    /// 원인 둘:
    ///   ① 하드 클램프 — 한계를 넘는 순간 고개가 극단 포즈에 **박힌다.** 90° 는 사람 목이
    ///      낼 수 없는 각도라 그 자체로 부러져 보였다.
    ///   ② 이동 중 몸이 카메라를 **부드럽게** 따라가므로(회전 스무딩) 도는 동안 몸이 카메라
    ///      뒤에 처진다. 그 차이를 고개가 전부 받아 뛸 때마다 목이 크게 꺾였다.
    /// 그래서 ⓐ 범위를 사람 범위로 줄이고 ⓑ 한계 근처를 부드럽게 포화시키고 ⓒ 한계를 넘으면
    /// **시선을 놓아 정면으로 돌아오게** 하고 ⓓ 이동·이모트 중에는 세기를 낮춘다.
    /// </summary>
    [RequireComponent(typeof(NetworkPlayer))]
    public class AvatarLook : NetworkBehaviour
    {
        [Header("고개 범위 — 사람이 편하게 돌아가는 만큼만")]
        [Tooltip("몸 대비 좌우 최대 각도. 머리·목·상체 셋을 합친 값이다. 55° 면 어깨 너머를 보는 정도다.")]
        [SerializeField] float _maxYaw = 55f;
        [SerializeField] float _maxPitchUp = 22f;
        [SerializeField] float _maxPitchDown = 20f;

        [Header("한계 밖 — 박히지 않고 놓는다")]
        [Tooltip("한계를 넘어 더 돌리면 이 폭에 걸쳐 시선을 놓고 정면으로 돌아온다. " +
                 "한계에 박혀 꺾인 채 버티는 것보다 자연스럽다.")]
        [SerializeField] float _releaseBand = 45f;

        [Header("분배 — 한 본이 다 받으면 부러져 보인다")]
        [SerializeField, Range(0f, 1f)] float _headShare = 0.50f;
        [SerializeField, Range(0f, 1f)] float _neckShare = 0.32f;
        [SerializeField, Range(0f, 1f)] float _chestShare = 0.18f;

        [Header("상태별 세기 — 뛸 때·이모트 중에는 애니메이션에 양보한다")]
        [SerializeField, Range(0f, 1f)] float _idleWeight = 1f;
        [SerializeField, Range(0f, 1f)] float _walkWeight = 0.55f;
        [SerializeField, Range(0f, 1f)] float _runWeight = 0.30f;

        [SerializeField] float _followLerp = 10f;
        [Tooltip("세기가 바뀔 때의 완화 속도. 이모트 진입·해제에서 고개가 튀지 않게 한다.")]
        [SerializeField] float _weightLerp = 7f;

        /// <summary>보고 있는 방향(yaw·pitch). Owner 가 쓰고 모두가 읽는다 — 원격에도 고개가 보인다.</summary>
        public NetworkVariable<Vector2> LookAngles = new(
            Vector2.zero, NetworkVariableReadPermission.Everyone, NetworkVariableWritePermission.Owner);

        const float SendThresholdDeg = 3f;   // 눈에 보이는 변화만 보낸다

        NetworkPlayer _player;
        PlayerCameraFollow _camera;
        PlayerAvatarVisual _visual;
        Transform _head, _neck, _chest;
        Animator _boundAnimator;
        Vector2 _sent;
        Vector2 _current;
        float _weight;

        /// <summary>
        /// 본 하나에 대해 "우리가 건드리기 전 포즈" 와 "우리가 써 넣은 값" 을 같이 들고 있는다.
        ///
        /// <para>왜 필요한가 — <see cref="Apply"/> 는 기존 회전 위에 offset 을 <b>곱한다.</b> 이건
        /// 애니메이터가 매 프레임 기본 포즈를 다시 써 준다는 전제인데, <see cref="AvatarAnimationLod"/>
        /// 는 원거리 아바타의 <c>Animator.enabled</c> 를 끄고 2~4프레임에 한 번만 <c>Animator.Update</c>
        /// 를 돌린다. 그 사이 프레임에는 <b>이미 꺾인 값 위에 또 꺾여</b> 각도가 쌓이고, 평가 프레임에
        /// 애니메이터가 포즈를 되쓰면 한꺼번에 돌아온다 — 멀리 있는 사람의 고개가 까딱거리던 원인
        /// (사용자 보고 2026-09-17). 화면 밖 컬링으로 평가가 건너뛰어도 같은 일이 난다.</para>
        /// </summary>
        struct BoneState
        {
            public Quaternion Base;      // 애니메이터가 만든 포즈 (우리 offset 이 빠진 것)
            public Quaternion Applied;   // 우리가 마지막으로 써 넣은 값
            public bool HasApplied;
        }

        BoneState _headState, _neckState, _chestState;

        void Awake()
        {
            _player = GetComponent<NetworkPlayer>();
            _camera = GetComponent<PlayerCameraFollow>();
            _visual = GetComponent<PlayerAvatarVisual>();
        }

        void LateUpdate()
        {
            BindBones();

            var target = IsOwner ? ReadOwnerLook() : LookAngles.Value;

            if (IsOwner &&
                (Mathf.Abs(Mathf.DeltaAngle(_sent.x, target.x)) > SendThresholdDeg ||
                 Mathf.Abs(target.y - _sent.y) > SendThresholdDeg))
            {
                LookAngles.Value = target;
                _sent = target;
            }

            float follow = 1f - Mathf.Exp(-_followLerp * Time.deltaTime);
            _current.x = Mathf.LerpAngle(_current.x, target.x, follow);
            _current.y = Mathf.Lerp(_current.y, target.y, follow);

            // 몸 대비 상대 각도. 이동 중에는 몸이 카메라를 따라가므로 대개 0 근처다.
            float rawYaw = Mathf.DeltaAngle(transform.eulerAngles.y, _current.x);

            float wanted = StateWeight() * ReleaseWeight(Mathf.Abs(rawYaw));
            _weight = Mathf.Lerp(_weight, wanted, 1f - Mathf.Exp(-_weightLerp * Time.deltaTime));

            if (_weight > 0.001f) ApplyBoneRotation(rawYaw);
        }

        void BindBones()
        {
            var animator = _visual != null ? _visual.CurrentAnimator : null;
            if (animator == _boundAnimator) return;
            _boundAnimator = animator;
            _head = _neck = _chest = null;
            _headState = _neckState = _chestState = default;   // 아바타가 갈렸다 — 기억한 포즈는 남의 것이다
            if (animator == null || !animator.isHuman) return;
            _head = animator.GetBoneTransform(HumanBodyBones.Head);
            _neck = animator.GetBoneTransform(HumanBodyBones.Neck);
            _chest = animator.GetBoneTransform(HumanBodyBones.Chest)
                     ?? animator.GetBoneTransform(HumanBodyBones.UpperChest)
                     ?? animator.GetBoneTransform(HumanBodyBones.Spine);
        }

        Vector2 ReadOwnerLook()
        {
            if (_camera != null && _camera.TryGetPlanarBasis(out var forward, out _))
                return new Vector2(Mathf.Atan2(forward.x, forward.z) * Mathf.Rad2Deg, _camera.CurrentPitch);
            return new Vector2(transform.eulerAngles.y, 0f);
        }

        /// <summary>
        /// 이모트 중에는 고개를 건드리지 않는다 — 클립이 이미 머리를 연출한다. 위에 회전을
        /// 곱하면 두 연출이 겹쳐 과하게 꺾인다. 이동 중에는 몸이 카메라를 따라가는 과도기에
        /// 각도 차가 커지므로 세기를 낮춘다.
        /// </summary>
        float StateWeight()
        {
            if (_player == null) return _idleWeight;
            if (_player.EmoteId.Value != PlayerEmoteId.None) return 0f;

            switch (_player.AnimState.Value)
            {
                case PlayerAnimState.Run:
                case PlayerAnimState.Jump:
                case PlayerAnimState.JumpLaunch:
                case PlayerAnimState.JumpLand:
                    return _runWeight;
                case PlayerAnimState.Walk:
                    return _walkWeight;
                default:
                    return _idleWeight;
            }
        }

        /// <summary>한계를 넘어서면 시선을 놓는다 — 극단 포즈에 박히는 것을 막는다.</summary>
        float ReleaseWeight(float absYaw)
        {
            float over = absYaw - _maxYaw;
            if (over <= 0f) return 1f;
            if (_releaseBand <= 0.01f) return 0f;
            return 1f - Mathf.SmoothStep(0f, 1f, Mathf.Clamp01(over / _releaseBand));
        }

        /// <summary>
        /// 한계에 **부드럽게 도착**한다. 하드 클램프는 한계를 넘는 순간 각도 변화가 뚝 끊겨
        /// 확 꺾인 것처럼 보인다. 사인 포화는 한계 근처에서 기울기가 0 이 되어 그 끊김이 없다.
        /// </summary>
        static float SoftLimit(float value, float limit)
        {
            if (limit <= 0.01f) return 0f;
            float t = Mathf.Clamp(value / limit, -1f, 1f);
            return limit * Mathf.Sin(t * Mathf.PI * 0.5f);
        }

        void ApplyBoneRotation(float rawYaw)
        {
            if (_head == null) return;

            float yaw = SoftLimit(rawYaw, _maxYaw) * _weight;
            float pitchLimit = _current.y >= 0f ? _maxPitchDown : _maxPitchUp;
            float pitch = SoftLimit(_current.y, pitchLimit) * _weight;

            // 본의 로컬 축은 모델마다 다르다(뼈가 어느 방향으로 뻗었는지 제작자 마음). 그래서
            // **몸 기준 월드 축**으로 회전을 곱한다 — yaw 는 월드 up, pitch 는 yaw 를 적용한 뒤의
            // 몸 오른쪽이다. 세 본이 같은 축으로 꺾여야 고개-목-상체가 한 방향으로 이어진다.
            var yawRotation = Quaternion.AngleAxis(yaw, Vector3.up);
            var pitchAxis = yawRotation * transform.right;

            Apply(ref _chestState, _chest, yaw * _chestShare, pitch * _chestShare, pitchAxis);
            Apply(ref _neckState, _neck, yaw * _neckShare, pitch * _neckShare, pitchAxis);
            Apply(ref _headState, _head, yaw * _headShare, pitch * _headShare, pitchAxis);
        }

        /// <summary>
        /// offset 을 <b>애니메이터가 만든 포즈에</b> 곱한다 — 직전에 우리가 써 넣은 값 위에 곱하지 않는다.
        ///
        /// <para>판정은 간단하다. 지금 본에 들어 있는 회전이 <b>우리가 마지막으로 써 넣은 그 값</b>이면
        /// 애니메이터가 이번 프레임에 포즈를 다시 쓰지 않은 것이다 — 그때는 기억해 둔 기본 포즈를
        /// 기준으로 삼는다. 값이 다르면 애니메이터가 새로 썼다는 뜻이라 그것이 곧 새 기준이다.</para>
        ///
        /// <para>이 방식은 평가가 <b>왜</b> 건너뛰었는지를 알 필요가 없다 — 거리 LOD 든 화면 밖
        /// 컬링이든 같은 경로로 막힌다. 갱신이 정상인 근거리에서는 매 프레임 기준이 새로 잡히므로
        /// 동작과 비용이 전과 같다.</para>
        /// </summary>
        static void Apply(ref BoneState state, Transform bone, float yaw, float pitch, Vector3 pitchAxis)
        {
            if (bone == null) return;

            var current = bone.rotation;
            var baseRotation = state.HasApplied && Quaternion.Angle(current, state.Applied) < 0.01f
                ? state.Base      // 애니메이터가 안 돌았다 — 우리 흔적을 걷어낸 포즈로 되돌린다
                : current;        // 애니메이터가 새로 썼다 — 이것이 기준이다

            var result = Quaternion.AngleAxis(yaw, Vector3.up) *
                         Quaternion.AngleAxis(pitch, pitchAxis) *
                         baseRotation;

            bone.rotation = result;
            state.Base = baseRotation;
            state.Applied = result;
            state.HasApplied = true;
        }
    }
}
