using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 플레이어 머리 위를 따라다니는 닉네임 이름표 (S15P21A604-355 후속).
    ///
    /// <para><b>대상은 사람뿐이다.</b> 부스에도 달아 봤지만 부스 이름표는 화면만 덮었다 —
    /// 어느 부스인지는 간판과 직원으로 이미 알 수 있다. 다른 방법으로 알 수 없는 것은
    /// "저 사람이 누구인가" 하나뿐이다.</para>
    ///
    /// <para><b>판 대신 외곽선과 그림자를 쓴다.</b> 어두운 판을 깔면 읽히기는 하지만 화면이
    /// 무거워지고 UI 를 월드에 억지로 붙인 티가 난다. 게임에서 쓰는 방식대로
    /// <b>여덟 방향 외곽선 + 아래쪽 그림자 + 볼드 본문</b>으로 쌓는다 — 배경이 밝든 어둡든
    /// 윤곽이 살고 글자에 무게가 생긴다.</para>
    ///
    /// <para><b>볼드는 합성이다.</b> 프로젝트 폰트가 Light 한 벌뿐이라 그대로 쓰면 가늘어서
    /// 야간 축제존 배경에 묻힌다 (S15P21A604-355 사용자 지적). 폰트가 Dynamic 으로
    /// 임포트돼 있어 <see cref="FontStyle.Bold"/> 를 주면 Unity 가 굵기를 만들어 준다 —
    /// 시스템 폰트를 저장소에 새로 들이지 않고 해결한다.</para>
    ///
    /// <para><b>화면 기준 크기를 유지한다.</b> 월드 크기로 고정하면 멀어질수록 못 읽는다.
    /// 거리에 비례해 키워(0.6~3배) 화면상 크기를 대체로 일정하게 만든다.</para>
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

        [Tooltip("머리 위 간격 — 이름표 자체 크기의 배수다. 월드 고정값이 아니라 비율이라야 "
               + "거리가 변해도 화면상 간격이 유지된다.")]
        [SerializeField, Range(0.05f, 1f)] float _headroomRatio = 0.38f;

        [Tooltip("기준 거리에서의 글자 크기(월드 유닛).")]
        [SerializeField] float _baseCharacterHeight = 1.7f;

        [Tooltip("이 거리에서 위 크기 그대로 보인다. 멀면 커지고 가까우면 작아진다(0.6~3배).")]
        [SerializeField] float _referenceDistance = 55f;

        [SerializeField] Color _color = new(1f, 0.99f, 0.95f, 1f);
        [SerializeField] Color _outlineColor = new(0.03f, 0.03f, 0.06f, 1f);

        [Tooltip("외곽선 두께 — 글자 크기 대비 비율")]
        [SerializeField, Range(0.02f, 0.2f)] float _outlineWidth = 0.075f;

        // 여덟 방향이라야 윤곽이 둥글게 닫힌다. 네 방향만 쓰면 대각선 모서리가 비어
        // 글자가 배경에 물린 것처럼 보인다. 대각선은 0.72 배라야 반경이 고르다.
        static readonly Vector2[] OutlineOffsets =
        {
            new(1f, 0f), new(-1f, 0f), new(0f, 1f), new(0f, -1f),
            new(0.72f, 0.72f), new(-0.72f, 0.72f), new(0.72f, -0.72f), new(-0.72f, -0.72f),
        };

        Transform _root;
        TextMesh _main, _shadow;
        MeshRenderer _mainRenderer, _shadowRenderer;
        readonly TextMesh[] _outline = new TextMesh[8];
        readonly MeshRenderer[] _outlineRenderers = new MeshRenderer[8];
        float _topY;
        bool _measured;

        // 머리 본과 정수리 사이의 거리. 한 번 재 두면 자세가 바뀌어도 유효하다 —
        // 본이 움직이면 이름표도 따라 움직인다.
        Transform _headBone;
        float _headToTop;

        public string Label
        {
            get => _label;
            set
            {
                _label = value;
                if (_main == null) return;
                _main.text = value;
                if (_shadow != null) _shadow.text = value;
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
            _root.position = transform.position;   // 정확한 높이는 LateUpdate 가 잡는다

            // Unity 6: 런타임 생성 TextMesh 는 폰트를 직접 지정해야 한다. 한글이 들어가므로
            // 프로젝트 폰트를 쓴다 — 내장 폰트로는 네모로 깨진다.
            var font = Resources.Load<Font>("Fonts/MalgunGothicLight");
            if (font == null) font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");

            // 뒤에서 앞으로 쌓는다: 그림자 → 외곽선 → 본문. 같은 폰트 머티리얼을 공유하므로
            // 조각이 열 개라도 배칭돼 드로우콜은 이름표 수에 비례한다.
            _shadow = MakeText(font, new Color(0f, 0f, 0f, 0.5f), -1, "Shadow", out _shadowRenderer);
            _shadow.transform.localPosition = new Vector3(0f, -_outlineWidth * 2.2f, 0.004f);

            for (int i = 0; i < OutlineOffsets.Length; i++)
            {
                _outline[i] = MakeText(font, _outlineColor, 0, "Outline", out _outlineRenderers[i]);
                var o = OutlineOffsets[i];
                _outline[i].transform.localPosition =
                    new Vector3(o.x * _outlineWidth, o.y * _outlineWidth, 0.002f);
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
            tm.fontStyle = FontStyle.Bold;   // 합성 볼드 — 위 클래스 주석 참조
            // fontSize 를 키우고 characterSize 를 줄이면 같은 크기에서 글자가 선명해진다.
            // 실제 크기는 _root 스케일이 정하므로 여기서는 1 유닛 기준으로 둔다.
            tm.fontSize = 128;
            tm.characterSize = 10f / 128f;

            renderer = go.GetComponent<MeshRenderer>();
            renderer.sharedMaterial = font.material;
            renderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            renderer.receiveShadows = false;
            renderer.sortingOrder = order;
            return tm;
        }

        /// <summary>
        /// 정수리 높이를 재고, 가능하면 <b>머리 본</b>에 물린다.
        ///
        /// <para><b>왜 본에 물리나.</b> 높이를 한 번만 재서 고정하면 앉는 순간 이름표가
        /// 선 키 그대로 허공에 남는다 (S15P21A604-355 사용자 지적). 스킨메시의
        /// <c>renderer.bounds</c> 는 바인드포즈 골격 범위라 자세가 바뀌어도 변하지 않아
        /// 매 프레임 다시 재도 소용이 없다. 머리 본은 자세를 그대로 따라가고 읽는 비용이
        /// 없다 — 정수리와의 거리만 한 번 재 두면 어떤 자세든 맞는다.</para>
        ///
        /// <para>정수리는 <c>BakeMesh</c> 로 현재 포즈를 구워서 잰다. 이때
        /// <c>RecalculateBounds</c> 를 반드시 불러야 한다 — 굽기만 하면 bounds 가
        /// 부풀린 바인드포즈 값 그대로다.</para>
        ///
        /// <para>측정은 <b>조립이 끝난 뒤</b>여야 한다 — 아바타는 런타임에 몸이 만들어져
        /// Start 시점엔 렌더러가 없다. 그래서 첫 LateUpdate 에서 한 번 잰다.</para>
        /// </summary>
        void MeasureTop()
        {
            float top = float.MinValue;
            foreach (var r in GetComponentsInChildren<Renderer>(false))   // 활성만
            {
                if (r == null || r.transform.IsChildOf(_root)) continue;  // 자기 글자는 제외

                if (r is SkinnedMeshRenderer skinned && skinned.sharedMesh != null)
                {
                    var baked = new Mesh();
                    skinned.BakeMesh(baked);
                    baked.RecalculateBounds();
                    var b = baked.bounds;
                    Destroy(baked);
                    // BakeMesh 는 스케일까지 적용해 굽는다 — 위치와 회전만 더한다.
                    var c = b.center; var e = b.extents;
                    for (int x = -1; x <= 1; x += 2)
                    for (int y = -1; y <= 1; y += 2)
                    for (int z = -1; z <= 1; z += 2)
                        top = Mathf.Max(top, (skinned.transform.position
                            + skinned.transform.rotation * (c + Vector3.Scale(e, new Vector3(x, y, z)))).y);
                    continue;
                }
                top = Mathf.Max(top, r.bounds.max.y);
            }
            // 렌더러가 하나도 없으면 사람 키를 기본값으로 쓴다.
            _topY = top > float.MinValue ? top : transform.position.y + 22.4f;

            var animator = GetComponentInChildren<Animator>();
            if (animator != null && animator.isHuman)
            {
                _headBone = animator.GetBoneTransform(HumanBodyBones.Head);
                if (_headBone != null) _headToTop = _topY - _headBone.position.y;
            }
            _measured = true;
        }

        /// <summary>지금 자세의 정수리 높이. 머리 본이 있으면 자세를 따라간다.</summary>
        float CurrentTopY() => _headBone != null ? _headBone.position.y + _headToTop : _topY;

        void LateUpdate()
        {
            if (_main == null) return;
            if (!_measured) MeasureTop();

            var cam = Camera.main;
            if (cam == null) { SetVisible(false); return; }

            // 멀어져도 화면에서 비슷한 크기로 읽히게 한다.
            float camDist = Vector3.Distance(cam.transform.position, transform.position);
            float scale = Mathf.Clamp(camDist / Mathf.Max(1f, _referenceDistance), 0.6f, 3f);
            float size = _baseCharacterHeight * scale;
            _root.localScale = Vector3.one * size;

            // 앵커가 LowerCenter 라 글자는 이 지점 **위로** 그려진다. 간격을 월드 고정값으로
            // 두면 이름표가 커질수록 실제 공백이 벌어져 머리 위에 붕 뜬다 — 처음에 4.5u 를
            // 박아 둬서 머리에서 한 뼘 넘게 떨어져 보였다 (S15P21A604-355 사용자 지적).
            // 이름표 자체 크기에 비례시켜 화면상 간격을 일정하게 만든다.
            _root.position = new Vector3(
                transform.position.x, CurrentTopY() + size * _headroomRatio, transform.position.z);

            var toCam = cam.transform.position - _root.position;
            float dist = toCam.magnitude;

            bool inFront = Vector3.Dot(cam.transform.forward, -toCam) > 0f;
            bool show = inFront && dist <= _visibleDistance && !string.IsNullOrEmpty(_label);
            SetVisible(show);
            if (!show) return;

            float fadeFrom = _visibleDistance * _fadeStart;
            float a = dist <= fadeFrom ? 1f
                    : Mathf.Clamp01(1f - (dist - fadeFrom) / (_visibleDistance - fadeFrom));
            var c = _color; c.a = a; _main.color = c;
            var oc = _outlineColor; oc.a = a;
            foreach (var o in _outline) if (o != null) o.color = oc;
            if (_shadow != null) _shadow.color = new Color(0f, 0f, 0f, 0.5f * a);

            // 빌보드는 Y 축만 돌린다 — 기울기까지 따라가면 글자가 누워 읽기 어렵다.
            var flat = cam.transform.position - _root.position;
            flat.y = 0f;
            if (flat.sqrMagnitude > 0.0001f)
                _root.rotation = Quaternion.LookRotation(-flat.normalized, Vector3.up);
        }

        void SetVisible(bool show)
        {
            if (_mainRenderer != null) _mainRenderer.enabled = show;
            if (_shadowRenderer != null) _shadowRenderer.enabled = show;
            foreach (var r in _outlineRenderers) if (r != null) r.enabled = show;
        }
    }
}
