using Festa.Booth;
using Festa.Content;
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
    /// <para><b>대사는 2D 토스트다.</b> 처음에는 월드 안 말풍선으로 만들었는데, 이 틈은 가용 폭이
    /// 10 u 뿐이라 판넬·기둥 모서리에 글자가 잘려 <b>무슨 말인지 읽을 수 없었다.</b> 위치·크기·
    /// 끌어내기를 세 번 바꿔도 마찬가지였다(2026-09-13 실측). 화면 평면에 그리면 지형과 무관하게
    /// 항상 온전히 보인다 — 대사 한 줄 때문에 공간과 싸울 이유가 없다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class NappingStaffInteractable : MonoBehaviour, IBoothInteractable
    {
        const string CaughtLine = "죄..죄송합니다. 얼른 들어가겠습니다.";

        [Tooltip("들킨 뒤 다시 돌아오기까지(초)")]
        [SerializeField] float _returnAfter = 300f;

        [Tooltip("대사를 띄워 두는 시간(초). 이 뒤에 사라진다")]
        [SerializeField] float _lineDuration = 2.6f;

        [Tooltip("일어섰을 때 발을 바닥에서 더 띄울 여유(u). 0 이면 보이는 몸의 맨 아래가 바닥에 닿는다")]
        [SerializeField] float _footLift = 0f;

        /// <summary>자는 포즈로 쓸 이모트. 인스펙터에서 바꿀 수 있게 둔다 — 클립마다 누운 방향이 다르다.</summary>
        [SerializeField] Festa.Network.PlayerEmoteId _sleepEmote = Festa.Network.PlayerEmoteId.LieSofa;

        Animator _animator;
        GameObject[] _visuals;
        float _hiddenUntil;
        float _lineUntil;
        bool _caught;

        Transform _visual;
        PinVisualRoot _pin;

        /// <summary>
        /// 일어선 자세를 어디에 세울지 계산하는 데 쓰는 **실측값들**. 하나도 적어 두지 않고 직접 잰다.
        ///
        /// <para><b>왜 필요한가.</b> 휴머노이드 클립은 저마다 몸이 루트에서 떨어진 거리를 안고 있다.
        /// 같은 루트에서 실측하면 눕기는 루트+(−5.93, 6.81, −2.30), Idle 은 루트+(−0.34, 10.12, −0.19) —
        /// <b>수평으로 5.98 u 차이</b>다. 자는 틈은 폭이 10 u 뿐이라 그만큼 움직이면 판넬을 뚫는다.</para>
        ///
        /// <para><b>임포트 설정으로는 못 없앤다.</b> 2026-09-13 에 네 조합을 전부 리임포트하며 쟀다 —
        /// Bake Into Pose(<c>lockRootPositionXZ</c>)는 값이 한 톨도 안 바뀌어 무관했고,
        /// Based Upon 을 Center of Mass 로 바꾸면 차이가 2.68 u 로 줄 뿐 0 이 되지 않는데다
        /// 그 설정은 에디터 샘플링과 런타임이 어긋나는 원인이라 되돌렸다(T-264).
        /// 그래서 <b>루트는 씬에 저작된 값을 그대로 두고</b>, 일어나 있는 동안만 자세를 계산해 세운다.</para>
        /// </summary>
        Vector3 _sleepHipsWorld;    // 자고 있을 때 골반이 있던 자리 — 일어나도 여기를 지킨다
        Vector3 _standHipsLocal;    // 일어선 자세의 골반을 시각물 로컬로. 돌려세워도 유효하다
        float _standFootDrop;       // 일어선 자세에서 골반과 가장 낮은 발의 높이 차 — 발을 바닥에 놓는 데 쓴다
        bool _measured;

        void Awake()
        {
            if (GetComponentsInChildren<Collider>(true).Length == 0)
                gameObject.AddComponent<BoxCollider>();
            if (GetComponent<BoothInteractionTarget>() == null)
                gameObject.AddComponent<BoothInteractionTarget>();
            BoothInteractionInput.Ensure();

            _animator = GetComponentInChildren<Animator>();
            _visual = _animator != null ? _animator.transform : (transform.childCount > 0 ? transform.GetChild(0) : null);
            _pin = GetComponent<PinVisualRoot>();

            // 숨길 대상은 **자식 렌더러를 가진 오브젝트**다. 이 오브젝트 자체를 끄면 이 스크립트도 멈춰
            // 5분 뒤 복귀가 영영 오지 않는다 — 그래서 껍데기는 살려 두고 보이는 것만 끈다.
            var renderers = GetComponentsInChildren<Renderer>(true);
            _visuals = new GameObject[renderers.Length];
            for (int i = 0; i < renderers.Length; i++) _visuals[i] = renderers[i].gameObject;

            PlaySleep();
        }

        void Start() => StartCoroutine(MeasureThenSleep());

        /// <summary>
        /// 재는 동안 두 자세가 한 프레임씩 보이면 안 되니 그리기만 잠깐 막았다가 되돌린다.
        /// 씬이 뜨는 순간 두 프레임뿐이고, 어차피 판넬 뒤라 눈에 닿지 않는다.
        ///
        /// <para><b>재는 동안 컬링을 풀어야 한다.</b> 이 Animator 는 <c>CullUpdateTransforms</c> 라
        /// 보이지 않으면 본을 갱신하지 않는다 — 그대로 재면 두 자세가 같은 값으로 나와 보정이 0 이 된다
        /// (2026-09-13 플레이 모드에서 실제로 이 함정에 빠졌다).</para>
        /// </summary>
        System.Collections.IEnumerator MeasureThenSleep()
        {
            var culling = _animator != null ? _animator.cullingMode : AnimatorCullingMode.AlwaysAnimate;
            if (_animator != null) _animator.cullingMode = AnimatorCullingMode.AlwaysAnimate;

            // **끄지 않고 "그리지만 않는다".** 렌더러를 비활성으로 만들면 스키닝이 멈춰 bounds 가
            // 굳어 버려서, 발바닥 높이를 잴 수 없다. forceRenderingOff 는 갱신은 그대로 두고 그리기만 막는다.
            var rends = GetComponentsInChildren<Renderer>(true);
            foreach (var r in rends)
            {
                r.forceRenderingOff = true;
                if (r is SkinnedMeshRenderer smr) smr.updateWhenOffscreen = true;
            }

            yield return MeasureStandUpFix();

            foreach (var r in rends) r.forceRenderingOff = false;

            if (_animator != null) _animator.cullingMode = culling;
            PlaySleep();
        }

        /// <summary>
        /// 두 자세를 한 번씩 <b>실제 프레임으로</b> 평가해 골반 차이를 잰다. 보정값의 출처는 이 측정뿐이다.
        ///
        /// <para><c>Animator.Update(0f)</c> 로는 안 된다 — 델타 0 으로는 포즈가 적용되지 않아
        /// 두 자세가 같은 값으로 나온다(2026-09-13 플레이 모드 실측). 프레임을 실제로 넘겨야 한다.</para>
        /// </summary>
        System.Collections.IEnumerator MeasureStandUpFix()
        {
            if (_animator == null || !_animator.isHuman || _visual == null) yield break;
            var hips = _animator.GetBoneTransform(HumanBodyBones.Hips);
            if (hips == null) yield break;

            int sleepHash = Animator.StringToHash($"Emote_{_sleepEmote}");
            int wakeHash = Animator.StringToHash(WakeState);
            if (!_animator.HasState(0, sleepHash) || !_animator.HasState(0, wakeHash)) yield break;

            _animator.Play(wakeHash, 0, 0f);
            yield return null;
            yield return null;
            var wakeHips = hips.position;
            // 골반을 **시각물 로컬**로 담아 둔다. 나중에 돌려세워도 그대로 쓸 수 있다.
            _standHipsLocal = _visual.InverseTransformPoint(wakeHips);
            _standFootDrop = wakeHips.y - LowestVisibleY();

            _animator.Play(sleepHash, 0, 0f);
            yield return null;
            yield return null;
            _sleepHipsWorld = hips.position;

            // 두 자세가 같은 값으로 나오면 측정이 실패한 것이다 — 조용히 넘어가면
            // 일어날 때 몸이 판넬을 뚫는데 아무도 이유를 모른다.
            var moved = _sleepHipsWorld - wakeHips;
            moved.y = 0f;
            if (moved.sqrMagnitude < 0.0001f)
            {
                Debug.LogError("[NappingStaff] 자세 차이 측정 실패 — Animator 가 포즈를 적용하지 않았다. 일어설 때 몸이 어긋난다");
                yield break;
            }

            _measured = true;
        }

        /// <summary>
        /// 보이는 몸의 <b>가장 낮은 지점</b>(월드 높이). 발을 바닥에 놓을 기준이다.
        ///
        /// <para><b>본을 기준으로 하면 안 된다.</b> 발목을 바닥에 맞추면 발가락과 신발이 그 아래로
        /// 내려가 발이 단에 파묻힌다 — 실측으로 발가락 본이 발목보다 0.92 u 아래였고, 신발 메시는
        /// 거기서 더 내려갔다 (2026-09-13 사용자 지적: "발이 블럭밑에 깔린다").
        /// 눈에 보이는 것이 기준이므로 렌더러 bounds 의 바닥을 쓴다.</para>
        /// </summary>
        float LowestVisibleY()
        {
            float y = float.MaxValue;
            foreach (var r in GetComponentsInChildren<Renderer>(true))
                if (r.bounds.min.y < y) y = r.bounds.min.y;
            return y == float.MaxValue ? _visual.position.y : y;
        }

        /// <summary>
        /// 일어설 자리의 바닥 높이를 <b>레이캐스트로</b> 찾는다. 이 틈은 단 위라 바닥이 0 이 아니고,
        /// 값을 적어 두면 단을 옮기는 순간 발이 묻힌다(2026-09-13 사용자 지적: "발이 블럭밑에 깔린다").
        /// 자기 몸은 건너뛴다 — 콜라이더가 몸에도 붙어 있다.
        /// </summary>
        bool TryFindGroundY(Vector3 xz, float fromY, out float groundY)
        {
            groundY = 0f;
            var hits = Physics.RaycastAll(new Vector3(xz.x, fromY + 4f, xz.z), Vector3.down, 40f,
                                          ~0, QueryTriggerInteraction.Ignore);
            bool found = false;
            foreach (var h in hits)
            {
                if (h.transform == transform || h.transform.IsChildOf(transform)) continue;
                if (!found || h.point.y > groundY) { groundY = h.point.y; found = true; }
            }
            return found;
        }

        const string WakeState = "Idle";

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
            if (!_caught) return;

            // 대사 시간이 지나면 사라지고, 그 뒤 5분이 지나면 돌아온다.
            if (_lineUntil > 0f && Time.time >= _lineUntil)
            {
                _lineUntil = 0f;
                SetVisible(false);
                _hiddenUntil = Time.time + _returnAfter;
            }
            else if (_hiddenUntil > 0f && Time.time >= _hiddenUntil)
            {
                _hiddenUntil = 0f;
                _caught = false;
                SetVisible(true);
                if (_pin != null) _pin.ClearPose();   // 다시 누우니 씬에 저작된 자세로 돌아간다
                PlaySleep();
            }
        }

        public void Interact()
        {
            if (_caught) return;   // 이미 들켰다 — 두 번 깨우지 않는다
            _caught = true;
            _lineUntil = Time.time + _lineDuration;

            // 화면 평면에 띄운다 (위 클래스 주석 참조).
            BoothInteractionInput.Toast(CaughtLine, _lineDuration);

            ApplyStandPose();

            if (_animator != null && _animator.HasState(0, Animator.StringToHash(WakeState)))
                _animator.CrossFadeInFixedTime(WakeState, 0.2f, 0);
        }

        /// <summary>
        /// 일어선 자세를 세운다 — <b>자던 자리를 지키고, 발을 단 위에 놓고, 말 건 사람을 본다.</b>
        ///
        /// <para>회전과 위치를 따로 주면 서로를 무너뜨린다. 돌려세우면 몸이 시각물 원점을 중심으로
        /// 돌아 자리가 또 바뀌기 때문이다. 그래서 <b>먼저 시선을 정하고, 그 회전에서 골반이
        /// 목표 자리에 오도록 위치를 역산</b>해 한 쌍으로 건다.</para>
        /// </summary>
        void ApplyStandPose()
        {
            if (_pin == null || _visual == null || !_measured) return;

            var target = _sleepHipsWorld;   // 자던 자리 (수평)

            // 발을 바닥에 놓는다. 못 찾으면 자던 높이를 그대로 둔다 — 조용히 0 으로 떨어뜨리지 않는다.
            if (TryFindGroundY(target, target.y, out float groundY)) target.y = groundY + _standFootDrop + _footLift;
            else Debug.LogWarning("[NappingStaff] 설 자리의 바닥을 못 찾았다 — 발 높이를 보정하지 않는다");

            // 말 건 사람을 바라본다. 기준은 사거리 판정과 같은 것을 쓴다.
            var parent = _visual.parent;
            var worldRot = _visual.rotation;
            var who = BoothInteractionInput.InteractorPosition();
            if (who.HasValue)
            {
                var to = who.Value - target;
                to.y = 0f;   // 고개를 들거나 숙이지 않는다. 몸은 수평으로만 돈다
                if (to.sqrMagnitude > 0.0001f) worldRot = Quaternion.LookRotation(to.normalized, Vector3.up);
            }

            var localRot = parent != null ? Quaternion.Inverse(parent.rotation) * worldRot : worldRot;
            var hipsLocal = Vector3.Scale(_visual.localScale, _standHipsLocal);
            var localPos = (parent != null ? parent.InverseTransformPoint(target) : target) - (localRot * hipsLocal);

            _pin.SetPose(localPos, localRot);
        }
    }
}
