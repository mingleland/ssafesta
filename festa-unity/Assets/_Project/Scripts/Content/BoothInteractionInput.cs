using UnityEngine;
using Bridge = Festa.Integration.BoothInteractBridge;
#if ENABLE_INPUT_SYSTEM
using UnityEngine.InputSystem;
#endif

namespace Festa.Content
{
    /// <summary>
    /// 부스 오브젝트 상호작용(F 키)을 한 곳에서 감지해 대상에게 전달한다.
    ///
    /// 왜 `OnMouseDown` 을 쓰지 않는가 (T-166) —
    /// WebGL 빌드에서 레거시 마우스 메시지가 디스패치되지 않는다. 콜라이더·배선·시야를
    /// 모두 실측으로 확인하고 카메라 태그까지 고쳐 재빌드했으나 발생하지 않았다.
    /// `OnMouseXXX` 는 레거시 입력 백엔드에 의존하므로, 새 Input System 포인터를
    /// 직접 읽고 `Physics.Raycast` 로 대상을 찾는 방식으로 바꿨다.
    ///
    /// 파츠마다 콜라이더에 중계 컴포넌트를 붙이던 구조도 함께 없앴다. 중앙에서
    /// 레이캐스트하면 설문·투표 등 다른 상호작용 파츠도 같은 경로를 쓸 수 있다.
    ///
    /// 런타임에 Local Spawn 되는 부스 오브젝트가 대상이므로 씬 배선을 요구하지 않는다.
    /// 첫 상호작용 오브젝트가 <see cref="Ensure"/> 로 자기 자신을 만들어 세운다.
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class BoothInteractionInput : MonoBehaviour
    {
        const float MaxRayDistance = 5000f; // 월드 스케일이 1 m = 10 unit 이라 넉넉히 둔다

        static BoothInteractionInput _instance;

        /// <summary>디스패처가 씬에 있도록 보장한다. 상호작용 오브젝트가 Awake 에서 호출한다.</summary>
        public static void Ensure()
        {
            if (_instance != null) return;

            _instance = FindAnyObjectByType<BoothInteractionInput>();
            if (_instance != null) return;

            var go = new GameObject("@BoothInteractionInput");
            _instance = go.AddComponent<BoothInteractionInput>();
            // 씬 전환에도 남기지 않는다 — 부스 오브젝트와 생애를 맞춘다.
        }

        void OnEnable() => Festa.Integration.BoothInteractBridge.OnSent += OnBridgeSent;
        void OnDisable() => Festa.Integration.BoothInteractBridge.OnSent -= OnBridgeSent;

        void OnDestroy()
        {
            _ring.Dispose();
            if (_instance == this) _instance = null;
        }

        Festa.Booth.BoothInteractionTarget _hovered;

        void Update()
        {
            var cam = ResolveCamera();
            if (cam == null) return;

            // 화면(미니게임 HUD·호스트 Overlay)이 열려 있으면 조준·프롬프트도 멈춘다 — 잠금 중에 프롬프트가
            // 겹쳐 떠 있으면 "눌러도 안 된다" 로 보인다 (S15P21A604-437).
            if (Festa.Integration.InputBridge.IsLocked)
            {
                UpdateHover(null);
                ShowHint(null);
                return;
            }

            bool interactKey = InteractKeyPressedThisFrame();

            // ── 1순위: 마우스 조준 ──────────────────────────────
            // 조준 중인 대상이 사거리 안이면 그것이 타깃이다 — 여러 대상이 겹칠 때
            // 플레이어가 명시적으로 고를 수 있는 유일한 수단이라 근접보다 우선한다.
            Festa.Booth.BoothInteractionTarget targeted = null;
            if (TryReadPointer(out var pointerPosition)
                && Physics.Raycast(cam.ScreenPointToRay(pointerPosition), out var hit, MaxRayDistance))
            {
                var aimed = hit.collider.GetComponentInParent<Festa.Booth.BoothInteractionTarget>();
                // **사거리 밖이면 대상으로 치지 않는다.** 전에는 화면에 보이기만 하면 눌렸다 —
                // 6.5 m 떨어진 부스가 열리는 것을 실측으로 확인했다.
                if (aimed != null && aimed.Interactive && IsInRange(aimed)) targeted = aimed;
            }

            // ── 2순위: 근접 자동 조준 (S15P21A604-346) ──────────
            // 마우스를 올리지 않아도 사거리 안에 들어오면 자동으로 잡힌다 — 3인칭 걷기에서
            // "가까이 가면 F" 가 기대 동작이고, 마우스 조준을 요구하면 상호작용이 없는 것처럼
            // 보인다 (실 BE 첫 걷기에서 실측된 혼란). 조준이 없을 때만 근접으로 채운다.
            targeted ??= NearestInteractableInRange();

            UpdateHover(targeted);
            ShowHint(targeted);

            // 실행은 F 키로만 한다 (S15P21A604-323). 포인터·근접은 조준에만 쓴다 —
            // 클릭을 실행에 쓰면 3인칭 카메라 조작·UI 클릭과 경쟁해 오조작이 난다.
            if (!interactKey || targeted == null) return;
            var interactable = targeted.GetComponentInParent<IBoothInteractable>()
                            ?? targeted.GetComponentInChildren<IBoothInteractable>(true);
            interactable?.Interact();
        }

        /// <summary>사거리 안에서 가장 가까운 F 응답 대상. 없으면 null.</summary>
        static Festa.Booth.BoothInteractionTarget NearestInteractableInRange()
        {
            var origin = InteractionOrigin();
            if (origin == null) return null;

            Festa.Booth.BoothInteractionTarget best = null;
            float bestDist = float.MaxValue;
            foreach (var t in Festa.Booth.BoothInteractionTarget.Active)
            {
                if (t == null || !t.Interactive) continue;
                // 표면 기준 — 피벗으로 재면 큰 오브젝트가 부당하게 멀게 잡힌다 (T-232).
                float d = t.DistanceFrom(origin.Value);
                if (d > t.MaxDistance || d >= bestDist) continue;
                best = t;
                bestDist = d;
            }
            return best;
        }

        /// <summary>
        /// 상호작용 사거리 안인가. **카메라가 아니라 플레이어 기준으로 잰다** —
        /// 3인칭이라 카메라는 플레이어보다 몇 m 뒤에 있어서, 카메라 거리로 재면
        /// 눈앞의 대상도 사거리 밖으로 판정된다. 플레이어를 못 찾으면 카메라로 떨어진다.
        /// </summary>
        static bool IsInRange(Festa.Booth.BoothInteractionTarget target)
        {
            var origin = InteractionOrigin();
            if (origin == null) return true;   // 기준을 못 잡으면 막지 않는다 (조용히 잠그지 않는다)
            return target.DistanceFrom(origin.Value) <= target.MaxDistance;
        }

        static Vector3? InteractionOrigin()
        {
            var nm = Unity.Netcode.NetworkManager.Singleton;
            var player = nm != null && nm.LocalClient != null ? nm.LocalClient.PlayerObject : null;
            if (player != null) return player.transform.position;

            var cam = ResolveCamera();
            return cam != null ? cam.transform.position : (Vector3?)null;
        }

        void UpdateHover(Festa.Booth.BoothInteractionTarget next)
        {
            if (ReferenceEquals(_hovered, next)) return;
            if (_hovered != null) _hovered.SetHighlight(false);
            _hovered = next;
            if (_hovered != null) _hovered.SetHighlight(true);
        }

        /// <summary>현재 포인터 화면 좌표. 마우스가 없으면(터치 전용) 실패한다.</summary>
        static bool TryReadPointer(out Vector2 screenPosition)
        {
            screenPosition = default;
#if ENABLE_INPUT_SYSTEM
            var mouse = Mouse.current;
            if (mouse != null) { screenPosition = mouse.position.ReadValue(); return true; }
            var touch = Touchscreen.current;
            if (touch != null && touch.primaryTouch.press.isPressed)
            { screenPosition = touch.primaryTouch.position.ReadValue(); return true; }
            if (mouse != null || touch != null) return false;
#endif
#if ENABLE_LEGACY_INPUT_MANAGER
            screenPosition = Input.mousePosition;
            return true;
#else
            return false;
#endif
        }

        /// <summary>
        /// 상호작용 키(F)가 이번 프레임에 눌렸는가.
        ///
        /// **실행 입력은 F 키 하나다** (S15P21A604-323). 클릭은 조준·호버에만 쓴다.
        /// 클릭을 실행에 쓰면 3인칭 카메라 조작·UI 클릭과 경쟁해 오조작이 난다 —
        /// 미니게임 화면에서 "정지" 를 누르면 뒤 월드가 반응하던 것이 그 예다.
        ///
        /// docs/02 RUNTIME-06 과 spec 016 은 "클릭하면" 으로 적혀 있다.
        /// 이 변경은 그 문서와 어긋나므로 계약 문서 갱신·FE 통지가 함께 가야 한다 (헌법 24조).
        /// </summary>
        static bool InteractKeyPressedThisFrame()
        {
            // 호스트 Overlay 가 열려 있으면 F 를 읽지 않는다 (G-8, InputBridge) — 오버레이 입력창에 'f' 를
            // 치는 것이 뒤의 부스를 열면 안 된다.
            if (Festa.Integration.InputBridge.IsLocked) return false;
#if ENABLE_INPUT_SYSTEM
            var keyboard = Keyboard.current;
            if (keyboard != null) return keyboard.fKey.wasPressedThisFrame;
#endif
#if ENABLE_LEGACY_INPUT_MANAGER
            return Input.GetKeyDown(KeyCode.F);
#else
            return false;
#endif
        }

        // ── 프롬프트 ─────────────────────────────────────────
        // 포털(부스 입장)과 **같은 화면 언어**를 쓴다 — 키캡 패널 + 발밑 링.
        // 전에는 여기만 화면 하단 uGUI 텍스트라, 같은 F 조작인데 다른 기능처럼 보였다
        // (S15P21A604-355 사용자 보고). 그리기는 InteractPromptUI 한 곳에만 있다.
        readonly Festa.World.InteractRing _ring = new Festa.World.InteractRing();
        string _toast;
        float _toastUntil;

        void ShowHint(Festa.Booth.BoothInteractionTarget target)
        {
            if (target == null) { _ring.Hide(); return; }
            var (pos, radius) = target.HighlightFootprint();
            _ring.Show(pos, radius);
        }

        /// <summary>대상별 행동 문구. 포털이 "3번 부스 입장" 을 쓰듯 여기도 무엇을 하는지 적는다.</summary>
        static string PromptFor(Festa.Booth.BoothInteractionTarget target)
        {
            // 부스 오브젝트가 아닌 월드 상호작용이 먼저다 — 관리 데스크는 BoothRuntimeObject 가
            // 없어서(부스 종속이 아니다, S15P21A604-414) 아래 Type 분기로는 잡히지 않는다.
            if (target.GetComponentInParent<Festa.World.ManagementDeskInteractable>() != null)
                return "내 부스 관리";

            var ro = target.GetComponentInParent<Festa.Booth.BoothRuntimeObject>();
            if (ro == null) return "상호작용";
            switch (ro.Type)
            {
                case Festa.Booth.BoothObjectType.Laptop:       return "노트북으로 홈페이지 열기";
                case Festa.Booth.BoothObjectType.AiAgent:      return "AI 직원과 대화";
                case Festa.Booth.BoothObjectType.ProjectPanel: return "프로젝트 전시 보기";
                case Festa.Booth.BoothObjectType.SurveyKiosk:  return "설문 참여하기";
                default: return "상호작용";
            }
        }

        void OnBridgeSent(string type)
        {
            // 가시 결과는 전부 웹 화면 몫이라, 단독 실행에서는 "반응이 없다" 로 보인다.
            // 종류별로 무엇을 보냈는지 알려 준다 (S15P21A604-348).
            _toast =
                type == Bridge.AiAgentInteract    ? "AI 직원 호출을 보냈습니다 — 대화 창은 웹 화면이 엽니다" :
                type == Bridge.ProjectInteract    ? "프로젝트 전시 요청을 보냈습니다 — 웹 화면에서 열립니다" :
                type == Bridge.SurveyInteract     ? "설문 열기 요청을 보냈습니다 — 웹 화면에서 열립니다" :
                type == Bridge.ManagementInteract ? "부스 관리 요청을 보냈습니다 — 웹 화면에서 열립니다" :
                                                    "홈페이지 열기 요청을 보냈습니다 — 웹 화면에서 열립니다";
            _toastUntil = Time.unscaledTime + 2.5f;
        }

        void OnGUI()
        {
            if (_hovered != null) Festa.World.InteractPromptUI.DrawPrompt(PromptFor(_hovered));
            if (_toast != null && Time.unscaledTime <= _toastUntil)
                Festa.World.InteractPromptUI.DrawToast(_toast);
        }

        /// <summary>
        /// 렌더링 중인 카메라를 찾는다. `Camera.main` 은 MainCamera 태그가 없으면 null 이므로
        /// 태그에만 의존하지 않는다 (검증 씬에서 실제로 걸렸던 함정).
        /// </summary>
        static Camera ResolveCamera()
        {
            if (Camera.main != null) return Camera.main;
            if (Camera.current != null) return Camera.current;
            return FindAnyObjectByType<Camera>();
        }
    }
}
