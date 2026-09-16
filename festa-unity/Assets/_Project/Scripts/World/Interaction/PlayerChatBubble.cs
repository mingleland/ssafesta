// 채팅 말풍선 — 월드 채팅 한 줄을 말한 사람 머리 위에 잠깐 띄운다 (사용자 지시 2026-09-15).
// 이 파일이 있는 이유: 채팅 자체는 React 오버레이가 그리지만, 화면 구석 로그만으로는 "누가 말했는지"가
// 보이지 않는다. 사람과 말을 잇는 표시는 월드 안에 있어야 하고 그건 Unity 만 그릴 수 있다.
using TMPro;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 플레이어 머리 위에 채팅 한 줄을 띄우는 말풍선.
    ///
    /// <para><b>이름표 위에 앉는다.</b> <see cref="WorldNameplate"/> 는 정수리에 붙고, 말풍선은 그보다 더
    /// 위에 둔다. 같은 높이에 두면 이름과 말이 겹쳐 둘 다 못 읽는다.</para>
    ///
    /// <para><b>사라지는 시간은 길이에 비례한다.</b> 긴 문장을 짧은 시간에 지우면 다 읽기 전에 사라지고,
    /// 짧은 말을 오래 남기면 화면이 말풍선으로 덮인다. 최소 3초, 최대 7초 사이에서 글자 수로 정한다.</para>
    ///
    /// <para><b>저장하지 않는다.</b> 서버에 채팅 표가 없고(계약 002 world-chat-api) 여기서도 마지막 한 줄만
    /// 들고 있는다. 같은 사람이 연달아 말하면 앞의 것을 덮고 시간을 다시 센다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class PlayerChatBubble : MonoBehaviour
    {
        /// <summary>이름표와 같은 SDF 폰트를 쓴다 — 한글이 깨지지 않게 프로젝트 폰트로 구운 것이다.</summary>
        const string FontResourcePath = "Fonts/NotoSansKRBold_SDF";

        /// <summary>이 거리(월드 유닛) 밖에서는 그리지 않는다. 이름표(260)보다 짧다 — 말은 가까이서만 읽는다.</summary>
        const float VisibleDistance = 220f;

        /// <summary>정수리에서 말풍선까지 띄울 거리(월드 유닛). 이름표가 그 사이에 들어간다.</summary>
        const float HeadroomAboveNameplate = 7.5f;

        /// <summary>캡슐을 못 찾았을 때 쓸 키(월드 유닛). 이 씬의 아바타 목표 높이가 22.375 다.</summary>
        const float FallbackHeight = 22.4f;

        const float MinSeconds = 3f;
        const float MaxSeconds = 7f;
        const float SecondsPerCharacter = 0.09f;

        /// <summary>이 거리에서 기준 크기로 보인다. 멀면 키우고 가까우면 줄여 화면상 크기를 유지한다.</summary>
        const float ReferenceDistance = 55f;
        const float BaseFontWorldScale = 1.15f;

        TextMeshPro _text;
        RectTransform _root;
        MeshRenderer _renderer;
        float _hideAt;
        float _height = FallbackHeight;

        /// <summary>
        /// 말풍선을 띄운다. 대상에 컴포넌트가 없으면 붙여서 쓴다 — 프리팹을 고치지 않아도
        /// 원격 아바타를 포함한 모든 플레이어에 적용된다.
        /// </summary>
        public static void Show(GameObject target, string message)
        {
            if (target == null || string.IsNullOrWhiteSpace(message)) return;
            var bubble = target.GetComponent<PlayerChatBubble>();
            if (bubble == null) bubble = target.AddComponent<PlayerChatBubble>();
            bubble.Say(message);
        }

        void Say(string message)
        {
            if (_text == null) Build();
            if (_text == null) return;

            var trimmed = message.Trim();
            _text.text = trimmed;
            _hideAt = Time.time + Mathf.Clamp(MinSeconds + trimmed.Length * SecondsPerCharacter, MinSeconds, MaxSeconds);
            if (_renderer != null) _renderer.enabled = true;
        }

        void Build()
        {
            // RectTransform 을 먼저 붙인다 — TextMeshPro 가 평범한 Transform 을 RectTransform 으로 **교체**하면
            // 그 전에 잡아 둔 참조가 파괴된 객체가 된다 (WorldNameplate 에서 이미 밟은 함정).
            var go = new GameObject("ChatBubble", typeof(RectTransform));
            _text = go.AddComponent<TextMeshPro>();
            _root = _text.rectTransform;
            _root.SetParent(transform, false);

            // 부모가 스케일돼 있으면(부스 앵커 아래 등) 글자 크기가 따라 변한다 — 되돌린다.
            var ls = transform.lossyScale;
            _root.localScale = new Vector3(1f / Mathf.Max(ls.x, 1e-4f), 1f / Mathf.Max(ls.y, 1e-4f), 1f / Mathf.Max(ls.z, 1e-4f));

            var font = Resources.Load<TMP_FontAsset>(FontResourcePath);
            if (font != null) _text.font = font;
            else Debug.LogWarning($"[PlayerChatBubble] SDF 폰트를 찾지 못했다 ({FontResourcePath}) — 한글이 깨질 수 있다.");

            _text.alignment = TextAlignmentOptions.Bottom;
            _text.textWrappingMode = TextWrappingModes.Normal;   // 긴 문장이 화면을 가로지르지 않게 접는다
            _text.overflowMode = TextOverflowModes.Overflow;
            _text.color = new Color(1f, 1f, 1f, 1f);
            _text.fontSize = 10f;                                 // TMP 포인트 기준 10 = 월드 1 유닛
            _root.sizeDelta = new Vector2(26f, 4f);
            _root.pivot = new Vector2(0.5f, 0f);

            var material = _text.fontMaterial;                    // 공유본을 건드리면 UI 글자까지 물든다
            WorldTextOcclusion.ApplyOverlayShader(material, "ChatBubble");   // 이름표와 같이 벽 위에 그린다 — 반 토막 방지
            material.EnableKeyword("OUTLINE_ON");
            material.SetFloat(ShaderUtilities.ID_OutlineWidth, 0.22f);
            material.SetColor(ShaderUtilities.ID_OutlineColor, new Color(0.02f, 0.02f, 0.04f, 1f));
            material.SetFloat(ShaderUtilities.ID_FaceDilate, 0.1f);

            _renderer = go.GetComponent<MeshRenderer>();
            _renderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            _renderer.receiveShadows = false;
            _renderer.enabled = false;

            var controller = GetComponent<CharacterController>();
            if (controller != null) _height = controller.height;
            else
            {
                var capsule = GetComponent<CapsuleCollider>();
                if (capsule != null) _height = capsule.height;
            }
        }

        void LateUpdate()
        {
            if (_renderer == null) return;

            if (Time.time >= _hideAt)
            {
                _renderer.enabled = false;
                return;
            }

            var cam = Camera.main;
            if (cam == null) { _renderer.enabled = false; return; }

            var anchor = transform.position + Vector3.up * (_height + HeadroomAboveNameplate);
            var toCamera = cam.transform.position - anchor;

            // 카메라 뒤로 지나간 대상까지 그리면 화면이 말풍선으로 덮인다.
            // 여기서 끈 것은 "이번 프레임만" 이다 — 표시 시간이 남아 있으면 다음 프레임에 다시 판정한다.
            // (예전에는 한 번 꺼지면 LateUpdate 첫 줄에서 빠져 시간이 남아도 영영 안 켜졌다.)
            if (Vector3.Dot(cam.transform.forward, anchor - cam.transform.position) <= 0f) { _renderer.enabled = false; return; }

            float distance = toCamera.magnitude;
            if (distance > VisibleDistance) { _renderer.enabled = false; return; }

            _root.position = anchor;
            _root.rotation = Quaternion.LookRotation(_root.position - cam.transform.position, Vector3.up);

            // 화면상 크기를 대체로 일정하게 — 멀면 키우고 가까우면 줄인다.
            float scale = BaseFontWorldScale * Mathf.Clamp(distance / ReferenceDistance, 0.6f, 3f);
            var ls = transform.lossyScale;
            _root.localScale = new Vector3(scale / Mathf.Max(ls.x, 1e-4f), scale / Mathf.Max(ls.y, 1e-4f), scale / Mathf.Max(ls.z, 1e-4f));

            // 이름표와 같은 기준으로 벽 차폐를 본다 — 대부분 벽 뒤면 감추고, 일부만 뒤면 Overlay 로 통째로 그린다.
            // 위치·회전·크기를 다 맞춘 뒤에 재야 이번 프레임의 글자 사각형이 기준이 된다.
            if (Time.frameCount - _occlusionFrame >= OcclusionInterval)
            {
                _occlusionFrame = Time.frameCount;
                _occluded = WorldTextOcclusion.IsMostlyOccluded(cam, _text, transform);
            }
            _renderer.enabled = !_occluded;
        }

        /// <summary>가림 판정 간격(프레임). 이름표(<see cref="WorldNameplate"/>)와 같다.</summary>
        const int OcclusionInterval = 3;
        int _occlusionFrame = -100;
        bool _occluded;

        void OnDestroy()
        {
            if (_text != null && _text.gameObject != null) Destroy(_text.gameObject);
        }
    }
}
