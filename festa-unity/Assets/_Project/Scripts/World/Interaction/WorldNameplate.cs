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
        const string FontResourcePath = "Fonts/NotoSansKRBold_SDF";   // 2026-09-06 이름표는 굵은 본문 글꼴(Noto Sans KR Bold) — 멀리서도 읽힌다

        [Tooltip("표시할 이름. 비어 있으면 그리지 않는다.")]
        [SerializeField] string _label;

        [Tooltip("이 거리(월드 유닛) 안에서만 보인다. 0 이하 = 거리 제한 없음. "
               + "2026-09-16 사용자 결정: 어느 위치에서든 시야에 들어오면 표시한다 — 기본 0.")]
        [SerializeField] float _visibleDistance = 0f;


        [Tooltip("머리 위 간격 — 이름표 자체 크기의 배수다. 월드 고정값이 아니라 비율이라야 "
               + "거리가 변해도 화면상 간격이 유지된다.")]
        [SerializeField, Range(0.05f, 2f)] float _headroomRatio = 1.5f;

        [Tooltip("기준 거리에서의 글자 크기(월드 유닛).")]
        [SerializeField] float _baseCharacterHeight = 1.35f;

        [Tooltip("이 거리에서 위 크기 그대로 보인다. 멀면 커지고 가까우면 작아진다(하한 0.6배, 상한 없음 — 화면상 크기 일정).")]
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

        // 몸 렌더러 — 오클루전 컬링·프러스텀 밖·초점 모드 숨김으로 몸이 안 보이면 이름표도 끈다
        // (벽 너머로 이름표만 떠다니던 것, 사용자 지적 2026-09-06).
        Renderer[] _bodyRenderers;
        int _bodyRefreshFrame;

        public string Label
        {
            get => _label;
            set { _label = value; if (_text != null) _text.text = value; }
        }

        /// <summary>런타임에 붙이는 쪽(플레이어 등)이 표시 거리를 정할 수 있게 한다.</summary>
        /// <summary>표시 거리(월드 유닛). 0 이하면 거리 제한 없음.</summary>
        public void SetVisibleDistance(float distance) => _visibleDistance = Mathf.Max(0f, distance);

        /// <summary>
        /// 글자 크기와 외곽선 두께를 올려 <b>배경이 무엇이든 읽히게</b> 한다. NPC 안내 이름표용.
        ///
        /// <para><b>왜 필요한가.</b> NPC 를 구분하려고 쓴 노란색(휘도 0.70)이 안내데스크 뒤 밝은 회색 벽
        /// (0.68)과 밝기가 거의 같아, 색만 다르고 명암이 없어 글자 모양이 안 잡혔다 (2026-09-14 사용자 지적).</para>
        ///
        /// <para><b>색으로는 못 푼다.</b> 밝은 벽과 밤하늘 양쪽에 동시에 대비가 나는 단일 색은 없다 —
        /// 어둡게 내리면 벽에서 읽히는 대신 밤 배경에서 묻힌다. 그래서 <b>배경별로 다른 부분이 담당한다</b>:
        /// 밝은 배경은 <b>검은 외곽선</b>이, 어두운 배경은 밝은 속면이 맡는다. 여기서 올리는 두 값이 그
        /// 분담을 실제로 작동시킨다 — 외곽선을 두껍게, 그리고 그 외곽선이 화면에서 픽셀로 분해되도록 크게.</para>
        ///
        /// <para><b>그림자(underlay)는 여전히 쓰지 않는다</b> — <see cref="ApplyOutline"/> 참고. 한글에서
        /// 획 사이를 뿌옇게 먹는다. 외곽선 상한이 0.25 인 것도 같은 이유라, 그 위는 받아도 깎는다.</para>
        /// </summary>
        public void SetLegibility(float characterHeight, float outlineWidth)
        {
            _baseCharacterHeight = Mathf.Max(0.1f, characterHeight);
            _outlineWidth = Mathf.Clamp(outlineWidth, 0f, 0.25f);
            if (_material != null) _material.SetFloat(ShaderUtilities.ID_OutlineWidth, _outlineWidth);
        }

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
            // 글자 크기는 월드 유닛 기준이다 — 부스 앵커(13.26배) 아래의 NPC 처럼 부모가 스케일돼 있으면 그만큼 되돌린다 (2026-09-06, AI 도우미 이름표 8 m).
            var ls = transform.lossyScale;
            _root.localScale = new Vector3(1f / Mathf.Max(ls.x, 1e-4f), 1f / Mathf.Max(ls.y, 1e-4f), 1f / Mathf.Max(ls.z, 1e-4f));
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

            // 벽 위에 그린다(ZTest Always). 차폐 판정(IsOccluded)이 "대부분 가려짐" 을 걸러 주므로, 여기까지 온
            // 글자는 일부가 벽 뒤여도 통째로 보이는 편이 반 토막보다 낫다 (S15P21A604-703 잔여, 2026-09-16).
            WorldTextOcclusion.ApplyOverlayShader(_material, _label);

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

        /// <summary>
        /// 머리 본을 따라가지 않고 <b>사람이 들어가 있는 구조물의 정점</b>에 고정할지.
        /// 매표소처럼 건물 안에 NPC 가 서 있는 자리에 이름을 붙일 때 쓴다 — 그때 기준은
        /// 사람 머리가 아니라 지붕이다 (2026-09-14 사용자 지정).
        /// </summary>
        bool _pinToStructureTop;

        /// <summary>찾아낸 구조물 정점의 월드 Y. 아직 못 찾았으면 <see cref="float.MinValue"/>.</summary>
        float _structureTopY = float.MinValue;

        /// <summary>머리 위로 지붕을 찾아 올려다볼 거리(u). 이 씬의 사람 키가 22 남짓이다.</summary>
        const float StructureSearchUp = 120f;
        const int StructureSearchMaxTries = 20;
        int _structureTries;
        static readonly RaycastHit[] _structureHits = new RaycastHit[16];

        /// <summary>구조물 정점에 고정한다(사람 머리를 따라가지 않는다).</summary>
        public void PinToStructureTop()
        {
            _pinToStructureTop = true;
            _headBone = null;
            _structureTopY = float.MinValue;
            _structureTries = 0;
        }

        /// <summary>
        /// 머리 위로 레이를 쏴 <b>맨 먼저 만나는 지붕</b>을 찾고, 그 콜라이더의 정점을 이름표 높이로 삼는다.
        ///
        /// <para><b>왜 자식 렌더러를 재지 않나.</b> 매표소 NPC 가 달린 <c>@ManagementDesk</c> 는 시각물
        /// 하나만 자식으로 가진 마커라, 자식을 재면 나오는 값이 <b>NPC 정수리</b>다 — 정확히 사용자가
        /// 아니라고 한 그 자리다. 매표소 구조물은 씬에서 별개 오브젝트라 계층으로는 닿지 않는다.</para>
        ///
        /// <para><b>왜 위에서 아래로 쏘지 않나.</b> 축제장이 실내라 높은 데서 내려쏘면 건물 천장이 먼저
        /// 잡힌다. 머리 위에서 올려쏘면 첫 히트가 그 사람을 덮고 있는 지붕이다 — 범위가 알아서 한정된다.</para>
        ///
        /// <para>가로 위치는 NPC 기준 그대로 둔다. 지붕 콜라이더의 중심을 쓰면 여러 부스를 덮는 큰 판일 때
        /// 엉뚱한 데로 끌려간다 — 사람 바로 위, 지붕 높이면 "매표소 위" 로 읽힌다.</para>
        ///
        /// <para>못 찾으면 정수리 높이로 남되 <b>조용히 그러지 않는다</b>. 지붕에 콜라이더가 없다는 건
        /// 여기서만 드러난다.</para>
        /// </summary>
        void MeasureStructureTop()
        {
            if (_structureTopY > float.MinValue || _structureTries >= StructureSearchMaxTries) return;
            _structureTries++;

            var origin = new Vector3(transform.position.x, _topY + 0.5f, transform.position.z);
            int n = Physics.RaycastNonAlloc(origin, Vector3.up, _structureHits, StructureSearchUp, ~0, QueryTriggerInteraction.Ignore);

            Collider nearest = null;
            float best = float.MaxValue;
            for (int i = 0; i < n; i++)
            {
                var c = _structureHits[i].collider;
                if (c == null || c.transform.IsChildOf(transform)) continue;   // 자기 몸은 지붕이 아니다
                if (_structureHits[i].distance < best) { best = _structureHits[i].distance; nearest = c; }
            }

            if (nearest == null)
            {
                if (_structureTries >= StructureSearchMaxTries)
                    Debug.LogWarning($"[WorldNameplate] '{_label}' 머리 위 {StructureSearchUp}u 안에 지붕 콜라이더가 없다 — 정수리 높이로 둔다");
                return;
            }

            _structureTopY = nearest.bounds.max.y;
            Debug.Log($"[WorldNameplate] '{_label}' 구조물 정점 {_structureTopY:F1} (정수리 {_topY:F1}, 지붕 {nearest.name})");
        }

        /// <summary>지금 자세의 정수리 높이. 머리 본이 있으면 자세를 따라간다.</summary>
        float RawTopY()
        {
            if (_pinToStructureTop) return _structureTopY > _topY ? _structureTopY : _topY;
            return _headBone != null ? _headBone.position.y + _headToTop : _topY;
        }

        // 머리 본을 못 잡았으면 주기적으로 다시 시도한다. 첫 측정은 아바타 파츠가 조립되기 전에 돌 수
        // 있어 Animator 가 아직 없고, 그러면 이름표가 **고정 높이**로 굳어 앉기(SitGround)·마시기 이모트에
        // 몸이 내려가도 허공에 남는다 (2026-09-05 사용자 테스트). 본이 잡히면 높이도 다시 잰다.
        int _rebindAttempts;
        const int RebindEveryFrames = 30, RebindMaxAttempts = 200;   // 약 0.5초 간격, 최대 ~100초
        void TryRebindHead()
        {
            if (_pinToStructureTop) return;   // 지붕에 고정된 이름표는 머리를 따라가지 않는다
            if (_headBone != null || _rebindAttempts >= RebindMaxAttempts) return;
            if (Time.frameCount % RebindEveryFrames != 0) return;
            _rebindAttempts++;
            var animator = GetComponentInChildren<Animator>();
            if (animator == null || !animator.isHuman) return;
            var head = animator.GetBoneTransform(HumanBodyBones.Head);
            if (head == null) return;
            MeasureTop();   // 파츠가 다 붙은 지금 기준으로 정점·머리 오프셋을 다시 잰다
        }

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
        /// <remarks>
        /// <b>몸통 높이만 거른다 — 캐릭터가 통째로 오르내리는 것은 그대로 따라간다.</b>
        /// 전에는 월드 Y 를 통으로 걸러서, 점프하면 이름표가 바닥 높이에 남아 캐릭터와 겹쳤다
        /// (사용자 지적 2026-09-11). 점프는 루트가 움직이는 것이라 거를 대상이 아닌데도
        /// 한 프레임 변화량이 <see cref="LargePoseChange"/> 를 못 넘어 즉시 붙기 분기에도 안 걸렸다.
        ///
        /// 걸러야 하는 것은 <b>루트 기준 머리 높이</b>다 — 걷기 반동·호흡은 여기서 흔들리고,
        /// 점프·계단·경사는 루트에서 움직인다. 둘을 나눠 앞의 것만 거른다.
        /// </remarks>
        float SmoothTopY()
        {
            float rootY = transform.position.y;
            float rawLocal = RawTopY() - rootY;   // 루트 위로 머리가 얼마나 있나
            if (!_smoothed) { _smoothLocalTop = rawLocal; _smoothed = true; return rootY + rawLocal; }
            // 큰 변화(앉기·눕기 등)는 굳이 늦출 이유가 없다 — 바로 따라간다.
            if (Mathf.Abs(rawLocal - _smoothLocalTop) > LargePoseChange) _smoothLocalTop = rawLocal;
            else _smoothLocalTop = Mathf.Lerp(_smoothLocalTop, rawLocal, 1f - Mathf.Exp(-TopFollowRate * Time.deltaTime));
            return rootY + _smoothLocalTop;
        }

        /// <summary>이보다 크게 바뀌면 자세가 통째로 바뀐 것으로 보고 즉시 맞춘다 (월드 유닛).</summary>
        const float LargePoseChange = 4f;
        const float TopFollowRate = 7f;
        float _smoothLocalTop;
        bool _smoothed;

        void LateUpdate()
        {
            if (_text == null) return;
            if (!_measured) MeasureTop();
            if (_pinToStructureTop) MeasureStructureTop();
            TryRebindHead();

            var cam = Camera.main;
            if (cam == null) { if (_renderer != null) _renderer.enabled = false; return; }

            // 멀어져도 화면에서 비슷한 크기로 읽히게 한다.
            float camDist = Vector3.Distance(cam.transform.position, transform.position);
            // 상한을 두지 않는다: 거리 제한이 없으므로 어디서 봐도 화면상 같은 크기로 읽혀야 한다.
            // (3배 상한은 165u 에서 포화해 그 너머 글자가 줄었다 — 2026-09-16.)
            float scale = Mathf.Max(0.6f, camDist / Mathf.Max(1f, _referenceDistance));
            float size = _baseCharacterHeight * scale;
            // 부모가 스케일된 NPC(부스 앵커 13.26배)에서는 그만큼 되돌린다 — 플레이어(lossy 1)는 종전과 같다.
            var ls = transform.lossyScale;
            _root.localScale = new Vector3(size / Mathf.Max(ls.x, 1e-4f), size / Mathf.Max(ls.y, 1e-4f), size / Mathf.Max(ls.z, 1e-4f));

            // pivot 이 바닥이라 글자는 이 지점 위로 그려진다. 간격을 월드 고정값으로 두면
            // 이름표가 커질수록 공백이 벌어져 머리 위에 붕 뜬다 — 크기에 비례시켜 화면상
            // 간격을 일정하게 만든다.
            _root.position = new Vector3(
                transform.position.x, SmoothTopY() + size * _headroomRatio, transform.position.z);

            var toCam = cam.transform.position - _root.position;
            float dist = toCam.magnitude;

            // 빌보드는 Y 축만 돌린다 — 기울기까지 따라가면 글자가 누워 읽기 어렵다.
            // 차폐 판정보다 **먼저** 돌린다: 판정이 글자 사각형의 모서리를 재므로 이번 프레임의 회전이 기준이어야 한다.
            var flat = cam.transform.position - _root.position;
            flat.y = 0f;
            if (flat.sqrMagnitude > 0.0001f)
                _root.rotation = Quaternion.LookRotation(-flat.normalized, Vector3.up);

            bool inFront = Vector3.Dot(cam.transform.forward, -toCam) > 0f;
            bool withinDistance = _visibleDistance <= 0f || dist <= _visibleDistance;
            bool show = inFront && withinDistance && !string.IsNullOrEmpty(_label)
                        && IsBodyVisible() && !IsOccluded(cam);
            _renderer.enabled = show;
            if (!show) return;

            // **반투명하게 만들지 않는다.** 거리에 따라 알파를 낮추면 글자가 흐려 보이고,
            // 특히 획이 촘촘한 한글은 반투명 상태에서 안쪽이 뭉개진다
            // (S15P21A604-355 사용자 지적). 보이거나 안 보이거나 둘 중 하나로 둔다 —
            // 표시 거리(_visibleDistance)를 넘으면 그냥 끈다.
            _text.color = _color;
        }

        /// <summary>
        /// 카메라와 글자 사이에 **단단한 것**이 있는가. 있으면 이름표를 감춘다.
        ///
        /// <para><b>왜 <see cref="Renderer.isVisible"/> 로는 안 되나.</b> 그 값은
        /// "어느 카메라에든 보이는가" 다 — 에디터에서는 **씬 뷰 카메라에만 보여도 true** 라
        /// 게임 뷰에서 벽 뒤에 있어도 이름표가 뜬다. 빌드에서도 그 벽이 베이크된 오클루더가
        /// 아니면 걸리지 않는다. 사용자가 반복해서 지적한 "벽 쪽으로 가면 닉네임만 뜬다" 가 이것이다.</para>
        ///
        /// <para>그래서 <b>카메라에서 글자 사각형(중심·네 모서리)까지 선분을 직접 쏘고 과반이 막히면 감춘다</b>
        /// (<see cref="WorldTextOcclusion"/>). 바닥 중앙 한 점만 재던 때는 줄전구 하나가 중앙을 스쳐도 라벨이
        /// 꺼졌고, 반대로 한쪽 끝만 벽에 박힌 경우는 못 걸러 글자가 잘린 채 남았다("ㅂ스 관리", 2026-09-16).
        /// 잘림 자체는 Overlay 셰이더(<see cref="ApplyOutline"/>)가 없앤다. 자기 몸(자식 콜라이더)은
        /// 건너뛰고, 트리거는 무시한다. 매 프레임 쏘면 사람 수만큼 늘어나므로 <c>OcclusionInterval</c> 프레임마다
        /// 한 번만 재고 그 사이는 직전 값을 쓴다 — 이름표가 깜빡일 만큼 빠른 변화가 아니다.</para>
        /// </summary>
        bool IsOccluded(Camera cam)
        {
            if (!_hideWhenOccluded) return false;
            if (Time.frameCount - _occlusionFrame < OcclusionInterval) return _occluded;
            _occlusionFrame = Time.frameCount;

            _occluded = WorldTextOcclusion.IsMostlyOccluded(cam, _text, transform);
            return _occluded;
        }

        [Tooltip("몸이 가려지면 이름표도 감춘다. 끄면 벽 너머로 이름만 떠 보인다")]
        [SerializeField] bool _hideWhenOccluded = true;

        /// <summary>가림 판정 간격(프레임). 사람이 많을수록 레이 수가 늘어나므로 매 프레임 쏘지 않는다.</summary>
        const int OcclusionInterval = 3;
        int _occlusionFrame = -100;
        bool _occluded;

        /// <summary>
        /// 몸 렌더러 중 하나라도 이번 프레임에 그려졌는가(<see cref="Renderer.isVisible"/> 는 프러스텀·오클루전 컬링 결과를 반영한다).
        /// 전부 비활성(초점 모드 자기 숨김)이거나 컬링됐으면 false.
        /// </summary>
        bool IsBodyVisible()
        {
            // 지붕에 붙은 이름표의 주인은 건물이지 그 안의 사람이 아니다. NPC 는 부스 벽에 가려
            // 컬링되기 쉬운데, 그걸로 지붕 위 글자를 끄면 밖에서는 이름이 아예 안 뜬다.
            if (_pinToStructureTop) return true;

            if (_bodyRenderers == null || Time.frameCount - _bodyRefreshFrame > 120)
            {
                var list = new System.Collections.Generic.List<Renderer>();
                foreach (var r in GetComponentsInChildren<Renderer>(true))
                    if (r != null && !r.transform.IsChildOf(_root) && r.gameObject.name != "__FestaOutline") list.Add(r);
                _bodyRenderers = list.ToArray();
                _bodyRefreshFrame = Time.frameCount;
            }
            if (_bodyRenderers.Length == 0) return true;   // 몸을 모르면 종전대로 보인다
            foreach (var r in _bodyRenderers)
                if (r != null && r.enabled && r.gameObject.activeInHierarchy && r.isVisible) return true;
            return false;
        }

        void OnDestroy()
        {
            // fontMaterial 은 인스턴스라 직접 지워야 샌다.
            if (_material != null) Destroy(_material);
        }
    }
}
