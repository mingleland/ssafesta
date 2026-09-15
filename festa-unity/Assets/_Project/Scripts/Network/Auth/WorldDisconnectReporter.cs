using UnityEngine;

namespace Festa.Network
{
    /// <summary>
    /// 클라이언트가 왜 끊겼는지 드러낸다 (S15P21A604-231).
    ///
    /// **`NetworkManager.DisconnectReason` 을 아무도 읽지 않고 있었다.** 서버가 거부 사유를
    /// 실어 보내는데 클라이언트 쪽에서 소비하는 코드가 없어, 사용자 눈에는 이유 없이
    /// 튕기는 것으로만 보였다.
    ///
    /// `S15P21A604-85` 로 입장 검증이 켜지면서 이게 훨씬 중요해졌다. 그 전에는 거부가 사실상
    /// 없었지만(빈 문자열만 아니면 통과) 이제는 만료·재사용·서명 불일치로 **실제로 거부된다.**
    /// 이유를 안 보여주면 "가끔 접속이 안 된다" 라는 재현 불가능한 신고가 된다 (T-24).
    ///
    /// 화면 UI 는 아직 없다. 여기서는 **로그와 상태를 남기는 것까지** 하고, UI 가 생기면
    /// <see cref="LastMessage"/> 를 읽어 띄우면 된다.
    ///
    /// 컴포넌트가 아니라 정적 클래스인 이유: 씬에 오브젝트를 하나 더 놓아야 동작하는 구조면
    /// **씬 배치를 빠뜨렸을 때 조용히 아무 일도 안 한다.** 이미 접속 이벤트를 구독하고 있는
    /// `ConnectionManager` 가 호출하게 두면 그럴 여지가 없다.
    /// </summary>
    public static class WorldDisconnectReporter
    {
        /// <summary>마지막 접속 종료 사유(서버가 보낸 원문). 정상 종료·전송 단절이면 빈 문자열이다.</summary>
        public static string LastReason { get; private set; } = "";

        /// <summary>사용자에게 보여줄 문구. UI 가 생기면 이걸 쓴다.</summary>
        public static string LastMessage { get; private set; } = "";

        /// <summary>내 접속이 끊겼을 때 호출한다. <paramref name="reason"/> 은 서버가 보낸 원문.</summary>
        public static void ReportLocalDisconnect(string reason)
        {
            LastReason = reason ?? "";
            LastMessage = Explain(LastReason);

            if (string.IsNullOrEmpty(LastReason))
            {
                // 사유가 비어 있는 것도 정보다 — 서버가 거부한 게 아니라 전송이 끊긴 쪽에 가깝다.
                LastMessage = "서버와의 연결이 끊겼습니다.";
                Debug.LogWarning("[Disconnect] 서버가 사유를 보내지 않았다 — 네트워크 단절이거나 " +
                                 "서버가 응답하지 않는 상태일 수 있다.");
                return;
            }

            Debug.LogWarning($"[Disconnect] {LastReason} — {LastMessage}");
        }

        /// <summary>
        /// 서버 사유 코드를 사람이 읽을 문구로 바꾼다.
        ///
        /// 모르는 코드는 **감추지 않고 그대로 돌려준다.** 임의로 "알 수 없는 오류" 로 뭉개면
        /// 서버가 새 사유를 추가했을 때 그 사실이 화면에서 사라진다.
        /// </summary>
        public static string Explain(string reason) => reason switch
        {
            // 입장 grant 는 120초 1회용이다. 재접속하려면 **새로 발급**받아야 한다 —
            // 같은 토큰을 다시 쓰면 재사용 원장이 거부한다 (S15P21A604-85).
            WorldDisconnectReason.InvalidToken =>
                "입장 권한이 만료되었거나 이미 사용되었습니다. 다시 입장해 주세요.",
            WorldDisconnectReason.ServerFull =>
                "서버 정원이 가득 찼습니다. 잠시 후 다시 시도해 주세요.",
            WorldDisconnectReason.ReplacedBySameUser =>
                "같은 계정이 다른 곳에서 접속했습니다.",
            _ => reason,
        };
    }
}
