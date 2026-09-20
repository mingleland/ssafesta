// React → Unity 월드 채팅 전달 — 채팅 한 줄을 말한 사람 머리 위 말풍선으로 잇는 수신부.
// 이 파일이 있는 이유: 채팅의 전송·수신·화면은 전부 React 가 한다(계약 002 world-chat-api).
// Unity 가 필요한 것은 "누가 말했는가" 를 월드 좌표에 놓는 일뿐이고, 그 한 조각만 여기서 받는다.
using UnityEngine;

namespace Festa.Integration
{
    /// <summary>
    /// 호스트(React)가 받은 월드 채팅을 Unity 로 넘겨 말풍선을 띄운다.
    ///
    /// <code>
    /// unityInstance.SendMessage('WorldChatBridge', 'ReceiveChat',
    ///   JSON.stringify({ senderUserId: 12, nickname: '홍길동', content: '안녕하세요' }));
    /// </code>
    ///
    /// <para><b>계약을 새로 만들지 않았다.</b> payload 세 필드는 서버가 이미
    /// <c>/topic/world/chat</c> 으로 방송하는 <c>WorldChatMessage</c> 그대로다 — React 는 자기가 받은
    /// 객체를 그대로 넘기면 되고, 필드를 새로 만들거나 고칠 필요가 없다. <c>sentAt</c> 처럼 남는 필드는
    /// 무시한다.</para>
    ///
    /// <para><b>누구인지는 <see cref="Festa.Network.NetworkPlayer.UserId"/> 로 가린다.</b> 서버가 조회해
    /// 기록한 값이라 사칭이 되지 않는다. 닉네임 대조는 <c>senderUserId</c> 가 0 으로 비어 올 때의
    /// 폴백일 뿐이다 — 닉네임은 겹칠 수 있어 기본 경로로 쓰지 않는다.</para>
    ///
    /// <para>보낸 사람이 지금 이 월드에 없으면(다른 층·이미 나감) 조용히 버린다. 화면 로그는 React 가
    /// 이미 그렸고, 월드에 없는 사람의 말풍선을 어딘가에 띄울 자리는 없다.</para>
    ///
    /// <para>씬을 고치지 않는다 — <see cref="InputBridge"/> 와 같은 자동 등록 오브젝트다.</para>
    /// </summary>
    public class WorldChatBridge : MonoBehaviour
    {
        public const string ObjectName = "WorldChatBridge";

        [System.Serializable]
        struct ChatPayload
        {
            public long senderUserId;
            public string nickname;
            public string content;
        }

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        static void AutoRegister()
        {
#if UNITY_SERVER
            return;
#else
            if (GameObject.Find(ObjectName) != null) return;
            var go = new GameObject(ObjectName);
            go.AddComponent<WorldChatBridge>();
            DontDestroyOnLoad(go);
#endif
        }

        /// <summary>호스트가 받은 채팅 한 줄(JSON). 파싱에 실패하면 버리되 조용히 넘기지 않는다.</summary>
        public void ReceiveChat(string json)
        {
            if (string.IsNullOrWhiteSpace(json)) return;

            ChatPayload payload;
            try { payload = JsonUtility.FromJson<ChatPayload>(json); }
            catch (System.Exception e)
            {
                Debug.LogWarning($"[WorldChatBridge] 채팅 payload 를 읽지 못했다 — {e.Message}");
                return;
            }

            if (string.IsNullOrWhiteSpace(payload.content)) return;

            var speaker = FindSpeaker(payload.senderUserId, payload.nickname);
            if (speaker == null) return;   // 이 월드에 없는 사람이다 — 화면 로그는 React 가 이미 그렸다

            Festa.World.PlayerChatBubble.Show(speaker.gameObject, payload.nickname, payload.content);
        }

        static Festa.Network.NetworkPlayer FindSpeaker(long userId, string nickname)
        {
            var players = Object.FindObjectsByType<Festa.Network.NetworkPlayer>(FindObjectsSortMode.None);
            if (userId > 0)
                foreach (var p in players)
                    if (p != null && p.UserId.Value == userId) return p;

            if (string.IsNullOrWhiteSpace(nickname)) return null;
            var wanted = nickname.Trim();
            foreach (var p in players)
                if (p != null && string.Equals(p.Nickname.Value.ToString(), wanted, System.StringComparison.Ordinal)) return p;
            return null;
        }
    }
}
