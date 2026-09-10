using Festa.Network;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 타격판을 끝점으로 하는 망치 궤적과 양손 그립을 재생한다 (S15P21A604-585).
    /// 대검 클립은 몸의 체중 이동만 사용하고, 자루 방향·접촉점은 기계 좌표에서 계산한다.
    /// </summary>
    [DisallowMultipleComponent]
    [DefaultExecutionOrder(100)]
    public sealed class AvatarStrikeProp : MonoBehaviour
    {
        public const float WindupTime = 0.28f;
        public const float ImpactTime = 0.46f;
        public const float RecoveryTime = 0.54f;
        public const float SwingDuration = 0.86f;
        static readonly int StrikeState = Animator.StringToHash("Emote_Strike");
        static readonly int IdleState = Animator.StringToHash("Idle");

        [Header("망치 치수(아바타 로컬 미터)")]
        [SerializeField] float _handleLength = 0.92f;
        [SerializeField] float _handleRadius = 0.028f;
        [SerializeField] float _headOffset = 0.66f;
        [SerializeField] float _headLength = 0.30f;
        [SerializeField] float _headRadius = 0.105f;

        NetworkPlayer _player;
        PlayerAvatarVisual _visual;
        Transform _prop;
        Transform _rightHand, _leftHand;
        Animator _boundAnimator;
        Arm _rightArm, _leftArm;
        bool _swinging, _recovering;
        float _startedAt, _savedSpeed, _scale;
        Vector3 _contact, _forward, _side, _origin;

        public bool IsSwinging => _swinging;
        public bool HasImpacted { get; private set; }

        void Awake()
        {
            _player = GetComponent<NetworkPlayer>();
            _visual = GetComponent<PlayerAvatarVisual>();
        }

        /// <summary>충돌 검사를 거쳐 타격판 정면에 선다.</summary>
        public bool TryAlign(HighStrikerMachine machine)
        {
            if (machine == null || !EnsureHands()) return false;
            float scale = Mathf.Abs(_boundAnimator.transform.lossyScale.y);
            var facing = Vector3.ProjectOnPlane(machine.PlayerFacing, Vector3.up).normalized;
            var destination = machine.StrikeContact - facing * (0.86f * scale);
            destination.y = transform.position.y;
            var delta = destination - transform.position;
            if (delta.magnitude > 1.8f * scale) return false;
            var controller = GetComponent<CharacterController>();
            if (controller == null || !controller.enabled) return false;
            controller.Move(delta);
            var error = destination - transform.position;
            error.y = 0f;
            if (error.magnitude > 0.08f * scale) return false;
            transform.rotation = Quaternion.LookRotation(facing, Vector3.up);
            return true;
        }

        public bool BeginSwing(HighStrikerMachine machine)
        {
            if (machine == null || !EnsureHands())
            { Debug.LogWarning("[HighStriker] 양손 휴머노이드 릭이 없어 망치 동작을 재생할 수 없습니다", this); return false; }
            if (!_boundAnimator.HasState(0, StrikeState) || !_boundAnimator.HasState(0, IdleState))
            { Debug.LogError("[HighStriker] Strike 또는 Idle 애니메이션 상태가 없습니다", this); return false; }
            EndSwing();
            _savedSpeed = _boundAnimator.speed;
            _scale = Mathf.Abs(_boundAnimator.transform.lossyScale.y);
            _contact = machine.StrikeContact;
            _forward = Vector3.ProjectOnPlane(machine.PlayerFacing, Vector3.up).normalized;
            _side = Vector3.Cross(Vector3.up, _forward);
            _origin = transform.position;
            _startedAt = Time.time;
            _swinging = true;
            _recovering = false;
            HasImpacted = false;
            _boundAnimator.CrossFadeInFixedTime(StrikeState, 0.08f, 0, 0f);
            _prop = BuildMallet(_boundAnimator.transform);
            return true;
        }

        void Update()
        {
            if (!_swinging) return;
            if (_boundAnimator == null || _visual.CurrentAnimator != _boundAnimator)
            { EndSwing(); return; }
            float t = Time.time - _startedAt;
            if (_player != null && _player.IsOwner && t > 0.08f && _player.EmoteId.Value != PlayerEmoteId.Strike)
            { EndSwing(); return; }
            if (t >= SwingDuration) { EndSwing(); return; }
            if (t >= RecoveryTime && HasImpacted)
            {
                if (!_recovering)
                {
                    _recovering = true;
                    _boundAnimator.speed = _savedSpeed;
                    _boundAnimator.CrossFadeInFixedTime(IdleState, SwingDuration - RecoveryTime, 0);
                }
                return;
            }
            if (t < 0.08f) return;
            // 후반 32%의 무릎 꿇기·지면 찌르기는 사용하지 않는다.
            float phase = t < WindupTime
                ? Mathf.Lerp(0f, 0.20f, Smooth(t / WindupTime))
                : Mathf.Lerp(0.20f, 0.68f, Mathf.Pow(Mathf.Clamp01((t - WindupTime) / (ImpactTime - WindupTime)), 2f));
            _boundAnimator.speed = 0f;
            _boundAnimator.Play(StrikeState, 0, phase);
        }

        void LateUpdate()
        {
            if (!_swinging || _prop == null || _boundAnimator == null) return;
            float t = Time.time - _startedAt;
            // 낮은 FPS에서도 접촉 자세를 한 프레임 표시한 뒤 반동으로 넘어간다.
            if (t >= ImpactTime && !HasImpacted) t = ImpactTime;
            PoseAt(t, out var grip, out var axis);
            _prop.position = grip;
            _prop.rotation = Quaternion.LookRotation(_side, axis);
            float weight = Mathf.Clamp01(t / 0.08f);
            _rightArm.Solve(grip + _side * (0.025f * _scale), _origin + _side * _scale + Vector3.up * _scale, weight, _prop.rotation, true);
            _leftArm.Solve(grip - axis * (0.16f * _scale) - _side * (0.025f * _scale), _origin - _side * _scale + Vector3.up * _scale, weight, _prop.rotation, false);
            if (t >= ImpactTime) HasImpacted = true;
        }

        void PoseAt(float t, out Vector3 grip, out Vector3 axis)
        {
            var raisedGrip = _origin + Vector3.up * (1.48f * _scale) + _forward * (0.10f * _scale);
            var raisedAxis = (Vector3.up - _forward * 0.30f).normalized;
            var impactAxis = (_forward - Vector3.up * 0.42f).normalized;
            var impactHead = _contact + Vector3.up * (_headRadius * _scale);
            var impactGrip = impactHead - impactAxis * (_headOffset * _scale);
            if (t <= WindupTime)
            {
                float u = Smooth(t / WindupTime);
                grip = Vector3.Lerp(_origin + Vector3.up * (0.95f * _scale) + _forward * (0.28f * _scale), raisedGrip, u);
                axis = Vector3.Slerp((_forward * 0.65f + Vector3.up).normalized, raisedAxis, u);
            }
            else if (t <= ImpactTime)
            {
                float u = Mathf.Pow(Mathf.Clamp01((t - WindupTime) / (ImpactTime - WindupTime)), 2f);
                grip = Vector3.Lerp(raisedGrip, impactGrip, u);
                axis = Vector3.Slerp(raisedAxis, impactAxis, u);
            }
            else
            {
                float u = Smooth((t - RecoveryTime) / (SwingDuration - RecoveryTime));
                float bounce = Mathf.Sin(Mathf.Clamp01((t - ImpactTime) / (RecoveryTime - ImpactTime)) * Mathf.PI) * 0.07f * _scale;
                grip = Vector3.Lerp(impactGrip, _origin + Vector3.up * (0.95f * _scale) + _forward * (0.28f * _scale), u) + Vector3.up * bounce;
                axis = Vector3.Slerp(impactAxis, (_forward * 0.65f + Vector3.up).normalized, u);
            }
        }

        static float Smooth(float t) { t = Mathf.Clamp01(t); return t * t * (3f - 2f * t); }

        void EndSwing()
        {
            if (_swinging && _boundAnimator != null) _boundAnimator.speed = _savedSpeed;
            _swinging = false;
            if (_prop != null) Destroy(_prop.gameObject);
            _prop = null;
        }

        void OnDisable() => EndSwing();

        bool EnsureHands()
        {
            var animator = _visual != null ? _visual.CurrentAnimator : null;
            if (animator == null || animator.avatar == null || !animator.avatar.isHuman) return false;
            if (animator != _boundAnimator || _rightHand == null || _leftHand == null)
            {
                EndSwing();
                _boundAnimator = animator;
                _rightHand = animator.GetBoneTransform(HumanBodyBones.RightHand);
                _leftHand = animator.GetBoneTransform(HumanBodyBones.LeftHand);
                if (_rightHand == null || _leftHand == null) return false;
                _rightArm = new Arm(animator, true);
                _leftArm = new Arm(animator, false);
            }
            return true;
        }

        // 해석적 두 본 IK: 길이를 바꾸지 않고 팔꿈치를 몸 바깥으로 향하게 한다.
        sealed class Arm
        {
            readonly Transform _upper, _lower, _hand;
            readonly Quaternion _gripBasis;
            public Arm(Animator a, bool right)
            {
                _upper = a.GetBoneTransform(right ? HumanBodyBones.RightUpperArm : HumanBodyBones.LeftUpperArm);
                _lower = a.GetBoneTransform(right ? HumanBodyBones.RightLowerArm : HumanBodyBones.LeftLowerArm);
                _hand = a.GetBoneTransform(right ? HumanBodyBones.RightHand : HumanBodyBones.LeftHand);
                var finger = a.GetBoneTransform(right ? HumanBodyBones.RightMiddleProximal : HumanBodyBones.LeftMiddleProximal);
                var thumb = a.GetBoneTransform(right ? HumanBodyBones.RightThumbProximal : HumanBodyBones.LeftThumbProximal);
                var along = finger != null ? _hand.InverseTransformDirection(finger.position - _hand.position).normalized : Vector3.forward;
                var across = thumb != null ? _hand.InverseTransformDirection(thumb.position - _hand.position).normalized : Vector3.right;
                var normal = Vector3.Cross(along, across);
                _gripBasis = Quaternion.LookRotation(along, normal.sqrMagnitude > 0.001f ? normal : Vector3.up);
            }
            public void Solve(Vector3 target, Vector3 hint, float weight, Quaternion gripRotation, bool right)
            {
                if (_upper == null || _lower == null || _hand == null) return;
                target = Vector3.Lerp(_hand.position, target, weight);
                var start = _upper.position;
                float a = Vector3.Distance(start, _lower.position), b = Vector3.Distance(_lower.position, _hand.position);
                var to = target - start;
                if (to.sqrMagnitude < 0.000001f) return;
                float distance = Mathf.Clamp(to.magnitude, Mathf.Abs(a - b) + 0.001f, a + b - 0.001f);
                var direction = to.normalized;
                var bend = Vector3.ProjectOnPlane(hint - start, direction).normalized;
                float along = (a * a - b * b + distance * distance) / (2f * distance);
                var elbow = start + direction * along + bend * Mathf.Sqrt(Mathf.Max(0f, a * a - along * along));
                _upper.rotation = Quaternion.FromToRotation(_lower.position - start, elbow - start) * _upper.rotation;
                _lower.rotation = Quaternion.FromToRotation(_hand.position - _lower.position, target - _lower.position) * _lower.rotation;
                var handRotation = gripRotation * Quaternion.Euler(0f, right ? 90f : -90f, 0f) * Quaternion.Inverse(_gripBasis);
                _hand.rotation = Quaternion.Slerp(_hand.rotation, handRotation, weight);
            }
        }

        Transform BuildMallet(Transform hand)
        {
            var root = new GameObject("StrikeMallet").transform;
            root.SetParent(hand, false);   // 아바타 루트 배율을 받고 두 손은 IK로 자루를 따라간다

            var wood = RuntimeMaterial("FestaMalletHandle", new Color(0.10f, 0.10f, 0.12f), 0.25f);   // 검은 자루
            var steel = RuntimeMaterial("FestaMalletHead", new Color(0.72f, 0.13f, 0.12f), 0.30f);   // 빨간 머리

            // 자루는 **머리 중심까지** 닿아야 한다. 전에는 길이를 따로 주다 보니 자루 끝과 머리 사이가
            // 4 cm 벌어져 "망치가 끊어져" 보였다(사용자 지적 2026-09-10).
            float handleBottom = _headOffset - _handleLength;
            var handle = Part(root, "Handle", wood);
            handle.localPosition = new Vector3(0f, (handleBottom + _headOffset) * 0.5f, 0f);
            handle.localScale = new Vector3(_handleRadius * 2f, _handleLength * 0.5f, _handleRadius * 2f);

            var head = Part(root, "Head", steel);
            head.localPosition = new Vector3(0f, _headOffset, 0f);
            // 머리는 자루에 **직교**한다 — 실린더를 눕혀 붙인다(자루 축은 로컬 y, 머리 축은 로컬 z).
            head.localRotation = Quaternion.Euler(90f, 0f, 0f);
            head.localScale = new Vector3(_headRadius * 2f, _headLength * 0.5f, _headRadius * 2f);

            return root;
        }

        static Transform Part(Transform parent, string name, Material mat)
        {
            var go = GameObject.CreatePrimitive(PrimitiveType.Cylinder);
            go.name = name;
            Destroy(go.GetComponent<Collider>());   // 손에 든 물건이 캐릭터를 밀면 안 된다
            go.transform.SetParent(parent, false);
            var r = go.GetComponent<Renderer>();
            r.sharedMaterial = mat;
            r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            return go.transform;
        }

        static readonly System.Collections.Generic.Dictionary<string, Material> s_materials = new();

        /// <summary>런타임 머티리얼은 URP Lit 를 명시한다 — 기본 셰이더로 만들면 WebGL 빌드에서 분홍이 된다.</summary>
        static Material RuntimeMaterial(string key, Color color, float smoothness)
        {
            Material m;
            if (s_materials.TryGetValue(key, out m) && m != null) return m;
            var shader = Shader.Find("Universal Render Pipeline/Lit") ?? Shader.Find("Standard");
            m = new Material(shader) { name = key };
            m.SetColor("_BaseColor", color);
            m.SetFloat("_Smoothness", smoothness);
            s_materials[key] = m;
            return m;
        }
    }
}
