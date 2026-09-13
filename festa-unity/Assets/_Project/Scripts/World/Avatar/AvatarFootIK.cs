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
            // 이모트(앉기·눕기 등)·점프 상태에서는 끈다 — 앉은 자세에 발 IK 를 걸면 골반이 내려가 쭈그린다(2026-09-09 소파 실측), 공중에서는 발을 땅으로 당긴다.
            var state = _anim.GetCurrentAnimatorStateInfo(layerIndex);
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

            // 골반: **지면이 루트보다 낮을 때만** 내린다. 올리지는 않는다 — 올리면 캡슐 밖으로 뜬다.
            //
            // 전에는 `지면높이 - 애니메이션 발높이` 를 썼는데, 그러면 **애니메이션이 들어 올린 발**까지
            // "떠 있다" 로 읽는다. 걷기 스윙이나 아이들의 무게중심 이동으로 한 발이 올라가는 순간
            // 그 차이만큼 골반이 내려가, 평지에 그냥 서 있어도 자세가 주저앉았다 (사용자 보고 2026-09-13).
            // 아래 `Apply` 에는 들린 발을 거르는 가드가 있는데 골반 계산에만 없었다.
            //
            // 기준을 **실제 지면 높이 대 루트 높이**로 바꾼다. 평지에서는 둘이 같아 보정이 0 이 되고,
            // 한 발이 낮은 단차 위에 있을 때만 그 깊이만큼 내려간다 — 원래 의도 그대로다.
            float rootY = transform.position.y;
            float lGround = _lHit ? _lPos.y - _footHeight : rootY;
            float rGround = _rHit ? _rPos.y - _footHeight : rootY;
            float drop = Mathf.Clamp(Mathf.Min(lGround, rGround) - rootY, -_maxPelvisDrop, 0f) * _weight;
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
