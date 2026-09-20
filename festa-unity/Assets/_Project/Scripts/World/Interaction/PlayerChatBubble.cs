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
    /// <para><b>말풍선이 이름표를 대신한다.</b> 헤더에 닉네임을 넣고 표시 중에는 기존 이름표를 숨긴다.
    /// 그래서 둘을 세로로 쌓지 않아도 되고 말풍선을 캐릭터 가까이에 낮게 둘 수 있다.</para>
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

        /// <summary>정수리에서 말풍선 하단까지의 간격. 기존 7.5u에서 낮췄다 — 이름표는 표시 중 숨긴다.</summary>
        const float Headroom = 1.4f;

        /// <summary>캡슐을 못 찾았을 때 쓸 키(월드 유닛). 이 씬의 아바타 목표 높이가 22.375 다.</summary>
        const float FallbackHeight = 22.4f;

        const float MinSeconds = 3f;
        const float MaxSeconds = 7f;
        const float SecondsPerCharacter = 0.09f;

        /// <summary>이 거리에서 기준 크기로 보인다. 멀면 키우고 가까우면 줄여 화면상 크기를 유지한다.</summary>
        const float ReferenceDistance = 55f;
        const float BaseFontWorldScale = 1.15f;

        /// <summary>배경판 셰이더. Resources 아래에 둬야 WebGL 빌드에서 스트립되지 않는다 (T-227).</summary>
        const string PanelShaderResourcePath = "Shaders/WorldChatPanel";

        /// <summary>글자 사각형 바깥으로 둘 여백(월드 유닛). 글자가 판에 닿아 보이지 않게 한다.</summary>
        const float PadX = 1.9f;
        const float PadY = 1.2f;

        /// <summary>모서리 반지름. 판 높이의 절반을 넘지 않게 안에서 한 번 더 자른다.</summary>
        const float CornerRadius = 1.15f;

        /// <summary>판을 글자보다 살짝 뒤에 둔다. 깊이 검사는 Always 라 그리는 순서가 정본이고, 이건 보조다.</summary>
        const float PanelDepth = 0.05f;

        TextMeshPro _text;
        RectTransform _root;
        MeshRenderer _renderer;
        PlayerNameplate _nameplate;
        MeshRenderer _panelRenderer;
        Mesh _panelMesh;
        Material _panelMaterial;
        /// <summary>글자 사각형의 중심(로컬). 말풍선을 머리 위 가운데로 되돌리는 데 쓴다.</summary>
        Vector2 _panelCenter;
        float _hideAt;
        float _height = FallbackHeight;

        /// <summary>정수리 높이를 이미 재고 있는 이름표. 말풍선은 같은 기준을 공유한다.</summary>
        WorldNameplate _plateAnchor;

        /// <summary>
        /// 말풍선을 띄운다. 대상에 컴포넌트가 없으면 붙여서 쓴다 — 프리팹을 고치지 않아도
        /// 원격 아바타를 포함한 모든 플레이어에 적용된다.
        /// </summary>
        public static void Show(GameObject target, string nickname, string message)
        {
            if (target == null || string.IsNullOrWhiteSpace(message)) return;
            var bubble = target.GetComponent<PlayerChatBubble>();
            if (bubble == null) bubble = target.AddComponent<PlayerChatBubble>();
            bubble.Say(nickname, message);
        }

        void Say(string nickname, string message)
        {
            if (_text == null) Build();
            if (_text == null) return;

            var trimmed = message.Trim();
            var shownName = string.IsNullOrWhiteSpace(nickname) ? _nameplate?.DisplayLabel : nickname.Trim();
            if (string.IsNullOrWhiteSpace(shownName)) shownName = "Player";
            // 채팅 문자열을 TMP 태그로 해석하지 않는다. 사용자가 크기·색·스프라이트 태그를 넣어
            // 다른 사람 화면을 덮는 것을 막고, 이 컴포넌트가 만든 스타일 태그만 허용한다.
            shownName = EscapeRichText(shownName);
            trimmed = EscapeRichText(trimmed);
            // 배경은 <mark> 가 아니라 뒤에 깔린 둥근 판이다. mark 는 글자 줄마다 딱 붙는 형광펜이라
            // 여백도 둥근 모서리도 만들 수 없고, 헤더와 본문의 상자 폭이 따로 놀아 계단처럼 보였다
            // (사용자 지적 2026-09-17).
            _text.text = "<color=#C6E45C><b>" + shownName + "</b></color>\n"
                       + "<color=#F4F5F2>" + trimmed + "</color>";
            _hideAt = Time.time + Mathf.Clamp(MinSeconds + trimmed.Length * SecondsPerCharacter, MinSeconds, MaxSeconds);
            ResizePanel();
            SetVisible(true);
            if (_nameplate != null) _nameplate.SetChatBubbleVisible(true);
        }

        static string EscapeRichText(string value)
            => value.Replace("<", "＜").Replace(">", "＞");

        /// <summary>글자와 배경판을 함께 켜고 끈다 — 둘은 서로 다른 렌더러라 한쪽만 끄면 판만 남는다.</summary>
        void SetVisible(bool visible)
        {
            if (_renderer != null) _renderer.enabled = visible;
            if (_panelRenderer != null) _panelRenderer.enabled = visible;
        }

        /// <summary>
        /// 글자가 실제로 차지한 사각형에 맞춰 판을 다시 만든다.
        ///
        /// <para><see cref="TMP_Text.textBounds"/> 는 메시가 만들어진 뒤에야 맞는 값이라
        /// <see cref="TMP_Text.ForceMeshUpdate"/> 를 먼저 부른다. 이걸 빼면 첫 말풍선이 옛 문장 크기로 그려진다.</para>
        /// </summary>
        void ResizePanel()
        {
            if (_panelMesh == null || _text == null) return;
            _text.ForceMeshUpdate();
            var b = _text.textBounds;
            if (b.size.x < 1e-3f || b.size.y < 1e-3f) return;

            _panelCenter = new Vector2(b.center.x, b.center.y);
            if (_panelRenderer != null)
                _panelRenderer.transform.localPosition = new Vector3(_panelCenter.x, _panelCenter.y, PanelDepth);
            BuildRoundedRect(_panelMesh, b.size.x + PadX * 2f, b.size.y + PadY * 2f, CornerRadius);
        }

        /// <summary>
        /// 둥근 사각형 메시를 (재)생성한다. 텍스처가 아니라 기하라서 가까이 가도 모서리가 뭉개지지 않는다.
        ///
        /// <para>바깥으로 한 겹을 더 두르고 그 알파를 0 으로 둔다 — 하드웨어 AA 없이도 가장자리가
        /// 계단지지 않는다. 안쪽은 중심에서 부채꼴로 채운다.</para>
        /// </summary>
        static void BuildRoundedRect(Mesh mesh, float width, float height, float radius)
        {
            const int Seg = 5;            // 모서리당 분할 — 4모서리 24점이면 이 크기에서 각이 안 보인다
            const float Feather = 0.14f;  // 알파 0 으로 빠지는 바깥 테두리 두께

            radius = Mathf.Min(radius, Mathf.Min(width, height) * 0.5f);
            float hw = width * 0.5f - radius;
            float hh = height * 0.5f - radius;
            var corners = new Vector2[] { new(hw, hh), new(-hw, hh), new(-hw, -hh), new(hw, -hh) };

            int n = 4 * (Seg + 1);
            var verts = new Vector3[1 + n * 2];
            var cols = new Color[verts.Length];
            verts[0] = Vector3.zero;
            cols[0] = Color.white;

            int i = 0;
            for (int k = 0; k < 4; k++)
            {
                for (int s = 0; s <= Seg; s++)
                {
                    float a = (k * 90f + 90f * s / Seg) * Mathf.Deg2Rad;
                    var nrm = new Vector2(Mathf.Cos(a), Mathf.Sin(a));
                    var p = corners[k] + nrm * radius;
                    verts[1 + i] = new Vector3(p.x, p.y, 0f);
                    cols[1 + i] = Color.white;
                    verts[1 + n + i] = new Vector3(p.x + nrm.x * Feather, p.y + nrm.y * Feather, 0f);
                    cols[1 + n + i] = new Color(1f, 1f, 1f, 0f);
                    i++;
                }
            }

            var tris = new int[n * 9];
            int t = 0;
            for (int k = 0; k < n; k++)
            {
                int a0 = 1 + k, b0 = 1 + (k + 1) % n;
                tris[t++] = 0; tris[t++] = a0; tris[t++] = b0;
            }
            for (int k = 0; k < n; k++)
            {
                int a0 = 1 + k, b0 = 1 + (k + 1) % n;
                int a1 = 1 + n + k, b1 = 1 + n + (k + 1) % n;
                tris[t++] = a0; tris[t++] = a1; tris[t++] = b1;
                tris[t++] = a0; tris[t++] = b1; tris[t++] = b0;
            }

            mesh.Clear();
            mesh.vertices = verts;
            mesh.colors = cols;
            mesh.triangles = tris;
            mesh.RecalculateBounds();
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

            _text.alignment = TextAlignmentOptions.BottomLeft;   // 레퍼런스처럼 닉네임·본문을 왼쪽에 맞춘다
            _text.textWrappingMode = TextWrappingModes.Normal;   // 긴 문장이 화면을 가로지르지 않게 접는다
            _text.overflowMode = TextOverflowModes.Overflow;
            _text.color = new Color(1f, 1f, 1f, 1f);
            _text.fontSize = 9f;                                  // 닉네임 헤더 + 본문 2줄을 한 패널에 담는다
            _text.richText = true;
            _text.lineSpacing = 12f;                              // 헤더와 본문이 붙어 보이지 않게 한 줄 간격을 벌린다
            _root.sizeDelta = new Vector2(28f, 6.5f);
            _root.pivot = new Vector2(0.5f, 0f);

            var material = _text.fontMaterial;                    // 공유본을 건드리면 UI 글자까지 물든다
            WorldTextOcclusion.ApplyOverlayShader(material, "ChatBubble");   // 이름표와 같이 벽 위에 그린다 — 반 토막 방지
            material.EnableKeyword("OUTLINE_ON");
            // 판이 뒤를 받쳐 주므로 외곽선을 얇게 — 두꺼우면 글자가 뭉개져 보인다.
            material.SetFloat(ShaderUtilities.ID_OutlineWidth, 0.12f);
            material.SetColor(ShaderUtilities.ID_OutlineColor, new Color(0.02f, 0.02f, 0.04f, 1f));
            material.SetFloat(ShaderUtilities.ID_FaceDilate, 0.1f);

            _renderer = go.GetComponent<MeshRenderer>();
            _renderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            _renderer.receiveShadows = false;
            _renderer.enabled = false;
            _nameplate = GetComponent<PlayerNameplate>();
            _plateAnchor = GetComponent<WorldNameplate>();

            var controller = GetComponent<CharacterController>();
            if (controller != null) _height = controller.height;
            else
            {
                var capsule = GetComponent<CapsuleCollider>();
                if (capsule != null) _height = capsule.height;
            }

            BuildPanel();
        }

        /// <summary>글자 뒤에 깔릴 둥근 판을 만든다. 글자보다 <b>먼저</b> 그려야 하므로 큐를 한 칸 앞에 둔다.</summary>
        void BuildPanel()
        {
            var shader = Resources.Load<Shader>(PanelShaderResourcePath);
            if (shader == null) shader = Shader.Find("Festa/WorldChatPanel");
            if (shader == null)
            {
                // 조용히 넘어가지 않는다 — 판 없이 글자만 뜨면 "원래 저런 디자인" 으로 오해된다 (T-24).
                Debug.LogError($"[PlayerChatBubble] Resources/{PanelShaderResourcePath}.shader 를 찾지 못했다 — " +
                               "말풍선 배경판 없이 글자만 그린다.");
                return;
            }

            var go = new GameObject("Panel");
            go.transform.SetParent(_root, false);

            _panelMesh = new Mesh { name = "ChatBubblePanel" };
            _panelMesh.MarkDynamic();                       // 말할 때마다 크기가 바뀐다
            go.AddComponent<MeshFilter>().sharedMesh = _panelMesh;

            _panelMaterial = new Material(shader);
            _panelMaterial.renderQueue = _text.fontMaterial.renderQueue - 1;

            _panelRenderer = go.AddComponent<MeshRenderer>();
            _panelRenderer.sharedMaterial = _panelMaterial;
            _panelRenderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            _panelRenderer.receiveShadows = false;
            _panelRenderer.enabled = false;
        }

        void LateUpdate()
        {
            if (_renderer == null) return;

            if (Time.time >= _hideAt)
            {
                SetVisible(false);
                if (_nameplate != null) _nameplate.SetChatBubbleVisible(false);
                return;
            }

            var cam = Camera.main;
            if (cam == null) { SetVisible(false); return; }

            // 높이는 **이름표가 재는 정수리**를 그대로 쓴다. 캡슐 높이로 재면 앉거나 누워도 값이 그대로라
            // 말풍선만 선 키 위 허공에 남는다(2026-09-20 지적). 이름표는 머리 본을 따라가므로 자세가 바뀌면 같이 내려온다.
            float topY = transform.position.y + _height;
            if (_plateAnchor == null) _plateAnchor = GetComponent<WorldNameplate>();
            if (_plateAnchor != null && _plateAnchor.TryGetPostureTopY(out var postureTopY)) topY = postureTopY;
            var anchor = new Vector3(transform.position.x, topY + Headroom, transform.position.z);
            var toCamera = cam.transform.position - anchor;

            // 카메라 뒤로 지나간 대상까지 그리면 화면이 말풍선으로 덮인다.
            // 여기서 끈 것은 "이번 프레임만" 이다 — 표시 시간이 남아 있으면 다음 프레임에 다시 판정한다.
            // (예전에는 한 번 꺼지면 LateUpdate 첫 줄에서 빠져 시간이 남아도 영영 안 켜졌다.)
            if (Vector3.Dot(cam.transform.forward, anchor - cam.transform.position) <= 0f) { SetVisible(false); return; }

            float distance = toCamera.magnitude;
            if (distance > VisibleDistance) { SetVisible(false); return; }

            _root.rotation = Quaternion.LookRotation(anchor - cam.transform.position, Vector3.up);

            // 화면상 크기를 대체로 일정하게 — 멀면 키우고 가까우면 줄인다.
            float scale = BaseFontWorldScale * Mathf.Clamp(distance / ReferenceDistance, 0.6f, 3f);
            var ls = transform.lossyScale;
            _root.localScale = new Vector3(scale / Mathf.Max(ls.x, 1e-4f), scale / Mathf.Max(ls.y, 1e-4f), scale / Mathf.Max(ls.z, 1e-4f));

            // 글자를 왼쪽 정렬로 두면 글자 뭉치가 앵커 오른쪽으로 쏠린다. 판 중심만큼 되밀어
            // **말풍선 자체**가 머리 위 가운데에 오게 한다 — 회전·배율을 정한 뒤라야 방향이 맞다.
            _root.position = anchor - _root.right * (_panelCenter.x * scale);

            // 이름표와 같은 기준으로 벽 차폐를 본다 — 대부분 벽 뒤면 감추고, 일부만 뒤면 Overlay 로 통째로 그린다.
            // 위치·회전·크기를 다 맞춘 뒤에 재야 이번 프레임의 글자 사각형이 기준이 된다.
            if (Time.frameCount - _occlusionFrame >= OcclusionInterval)
            {
                _occlusionFrame = Time.frameCount;
                _occluded = WorldTextOcclusion.IsMostlyOccluded(cam, _text, transform);
            }
            SetVisible(!_occluded);
        }

        /// <summary>가림 판정 간격(프레임). 이름표(<see cref="WorldNameplate"/>)와 같다.</summary>
        const int OcclusionInterval = 3;
        int _occlusionFrame = -100;
        bool _occluded;

        void OnDestroy()
        {
            if (_nameplate != null) _nameplate.SetChatBubbleVisible(false);
            if (_panelMesh != null) Destroy(_panelMesh);
            if (_panelMaterial != null) Destroy(_panelMaterial);
            if (_text != null && _text.gameObject != null) Destroy(_text.gameObject);
        }
    }
}
