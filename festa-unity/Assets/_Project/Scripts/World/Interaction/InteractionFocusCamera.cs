using Festa.Integration;
using UnityEngine;
using UnityEngine.InputSystem;

namespace Festa.World
{
    /// <summary>
    /// 상호작용 대상으로 카메라를 끌어당기는 <b>초점 모드</b> (S15P21A604-439·-440).
    ///
    /// <para>slot machine·게임기처럼 "화면을 들여다보는" 상호작용은 3인칭 추적 카메라 그대로면 대상이
    /// 작게 보이고 캐릭터가 화면을 가린다. 초점 모드는 로컬 플레이어의 <see cref="PlayerCameraFollow"/> 를
    /// 잠시 끄고 메인 카메라를 대상 앞의 정해진 자리로 보간해 옮긴다. 그 동안 월드 입력은
    /// <see cref="InputBridge"/> 로 잠근다 — 캐릭터가 뛰어다니며 상호작용 프롬프트가 겹치는 일(-437)이 없다.</para>
    ///
    /// <para><b>갇히지 않는다.</b> Esc·우상단 [Esc] 알약으로 언제든 나가고, 호스트가
    /// <c>SendMessage('WorldUiBridge','RequestExitWorldUi','esc')</c> 를 보내도 풀린다(#132). 어느 쪽이든
    /// <see cref="Released"/> 가 한 번 발생한다. 호스트의 <c>SetInputLocked('0')</c> 만으로는 <b>풀리지 않는다</b> —
    /// 잠금이 owner-set 이 된 뒤(09-08)로는 초점이 자기 잠금을 쥐고 있어 <c>LockedChanged</c> 가 오지 않기 때문이다.
    /// <see cref="OnLockedChanged"/> 는 다른 주인이 전부 놓아 잠금이 실제로 풀린 경우에만 반응한다.</para>
    ///
    /// <para>씬을 고치지 않는다 — 처음 쓸 때 자동 생성되는 단일 인스턴스다.</para>
    /// </summary>
    public sealed class InteractionFocusCamera : MonoBehaviour
    {
        /// <summary>입력 잠금 주인 이름. 호스트 Overlay 가 자기 잠금을 풀 때 초점 잠금까지 풀지 않게 한다.</summary>
        const string LockOwner = "InteractionFocusCamera";

        static InteractionFocusCamera s_instance;

        /// <summary>초점 모드가 끝났다(Esc·외부 잠금 해제·명시적 Release). 어떤 이유든 한 번만.</summary>
        public static event System.Action Released;

        public static bool IsFocused => s_instance != null && s_instance._active;

        [SerializeField] float _lerp = 6f;

        Camera _cam;
        PlayerCameraFollow _follow;
        Vector3 _targetPos;
        Quaternion _targetRot;
        bool _active;
        bool _lockedByUs;
        bool _releasing;
        bool _hadFollow;
        readonly System.Collections.Generic.List<Renderer> _hiddenSelf = new();

        /// <summary>
        /// 대상 앞으로 카메라를 옮긴다. <paramref name="cameraLocal"/>·<paramref name="lookLocal"/> 은
        /// <paramref name="anchor"/> 로컬 좌표(월드 단위 — 1 m ≈ 13.26).
        /// 이미 초점 중이면 대상만 바꾼다.
        /// </summary>
        public static void Focus(Transform anchor, Vector3 cameraLocal, Vector3 lookLocal, bool lockInput = true)
        {
            if (anchor == null) return;
            var inst = Ensure();
            inst.BeginFocus(anchor, cameraLocal, lookLocal, lockInput);
        }

        /// <summary>
        /// 대상의 렌더러 경계를 기준으로 자동 구도를 잡는다 — 부스 오브젝트(노트북·패널·키오스크·NPC)처럼 프리팹마다
        /// 크기·정면이 다른 대상용. 로컬 플레이어 → 대상 방향 선 위, 대상 크기의 <paramref name="distanceScale"/> 배 거리에서
        /// 대상 중심(조금 위)을 본다. 일반적인 3인칭 게임의 "대화/조사 카메라" 와 같은 방식.
        /// </summary>
        public static void FocusOn(GameObject target, float distanceScale = 2.2f, float heightBias = 0.5f, bool lockInput = true)
        {
            if (target == null) return;
            var renderers = target.GetComponentsInChildren<Renderer>();
            Bounds b = new Bounds(target.transform.position, Vector3.one * 5f);
            bool first = true;
            foreach (var r in renderers)
            {
                if (r.gameObject.name == "__FestaOutline" || r is ParticleSystemRenderer) continue;
                if (first) { b = r.bounds; first = false; } else b.Encapsulate(r.bounds);
            }

            var cam = Camera.main;
            Vector3 from = cam != null ? cam.transform.position : target.transform.position + Vector3.back * 30f;
            var local = FindLocalFollow();
            if (local != null) from = local.transform.position + Vector3.up * b.extents.y;

            Vector3 dir = from - b.center; dir.y = 0f;
            if (dir.sqrMagnitude < 0.01f) dir = -target.transform.forward;
            dir.Normalize();

            float size = Mathf.Max(b.extents.x, b.extents.y, b.extents.z);
            // 거리는 대상 크기에서만 정한다. 플레이어 뒤로 넘어가도 초점 중에는 자기 아바타를 숨기므로 뒤통수가 화면을 가리지 않는다.
            // (한때 플레이어 앞으로 당겨 붙였더니 카운터형 오브젝트의 앞판만 가득 잡혔다 — 2026-09-06 키오스크 실측.)
            float distance = Mathf.Max(size * distanceScale, 8f);
            // 시선은 경계의 위쪽(heightBias 0.5 = 중심과 꼭대기의 중간)으로 — 카운터·키오스크·노트북은 볼거리가 위에 있다.
            Vector3 lookAt = b.center + Vector3.up * b.extents.y * heightBias;
            Vector3 camPos = lookAt + dir * distance + Vector3.up * size * 0.45f;

            var inst = Ensure();
            inst.BeginFocusWorld(target.transform, camPos, lookAt, lockInput);
        }

        /// <summary>초점 모드를 끝내고 추적 카메라·입력을 되돌린다. 초점 중이 아니면 아무 일도 없다.</summary>
        public static void Release()
        {
            if (s_instance == null) return;
            s_instance.EndFocus();
        }

        static InteractionFocusCamera Ensure()
        {
            if (s_instance != null) return s_instance;
            var go = new GameObject("@InteractionFocusCamera");
            s_instance = go.AddComponent<InteractionFocusCamera>();
            return s_instance;
        }

        void OnEnable() => InputBridge.LockedChanged += OnLockedChanged;
        void OnDisable() => InputBridge.LockedChanged -= OnLockedChanged;
        void OnDestroy()
        {
            // 초점 중에 씬이 바뀌면(Single 로드) 이 오브젝트는 파괴되는데 InputBridge 잠금은 static 이라 다음 씬으로
            // 넘어간다 — 풀어 줄 주체가 없어 이동·F·이모트가 영원히 죽었다(QA 2026-09-08 #51). 우리가 건 잠금만 되돌린다.
            if (_active) EndFocus();
            if (s_instance == this) s_instance = null;
        }

        void BeginFocus(Transform anchor, Vector3 cameraLocal, Vector3 lookLocal, bool lockInput)
            => BeginFocusWorld(anchor, anchor.TransformPoint(cameraLocal), anchor.TransformPoint(lookLocal), lockInput);

        void BeginFocusWorld(Transform anchor, Vector3 cameraWorld, Vector3 lookAtWorld, bool lockInput)
        {
            _cam = Camera.main;
            if (_cam == null)
            {
                Debug.LogError("[InteractionFocusCamera] Camera.main 이 없어 초점 모드를 열 수 없다.");
                return;
            }

            _targetPos = cameraWorld;
            var lookAt = lookAtWorld;
            _targetRot = Quaternion.LookRotation(lookAt - _targetPos, Vector3.up);

            if (!_active)
            {
                _follow = FindLocalFollow();
                _hadFollow = _follow != null;
                if (_follow != null) { _follow.enabled = false; HideSelf(_follow.gameObject); }
                _active = true;

                if (lockInput && !InputBridge.IsLocked)
                {
                    _lockedByUs = true;
                    InputBridge.SetLocked(true, LockOwner);
                }
                Debug.Log($"[InteractionFocusCamera] 초점 시작 → {anchor.name}");
                WorldUiBridge.Publish();   // 호스트에 focus=true (#132 — ESC 중재용 상태 push, 전이 때만)
            }
        }

        void EndFocus()
        {
            if (!_active || _releasing) return;
            _releasing = true;
            _active = false;

            if (_follow != null) _follow.enabled = true;   // 다음 LateUpdate 부터 추적 카메라가 보간으로 되돌아간다
            _follow = null;
            _hadFollow = false;
            ShowSelf();

            if (_lockedByUs)
            {
                _lockedByUs = false;
                InputBridge.SetLocked(false, LockOwner);   // LockedChanged(false) 가 다시 들어오지만 _active 가 false 라 무시된다
            }

            Debug.Log("[InteractionFocusCamera] 초점 해제");
            _releasing = false;
            Released?.Invoke();
            WorldUiBridge.Publish();   // 호스트에 focus=false
        }

        void OnLockedChanged(bool locked)
        {
            // 호스트가 오버레이를 닫으며 잠금을 풀었다 — 초점도 함께 끝낸다.
            if (!locked && _active) EndFocus();
        }

        /// <summary>
        /// 초점 모드 나가기 어포던스 — 우상단 `[Esc] 나가기` 알약. Esc 하나만 있으면 ① 키보드 없는 기기, ② 캔버스가
        /// 포커스를 잃어 Esc 가 브라우저로 가는 경우(captureAllKeyboardInput=false), ③ FE 가 잠금을 안 풀어 주는 경우에
        /// 갇힌다(QA 2026-09-08 #54). 마우스·터치로도 눌린다. IMGUI 라 씬 배선이 없다.
        ///
        /// <para><b>FE 임베드에서는 그리지 않는다</b> (사용자 지시 2026-09-14). 임베드에서는 같은 자리에 React
        /// HUD 의 상담 버튼이 있고 이 알약은 Unity 캔버스라 그 <b>뒤로 깔려</b>, 반쯤 가린 흰 판때기로만 보였다.
        /// 기능도 겹친다 — 그쪽은 오버레이의 X·배경 클릭·ESC 가 <c>RequestExitWorldUi</c> 로 초점까지 함께 끝낸다(#132).
        /// 게이트는 토스트(#141)와 같은 <b>FE 존재 여부</b>다. 단독 실행에는 그 대체 경로가 없으므로 그대로 남는다 —
        /// 여기서 통째로 지우면 #54 가 그대로 돌아온다.</para>
        /// </summary>
        void OnGUI()
        {
            if (!_active) return;
            if (Festa.World.UI.ControlsHintHud.HostProvidesUi) return;
            float ui = InteractPromptUI.UiScale();
            float h = Mathf.Round(44f * ui), cap = Mathf.Round(34f * ui), pad = Mathf.Round(14f * ui), gap = Mathf.Round(10f * ui);
            const string label = "나가기";
            int fs = Mathf.RoundToInt(20f * ui);
            float labelW = InteractPromptUI.MeasureLabel(label, fs);
            float w = pad + cap + gap + labelW + pad;
            var rect = new Rect(Screen.width - w - Mathf.Round(24f * ui), Mathf.Round(24f * ui), w, h);
            InteractPromptUI.DrawCard(rect, Mathf.RoundToInt(h / 2f), new Color(1f, 0.99f, 0.965f, 0.96f));
            InteractPromptUI.DrawKeycap(new Rect(rect.x + pad, rect.y + (h - cap) / 2f, cap, cap), "Esc", Mathf.RoundToInt(13f * ui));
            InteractPromptUI.DrawLabel(new Rect(rect.x + pad + cap + gap, rect.y, labelW + 4f, h), label, fs, Festa.World.UI.FestaUiKit.Text);

            var e = Event.current;
            if (e.type == EventType.MouseDown && rect.Contains(e.mousePosition))
            {
                e.Use();
                EndFocus();
            }
        }

        void LateUpdate()
        {
            if (!_active || _cam == null) return;

            // 초점 중에 로컬 플레이어가 사라졌다(재접속으로 새 플레이어가 스폰됨, 2026-09-06 WebGL 실측 — 탭이 숨겨져 끊긴 뒤
            // 자동 재접속). 새 플레이어의 추적 카메라가 켜져 화면은 돌아갔는데 초점 모드·잠금·HUD 만 남는 어중간한 상태가 되므로
            // 초점을 끝내 HUD 도 함께 닫는다(Released). 원래 추적 카메라가 없던 경우(서버 단독)는 해당 없음.
            if (_hadFollow && _follow == null)
            {
                Debug.Log("[InteractionFocusCamera] 로컬 플레이어가 바뀌어(재스폰) 초점을 끝낸다.");
                EndFocus();
                return;
            }

            var kb = Keyboard.current;
            if (kb != null && kb.escapeKey.wasPressedThisFrame)
            {
                EndFocus();
                return;
            }

            float t = 1f - Mathf.Exp(-_lerp * Time.deltaTime);
            var ct = _cam.transform;
            ct.position = Vector3.Lerp(ct.position, _targetPos, t);
            ct.rotation = Quaternion.Slerp(ct.rotation, _targetRot, t);
        }

        /// <summary>초점 중에는 자기 아바타(몸·이름표)를 감춘다 — 카메라가 대상 앞으로 가면 뒤통수·어깨가 화면을 가린다(사용자 지적 2026-09-06).</summary>
        void HideSelf(GameObject player)
        {
            _hiddenSelf.Clear();
            foreach (var r in player.GetComponentsInChildren<Renderer>())
            {
                if (!r.enabled) continue;
                r.enabled = false;
                _hiddenSelf.Add(r);
            }
        }

        void ShowSelf()
        {
            foreach (var r in _hiddenSelf) if (r != null) r.enabled = true;
            _hiddenSelf.Clear();
        }

        static PlayerCameraFollow FindLocalFollow()
        {
            // 소유자만 enabled 라(OnNetworkSpawn) 활성 컴포넌트가 곧 로컬 플레이어의 것이다.
            foreach (var f in FindObjectsByType<PlayerCameraFollow>(FindObjectsSortMode.None))
                if (f.enabled) return f;
            return null;
        }
    }
}
