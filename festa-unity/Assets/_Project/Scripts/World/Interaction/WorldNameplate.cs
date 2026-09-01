using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 월드에 떠 있는 이름표. NPC·다른 사용자·부스가 **누구/무엇인지**를 다가가기 전에 알려준다
    /// (S15P21A604-355 후속).
    ///
    /// <para><b>왜 3D 텍스트인가.</b> 화면 고정 UI 는 대상이 여럿일 때 어느 것을 가리키는지
    /// 알 수 없다. 이름표는 대상 위에 붙어 있어야 의미가 있고, 그러면 월드 공간이어야 한다.
    /// 상호작용 프롬프트(<see cref="InteractPromptUI"/>)는 반대로 "지금 고른 하나"를 말하므로
    /// 화면 중앙에 고정한다 — 둘은 역할이 다르다.</para>
    ///
    /// <para><b>표시 조건</b> — 셋을 모두 만족해야 그린다.
    /// ① 카메라와의 거리가 <see cref="_visibleDistance"/> 안 ② 카메라 <b>앞쪽</b>(뒤로 지나간
    /// 대상까지 그리면 화면이 이름표로 뒤덮인다) ③ 텍스트가 비어 있지 않음.</para>
    ///
    /// <para>거리가 멀수록 흐려진다. 경계에서 딱 끊기면 걷는 동안 이름표가 깜빡인다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class WorldNameplate : MonoBehaviour
    {
        [Tooltip("표시할 이름. 비어 있으면 그리지 않는다.")]
        [SerializeField] string _label;

        [Tooltip("이 거리(월드 유닛) 안에서만 보인다.")]
        [SerializeField] float _visibleDistance = 260f;

        [Tooltip("페이드가 시작되는 거리 비율 (0.7 이면 70% 지점부터 흐려진다)")]
        [SerializeField, Range(0.3f, 1f)] float _fadeStart = 0.7f;

        [Tooltip("대상 머리 위 여유 (월드 유닛)")]
        [SerializeField] float _headroom = 6f;

        [Tooltip("글자 크기 — 월드 유닛 기준 문자 높이")]
        [SerializeField] float _characterHeight = 5.2f;

        [SerializeField] Color _color = new(1f, 0.97f, 0.88f, 1f);

        TextMesh _text;
        MeshRenderer _renderer;
        Transform _backdrop;          // 글자 뒤 어두운 판 — 밝은 배경에서도 읽히게 한다
        MeshRenderer _backdropRenderer;
        static Material s_backdropMat;
        float _topY;

        public string Label
        {
            get => _label;
            set { _label = value; if (_text != null) _text.text = value; }
        }

        /// <summary>런타임에 붙이는 쪽(플레이어 등)이 표시 거리를 정할 수 있게 한다.</summary>
        public void SetVisibleDistance(float distance) => _visibleDistance = Mathf.Max(1f, distance);

        bool _placed;

        void Start() => Build();

        /// <summary>
        /// 대상의 렌더 높이를 재서 이름표를 머리 위에 놓는다.
        ///
        /// <para><b>비활성 렌더러를 세면 안 된다.</b> 축제 슬롯은 결합 메시를 쓰고 원본 부스가
        /// 꺼져 있는데, 꺼진 렌더러의 bounds 는 실제 위치를 반영하지 않는다. 그 값을 섞어
        /// 재는 바람에 이름표가 부스 상단(80)이 아니라 66 에 놓여 **간판 뒤에 파묻혔다**
        /// (S15P21A604-355). 켜져 있는 렌더러만 센다.</para>
        ///
        /// <para>측정은 <b>조립이 끝난 뒤</b>여야 한다 — 직원처럼 런타임에 몸이 만들어지는
        /// 대상은 Start 시점에 아직 렌더러가 없다. 그래서 첫 LateUpdate 에서 한 번 잰다.</para>
        /// </summary>
        void PlaceAboveTop()
        {
            float top = float.MinValue;
            foreach (var r in GetComponentsInChildren<Renderer>(false))   // 활성만
            {
                if (r == null || r == _renderer) continue;                 // 자기 글자는 제외
                top = Mathf.Max(top, r.bounds.max.y);
            }
            // 렌더러가 하나도 없으면 사람 키를 기본값으로 쓴다.
            _topY = top > float.MinValue ? top : transform.position.y + 22.4f;
            _text.transform.position = new Vector3(
                transform.position.x, _topY + _headroom, transform.position.z);
            _placed = true;
        }

        void Build()
        {
            var go = new GameObject("Nameplate");
            go.transform.SetParent(transform, false);
            go.transform.position = transform.position;   // 정확한 높이는 PlaceAboveTop 이 잡는다

            _text = go.AddComponent<TextMesh>();
            // Unity 6: 런타임 생성 TextMesh 는 폰트를 직접 지정해야 한다 (미지정 시 글리치).
            // 한글이 있으므로 프로젝트 폰트를 쓴다 — 내장 폰트로는 네모로 깨진다.
            var font = Resources.Load<Font>("Fonts/MalgunGothicLight");
            _text.font = font != null ? font : Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");
            _text.text = _label;
            _text.anchor = TextAnchor.LowerCenter;
            _text.alignment = TextAlignment.Center;
            _text.color = _color;
            // characterSize 는 폰트 크기와 함께 실제 크기를 정한다. fontSize 를 키우고
            // characterSize 를 줄이면 같은 크기에서 글자가 선명해진다.
            _text.fontSize = 64;
            _text.characterSize = _characterHeight / 64f * 10f;

            _renderer = go.GetComponent<MeshRenderer>();
            _renderer.sharedMaterial = _text.font.material;
            _renderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            _renderer.receiveShadows = false;
            // 글자는 항상 앞에 그린다 — 배경판과 z-fighting 하지 않게 렌더 순서를 올린다.
            _renderer.sortingOrder = 1;

            BuildBackdrop(go.transform);
        }

        /// <summary>
        /// 글자 뒤 어두운 판. 야간 축제존은 배경이 화려해서 흰 글자만으로는 묻힌다 —
        /// 게임에서 이름표에 늘 판을 까는 이유다.
        /// </summary>
        void BuildBackdrop(Transform parent)
        {
            var go = new GameObject("Backdrop");
            go.transform.SetParent(parent, false);
            var quad = GameObject.CreatePrimitive(PrimitiveType.Quad);
            Destroy(quad.GetComponent<Collider>());
            quad.transform.SetParent(go.transform, false);
            quad.name = "Panel";

            if (s_backdropMat == null)
            {
                var sh = Shader.Find("Universal Render Pipeline/Unlit");
                s_backdropMat = new Material(sh) { color = new Color(0.05f, 0.05f, 0.07f, 0.62f) };
                // 반투명으로 그린다 — 불투명이면 검은 판이 배경을 가린다.
                s_backdropMat.SetFloat("_Surface", 1f);
                s_backdropMat.SetFloat("_Blend", 0f);
                s_backdropMat.renderQueue = 3000;
                s_backdropMat.SetColor("_BaseColor", new Color(0.05f, 0.05f, 0.07f, 0.62f));
            }
            _backdropRenderer = quad.GetComponent<MeshRenderer>();
            _backdropRenderer.sharedMaterial = s_backdropMat;
            _backdropRenderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            _backdropRenderer.receiveShadows = false;
            _backdrop = go.transform;
        }

        /// <summary>글자 크기에 맞춰 배경판을 맞춘다. 글자는 내용에 따라 폭이 달라진다.</summary>
        void FitBackdrop()
        {
            if (_backdrop == null || _renderer == null) return;
            var b = _renderer.bounds;
            float w = b.size.x + _characterHeight * 0.9f;
            float h = b.size.y + _characterHeight * 0.5f;
            var panel = _backdrop.GetChild(0);
            panel.localScale = new Vector3(w, h, 1f);
            // 글자 앵커가 LowerCenter 라 중심이 위로 반 칸 올라간다.
            panel.localPosition = new Vector3(0f, h * 0.5f - _characterHeight * 0.12f, 0.15f);
        }

        void LateUpdate()
        {
            if (_text == null) return;
            if (!_placed) PlaceAboveTop();

            var cam = Camera.main;
            if (cam == null) { _renderer.enabled = false; return; }

            var toCam = cam.transform.position - _text.transform.position;
            float dist = toCam.magnitude;

            // 카메라 뒤쪽 대상은 그리지 않는다 — 지나온 부스 이름표까지 남으면 화면이 덮인다.
            bool inFront = Vector3.Dot(cam.transform.forward, -toCam) > 0f;
            bool show = inFront && dist <= _visibleDistance && !string.IsNullOrEmpty(_label);
            _renderer.enabled = show;
            if (_backdropRenderer != null) _backdropRenderer.enabled = show;
            if (!show) return;
            FitBackdrop();

            // 경계에서 딱 끊으면 걷는 동안 깜빡인다 — 끝 구간에서 흐려지게 한다.
            float fadeFrom = _visibleDistance * _fadeStart;
            float a = dist <= fadeFrom ? 1f
                    : Mathf.Clamp01(1f - (dist - fadeFrom) / (_visibleDistance - fadeFrom));
            var c = _color; c.a *= a;
            _text.color = c;
            if (_backdropRenderer != null)
            {
                var bc = new Color(0.05f, 0.05f, 0.07f, 0.62f * a);
                _backdropRenderer.material.SetColor("_BaseColor", bc);
            }

            // 카메라를 향해 세운다(빌보드). 기울기까지 따라가면 글자가 누워 읽기 어렵다 —
            // Y 축만 돌린다.
            var flat = cam.transform.position - _text.transform.position;
            flat.y = 0f;
            if (flat.sqrMagnitude > 0.0001f)
                _text.transform.rotation = Quaternion.LookRotation(-flat.normalized, Vector3.up);
        }
    }
}
