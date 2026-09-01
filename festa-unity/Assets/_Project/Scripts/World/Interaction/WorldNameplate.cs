using TMPro;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 플레이어 머리 위를 따라다니는 닉네임 이름표 (S15P21A604-355).
    ///
    /// <para><b>대상은 사람뿐이다.</b> 부스에도 달아 봤지만 부스 이름표는 화면만 덮었다 —
    /// 어느 부스인지는 간판과 직원으로 이미 알 수 있다. 다른 방법으로 알 수 없는 것은
    /// "저 사람이 누구인가" 하나뿐이다.</para>
    ///
    /// <para><b>TextMeshPro(SDF) 를 쓴다.</b> 처음에는 레거시 <see cref="TextMesh"/> 에
    /// 글자를 여러 겹 겹쳐 외곽선을 흉내 냈는데, 비트맵 글리프를 월드에서 확대하는 방식이라
    /// 가까이 갈수록 뭉개져 "눈이 아프다" 는 지적을 받았다. SDF 는 거리장으로 윤곽을 계산해
    /// 어떤 크기에서도 가장자리가 날카롭고, 외곽선·그림자를 <b>셰이더가 한 번에</b> 그린다 —
    /// 겹쳐 그리던 조각 열 개가 하나로 줄어 드로우콜도 함께 준다.</para>
    ///
    /// <para><b>본인과 남을 색으로 나눈다.</b> 이름을 읽어야 내 캐릭터를 찾을 수 있으면
    /// 이름표가 제 역할을 못 한다. 색은 <see cref="PlayerNameplate"/> 가 정한다.</para>
    ///
    /// <para><b>화면 기준 크기를 유지한다.</b> 월드 크기로 고정하면 멀어질수록 못 읽는다.
    /// 거리에 비례해 키워(0.6~3배) 화면상 크기를 대체로 일정하게 만든다.</para>
    ///
    /// <para><b>표시 조건</b> — ① 거리 안 ② 카메라 <b>앞쪽</b>(뒤로 지나간 대상까지 그리면
    /// 화면이 이름표로 덮인다) ③ 문구가 비어 있지 않음. <b>반투명 단계는 두지 않는다</b> —
    /// 알파를 낮추면 획이 촘촘한 한글이 뭉개져 흐려 보인다. 보이거나 안 보이거나 둘 중
    /// 하나다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class WorldNameplate : MonoBehaviour
    {
        /// <summary>SDF 폰트 경로. 한글이라 프로젝트 폰트로 구운 것을 쓴다.</summary>
        const string FontResourcePath = "Fonts/MalgunGothic_SDF";

        [Tooltip("표시할 이름. 비어 있으면 그리지 않는다.")]
        [SerializeField] string _label;

        [Tooltip("이 거리(월드 유닛) 안에서만 보인다.")]
        [SerializeField] float _visibleDistance = 260f;


        [Tooltip("머리 위 간격 — 이름표 자체 크기의 배수다. 월드 고정값이 아니라 비율이라야 "
               + "거리가 변해도 화면상 간격이 유지된다.")]
        [SerializeField, Range(0.05f, 1f)] float _headroomRatio = 0.85f;

        [Tooltip("기준 거리에서의 글자 크기(월드 유닛).")]
        [SerializeField] float _baseCharacterHeight = 1.35f;

        [Tooltip("이 거리에서 위 크기 그대로 보인다. 멀면 커지고 가까우면 작아진다(0.6~3배).")]
        [SerializeField] float _referenceDistance = 55f;

        [SerializeField] Color _color = new(1f, 0.99f, 0.95f, 1f);

        [Tooltip("외곽선 두께 (SDF 비율, 0~1). 0.25 를 넘기면 획 사이가 메워진다.")]
        [SerializeField, Range(0f, 0.35f)] float _outlineWidth = 0.2f;

        [SerializeField] Color _outlineColor = new(0.02f, 0.02f, 0.04f, 1f);

        [Tooltip("글자 획 굵기 (SDF face dilate). 프로젝트 폰트가 Light 한 벌뿐이라 이걸로 살찌운다.")]
        [SerializeField, Range(-0.3f, 0.4f)] float _faceDilate = 0.22f;

        Transform _root;
        TextMeshPro _text;
        MeshRenderer _renderer;
        Material _material;          // 인스턴스 — 색·외곽선을 이름표마다 따로 준다
        float _topY;
        bool _measured;

        // 머리 본과 정수리 사이의 거리. 한 번 재 두면 자세가 바뀌어도 유효하다 —
        // 본이 움직이면 이름표도 따라 움직인다.
        Transform _headBone;
        float _headToTop;

        public string Label
        {
            get => _label;
            set { _label = value; if (_text != null) _text.text = value; }
        }

        /// <summary>런타임에 붙이는 쪽(플레이어 등)이 표시 거리를 정할 수 있게 한다.</summary>
        public void SetVisibleDistance(float distance) => _visibleDistance = Mathf.Max(1f, distance);

        /// <summary>
        /// 글자 색을 바꾼다. <b>본인과 남을 색으로 구분</b>하는 데 쓴다 — 이름을 읽지 않고도
        /// 어느 쪽이 나인지 한눈에 들어와야 한다.
        /// </summary>
        public void SetColor(Color color)
        {
            _color = color;
            if (_text != null) _text.color = color;
        }

        void Start() => Build();

        void Build()
        {
            // **RectTransform 을 먼저 붙여야 한다.** TextMeshPro 는 RectTransform 을 요구하는데,
            // 평범한 Transform 을 가진 오브젝트에 붙이면 Unity 가 Transform 을 RectTransform 으로
            // **교체**한다 — 그 전에 잡아 둔 Transform 참조는 파괴된 객체가 되어 이후 접근이
            // 전부 MissingReferenceException 으로 터진다 (S15P21A604-355).
            var rootGo = new GameObject("Nameplate", typeof(RectTransform));
            _text = rootGo.AddComponent<TextMeshPro>();
            _root = _text.rectTransform;
            _root.SetParent(transform, false);
            _root.position = transform.position;   // 정확한 높이는 LateUpdate 가 잡는다

            var font = Resources.Load<TMP_FontAsset>(FontResourcePath);
            if (font != null) _text.font = font;
            else Debug.LogWarning($"[WorldNameplate] SDF 폰트를 찾지 못했다 ({FontResourcePath}) — 기본 폰트로 그린다. 한글이 깨질 수 있다.");

            _text.text = _label;
            _text.alignment = TextAlignmentOptions.Bottom;
            _text.enableWordWrapping = false;
            _text.overflowMode = TextOverflowModes.Overflow;
            _text.color = _color;
            // TMP 의 fontSize 는 포인트 단위다 — 10 이 월드 1 유닛에 해당한다.
            // 1 로 두면 0.1 유닛짜리 글자가 되어 화면에서 2 px 로 찍힌다.
            _text.fontSize = 10f;
            _text.rectTransform.sizeDelta = new Vector2(20f, 2f);
            _text.rectTransform.pivot = new Vector2(0.5f, 0f);

            _renderer = rootGo.GetComponent<MeshRenderer>();
            _renderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            _renderer.receiveShadows = false;

            ApplyOutline();
        }

        /// <summary>
        /// 외곽선과 그림자를 셰이더에 건다.
        ///
        /// <para><b>공유 머티리얼을 건드리면 안 된다.</b> SDF 폰트의 머티리얼은 그 폰트를 쓰는
        /// 모든 텍스트가 공유하므로, 여기서 색을 쓰면 UI 문구까지 같이 물든다.
        /// <c>fontMaterial</c> 은 이 텍스트 전용 인스턴스를 만들어 준다.</para>
        /// </summary>
        void ApplyOutline()
        {
            _material = _text.fontMaterial;      // 인스턴스 생성

            // 프로젝트 폰트가 Light 한 벌뿐이라 그대로 쓰면 획이 가늘어 배경에 묻힌다.
            // SDF 는 거리장을 부풀려 굵기를 만들 수 있다 — 합성 볼드처럼 획이 뭉개지지 않고
            // 가장자리가 그대로 날카롭다.
            _material.SetFloat(ShaderUtilities.ID_FaceDilate, _faceDilate);

            _material.EnableKeyword("OUTLINE_ON");
            _material.SetColor(ShaderUtilities.ID_OutlineColor, _outlineColor);
            _material.SetFloat(ShaderUtilities.ID_OutlineWidth, _outlineWidth);

            // **그림자(underlay)를 쓰지 않는다.** 부드럽게 번지는 성질이라 획 사이가 좁은
            // 한글에서는 글자 안쪽까지 뿌옇게 먹어 "군데군데 흐리다" 로 보인다
            // (S15P21A604-355 사용자 지적). 가독은 외곽선만으로 충분하다.
            _material.DisableKeyword("UNDERLAY_ON");

            // 가장자리를 뭉개지 않는다. Softness 를 올리면 SDF 의 장점인 날카로움이 사라진다.
            _material.SetFloat(ShaderUtilities.ID_OutlineSoftness, 0f);
        }

        /// <summary>
        /// 정수리 높이를 재고, 가능하면 <b>머리 본</b>에 물린다.
        ///
        /// <para><b>왜 본에 물리나.</b> 높이를 한 번만 재서 고정하면 앉는 순간 이름표가
        /// 선 키 그대로 허공에 남는다. 스킨메시의 <c>renderer.bounds</c> 는 바인드포즈 골격
        /// 범위라 자세가 바뀌어도 변하지 않아 매 프레임 다시 재도 소용이 없다. 머리 본은
        /// 자세를 그대로 따라가고 읽는 비용이 없다 — 정수리와의 거리만 한 번 재 두면
        /// 어떤 자세든 맞는다.</para>
        ///
        /// <para>정수리는 <c>BakeMesh</c> 로 현재 포즈를 구워서 잰다. 이때
        /// <c>RecalculateBounds</c> 를 반드시 불러야 한다 — 굽기만 하면 bounds 가
        /// 부풀린 바인드포즈 값 그대로다.</para>
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
        float RawTopY() => _headBone != null ? _headBone.position.y + _headToTop : _topY;

        /// <summary>
        /// 머리 높이를 <b>부드럽게</b> 따라간다.
        ///
        /// <para><b>왜 그대로 쓰면 안 되나.</b> 머리 본은 걷기 애니메이션에서 매 프레임 위아래로
        /// 흔들린다. 이름표가 그 값을 곧이곧대로 따라가면 움직일 때마다 글자가 떨려 눈이 아프다
        /// (S15P21A604-355 사용자 지적). 실제 게임 이름표는 캐릭터 위에 <b>가만히</b> 떠 있다.</para>
        ///
        /// <para>낮은 주파수만 통과시킨다 — 걷기 반동처럼 빠르게 오르내리는 성분은 걸러지고,
        /// 앉기처럼 크게 한 번 바뀌는 변화는 0.3 초 안에 따라붙는다. 그래서 감쇠 계수를
        /// 프레임률과 무관하게 지수식으로 준다(가변 프레임률에서 흔들림 폭이 달라지지 않는다).</para>
        /// </summary>
        float SmoothTopY()
        {
            float raw = RawTopY();
            if (!_smoothed) { _smoothTopY = raw; _smoothed = true; return raw; }
            // 큰 변화(앉기 등)는 굳이 늦출 이유가 없다 — 바로 따라간다.
            if (Mathf.Abs(raw - _smoothTopY) > LargePoseChange) { _smoothTopY = raw; return raw; }
            _smoothTopY = Mathf.Lerp(_smoothTopY, raw, 1f - Mathf.Exp(-TopFollowRate * Time.deltaTime));
            return _smoothTopY;
        }

        /// <summary>이보다 크게 바뀌면 자세가 통째로 바뀐 것으로 보고 즉시 맞춘다 (월드 유닛).</summary>
        const float LargePoseChange = 4f;
        const float TopFollowRate = 7f;
        float _smoothTopY;
        bool _smoothed;

        void LateUpdate()
        {
            if (_text == null) return;
            if (!_measured) MeasureTop();

            var cam = Camera.main;
            if (cam == null) { if (_renderer != null) _renderer.enabled = false; return; }

            // 멀어져도 화면에서 비슷한 크기로 읽히게 한다.
            float camDist = Vector3.Distance(cam.transform.position, transform.position);
            float scale = Mathf.Clamp(camDist / Mathf.Max(1f, _referenceDistance), 0.6f, 3f);
            float size = _baseCharacterHeight * scale;
            _root.localScale = Vector3.one * size;

            // pivot 이 바닥이라 글자는 이 지점 위로 그려진다. 간격을 월드 고정값으로 두면
            // 이름표가 커질수록 공백이 벌어져 머리 위에 붕 뜬다 — 크기에 비례시켜 화면상
            // 간격을 일정하게 만든다.
            _root.position = new Vector3(
                transform.position.x, SmoothTopY() + size * _headroomRatio, transform.position.z);

            var toCam = cam.transform.position - _root.position;
            float dist = toCam.magnitude;

            bool inFront = Vector3.Dot(cam.transform.forward, -toCam) > 0f;
            bool show = inFront && dist <= _visibleDistance && !string.IsNullOrEmpty(_label);
            _renderer.enabled = show;
            if (!show) return;

            // **반투명하게 만들지 않는다.** 거리에 따라 알파를 낮추면 글자가 흐려 보이고,
            // 특히 획이 촘촘한 한글은 반투명 상태에서 안쪽이 뭉개진다
            // (S15P21A604-355 사용자 지적). 보이거나 안 보이거나 둘 중 하나로 둔다 —
            // 표시 거리(_visibleDistance)를 넘으면 그냥 끈다.
            _text.color = _color;

            // 빌보드는 Y 축만 돌린다 — 기울기까지 따라가면 글자가 누워 읽기 어렵다.
            var flat = cam.transform.position - _root.position;
            flat.y = 0f;
            if (flat.sqrMagnitude > 0.0001f)
                _root.rotation = Quaternion.LookRotation(-flat.normalized, Vector3.up);
        }

        void OnDestroy()
        {
            // fontMaterial 은 인스턴스라 직접 지워야 샌다.
            if (_material != null) Destroy(_material);
        }
    }
}
