using System.Runtime.InteropServices;
using UnityEngine;

namespace Festa.Integration
{
    /// <summary>
    /// Unity 안에서 열리는 <b>모달 화면 상태</b>를 호스트(React)에 알리고, 호스트의 "최상위 모달 닫기" 요청을 받는다
    /// (G-8-2 반대 방향, GitLab #132 2026-09-10 제안 계약).
    ///
    /// <para><b>왜 필요한가.</b> FE 의 ESC 판정은 FE 스토어(Overlay Bus·gameClientUi)만 본다. Unity 가 초점 카메라나
    /// 미니게임 HUD 를 쥐고 있어도 FE 에게는 'world' 로 보여 ESC 한 번에 GameMenu 가 함께 열렸다(ESC 이중 소비).
    /// <c>captureAllKeyboardInput=false</c> 라 FE 가 항상 ESC 를 받는 비대칭은 되돌릴 수 없으므로,
    /// Unity 가 자기 모달 상태를 밀어 주고 FE 가 중재한다.</para>
    ///
    /// <para><b>상태와 명령을 분리한다.</b> 상태(<see cref="Publish"/>)는 값이 <b>바뀔 때만</b> 밀고, 닫기는
    /// 호스트의 명시적 요청(<see cref="RequestExitWorldUi"/>)으로만 한다. "상태가 false 가 됐으니 남의 기능도 닫는다"
    /// 가 09-08 슬롯머신 자동 종료 사고였다 — <see cref="InputBridge"/> 의 owner-set 은 건드리지 않는다.</para>
    ///
    /// <code>
    /// Unity → FE : window.FestaUnity.onWorldUiState('{"focus":true,"minigame":false}')
    /// FE → Unity : unityInstance.SendMessage('WorldUiBridge', 'RequestExitWorldUi', 'esc')
    /// </code>
    ///
    /// <para>씬을 고치지 않는다 — InputBridge·AuthBridge 와 같은 자동 등록 오브젝트다.</para>
    /// </summary>
    public class WorldUiBridge : MonoBehaviour
    {
        public const string ObjectName = "WorldUiBridge";

#if UNITY_WEBGL && !UNITY_EDITOR && !UNITY_SERVER
        [DllImport("__Internal")]
        static extern void FestaNotifyWorldUiState(string json);
#endif

        /// <summary>미니게임 HUD 가 떠 있는지 알려 주는 공급자 — HUD 쪽이 Open/Close 에서 <see cref="Publish"/> 를 부른다.</summary>
        public static System.Func<bool> MinigameOpen;

        /// <summary>미니게임 HUD 를 닫는 명령 — HUD 가 열릴 때 등록하고 닫힐 때 비운다. 없으면 초점만 닫는다.</summary>
        public static System.Action CloseMinigame;

        static bool s_lastFocus, s_lastMinigame, s_published;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        static void AutoRegister()
        {
#if UNITY_SERVER
            return;
#else
            if (GameObject.Find(ObjectName) != null) return;
            var go = new GameObject(ObjectName);
            go.AddComponent<WorldUiBridge>();
            DontDestroyOnLoad(go);
#endif
        }

        /// <summary>
        /// 현재 모달 상태를 계산해 <b>바뀌었을 때만</b> 호스트로 민다. 초점 카메라·미니게임 HUD 가 열고 닫을 때 부른다.
        /// 매 프레임 부르지 않는다 — 전이 push 계약이다(#132).
        /// </summary>
        public static void Publish()
        {
            bool focus = Festa.World.InteractionFocusCamera.IsFocused;
            bool minigame = MinigameOpen != null && MinigameOpen();
            if (s_published && focus == s_lastFocus && minigame == s_lastMinigame) return;
            s_published = true; s_lastFocus = focus; s_lastMinigame = minigame;

            string json = "{\"focus\":" + (focus ? "true" : "false") + ",\"minigame\":" + (minigame ? "true" : "false") + "}";
#if UNITY_WEBGL && !UNITY_EDITOR && !UNITY_SERVER
            try { FestaNotifyWorldUiState(json); }
            catch (System.Exception ex) { Debug.LogError($"[WorldUiBridge] onWorldUiState 송신 실패: {ex.Message}"); }
#else
            Debug.Log($"[WorldUiBridge] onWorldUiState {json} (에디터 — 호스트 없음)");
#endif
        }

        /// <summary>
        /// 호스트 → Unity. <b>최상위 모달 하나만</b> 닫는다: 미니게임 HUD 가 있으면 그것, 없으면 초점 카메라.
        /// 둘 다 없으면 아무 일도 없다 — FE 가 3단계 중재(FE 레이어 → Unity 모달 → GameMenu)에서 2단계일 때만 부른다.
        /// 닫힌 쪽이 <see cref="Publish"/> 를 부르므로 상태는 따로 밀지 않는다.
        /// </summary>
        public void RequestExitWorldUi(string reason)
        {
            if (MinigameOpen != null && MinigameOpen() && CloseMinigame != null)
            {
                Debug.Log($"[WorldUiBridge] RequestExitWorldUi({reason}) → 미니게임 HUD 닫기");
                CloseMinigame();
                return;
            }
            if (Festa.World.InteractionFocusCamera.IsFocused)
            {
                Debug.Log($"[WorldUiBridge] RequestExitWorldUi({reason}) → 초점 해제");
                Festa.World.InteractionFocusCamera.Release();
                return;
            }
            Debug.Log($"[WorldUiBridge] RequestExitWorldUi({reason}) — 닫을 Unity 모달이 없다");
        }
    }
}
