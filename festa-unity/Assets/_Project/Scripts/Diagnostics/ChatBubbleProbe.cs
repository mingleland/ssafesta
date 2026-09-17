// 말풍선 눈으로 확인용 진단 키 — H 를 누르면 내 아바타 위에 표본 말풍선을 띄운다. **에디터 전용이다.**
// 이 파일이 있는 이유: 말풍선은 남이 채팅을 쳐야 보이므로 혼자서는 확인할 수가 없다.
// 서버·다른 사람 없이 모양과 줄바꿈·표시 시간을 확인할 통로가 필요하다.
//
// 파일 전체가 UNITY_EDITOR 로 묶여 있다 — 빌드에는 클래스 자체가 존재하지 않는다. 확인용 도구가
// 사용자 빌드에 남아 H 가 먹히면, 채팅을 치려다 누른 사람에게 영문 모를 말풍선이 뜬다.
#if UNITY_EDITOR
using UnityEngine;
using UnityEngine.InputSystem;
using Unity.Netcode;

namespace Festa.Diagnostics
{
    /// <summary>
    /// <b>H</b> 를 누를 때마다 표본 문장을 하나씩 돌려 가며 내 아바타 머리 위에 말풍선을 띄운다.
    ///
    /// <para><b>내 화면에만 보인다.</b> <see cref="Festa.World.PlayerChatBubble.Show"/> 는 대상 오브젝트에
    /// 컴포넌트를 붙여 글자만 바꾸는 로컬 표시라 네트워크로 나가지 않는다 — 남의 화면에 가짜 채팅이
    /// 뜨지 않고, 서버 채팅 기록도 건드리지 않는다.</para>
    ///
    /// <para>표본은 넷이다. <b>짧은 말·긴 말·줄바꿈이 필요한 말·이모지 섞인 말</b> — 이 넷이면
    /// 폭 제한, 글자 수에 비례하는 표시 시간(3~7초), 한글 SDF 폰트, 이름표와의 겹침을 한 번에 볼 수 있다.</para>
    ///
    /// <para>씬을 고치지 않는다. 스스로 붙으므로 프리팹·씬 배선이 필요 없고, 지울 때도 이 파일만 지우면 된다.</para>
    ///
    /// <para><b>에디터에서만 존재한다.</b> Play 모드로 월드에 들어간 뒤 H 를 누르면 된다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class ChatBubbleProbe : MonoBehaviour
    {
        const KeyCode ClaimedKey = KeyCode.H;

        static readonly string[] Samples =
        {
            "안녕하세요!",
            "이 부스 프로젝트 설명 좀 들을 수 있을까요? 담당자분 계신가요",
            "12번 부스 앞에서 만나요",
            "하이스트라이커 방금 900 넘었다 ㅋㅋㅋ",
        };

        int _next;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Bootstrap()
        {
            var go = new GameObject("@ChatBubbleProbe") { hideFlags = HideFlags.DontSave };
            Object.DontDestroyOnLoad(go);
            go.AddComponent<ChatBubbleProbe>();
        }

        void Awake() => DiagnosticKeys.Claim(nameof(ChatBubbleProbe), ClaimedKey);   // 중복·예약키 검사에 등록 (QA #64)

        void Update()
        {
            var kb = Keyboard.current;
            if (kb == null || !kb.hKey.wasPressedThisFrame) return;

            var nm = NetworkManager.Singleton;
            var me = nm != null && nm.LocalClient != null ? nm.LocalClient.PlayerObject : null;
            if (me == null)
            {
                // 조용히 넘어가지 않는다 — 눌렀는데 아무 일도 없으면 말풍선이 깨진 줄 안다 (T-24).
                Debug.LogWarning("[ChatBubbleProbe] 아직 월드에 들어오지 않아 표본 말풍선을 띄울 수 없다. " +
                                 "접속해서 아바타가 생긴 뒤에 H 를 눌러라.");
                return;
            }

            var message = Samples[_next % Samples.Length];
            _next++;
            Festa.World.PlayerChatBubble.Show(me.gameObject, message);
            Debug.Log($"[ChatBubbleProbe] 표본 말풍선 — \"{message}\" (내 화면에만 보인다, {_next}/{Samples.Length} 순환)");
        }
    }
}
#endif
