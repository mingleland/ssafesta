namespace Festa.Network
{
    /// <summary>
    /// 서버가 접속을 거부·종료할 때 실어 보내는 사유 코드.
    ///
    /// **입장 계약의 일부라 여기(Auth)에 둔다.** 승인 로직과 사유 코드가 서로 다른 어셈블리에
    /// 흩어져 있으면 한쪽만 고치고 다른 쪽을 잊기 쉽다.
    /// <see cref="ConnectionManager"/> 의 기존 상수는 이 값을 가리키는 별칭으로 남겨 뒀다 —
    /// 이미 그 이름을 쓰던 코드를 깨지 않기 위해서다.
    /// </summary>
    public static class WorldDisconnectReason
    {
        public const string InvalidToken = "INVALID_TOKEN";
        public const string ServerFull = "SERVER_FULL";

        /// <summary>같은 계정이 새로 접속해 밀려났다 (S15P21A604-231).</summary>
        public const string ReplacedBySameUser = "REPLACED_BY_SAME_USER";
    }
}
