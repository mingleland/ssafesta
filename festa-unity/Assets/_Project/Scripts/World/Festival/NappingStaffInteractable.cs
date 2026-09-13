using Festa.Booth;
using Festa.Content;
using TMPro;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 판넬 뒤 숨은 틈에서 자고 있는 직원 (S15P21A604-672).
    ///
    /// <para>거기까지 걸어 들어온 사람에게만 보이는 것이라 <b>네트워크를 타지 않는다.</b>
    /// 여러 명이 같은 상태를 보아야 할 이유가 없고, 동기화하면 계약과 서버 부하만 늘어난다.
    /// 각자의 화면에서 각자 깨우고 각자 5분을 센다.</para>
    ///
    /// <para>대사도 <b>월드 안 말풍선</b>이다. FE 오버레이를 쓰지 않는다 — 이스터에그 하나 때문에
    /// 계약을 늘리지 않는다(<see cref="GuideDeskInteractable"/> 과 같은 판단).</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class NappingStaffInteractable : MonoBehaviour, IBoothInteractable
    {
        // **두 줄로 끊는다.** 한 줄이면 좁은 틈(가용 폭 10 u)에서 양끝이 화면 밖으로 나가
        // "뭐라고 하는지" 를 읽을 수 없다 — 실측으로 확인했다. TMP 자동 줄바꿈은 한글을
        // 글자 단위로 끊으므로 문구에 직접 넣는다(간판에서 겪은 것).
        const string CaughtLine = "죄..죄송합니다.\n얼른 들어가겠습니다.";

        [Tooltip("들킨 뒤 다시 돌아오기까지(초)")]
        [SerializeField] float _returnAfter = 300f;

        [Tooltip("대사를 띄워 두는 시간(초). 이 뒤에 사라진다")]
        [SerializeField] float _lineDuration = 2.2f;

        [Tooltip("말풍선을 몸 위 얼마에 둘지(u). 누워 있으므로 낮다 — 1 m = 13.26 u")]
        [SerializeField] float _bubbleHeight = 13f;

        [Tooltip("말풍선이 보이기 시작하는 거리(u)")]
        [SerializeField] float _visibleDistance = 180f;

        [Tooltip("말풍선을 보는 사람 쪽으로 수평으로 끌어내는 양(u). 몸이 벽에 묻혀 있어 골반 위에 두면 대사가 안 보인다")]
        [SerializeField] float _bubblePullToCamera = 0f;

        /// <summary>자는 포즈로 쓸 이모트. 인스펙터에서 바꿀 수 있게 둔다 — 클립마다 누운 방향이 다르다.</summary>
        [SerializeField] Festa.Network.PlayerEmoteId _sleepEmote = Festa.Network.PlayerEmoteId.LieSofa;

        Transform _bubble;
        /// <summary>
        /// 말풍선을 매달 기준점. **루트가 아니라 골반이다.** 눕기 클립은 몸을 루트에서 6 u 옆으로 밀어 놓는데
        /// (PinVisualRoot 가 루트를 제자리에 못 박아도 포즈 오프셋은 남는다), 루트 위에 띄우면 말풍선이
        /// 옆 판넬 **안**에 들어가 보이지 않는다 — 실측으로 확인했다(틈 폭 4.3 u, 몸 중심은 루트에서 -5.9 u).
        /// </summary>
        Transform _hips;
        TMP_Text _label;
        Animator _animator;
        GameObject[] _visuals;
        float _hiddenUntil;
        float _lineUntil;
        bool _caught;

        void Awake()
        {
            if (GetComponentsInChildren<Collider>(true).Length == 0)
                gameObject.AddComponent<BoxCollider>();
            if (GetComponent<BoothInteractionTarget>() == null)
                gameObject.AddComponent<BoothInteractionTarget>();
            BoothInteractionInput.Ensure();

            _animator = GetComponentInChildren<Animator>();
            if (_animator != null && _animator.isHuman) _hips = _animator.GetBoneTransform(HumanBodyBones.Hips);
            // 숨길 대상은 **자식 렌더러를 가진 오브젝트**다. 이 오브젝트 자체를 끄면 이 스크립트도 멈춰
            // 5분 뒤 복귀가 영영 오지 않는다 — 그래서 껍데기는 살려 두고 보이는 것만 끈다.
            var renderers = GetComponentsInChildren<Renderer>(true);
            _visuals = new GameObject[renderers.Length];
            for (int i = 0; i < renderers.Length; i++) _visuals[i] = renderers[i].gameObject;

            BuildBubble();
            PlaySleep();
        }

        void BuildBubble()
        {
            var font = Resources.Load<TMP_FontAsset>("Fonts/ChalkboardKR_SDF")
                    ?? Resources.Load<TMP_FontAsset>("Fonts/Jua_SDF")
                    ?? Resources.Load<TMP_FontAsset>("Fonts/NotoSansKRBold_SDF");
            if (font == null)
            {
                Debug.LogError("[NappingStaff] Resources/Fonts 에 쓸 글꼴이 없어 말풍선을 만들지 못했다");
                return;
            }
            var go = new GameObject("NapBubble");
            go.transform.SetParent(transform, false);

            var tmp = go.AddComponent<TextMeshPro>();

            // **Transform 은 AddComponent 뒤에 잡는다** — TMP 가 RectTransform 으로 갈아끼우면서
            // 먼저 잡아 둔 Transform 을 파괴한다. 그러면 LateUpdate 가 매 프레임 즉시 되돌아 나가
            // 빌보드가 돌지 않는다 (안내 말풍선에서 실측으로 확인한 것과 같은 원인).
            _bubble = tmp.transform;
            _bubble.localPosition = new Vector3(0f, _bubbleHeight, 0f);   // LateUpdate 가 골반 위로 다시 잡는다

            tmp.font = font;
            tmp.text = CaughtLine;
            tmp.color = new Color(1f, 0.99f, 0.95f);
            tmp.alignment = TextAlignmentOptions.Center;
            tmp.textWrappingMode = TextWrappingModes.NoWrap;
            tmp.overflowMode = TextOverflowModes.Overflow;
            tmp.fontSize = 15f;   // 틈이 좁아 22 면 한 줄이 화면 밖으로 나간다 (실측)
            tmp.enabled = false;   // 들키기 전에는 조용하다
            _label = tmp;

            // 외곽선은 머티리얼 인스턴스에 넣어야 실제로 그려진다 (이름표와 같은 방식).
            var mat = tmp.fontMaterial;
            mat.SetFloat(ShaderUtilities.ID_FaceDilate, 0.15f);
            mat.EnableKeyword("OUTLINE_ON");
            mat.SetColor(ShaderUtilities.ID_OutlineColor, new Color(0.02f, 0.02f, 0.04f, 1f));
            mat.SetFloat(ShaderUtilities.ID_OutlineWidth, 0.25f);
        }

        void PlaySleep()
        {
            if (_animator == null) return;
            var state = $"Emote_{_sleepEmote}";
            if (_animator.HasState(0, Animator.StringToHash(state))) _animator.CrossFadeInFixedTime(state, 0.25f, 0);
            else Debug.LogWarning($"[NappingStaff] 자는 포즈 상태를 찾지 못했다: {state}");
        }

        void SetVisible(bool on)
        {
            if (_visuals == null) return;
            for (int i = 0; i < _visuals.Length; i++)
                if (_visuals[i] != null) _visuals[i].SetActive(on);
            var target = GetComponent<BoothInteractionTarget>();
            if (target != null) target.enabled = on;   // 안 보이는 사람을 조준하지 않게
        }

        void LateUpdate()
        {
            // ── 들킨 뒤: 대사 → 사라짐 → 5분 뒤 복귀 ──
            if (_caught)
            {
                if (_lineUntil > 0f && Time.time >= _lineUntil)
                {
                    _lineUntil = 0f;
                    if (_label != null) _label.enabled = false;
                    SetVisible(false);
                    _hiddenUntil = Time.time + _returnAfter;
                }
                else if (_hiddenUntil > 0f && Time.time >= _hiddenUntil)
                {
                    _hiddenUntil = 0f;
                    _caught = false;
                    SetVisible(true);
                    PlaySleep();
                }
            }

            if (_bubble == null || _label == null || !_label.enabled) return;
            var cam = Camera.main;
            if (cam == null) return;
            if (_hips != null)
            {
                // 골반 위에 그대로 두면 **벽 속**이다 — 이 직원은 깊이 0.5 m 짜리 창틀 니치에 누워 있어
                // 몸의 절반이 창측 벽에 묻혀 있다(실측: 니치 6.66 u, 몸 폭 9.68 u). 말풍선까지 거기 두면
                // 깨워도 대사가 안 보인다. 그래서 **보는 사람 쪽으로 수평으로 끌어낸다.**
                // **카메라 쪽으로 끌어내지 않는다(기본 0).** 끌어내면 틈 밖으로 나가 벽 모서리에 잘린다 —
                // 실측으로 확인했다. 골반 바로 위, 틈 안쪽에 두는 쪽이 가장 잘 읽힌다.
                var toCam = cam.transform.position - _hips.position;
                toCam.y = 0f;
                var pull = (_bubblePullToCamera > 0.01f && toCam.sqrMagnitude > 0.0001f)
                    ? toCam.normalized * _bubblePullToCamera : Vector3.zero;
                _bubble.position = _hips.position + Vector3.up * _bubbleHeight + pull;
            }
            if ((cam.transform.position - _bubble.position).sqrMagnitude > _visibleDistance * _visibleDistance) return;
            var to = cam.transform.position - _bubble.position;
            to.y = 0f;
            if (to.sqrMagnitude > 0.0001f)
                _bubble.rotation = Quaternion.LookRotation(-to.normalized, Vector3.up);
        }

        public void Interact()
        {
            if (_caught) return;   // 이미 들켰다 — 두 번 깨우지 않는다
            _caught = true;
            _lineUntil = Time.time + _lineDuration;
            if (_label != null) _label.enabled = true;
            // 일어나는 척 — 누운 이모트를 풀면 기본 자세로 돌아간다.
            if (_animator != null && _animator.HasState(0, Animator.StringToHash("Idle")))
                _animator.CrossFadeInFixedTime("Idle", 0.2f, 0);
        }
    }
}
