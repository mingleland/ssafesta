using System.Text;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// React 프로필에서 확정된 닉네임을 로컬 플레이어의 동기화 컨트롤러에 전달한다.
    ///
    /// 씬에 수동으로 둘 필요가 없다. WebGL 호스트의 SendMessage 대상이 어느 씬에서도 있어야 하므로
    /// 런타임에 한 번 등록하고, 플레이어 스폰 전 요청은 컨트롤러가 받을 때까지 보관한다.
    /// </summary>
    public sealed class ProfileNicknameBridge : MonoBehaviour
    {
        public const string ObjectName = "ProfileNicknameBridge";
        const int MaxUtf8Bytes = 120; // 서버 정책 최대 30 code point × UTF-8 4 byte

        static string s_pendingNickname;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        static void AutoRegister()
        {
#if UNITY_SERVER
            return;
#else
            if (GameObject.Find(ObjectName) != null) return;
            var bridge = new GameObject(ObjectName);
            bridge.AddComponent<ProfileNicknameBridge>();
            DontDestroyOnLoad(bridge);
#endif
        }

        /// <summary>React → Unity. 서버가 저장한 nickname echo만 받는다.</summary>
        public void SetNickname(string nickname)
        {
            var normalized = nickname?.Trim();
            if (string.IsNullOrEmpty(normalized) || Encoding.UTF8.GetByteCount(normalized) > MaxUtf8Bytes)
            {
                Debug.LogError("[ProfileNicknameBridge] 유효하지 않은 닉네임 동기화 요청을 거부했다.");
                return;
            }

            s_pendingNickname = normalized;
            TryApplyPending(null);
        }

        internal static void TryApplyPending(PlayerAppearanceController controller)
        {
            if (string.IsNullOrEmpty(s_pendingNickname)) return;
            if (controller == null)
            {
                var localPlayer = Unity.Netcode.NetworkManager.Singleton?.LocalClient?.PlayerObject;
                controller = localPlayer != null ? localPlayer.GetComponent<PlayerAppearanceController>() : null;
            }
            if (controller == null || !controller.IsOwner) return;

            // 문자열은 React에서 서버 echo인지 판단하지 않는다. 컨트롤러가 Spring에 새 world-session을
            // 요청해 서명된 nickname 클레임만 게임 서버에 전달한다.
            controller.RequestNicknameChange();
            s_pendingNickname = null;
        }
    }
}
