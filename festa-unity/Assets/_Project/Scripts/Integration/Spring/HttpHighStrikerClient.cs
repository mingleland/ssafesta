using System.Text;
using System.Threading.Tasks;
using UnityEngine;
using UnityEngine.Networking;

namespace Festa.Integration
{
    /// <summary>
    /// <c>POST /api/v1/minigames/high-striker/plays</c> — 게임 파트가 확정해 통보한 계약 (GitLab #233).
    ///
    /// <code>
    /// 요청  { machineId, score }
    /// → 201 { playId, score, playsToday, bestScoreToday }
    /// → 400 VALIDATION_FAILED   score 가 음수이거나 machineId 가 비었다
    /// → 401 UNAUTHORIZED
    /// → 403 MEMBER_ONLY         게스트 — 코인을 받을 자리가 없다(헌법 12조)
    /// → 404 HIGH_STRIKER_NOT_FOUND  서버는 살아 있고 machineId 를 모른다
    /// </code>
    ///
    /// <para><b>이 호출은 게임을 막지 않는다.</b> 스윙 연출은 이미 Netcode 로 끝났고, 여기는 미션 근거를 남기는
    /// 뒷정리다. 그래서 실패해도 사용자에게 오류를 띄우지 않고 로그로만 남긴다 — 슬롯(<see cref="HttpSlotMachineClient"/>)
    /// 이 실패를 화면까지 올리는 것과 반대인데, 저쪽은 실패가 곧 <b>코인이 빠졌는지 모르는 상태</b>라서 그렇다.</para>
    ///
    /// <para><b>경로 미배포와 게스트는 한 번만 말하고 멈춘다.</b> BE 가 아직 이 경로를 올리지 않았거나(본문 code 없는 404·405)
    /// 게스트로 접속한 경우, 칠 때마다 요청을 날리면 콘솔이 같은 경고로 덮이고 무의미한 왕복이 쌓인다. 첫 회에 사유를 남기고
    /// 이후에는 네트워크를 건드리지 않는다. <b>조용한 실패가 아니다</b> — 이유를 한 번은 반드시 남긴다 (T-24).</para>
    ///
    /// <para>확정 계약의 <c>404 HIGH_STRIKER_NOT_FOUND</c> 는 경로 미배포와 다르다. 본문 <c>code</c> 유무로 가른다 —
    /// 전역 오류 봉투는 code 가 항상 있다. 이건 씬 machineId 와 서버 화이트리스트가 어긋난 것이므로 멈추지 않고 계속 알린다.</para>
    /// </summary>
    public sealed class HttpHighStrikerClient : IHighStrikerClient
    {
        public const string EndpointMissing = "ENDPOINT_MISSING";
        const int TimeoutSeconds = 10;

        readonly string _baseUrl;
        readonly IAccessTokenProvider _tokenProvider;

        /// <summary>더 보내 봐야 같은 결과인 상태(경로 미배포·게스트)에 들어갔는가.</summary>
        bool _stopped;

        public string LastError { get; private set; }

        public HttpHighStrikerClient(string baseUrl, IAccessTokenProvider tokenProvider)
        {
            _baseUrl = (baseUrl ?? string.Empty).TrimEnd('/');
            _tokenProvider = tokenProvider ?? new EmptyAccessTokenProvider();
        }

        [System.Serializable] class PlayRequest { public string machineId; public int score; }
        [System.Serializable] class PlayResponse { public string playId; public int score; public int playsToday = -1; public int bestScoreToday = -1; }
        [System.Serializable] class ErrorBody { public string code; public string message; }

        public async Task<HighStrikerPlayAckDto> ReportPlayAsync(string machineId, int score)
        {
            if (_stopped)
                return new HighStrikerPlayAckDto { accepted = false, error = LastError ?? EndpointMissing, score = score };

            var url = $"{_baseUrl}/api/v1/minigames/high-striker/plays";
            var payload = JsonUtility.ToJson(new PlayRequest { machineId = machineId ?? string.Empty, score = score });
            using var request = new UnityWebRequest(url, UnityWebRequest.kHttpVerbPOST)
            {
                uploadHandler = new UploadHandlerRaw(Encoding.UTF8.GetBytes(payload)),
                downloadHandler = new DownloadHandlerBuffer(),
                timeout = TimeoutSeconds,
            };
            request.SetRequestHeader("Content-Type", "application/json");
            var token = _tokenProvider.GetAccessToken();
            if (!string.IsNullOrEmpty(token))
                request.SetRequestHeader("Authorization", $"Bearer {token}");

            try { await request.SendWebRequest(); }
            catch { /* result 로 판정한다 */ }

            var body = request.downloadHandler?.text;
            var fail = new HighStrikerPlayAckDto { accepted = false, score = score };

            switch (request.result)
            {
                case UnityWebRequest.Result.Success:
                    LastError = null;
                    try
                    {
                        var r = JsonUtility.FromJson<PlayResponse>(body);
                        return new HighStrikerPlayAckDto
                        {
                            accepted = true,
                            playId = r?.playId,
                            score = r != null && r.score > 0 ? r.score : score,
                            playsToday = r?.playsToday ?? -1,
                            bestScoreToday = r?.bestScoreToday ?? -1,
                            simulated = false,
                        };
                    }
                    catch
                    {
                        // 기록은 됐다 — 본문을 못 읽은 것뿐이라 실패로 취급하지 않는다.
                        return new HighStrikerPlayAckDto { accepted = true, score = score };
                    }

                case UnityWebRequest.Result.ProtocolError when request.responseCode == 404 || request.responseCode == 405:
                    var code404 = ParseCode(body);
                    if (!string.IsNullOrEmpty(code404))
                    {
                        LastError = fail.error = code404;
                        Debug.LogError($"[HttpHighStriker] 404 {code404} — 서버가 machineId '{machineId}' 를 모른다. " +
                                       "씬 machineId 와 서버 화이트리스트를 맞춰야 미션 3·4 가 집계된다 (GitLab #233).");
                        return fail;
                    }
                    LastError = fail.error = EndpointMissing;
                    Stop("BE 에 하이 스트라이커 기록 경로가 아직 없다(404) — 일일 미션 STRIKER_PLAY_3·STRIKER_SCORE 는 " +
                         "BE 가 GitLab #233 계약을 배포할 때까지 진행도가 오르지 않는다. 게임 진행에는 영향이 없다.");
                    return fail;

                case UnityWebRequest.Result.ProtocolError when request.responseCode == 403:
                    LastError = fail.error = "MEMBER_ONLY";
                    Stop("게스트는 미션 보상을 받을 수 없어 하이 스트라이커 기록을 남기지 않는다(403, 헌법 12조). " +
                         "게임 진행에는 영향이 없다.");
                    return fail;

                case UnityWebRequest.Result.ProtocolError when request.responseCode == 401:
                    LastError = fail.error = "UNAUTHORIZED";
                    Debug.LogWarning("[HttpHighStriker] 401 — 로그인이 만료됐다. 이번 판은 미션에 집계되지 않는다.");
                    return fail;

                case UnityWebRequest.Result.ProtocolError:
                    LastError = fail.error = $"HTTP_{request.responseCode}";
                    Debug.LogWarning($"[HttpHighStriker] POST plays → HTTP {request.responseCode}: {body}");
                    return fail;

                default:
                    LastError = fail.error = "NETWORK";
                    Debug.LogWarning($"[HttpHighStriker] POST plays 실패: {request.error}");
                    return fail;
            }
        }

        void Stop(string reason)
        {
            _stopped = true;
            Debug.LogWarning($"[HttpHighStriker] {reason} 이후 이 세션에서는 다시 보내지 않는다.");
        }

        static string ParseCode(string body)
        {
            if (string.IsNullOrEmpty(body)) return null;
            try { return JsonUtility.FromJson<ErrorBody>(body)?.code; }
            catch { return null; }
        }
    }
}
