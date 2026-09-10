using Festa.Network;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 내리찍기 이모트(<see cref="PlayerEmoteId.Strike"/>) 동안에만 손에 망치를 쥐여 준다
    /// (사용자 지시 2026-09-10 — "저 애니메이션 중에만 손에 망치를"). 다른 때는 존재하지 않는다.
    ///
    /// <para>망치 모델을 따로 들이지 않고 프리미티브로 만든다. 벤더 팩의 곡괭이는 하이 스트라이커에
    /// 어울리지 않고(끝이 뾰족하다), 필요한 것은 자루 + 머리 두 덩이뿐이다.</para>
    ///
    /// <para>손에 붙이는 각도를 상수로 박지 않는다. 리타게팅된 손의 로컬 축은 아바타 릭마다 다르고,
    /// 이 클립은 <b>두 손 grip</b> 이라 자루 방향이 두 손을 잇는 선으로 이미 정해져 있다 —
    /// 매 프레임 그 선을 읽어 맞춘다(오브젝트 하나라 비용은 없다). 애니메이션 뒤에 맞춰야 하므로
    /// <see cref="LateUpdate"/> 다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class AvatarStrikeProp : MonoBehaviour
    {
        [Header("망치 치수(아바타 로컬 미터)")]
        [SerializeField] float _handleLength = 0.74f;   // 두 손 아래(-0.20)부터 머리 앞(+0.54)까지
        [SerializeField] float _handleRadius = 0.022f;
        [SerializeField] float _headOffset = 0.56f;     // 오른손에서 머리 중심까지
        [SerializeField] float _headLength = 0.19f;
        [SerializeField] float _headRadius = 0.052f;

        NetworkPlayer _player;
        PlayerAvatarVisual _visual;
        Transform _prop;
        Transform _rightHand, _leftHand;
        Animator _boundAnimator;

        void Awake()
        {
            _player = GetComponent<NetworkPlayer>();
            _visual = GetComponent<PlayerAvatarVisual>();
        }

        void LateUpdate()
        {
            if (_player == null) return;
            bool wanted = _player.EmoteId.Value == PlayerEmoteId.Strike;

            if (!wanted)
            {
                if (_prop != null) { Destroy(_prop.gameObject); _prop = null; }
                return;
            }

            if (!EnsureHands()) return;
            if (_prop == null) _prop = BuildMallet(_rightHand);
            if (_prop == null) return;

            // 자루 축 = 뒤 손(오른손) → 앞 손(왼손). 머리는 앞 손 너머에 달린다.
            // 처음에는 반대로 잡아 망치가 사람 쪽으로 향했다(사용자 지적 2026-09-10) — 이 클립은 왼손이 앞이다.
            var axis = _leftHand.position - _rightHand.position;
            if (axis.sqrMagnitude < 1e-6f) axis = _rightHand.forward;
            _prop.position = _rightHand.position;
            _prop.rotation = Quaternion.FromToRotation(Vector3.up, axis.normalized);
        }

        /// <summary>외형은 아바타 조립이 끝난 뒤에야 생긴다 — 애니메이터가 바뀌면 손을 다시 잡는다.</summary>
        bool EnsureHands()
        {
            var animator = _visual != null ? _visual.CurrentAnimator : null;
            if (animator == null || animator.avatar == null || !animator.avatar.isHuman) return false;
            if (animator != _boundAnimator || _rightHand == null || _leftHand == null)
            {
                _boundAnimator = animator;
                _rightHand = animator.GetBoneTransform(HumanBodyBones.RightHand);
                _leftHand = animator.GetBoneTransform(HumanBodyBones.LeftHand);
                if (_prop != null) { Destroy(_prop.gameObject); _prop = null; }
            }
            return _rightHand != null && _leftHand != null;
        }

        Transform BuildMallet(Transform hand)
        {
            var root = new GameObject("StrikeMallet").transform;
            root.SetParent(hand, false);   // 손에 붙어 아바타 배율을 그대로 받는다 — 치수는 미터로 쓴다

            var wood = RuntimeMaterial("FestaMalletHandle", new Color(0.36f, 0.24f, 0.14f), 0.15f);
            var steel = RuntimeMaterial("FestaMalletHead", new Color(0.30f, 0.31f, 0.34f), 0.65f);

            var handle = Part(root, "Handle", wood);
            handle.localPosition = new Vector3(0f, _headOffset - _headLength * 0.5f - _handleLength * 0.5f, 0f);
            handle.localScale = new Vector3(_handleRadius * 2f, _handleLength * 0.5f, _handleRadius * 2f);

            var head = Part(root, "Head", steel);
            head.localPosition = new Vector3(0f, _headOffset, 0f);
            // 머리는 자루에 직교한다 — 실린더를 눕혀 붙인다.
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
