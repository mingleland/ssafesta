namespace Festa.Integration
{
    /// <summary>
    /// Authorization 토큰 주입 경계. 로그인 구현 전에는 Mock/Empty를 사용하고,
    /// Auth 기능 spec 확정 후 실제 Access Token 저장소 구현으로 교체한다.
    /// 빈 문자열/null 반환 시 Authorization 헤더를 붙이지 않는다.
    /// </summary>
    public interface IAccessTokenProvider
    {
        string GetAccessToken();
    }

    /// <summary>로그인 구현 전 기본값 — 헤더 미부착.</summary>
    public class EmptyAccessTokenProvider : IAccessTokenProvider
    {
        public string GetAccessToken() => null;
    }

    /// <summary>헤더 부착 경로 테스트용.</summary>
    public class MockAccessTokenProvider : IAccessTokenProvider
    {
        public string GetAccessToken() => "mock-access-token";
    }
}
