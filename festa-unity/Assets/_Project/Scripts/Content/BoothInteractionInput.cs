using UnityEngine;
#if ENABLE_INPUT_SYSTEM
using UnityEngine.InputSystem;
#endif

namespace Festa.Content
{
    /// <summary>
    /// 부스 오브젝트 클릭을 한 곳에서 감지해 대상에게 전달한다.
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

        void Update()
        {
            if (!TryReadPress(out var screenPosition)) return;

            var cam = ResolveCamera();
            if (cam == null) return;

            var ray = cam.ScreenPointToRay(screenPosition);
            if (!Physics.Raycast(ray, out var hit, MaxRayDistance)) return;

            // 콜라이더가 자식에 있어도 루트의 상호작용 컴포넌트를 찾는다.
            var laptop = hit.collider.GetComponentInParent<LaptopInteractable>();
            if (laptop != null) laptop.Interact();
        }

        /// <summary>
        /// 이번 프레임에 눌렸는지와 화면 좌표를 읽는다.
        /// Input System 이 켜져 있으면 그쪽을 먼저 쓰고, 없으면 레거시로 떨어진다.
        /// </summary>
        static bool TryReadPress(out Vector2 screenPosition)
        {
            screenPosition = default;

#if ENABLE_INPUT_SYSTEM
            var mouse = Mouse.current;
            if (mouse != null && mouse.leftButton.wasPressedThisFrame)
            {
                screenPosition = mouse.position.ReadValue();
                return true;
            }

            var touch = Touchscreen.current;
            if (touch != null && touch.primaryTouch.press.wasPressedThisFrame)
            {
                screenPosition = touch.primaryTouch.position.ReadValue();
                return true;
            }

            // Input System 이 활성인데 장치가 잡히면 레거시로 내려가지 않는다 —
            // 두 백엔드가 함께 켜진 경우(Both) 같은 클릭이 두 번 처리되는 것을 막는다.
            if (mouse != null || touch != null) return false;
#endif

#if ENABLE_LEGACY_INPUT_MANAGER
            if (Input.GetMouseButtonDown(0))
            {
                screenPosition = Input.mousePosition;
                return true;
            }
#endif
            return false;
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
