using UnityEngine;
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

        void OnDestroy()
        {
            if (_instance == this) _instance = null;
        }

        Festa.Booth.BoothInteractionTarget _hovered;

        void Update()
        {
            var cam = ResolveCamera();
            if (cam == null) return;

            bool interactKey = InteractKeyPressedThisFrame();
            if (!TryReadPointer(out var pointerPosition)) { UpdateHover(null); return; }

            // 호버·조준용 레이는 매 프레임 한 번만 쏜다.
            bool hasHit = Physics.Raycast(cam.ScreenPointToRay(pointerPosition), out var hit, MaxRayDistance);

            // 콜라이더가 자식에 있어도 루트의 상호작용 컴포넌트를 찾는다.
            var aimed = hasHit ? hit.collider.GetComponentInParent<Festa.Booth.BoothInteractionTarget>() : null;

            // **사거리 밖이면 대상으로 치지 않는다.** 전에는 화면에 보이기만 하면 눌렸다 —
            // 6.5 m 떨어진 부스가 열리는 것을 실측으로 확인했다. MaxDistance 를 두고도
            // 디스패처가 검사하지 않았던 탓이다.
            bool inRange = aimed != null && IsInRange(aimed);
            UpdateHover(inRange ? aimed : null);
            ShowHint(inRange ? aimed : null);

            // 실행은 F 키로만 한다 (S15P21A604-323). 포인터는 조준·호버에만 쓴다 —
            // 클릭을 실행에 쓰면 3인칭 카메라 조작·UI 클릭과 경쟁해 오조작이 난다.
            if (!interactKey) return;
            if (!inRange || !hasHit) return;
            Dispatch(hit.collider);
        }

        /// <summary>타입별 상호작용으로 넘긴다.</summary>
        static void Dispatch(Collider collider)
        {
            var laptop = collider.GetComponentInParent<LaptopInteractable>();
            if (laptop != null) { laptop.Interact(); return; }

            var ai = collider.GetComponentInParent<AiNpcInteractable>();
            if (ai != null) { ai.Interact(); return; }

            // spec 014 미니게임. 타입 분기 일반화는 S15P21A604-303 의 몫이라 여기서 섞지 않는다.
            var minigame = collider.GetComponentInParent<Festa.Minigame.MinigameInteractable>();
            if (minigame != null) minigame.Interact();
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
            return Vector3.Distance(origin.Value, target.transform.position) <= target.MaxDistance;
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

        // ── 근접 힌트 ────────────────────────────────────────
        // 사거리 안에 상호작용 대상이 들어오면 "F — 상호작용" 을 띄운다.
        // 키가 있다는 걸 알려주지 않으면 F 조작은 없는 기능이나 마찬가지다.
        static UnityEngine.UI.Text s_hint;

        static void ShowHint(Festa.Booth.BoothInteractionTarget target)
        {
            if (target == null)
            {
                if (s_hint != null) s_hint.enabled = false;
                return;
            }
            EnsureHint();
            if (s_hint == null) return;
            s_hint.enabled = true;
        }

        static void EnsureHint()
        {
            if (s_hint != null) return;

            var canvasGo = new GameObject("@InteractHint",
                typeof(Canvas), typeof(UnityEngine.UI.CanvasScaler));
            var canvas = canvasGo.GetComponent<Canvas>();
            canvas.renderMode = RenderMode.ScreenSpaceOverlay;
            // 게임 화면(500)보다 아래 — 모달이 떠 있으면 힌트가 그 위로 올라오면 안 된다.
            canvas.sortingOrder = 100;
            var scaler = canvasGo.GetComponent<UnityEngine.UI.CanvasScaler>();
            scaler.uiScaleMode = UnityEngine.UI.CanvasScaler.ScaleMode.ScaleWithScreenSize;
            scaler.referenceResolution = new Vector2(1920, 1080);
            scaler.matchWidthOrHeight = 0.5f;

            var go = new GameObject("Hint", typeof(RectTransform), typeof(UnityEngine.UI.Text));
            go.transform.SetParent(canvasGo.transform, false);
            s_hint = go.GetComponent<UnityEngine.UI.Text>();
            // 로비·미니게임과 같은 폰트라야 한글이 깨지지 않는다 (T-22 는 IMGUI 한정).
            var font = Resources.Load<Font>("Fonts/MalgunGothicLight");
            s_hint.font = font != null ? font : Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");
            s_hint.text = "F — 상호작용";
            s_hint.fontSize = 30;
            s_hint.fontStyle = FontStyle.Bold;
            s_hint.color = new Color(1f, 0.86f, 0.5f, 1f);
            s_hint.alignment = TextAnchor.MiddleCenter;
            s_hint.horizontalOverflow = HorizontalWrapMode.Overflow;
            s_hint.raycastTarget = false;   // 힌트가 클릭을 먹으면 안 된다

            var rect = s_hint.rectTransform;
            rect.anchorMin = rect.anchorMax = new Vector2(0.5f, 0f);
            rect.pivot = new Vector2(0.5f, 0f);
            rect.sizeDelta = new Vector2(600, 48);
            rect.anchoredPosition = new Vector2(0f, 150f);
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
