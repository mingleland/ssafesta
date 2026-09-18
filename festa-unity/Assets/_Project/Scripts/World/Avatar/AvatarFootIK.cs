// 발 IK — 단차·경사에서 한쪽 다리가 떠 있거나 파묻히지 않게 발을 지면에 맞춘다 (사용자 지시 2026-09-09, 3번 사진).
// 왜 있는가: CharacterController 는 캡슐 하나로 서 있어 계단 모서리·카펫 단차에서는 한 발이 공중에, 한 발이 바닥 아래에 놓인다.
// Animator IK 패스에서 두 발을 각각 아래로 레이캐스트해 지면 높이·기울기에 붙이고, 더 낮은 발만큼 골반을 내린다.
// 이동 중에는 가중치를 낮춰(발이 땅을 끌지 않게) 정지·걷기 전환이 튀지 않도록 한다. 원격 아바타에도 그대로 붙는다(로컬 계산, 네트워크 없음).
using UnityEngine;

namespace Festa.World
{
    [DisallowMultipleComponent]
    [RequireComponent(typeof(Animator))]
    public sealed class AvatarFootIK : MonoBehaviour
    {
        [Tooltip("지면으로 인정할 레이어. Player 레이어(8)는 뺀다 — 자기 캡슐·다른 아바타에 발을 얹지 않게")]
        [SerializeField] LayerMask _groundMask = ~(1 << 8);
        [Tooltip("발 위에서 시작하는 레이 길이(u). 1 m = 13.26 u — 단차 0.3 m(4u)까지 잡는다")]
        [SerializeField] float _rayUp = 6f;
        [SerializeField] float _rayDown = 8f;
        [Tooltip("발바닥 두께 보정(u). 발목 본에서 지면까지 — 모듈러 아바타 1.6 u")]
        [SerializeField] float _footHeight = 1.6f;
        [Tooltip("골반이 내려갈 수 있는 최대(u). 3u = 0.23 m 단차까지 — 그보다 큰 차이(소파 모서리 등)에서는 쭈그리지 않는다")]
        [SerializeField] float _maxPelvisDrop = 3f;
        [SerializeField] float _weightLerp = 10f;

        /// <summary>
        /// 이보다 작은 좌우 지면 차는 단차로 보지 않는다(u). 1 m = 13.26 u 이므로 0.15 u ≈ 1 cm —
        /// 타일 이음매·메시 미세 단차에 골반이 떨리지 않게 한다.
        /// </summary>
        const float GroundDeadzone = 0.15f;

        Animator _anim;
        float _weight;          // 정지 1 → 이동 0.35
        float _pelvisOffset;    // 현재 골반 보정(음수)
        Vector3 _lPos, _rPos; Quaternion _lRot, _rRot; bool _lHit, _rHit;
        Vector3 _lastPos; float _speed;   // 컨트롤러에 Speed 파라미터가 없다(IsWalking/IsRunning/MoveX/MoveY) — 위치 변화로 속도를 잰다

        void Awake()
        {
            _anim = GetComponent<Animator>();
            _lastPos = transform.position;
        }

        void Update()
        {
            float dt = Time.deltaTime; if (dt <= 0f) return;
            var p = transform.position; var d = p - _lastPos; d.y = 0f;
            _speed = Mathf.Lerp(_speed, d.magnitude / dt, 0.3f); _lastPos = p;
        }

        void OnAnimatorIK(int layerIndex)
        {
            if (_anim == null || !_anim.isHuman) return;
            var state = _anim.GetCurrentAnimatorStateInfo(layerIndex);

            // 의자 자세는 엉덩이를 좌면에 고정한 채 발만 바닥 위로 올린다. 일반 발 IK처럼 골반을
            // 움직이면 좌면에서 다시 뜨므로, 발이 바닥 아래인 쪽에만 위치 IK를 건다.
            if (IsChairSit(state))
            {
                _weight = 1f;
                _pelvisOffset = 0f;
                ClampChairFoot(AvatarIKGoal.LeftFoot);
                ClampChairFoot(AvatarIKGoal.RightFoot);
                return;
            }

            // 이모트(앉기·눕기 등)·점프 상태에서는 끈다 — 앉은 자세에 발 IK 를 걸면 골반이 내려가 쭈그린다(2026-09-09 소파 실측), 공중에서는 발을 땅으로 당긴다.
            if (state.IsTag("NoFootIK") || IsEmoteOrJump(state))
            {
                _weight = 0f; _pelvisOffset = 0f;
                _anim.SetIKPositionWeight(AvatarIKGoal.LeftFoot, 0f); _anim.SetIKRotationWeight(AvatarIKGoal.LeftFoot, 0f);
                _anim.SetIKPositionWeight(AvatarIKGoal.RightFoot, 0f); _anim.SetIKRotationWeight(AvatarIKGoal.RightFoot, 0f);
                return;
            }
            // 이동 중에는 약하게 — 발이 바닥에 붙어 끌리는 것보다 애니메이션이 우선. (1 m = 13.26 u → 1.3 u/s 미만이면 정지로 본다)
            float target = _speed > 1.3f ? 0.35f : 1f;
            _weight = Mathf.Lerp(_weight, target, Time.deltaTime * _weightLerp);

            _lHit = Probe(AvatarIKGoal.LeftFoot, out _lPos, out _lRot);
            _rHit = Probe(AvatarIKGoal.RightFoot, out _rPos, out _rRot);

            // 평지에서는 원본 걷기 애니메이션을 그대로 둔다. 골반 보정이 0이어도 발 목표를 지면에
            // 강제로 고정하면 발목 높이 오차만큼 무릎이 살짝 접힌다. 양발 접촉면 차이가 미세하면
            // 단차가 아니므로 발 위치·회전 IK까지 모두 끈다.
            if (_lHit && _rHit)
            {
                float flatLeft = _lPos.y - _footHeight;
                float flatRight = _rPos.y - _footHeight;
                if (Mathf.Abs(flatLeft - flatRight) <= GroundDeadzone)
                {
                    _pelvisOffset = 0f;
                    _anim.SetIKPositionWeight(AvatarIKGoal.LeftFoot, 0f);
                    _anim.SetIKRotationWeight(AvatarIKGoal.LeftFoot, 0f);
                    _anim.SetIKPositionWeight(AvatarIKGoal.RightFoot, 0f);
                    _anim.SetIKRotationWeight(AvatarIKGoal.RightFoot, 0f);
                    return;
                }
            }

            // 골반: **두 발이 딛는 지면의 높이 차**만큼만 내린다. 올리지는 않는다 — 올리면 캡슐 밖으로 뜬다.
            //
            // 두 번 틀렸던 자리다.
            // ① `지면높이 - 애니메이션 발높이` — 애니메이션이 들어 올린 발까지 "떠 있다" 로 읽어,
            //    걷기 스윙마다 골반이 내려갔다 (2026-09-13).
            // ② `지면높이 - transform.position.y` — 이 스크립트는 **Animator 노드**에 붙어 있고,
            //    그 노드는 'AvatarVisual_Modular' 아래라 오프셋·배율이 끼어 있다. 바닥 높이가 아니다.
            //    평지 실측(2026-09-14): 지면 0.084 · 노드 0.337 → 보정 **−0.253 이 상수로** 걸려
            //    가만히 서 있어도 무릎이 굽었다 (사용자 지적, 2번 사진).
            //
            // 필요한 값은 애초에 절대 높이가 아니라 **한쪽 발이 다른 쪽보다 얼마나 낮은 데 있는가** 하나다.
            // 그것만 쓰면 노드가 어디 있든·배율이 얼마든 평지에서는 정확히 0 이 된다.
            float drop = 0f;
            if (_lHit && _rHit)
            {
                float lGround = _lPos.y - _footHeight;
                float rGround = _rPos.y - _footHeight;
                float diff = Mathf.Min(lGround, rGround) - Mathf.Max(lGround, rGround);   // 항상 ≤ 0
                if (diff < -GroundDeadzone) drop = Mathf.Clamp(diff, -_maxPelvisDrop, 0f) * _weight;
            }
            _pelvisOffset = Mathf.Lerp(_pelvisOffset, drop, Time.deltaTime * _weightLerp);
            if (Mathf.Abs(_pelvisOffset) > 0.001f)
            {
                var body = _anim.bodyPosition; body.y += _pelvisOffset; _anim.bodyPosition = body;
                // 골반을 내린 만큼 발 목표도 같이 내려가야 원래 닿던 발이 바닥을 뚫지 않는다 → 아래에서 IK 위치는 절대 지면값이므로 그대로 둔다
            }

            Apply(AvatarIKGoal.LeftFoot, _lHit, _lPos, _lRot);
            Apply(AvatarIKGoal.RightFoot, _rHit, _rPos, _rRot);
        }

        // AvatarAnimator 의 상태 이름: Emote_* (PlayerEmoteId 와 이름으로 짝), Jump_Launch/Jump_Air/Jump_Land
        static readonly System.Collections.Generic.HashSet<int> s_skipStates = BuildSkipStates();
        static System.Collections.Generic.HashSet<int> BuildSkipStates()
        {
            var set = new System.Collections.Generic.HashSet<int>();
            foreach (var n in System.Enum.GetNames(typeof(Festa.Network.PlayerEmoteId))) if (n != "None") set.Add(Animator.StringToHash("Emote_" + n));
            foreach (var n in new[] { "Jump_Launch", "Jump_Air", "Jump_Land" }) set.Add(Animator.StringToHash(n));
            return set;
        }
        static bool IsEmoteOrJump(AnimatorStateInfo state) => s_skipStates.Contains(state.shortNameHash);

        static readonly int s_sitChair1 = Animator.StringToHash("Emote_SitChair1");
        static readonly int s_sitChair2 = Animator.StringToHash("Emote_SitChair2");
        static bool IsChairSit(AnimatorStateInfo state)
            => state.shortNameHash == s_sitChair1 || state.shortNameHash == s_sitChair2;

        void ClampChairFoot(AvatarIKGoal goal)
        {
            var original = _anim.GetIKPosition(goal);
            if (!Probe(goal, out var grounded, out var rotation) || original.y >= grounded.y) {
                _anim.SetIKPositionWeight(goal, 0f);
                _anim.SetIKRotationWeight(goal, 0f);
                return;
            }

            _anim.SetIKPositionWeight(goal, 1f);
            _anim.SetIKRotationWeight(goal, 0.7f);
            _anim.SetIKPosition(goal, grounded);
            _anim.SetIKRotation(goal, rotation);
        }

        bool Probe(AvatarIKGoal goal, out Vector3 pos, out Quaternion rot)
        {
            var footPos = _anim.GetIKPosition(goal);
            var footRot = _anim.GetIKRotation(goal);
            pos = footPos; rot = footRot;
            var origin = footPos + Vector3.up * _rayUp;
            if (!Physics.Raycast(origin, Vector3.down, out var hit, _rayUp + _rayDown, _groundMask, QueryTriggerInteraction.Ignore)) return false;
            pos = hit.point + Vector3.up * _footHeight;
            // 발을 지면 기울기에 맞춰 회전 — 원래 발 방향(forward)은 유지
            var fwd = Vector3.ProjectOnPlane(footRot * Vector3.forward, hit.normal);
            if (fwd.sqrMagnitude > 0.0001f) rot = Quaternion.LookRotation(fwd, hit.normal);
            return true;
        }

        void Apply(AvatarIKGoal goal, bool hit, Vector3 pos, Quaternion rot)
        {
            if (!hit) { _anim.SetIKPositionWeight(goal, 0f); _anim.SetIKRotationWeight(goal, 0f); return; }
            // 애니메이션이 발을 들어 올린 프레임(지면보다 높은 발)은 그대로 둔다 — 걷기 스윙을 땅에 눌러붙이지 않는다
            float animY = _anim.GetIKPosition(goal).y;
            float w = animY > pos.y + 1.5f ? 0f : _weight;
            _anim.SetIKPositionWeight(goal, w);
            _anim.SetIKRotationWeight(goal, w * 0.7f);
            _anim.SetIKPosition(goal, pos);
            _anim.SetIKRotation(goal, rot);
        }
    }
}
