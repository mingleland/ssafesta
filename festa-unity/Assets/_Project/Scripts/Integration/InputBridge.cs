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

        /// <summary>
        /// 지금 잠금을 쥐고 있는 주체들. **하나라도 쥐고 있으면 잠긴 것이다.**
        ///
        /// <para>예전에는 <c>bool</c> 하나였고 "화면은 동시에 하나" 라고 가정했다. 그 가정이 틀렸다 —
        /// 슬롯머신 HUD 가 잠근 상태에서 FE 패널을 열었다 닫으면 호스트의 <c>SetInputLocked("0")</c> 가
        /// 그 잠금을 대신 풀어 버리고, <c>LockedChanged(false)</c> 를 받은 미니게임이 스스로 닫혔다.
        /// 사용자에게는 "돌아가던 슬롯머신이 저절로 꺼진다" 로 보인다 (2026-09-08 조사).</para>
        ///
        /// <para>주인 이름으로 세면 각자 자기 것만 놓는다. 같은 주인이 두 번 잠가도 한 번만 센다 —
        /// 해제를 한 번 빠뜨려 영영 잠기는 쪽이 두 번 잠기는 쪽보다 훨씬 나쁘다.</para>
        /// </summary>
        static readonly System.Collections.Generic.HashSet<string> s_holders = new();

        const string HostOwner = "host";

        /// <summary>호스트 → Unity. "1"/"true" 잠금, "0"/"false"/빈 값 해제.</summary>
        public void SetInputLocked(string value)
        {
            bool locked = value == "1" || string.Equals(value, "true", System.StringComparison.OrdinalIgnoreCase);
            SetLocked(locked, HostOwner);
        }

        /// <summary>
        /// Unity 안에서 여는 화면(미니게임 HUD·초점 카메라 등)이 직접 잠근다.
        /// <paramref name="owner"/> 는 그 화면을 가리키는 안정적인 이름이어야 한다 — 잠글 때와 풀 때가 같아야 한다.
        /// </summary>
        public static void SetLocked(bool locked, string owner)
        {
            if (string.IsNullOrEmpty(owner)) owner = "unknown";
            bool before = IsLocked;

            if (locked) s_holders.Add(owner);
            else s_holders.Remove(owner);

            IsLocked = s_holders.Count > 0;
            if (IsLocked == before) return;   // 다른 주인이 아직 쥐고 있으면 상태는 그대로다

            Debug.Log($"[InputBridge] 월드 입력 {(IsLocked ? "잠금" : "해제")} " +
                      $"(요청 '{owner}', 남은 주인 {s_holders.Count})");
            LockedChanged?.Invoke(IsLocked);
        }

        /// <summary>지금 잠금을 쥔 주체 목록. 안 풀리는 잠금을 추적할 때 쓴다.</summary>
        public static string HoldersDescription() =>
            s_holders.Count == 0 ? "(없음)" : string.Join(", ", s_holders);

        /// <summary>에디터·테스트용 별칭.</summary>
        public static void SetLockedForTesting(bool locked) => SetLocked(locked, "test");
    }
}
