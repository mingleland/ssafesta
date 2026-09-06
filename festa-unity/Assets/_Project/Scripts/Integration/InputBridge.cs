using UnityEngine;

namespace Festa.Integration
{
    /// <summary>
    /// WebGL 호스트(React) ↔ Unity 입력 소유권 경계 (G-8, GitLab #132).
    ///
    /// 호스트가 Overlay 를 열면 월드 입력을 멈추고, 닫으면 다시 켠다:
    ///   unityInstance.SendMessage('InputBridge', 'SetInputLocked', '1')   // 잠금
    ///   unityInstance.SendMessage('InputBridge', 'SetInputLocked', '0')   // 해제
    ///
    /// 잠금은 **읽는 쪽이 지킨다** — <see cref="IsLocked"/> 를 보는 곳은
    /// PlayerMovement(WASD·Shift·Space)·BoothInteractionInput(F)·PlayerEmoteController(Alt+클릭) 셋이다.
    /// 입력 장치를 끄지 않는 이유: Input System 을 비활성화하면 호스트가 다시 켜 줄 때까지
    /// 카메라·UI 까지 함께 죽고, 재개 순서를 호스트에 의존하게 된다. 플래그 하나가 더 단순하고 되돌리기 쉽다.
    ///
    /// 함께 하는 일: WebGL 에서 <c>WebGLInput.captureAllKeyboardInput</c> 을 **false** 로 둔다.
    /// 기본값(true)은 페이지 전체의 키 입력을 Unity 가 가로채므로 React 입력 필드(설문 주관식·상담
    /// 메시지·닉네임)에 글자가 들어가지 않는다. false 면 canvas 가 focus 를 가질 때만 키를 받는다 —
    /// canvas 는 <c>tabIndex=-1</c>(-421)이라 클릭·프로그램으로 focus 를 받을 수 있고, 호스트는
    /// Overlay 를 닫을 때 canvas 로 focus 를 돌려준다(-428).
    ///
    /// 씬을 고치지 않는다 — AuthBridge 와 같은 자동 등록 오브젝트다. 상태는 static 이라 씬 전환에도 유지된다.
    /// </summary>
    public class InputBridge : MonoBehaviour
    {
        public const string ObjectName = "InputBridge";

        /// <summary>true 면 월드 입력(이동·상호작용·이모트)을 읽지 않는다.</summary>
        public static bool IsLocked { get; private set; }

        /// <summary>잠금 상태가 바뀔 때(true=잠김). 초점 카메라가 호스트의 해제를 따라 풀리는 데 쓴다 (-439·-440).</summary>
        public static event System.Action<bool> LockedChanged;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        static void AutoRegister()
        {
#if UNITY_SERVER
            return;
#else
#if UNITY_WEBGL && !UNITY_EDITOR
            // 페이지의 다른 입력 필드를 살린다. 이 한 줄이 없으면 Overlay 안 <input> 에 타이핑이 안 된다.
            WebGLInput.captureAllKeyboardInput = false;
            Debug.Log("[InputBridge] WebGLInput.captureAllKeyboardInput = false — canvas focus 일 때만 키 입력");
#endif
            if (GameObject.Find(ObjectName) != null) return;
            var go = new GameObject(ObjectName);
            go.AddComponent<InputBridge>();
            DontDestroyOnLoad(go);
#endif
        }

        /// <summary>호스트 → Unity. "1"/"true" 잠금, "0"/"false"/빈 값 해제.</summary>
        public void SetInputLocked(string value)
        {
            bool locked = value == "1" || string.Equals(value, "true", System.StringComparison.OrdinalIgnoreCase);
            if (locked == IsLocked) return;
            IsLocked = locked;
            Debug.Log($"[InputBridge] 월드 입력 {(locked ? "잠금" : "해제")}");
            LockedChanged?.Invoke(locked);
        }

        /// <summary>
        /// Unity 안에서 여는 화면(미니게임 HUD 등)이 직접 잠근다. 호스트 Overlay 와 같은 규칙 —
        /// 열릴 때 true, 닫힐 때 false. 여러 곳이 겹쳐 잠그는 경우는 없다(화면은 동시에 하나).
        /// </summary>
        public static void SetLocked(bool locked)
        {
            if (locked == IsLocked) return;
            IsLocked = locked;
            Debug.Log($"[InputBridge] 월드 입력 {(locked ? "잠금" : "해제")} (Unity 내부)");
            LockedChanged?.Invoke(locked);
        }

        /// <summary>에디터·테스트용 별칭.</summary>
        public static void SetLockedForTesting(bool locked) => SetLocked(locked);
    }
}
