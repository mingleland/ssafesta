using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 머리 위를 따라다니는 이름표 (S15P21A604-355 후속).
    ///
    /// <para><b>대상은 사람뿐이다.</b> 처음에는 부스에도 달았지만, 부스 이름표는 화면을 덮기만
    /// 하고 정보를 주지 못했다 — 어느 부스인지는 간판과 직원으로 이미 알 수 있다. 남는 것은
    /// "저 사람이 누구인가" 하나이고, 그건 다른 방법으로는 알 수 없다.</para>
    ///
    /// <para><b>판 대신 외곽선을 쓴다.</b> 어두운 판을 깔면 읽히긴 하지만 화면이 무거워지고
    /// UI 를 월드에 억지로 붙인 티가 난다. 게임에서 쓰는 방식대로 <b>같은 글자를 어둡게 네 방향
    /// 으로 깔고 그 위에 본 글자</b>를 얹는다 — 배경이 밝든 어둡든 윤곽이 살고, 판이 없어
    /// 가볍다.</para>
    ///
    /// <para><b>화면 기준 크기를 유지한다.</b> 월드 크기로 고정하면 멀어질수록 못 읽는다.
    /// 거리에 비례해 키워 화면상 크기를 대체로 일정하게 만든다 — 게임 이름표가 멀리서도
    /// 읽히는 이유다.</para>
    ///
    /// <para><b>표시 조건</b> — ① 거리 안 ② 카메라 <b>앞쪽</b>(뒤로 지나간 대상까지 그리면
    /// 화면이 이름표로 덮인다) ③ 문구가 비어 있지 않음. 경계에서 딱 끊으면 걷는 동안
    /// 깜빡이므로 끝 구간에서 흐려진다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class WorldNameplate : MonoBehaviour
    {
        [Tooltip("표시할 이름. 비어 있으면 그리지 않는다.")]
        [SerializeField] string _label;

        [Tooltip("이 거리(월드 유닛) 안에서만 보인다.")]
        [SerializeField] float _visibleDistance = 260f;

        [Tooltip("페이드가 시작되는 거리 비율 (0.75 면 75% 지점부터 흐려진다)")]
        [SerializeField, Range(0.3f, 1f)] float _fadeStart = 0.75f;

        [Tooltip("대상 머리 위 여유 (월드 유닛)")]
        [SerializeField] float _headroom = 4.5f;

        [Tooltip("기준 거리에서의 글자 높이(월드 유닛).")]
        [SerializeField] float _baseCharacterHeight = 3.2f;

        [Tooltip("이 거리에서 위 크기 그대로 보인다. 멀면 커지고 가까우면 작아진다(0.6~3배).")]
        [SerializeField] float _referenceDistance = 55f;

        [SerializeField] Color _color = new(1f, 0.98f, 0.92f, 1f);
        [SerializeField] Color _outlineColor = new(0.02f, 0.02f, 0.05f, 1f);

        // 상하좌우 네 방향이면 윤곽이 닫힌다. 여덟 방향은 두 배 비싸면서 눈에 띄게 낫지 않다.
        static readonly Vector2[] OutlineOffsets =
        {
            new(1f, 0f), new(-1f, 0f), new(0f, 1f), new(0f, -1f),
        };

        Transform _root;
        TextMesh _main;
        MeshRenderer _mainRenderer;
        readonly TextMesh[] _outline = new TextMesh[4];
        readonly MeshRenderer[] _outlineRenderers = new MeshRenderer[4];
        float _topY;
        bool _placed;

        public string Label
        {
            get => _label;
            set
            {
                _label = value;
                if (_main == null) return;
                _main.text = value;
                foreach (var o in _outline) if (o != null) o.text = value;
            }
        }

        /// <summary>런타임에 붙이는 쪽(플레이어 등)이 표시 거리를 정할 수 있게 한다.</summary>
        public void SetVisibleDistance(float distance) => _visibleDistance = Mathf.Max(1f, distance);

        void Start() => Build();

        void Build()
        {
            var rootGo = new GameObject("Nameplate");
            _root = rootGo.transform;
            _root.SetParent(transform, false);
            _root.position = transform.position;   // 정확한 높이는 PlaceAboveTop 이 잡는다

            // Unity 6: 런타임 생성 TextMesh 는 폰트를 직접 지정해야 한다. 한글이 들어가므로
            // 프로젝트 폰트를 쓴다 — 내장 폰트로는 네모로 깨진다.
            var font = Resources.Load<Font>("Fonts/MalgunGothicLight");
            if (font == null) font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");

            // 외곽선을 먼저 만든다 — 나중 것이 위에 그려져야 본 글자가 덮이지 않는다.
            for (int i = 0; i < OutlineOffsets.Length; i++)
            {
                _outline[i] = MakeText(font, _outlineColor, 0, "Outline", out _outlineRenderers[i]);
                var o = OutlineOffsets[i];
                _outline[i].transform.localPosition = new Vector3(o.x * 0.068f, o.y * 0.068f, 0.002f);
            }
            _main = MakeText(font, _color, 1, "Text", out _mainRenderer);
        }

        TextMesh MakeText(Font font, Color color, int order, string goName, out MeshRenderer renderer)
        {
            var go = new GameObject(goName);
            go.transform.SetParent(_root, false);

            var tm = go.AddComponent<TextMesh>();
            tm.font = font;
            tm.text = _label;
            tm.anchor = TextAnchor.LowerCenter;
            tm.alignment = TextAlignment.Center;
            tm.color = color;
            // fontSize 를 키우고 characterSize 를 줄이면 같은 크기에서 글자가 선명해진다.
            // 실제 크기는 _root 스케일이 정하므로 여기서는 1 유닛 기준으로 둔다.
            tm.fontSize = 128;
            tm.characterSize = 1f / 128f * 10f;

            renderer = go.GetComponent<MeshRenderer>();
            renderer.sharedMaterial = font.material;
            renderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            renderer.receiveShadows = false;
            renderer.sortingOrder = order;
            return tm;
        }

        /// <summary>
        /// 대상의 렌더 높이를 재서 이름표를 머리 위에 놓는다.
        ///
        /// <para><b>비활성 렌더러를 세면 안 된다.</b> 꺼진 렌더러의 bounds 는 실제 위치를
        /// 반영하지 않아, 섞어 재면 이름표가 대상 안에 파묻힌다 (S15P21A604-355).</para>
        ///
        /// <para>측정은 <b>조립이 끝난 뒤</b>여야 한다 — 아바타는 런타임에 몸이 만들어져
        /// Start 시점엔 렌더러가 없다. 그래서 첫 LateUpdate 에서 한 번 잰다.</para>
        /// </summary>
        void PlaceAboveTop()
        {
            float top = float.MinValue;
            foreach (var r in GetComponentsInChildren<Renderer>(false))   // 활성만
            {
                if (r == null || r.transform.IsChildOf(_root)) continue;  // 자기 글자는 제외
                top = Mathf.Max(top, r.bounds.max.y);
            }
            // 렌더러가 하나도 없으면 사람 키를 기본값으로 쓴다.
            _topY = top > float.MinValue ? top : transform.position.y + 22.4f;
            _placed = true;
        }

        void LateUpdate()
        {
            if (_main == null) return;
            if (!_placed) PlaceAboveTop();

            var cam = Camera.main;
            if (cam == null) { SetVisible(false); return; }

            _root.position = new Vector3(transform.position.x, _topY + _headroom, transform.position.z);

            var toCam = cam.transform.position - _root.position;
            float dist = toCam.magnitude;

            bool inFront = Vector3.Dot(cam.transform.forward, -toCam) > 0f;
            bool show = inFront && dist <= _visibleDistance && !string.IsNullOrEmpty(_label);
            SetVisible(show);
            if (!show) return;

            // 멀어져도 화면에서 비슷한 크기로 읽히게 한다.
            float scale = Mathf.Clamp(dist / Mathf.Max(1f, _referenceDistance), 0.6f, 3f);
            _root.localScale = Vector3.one * (_baseCharacterHeight * scale);

            float fadeFrom = _visibleDistance * _fadeStart;
            float a = dist <= fadeFrom ? 1f
                    : Mathf.Clamp01(1f - (dist - fadeFrom) / (_visibleDistance - fadeFrom));
            var c = _color; c.a = a; _main.color = c;
            var oc = _outlineColor; oc.a = a;
            foreach (var o in _outline) if (o != null) o.color = oc;

            // 빌보드는 Y 축만 돌린다 — 기울기까지 따라가면 글자가 누워 읽기 어렵다.
            var flat = cam.transform.position - _root.position;
            flat.y = 0f;
            if (flat.sqrMagnitude > 0.0001f)
                _root.rotation = Quaternion.LookRotation(-flat.normalized, Vector3.up);
        }

        void SetVisible(bool show)
        {
            if (_mainRenderer != null) _mainRenderer.enabled = show;
            foreach (var r in _outlineRenderers) if (r != null) r.enabled = show;
        }
    }
}
