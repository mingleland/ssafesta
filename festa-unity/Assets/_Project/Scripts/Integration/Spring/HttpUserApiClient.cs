using System;
using System.Text;
using System.Threading.Tasks;
using UnityEngine;
using UnityEngine.Networking;

namespace Festa.Integration
{
    /// <summary>
    /// Spring User/Session API 실 HTTP 구현 (spec 013 BE plan · spec 002 BE plan 계약 확정분).
    ///
    /// **인증 실패를 조용히 삼키지 않는다.** 401/403 은 Mock 으로 폴백하지 않고 로그·상태로 드러낸다 —
    /// 조용한 기본값 복귀가 T-24 의 원인이었다. 저장 실패를 성공으로 보고하면
    /// 사용자는 외형이 저장된 줄 알고 재접속에서 잃는다.
    ///
    /// WebGL: 다른 오리진이면 서버 CORS 헤더가 필요하고, HTTPS 페이지에서는 HTTPS API 만 호출된다.
    /// </summary>
    public class HttpUserApiClient : IUserApiClient
    {
        const int TimeoutSeconds = 10;

        readonly string _baseUrl;
        readonly IAccessTokenProvider _tokenProvider;

        /// <summary>마지막 인증 실패(401/403) 사유. UI 가 오류 상태를 표시할 때 읽는다.</summary>
        public string LastAuthError { get; private set; }

        public HttpUserApiClient(string baseUrl, IAccessTokenProvider tokenProvider = null)
        {
            _baseUrl = (baseUrl ?? string.Empty).TrimEnd('/');
            _tokenProvider = tokenProvider ?? new EmptyAccessTokenProvider();
        }

        /// <summary>
        /// GET /api/v1/users/me — 응답에 avatarCode 가 실려 온다 (신규 사용자는 null).
        /// 서버는 기본값을 만들지 않으므로 null 은 정상이며, 호출자가 카탈로그 기본값을 고른다.
        /// </summary>
        public async Task<UserProfileDto> GetMyProfileAsync()
        {
            using var request = UnityWebRequest.Get($"{_baseUrl}/api/v1/users/me");
            var body = await SendAsync(request, "GET /users/me");
            if (body == null) return null;

            var profile = ParseOrNull<UserProfileDto>(body, "users/me");
            // status·providers 등 Unity 가 안 쓰는 필드는 JsonUtility 가 무시한다.
            return profile;
        }

        /// <summary>
        /// PUT /api/v1/users/me/avatar — 200 응답은 저장한 값을 그대로 echo 한다.
        /// 왕복 무손실이 계약(data-model §2)이라, echo 가 보낸 값과 다르면 저장을 신뢰하지 않는다.
        /// </summary>
        public async Task<bool> UpdateMyAvatarAsync(string encodedAppearance)
        {
            if (string.IsNullOrEmpty(encodedAppearance))
            {
                Debug.LogError("[HttpUserApi] 아바타 저장 거부 — 빈 문자열 (서버도 400 으로 거부한다)");
                return false;
            }

            var payload = JsonUtility.ToJson(new AvatarUpdateRequest { avatarCode = encodedAppearance });

            using var request = new UnityWebRequest($"{_baseUrl}/api/v1/users/me/avatar", UnityWebRequest.kHttpVerbPUT)
            {
                uploadHandler = new UploadHandlerRaw(Encoding.UTF8.GetBytes(payload)),
                downloadHandler = new DownloadHandlerBuffer(),
            };
            request.SetRequestHeader("Content-Type", "application/json");

            var body = await SendAsync(request, "PUT /users/me/avatar");
            if (body == null) return false;

            var echo = ParseOrNull<AvatarUpdateRequest>(body, "users/me/avatar");
            if (echo == null) return false;

            if (echo.avatarCode != encodedAppearance)
            {
                // 서버가 정규화·트림을 했다는 뜻 — 계약 위반이라 성공으로 처리하면 안 된다.
                Debug.LogError("[HttpUserApi] 저장 echo 불일치 — 왕복 무손실 계약 위반. " +
                               $"보낸 길이={encodedAppearance.Length} 받은 길이={echo.avatarCode?.Length ?? -1}");
                return false;
            }

            return true;
        }

        /// <summary>
        /// POST /api/v1/world-sessions — 구조화 endpoint + 120초 1회용 connection token.
        /// Unity 는 endpoint 를 하드코딩하지 않고 항상 이 응답만 쓴다 (헌법 8조).
        /// 본문은 전체 optional 이지만 worldId 를 명시해 서버 기본값에 의존하지 않는다.
        /// </summary>
        public async Task<WorldSessionDto> CreateWorldSessionAsync()
        {
            var payload = JsonUtility.ToJson(new WorldSessionRequest { worldId = "11F" });

            using var request = new UnityWebRequest($"{_baseUrl}/api/v1/world-sessions", UnityWebRequest.kHttpVerbPOST)
            {
                uploadHandler = new UploadHandlerRaw(Encoding.UTF8.GetBytes(payload)),
                downloadHandler = new DownloadHandlerBuffer(),
            };
            request.SetRequestHeader("Content-Type", "application/json");

            var body = await SendAsync(request, "POST /world-sessions");
            if (body == null) return null;

            var session = ParseOrNull<WorldSessionDto>(body, "world-sessions");
            if (session == null) return null;

            if (session.endpoint == null || string.IsNullOrEmpty(session.endpoint.host) || session.endpoint.port == 0)
            {
                Debug.LogError("[HttpUserApi] world-session 응답에 endpoint 가 없다 — 접속 불가");
                return null;
            }

            return session;
        }

        // ── 공통부 ────────────────────────────────────────────────

        /// <summary>
        /// 전송 공통부. 실패는 null 을 돌려주되 **원인을 반드시 로그로 남긴다.**
        /// 401/403 은 LastAuthError 에도 적어 UI 가 "로그인이 필요하다"를 표시할 수 있게 한다.
        /// </summary>
        async Task<string> SendAsync(UnityWebRequest request, string what)
        {
            request.timeout = TimeoutSeconds;

            var token = _tokenProvider.GetAccessToken();
            if (!string.IsNullOrEmpty(token))
                request.SetRequestHeader("Authorization", $"Bearer {token}");

            try
            {
                await request.SendWebRequest();
            }
            catch
            {
                // WebGL 에서 실패한 요청의 await 가 예외를 던질 수 있다 — 아래 분기에서 처리한다.
            }

            switch (request.result)
            {
                case UnityWebRequest.Result.Success:
                    LastAuthError = null;
                    return request.downloadHandler.text;

                case UnityWebRequest.Result.ProtocolError when request.responseCode == 401:
                    LastAuthError = string.IsNullOrEmpty(token)
                        ? "Access Token 이 주입되지 않았다 (WebGL 호스트 wiring 확인)"
                        : "Access Token 이 만료·무효다";
                    Debug.LogError($"[HttpUserApi] {what} → 401 UNAUTHORIZED. {LastAuthError}");
                    return null;

                case UnityWebRequest.Result.ProtocolError when request.responseCode == 403:
                    LastAuthError = "게스트 계정은 이 기능을 쓸 수 없다 (MEMBER_ONLY)";
                    Debug.LogError($"[HttpUserApi] {what} → 403. {LastAuthError}");
                    return null;

                case UnityWebRequest.Result.ProtocolError:
                    // 400 VALIDATION_FAILED 등 — 서버 봉투에 사유가 들어 있어 본문째로 남긴다.
                    Debug.LogError($"[HttpUserApi] {what} → HTTP {request.responseCode}: {request.downloadHandler?.text}");
                    return null;

                default: // ConnectionError, DataProcessingError, timeout
                    Debug.LogError($"[HttpUserApi] {what} 실패: {request.error} (CORS·네트워크·타임아웃 확인)");
                    return null;
            }
        }

        static T ParseOrNull<T>(string body, string who) where T : class
        {
            try
            {
                var parsed = JsonUtility.FromJson<T>(body);
                if (parsed == null) Debug.LogError($"[HttpUserApi] {who}: 응답 JSON 이 비었다");
                return parsed;
            }
            catch (Exception e)
            {
                Debug.LogError($"[HttpUserApi] {who}: 응답 JSON 파싱 실패 — {e.Message}");
                return null;
            }
        }

        // JsonUtility 는 익명 타입을 못 쓴다 — 요청/echo 본문용 최소 타입.
        [Serializable]
        class AvatarUpdateRequest
        {
            public string avatarCode;
        }

        [Serializable]
        class WorldSessionRequest
        {
            public string worldId;
        }
    }
}
