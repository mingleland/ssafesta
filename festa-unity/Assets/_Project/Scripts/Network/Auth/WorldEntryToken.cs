using System;

namespace Festa.Network
{
    /// <summary>
    /// 검증을 통과한 월드 입장 grant 의 클레임.
    ///
    /// **이 값들이 신원의 정본이다.** Backend 가 계정 행(회원) 또는 Access Token subject(게스트)에서
    /// 직접 만들어 서명한 것이고, 발급 코드 주석이 게임 서버에게
    /// *"클라이언트가 준 userId·nickname·외형을 무시하고 이 클레임을 쓰라"* 고 지시한다 (헌법 16조).
    ///
    /// 그래서 승인 후 <see cref="ToPayload"/> 로 만든 payload 를 쓰고,
    /// 클라이언트가 보낸 <see cref="ConnectionPayload"/> 의 신원 필드는 버린다.
    /// </summary>
    public readonly struct WorldEntryToken
    {
        /// <summary>재사용 차단용 1회성 식별자 (spec 002 FR-014).</summary>
        public readonly string Jti;
        public readonly string Subject;
        public readonly string Role;
        public readonly string PlayerId;
        public readonly string Nickname;
        public readonly string SessionId;
        public readonly string WorldId;
        public readonly string ChannelId;

        /// <summary>저장된 외형. 게스트이거나 저장한 적이 없으면 null 이다 — 빈 문자열이 아니다.</summary>
        public readonly string AvatarCode;

        /// <summary>만료 시각(Unix 초). 원장이 만료된 항목을 정리할 때도 쓴다.</summary>
        public readonly long ExpiresAtUnix;

        public WorldEntryToken(string jti, string subject, string role, string playerId,
                               string nickname, string sessionId, string worldId, string channelId,
                               string avatarCode, long expiresAtUnix)
        {
            Jti = jti;
            Subject = subject;
            Role = role;
            PlayerId = playerId;
            Nickname = nickname;
            SessionId = sessionId;
            WorldId = worldId;
            ChannelId = channelId;
            AvatarCode = avatarCode;
            ExpiresAtUnix = expiresAtUnix;
        }

        /// <summary>
        /// 클레임에서 접속 payload 를 만든다.
        ///
        /// <paramref name="connectionToken"/> 은 원문 그대로 옮긴다 — 이후 단계에서 다시 쓰지는
        /// 않지만, 세션 저장소가 보관하는 payload 모양을 바꾸지 않기 위해서다.
        /// </summary>
        public ConnectionPayload ToPayload(string connectionToken) => new ConnectionPayload
        {
            // playerId 는 문자열이다. 숫자가 아니면(게스트 등) 0 으로 두고 신원은 subject 로 남는다.
            userId = long.TryParse(PlayerId, out var id) ? id : 0L,
            nickname = Nickname,
            avatarCode = AvatarCode,
            connectionToken = connectionToken,
        };

        public override string ToString() =>
            // 토큰 원문·서명은 절대 남기지 않는다 (계약: Secret·토큰 원문 미수집).
            $"sub={Subject} role={Role} session={SessionId} channel={ChannelId} exp={ExpiresAtUnix}";

        public static long UnixNow() => DateTimeOffset.UtcNow.ToUnixTimeSeconds();
    }
}
