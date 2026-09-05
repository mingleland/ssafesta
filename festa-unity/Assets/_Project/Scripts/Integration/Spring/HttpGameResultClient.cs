using System.Collections.Generic;
using System.Text;
using System.Threading.Tasks;
using UnityEngine;
using UnityEngine.Networking;

namespace Festa.Integration
{
    /// <summary>
    /// 타이밍 스톱 결과 보고의 실서버 구현 (spec 014 T008, S15P21A604-294) — GitLab #134 §2 제안 계약.
    ///
    /// <code>
    /// POST /api/v1/minigames/timer-stop/sessions              → { sessionId, targetSeconds, failAfterSeconds }
    /// POST /api/v1/minigames/timer-stop/sessions/{id}/result  { targetSeconds, stoppedSeconds, errorSeconds, timedOut }
    ///                                                         → { accepted, rewardedCoins, dailyLimitReached, message }
    /// </code>
    ///
    /// <para>BE 가 아직 경로를 만들지 않았다(2026-09-06 로컬 Spring: 404). 404·405 는 <see cref="LastEndpointMissing"/> 로
    /// 표시하고 null 을 돌려준다 — <see cref="ServerFirstGameResultClient"/> 가 그때만 Mock 으로 넘긴다.
    /// 그 밖의 실패는 null + 로그로 표면화한다. 목표 시간을 클라이언트가 지어내지 않는다 (FR-008).</para>
    /// </summary>
    public sealed class HttpGameResultClient : IGameResultClient
    {
        const int TimeoutSeconds = 10;
        readonly string _baseUrl;
        readonly IAccessTokenProvider _tokenProvider;

        /// <summary>직전 호출이 404/405(경로 없음)로 끝났는가.</summary>
        public bool LastEndpointMissing { get; private set; }

        public HttpGameResultClient(string baseUrl, IAccessTokenProvider tokenProvider)
        {
            _baseUrl = (baseUrl ?? string.Empty).TrimEnd('/');
            _tokenProvider = tokenProvider ?? new EmptyAccessTokenProvider();
        }

        [System.Serializable] class StartRequest { public string gameId; }
        [System.Serializable] class ResultRequest { public float targetSeconds; public float stoppedSeconds; public float errorSeconds; public bool timedOut; }

        public async Task<GameSessionDto> StartAsync(string gameId)
        {
            var body = await PostAsync($"{_baseUrl}/api/v1/minigames/timer-stop/sessions",
                                       JsonUtility.ToJson(new StartRequest { gameId = gameId }), "세션 발급");
            if (body == null) return null;
            try
            {
                var s = JsonUtility.FromJson<GameSessionDto>(body);
                if (s == null || string.IsNullOrEmpty(s.sessionId) || s.targetSeconds <= 0f)
                {
                    Debug.LogError($"[HttpGameResult] 세션 응답이 불완전하다: {body}");
                    return null;
                }
                return s;
            }
            catch (System.Exception ex)
            {
                Debug.LogError($"[HttpGameResult] 세션 응답 파싱 실패: {ex.Message} — {body}");
                return null;
            }
        }

        public async Task<GameResultAckDto> ReportAsync(GameResultDto result)
        {
            if (result == null || string.IsNullOrEmpty(result.sessionId))
            {
                Debug.LogError("[HttpGameResult] sessionId 없는 결과는 보고할 수 없다 — 멱등성 키가 없다.");
                return null;
            }

            var payload = JsonUtility.ToJson(new ResultRequest
            {
                targetSeconds = result.targetSeconds,
                stoppedSeconds = result.stoppedSeconds,
                errorSeconds = result.errorSeconds,
                timedOut = result.timedOut,
            });
            var body = await PostAsync($"{_baseUrl}/api/v1/minigames/timer-stop/sessions/{UnityWebRequest.EscapeURL(result.sessionId)}/result",
                                       payload, "결과 보고");
            if (body == null) return null;
            try { return JsonUtility.FromJson<GameResultAckDto>(body); }
            catch (System.Exception ex)
            {
                Debug.LogError($"[HttpGameResult] 결과 응답 파싱 실패: {ex.Message} — {body}");
                return null;
            }
        }

        async Task<string> PostAsync(string url, string payload, string what)
        {
            LastEndpointMissing = false;
            using var request = new UnityWebRequest(url, UnityWebRequest.kHttpVerbPOST)
            {
                uploadHandler = new UploadHandlerRaw(Encoding.UTF8.GetBytes(payload ?? "{}")),
                downloadHandler = new DownloadHandlerBuffer(),
                timeout = TimeoutSeconds,
            };
            request.SetRequestHeader("Content-Type", "application/json");
            var token = _tokenProvider.GetAccessToken();
            if (!string.IsNullOrEmpty(token))
                request.SetRequestHeader("Authorization", $"Bearer {token}");

            try { await request.SendWebRequest(); }
            catch { /* result 로 판정한다 */ }

            switch (request.result)
            {
                case UnityWebRequest.Result.Success:
                    return request.downloadHandler.text;
                case UnityWebRequest.Result.ProtocolError when request.responseCode == 404 || request.responseCode == 405:
                    LastEndpointMissing = true;
                    return null;
                case UnityWebRequest.Result.ProtocolError when request.responseCode == 401:
                    Debug.LogWarning($"[HttpGameResult] {what} → 401. 로그인이 필요하거나 만료됐다.");
                    return null;
                case UnityWebRequest.Result.ProtocolError when request.responseCode == 403:
                    Debug.Log($"[HttpGameResult] {what} → 403 (게스트는 보상 대상이 아니다).");
                    return null;
                case UnityWebRequest.Result.ProtocolError:
                    Debug.LogError($"[HttpGameResult] {what} → HTTP {request.responseCode}: {request.downloadHandler?.text}");
                    return null;
                default:
                    Debug.LogError($"[HttpGameResult] {what} 실패: {request.error}");
                    return null;
            }
        }
    }

    /// <summary>
    /// **서버 우선, 없으면 Mock.** 세션 발급을 BE 에 먼저 묻고 경로가 없을 때(404)만 Mock 세션으로 진행한다.
    /// Mock 이 발급한 세션의 결과는 Mock 에 보고한다 — 서버는 그 sessionId 를 모른다.
    /// 첫 폴백에 경고를 남기고, 그 밖의 실패(401·5xx)는 폴백하지 않는다 (T-24). BE 가 GitLab #134 계약을 올리면
    /// Unity 변경 없이 실서버 판정으로 넘어간다 (S15P21A604-294).
    /// </summary>
    public sealed class ServerFirstGameResultClient : IGameResultClient
    {
        readonly HttpGameResultClient _server;
        readonly IGameResultClient _mock;
        readonly HashSet<string> _mockSessions = new();
        bool _warned;

        public ServerFirstGameResultClient(HttpGameResultClient server, IGameResultClient mock)
        {
            _server = server ?? throw new System.ArgumentNullException(nameof(server));
            _mock = mock ?? throw new System.ArgumentNullException(nameof(mock));
        }

        public async Task<GameSessionDto> StartAsync(string gameId)
        {
            var session = await _server.StartAsync(gameId);
            if (session != null || !_server.LastEndpointMissing) return session;

            if (!_warned)
            {
                _warned = true;
                Debug.LogWarning("[ServerFirstGameResult] BE 에 timer-stop 세션 API 가 아직 없다(404) — Mock 세션으로 진행한다. " +
                                 "보상은 기록되지 않는다(FR-008 미성립). BE 가 GitLab #134 계약을 올리면 자동으로 실서버로 바뀐다 (S15P21A604-294).");
            }
            var mockSession = await _mock.StartAsync(gameId);
            if (mockSession != null && !string.IsNullOrEmpty(mockSession.sessionId))
                _mockSessions.Add(mockSession.sessionId);
            return mockSession;
        }

        public Task<GameResultAckDto> ReportAsync(GameResultDto result)
        {
            if (result != null && !string.IsNullOrEmpty(result.sessionId) && _mockSessions.Contains(result.sessionId))
                return _mock.ReportAsync(result);
            return _server.ReportAsync(result);
        }
    }
}
