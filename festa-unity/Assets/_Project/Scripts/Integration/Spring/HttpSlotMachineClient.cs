using System.Text;
using System.Threading.Tasks;
using UnityEngine;
using UnityEngine.Networking;

namespace Festa.Integration
{
    /// <summary>
    /// <c>POST /api/v1/minigames/slot-machines/{machineId}/spins</c> — GitLab #134 §1 에 게시한 제안 계약 (S15P21A604-439).
    ///
    /// <para>BE 가 아직 이 경로를 만들지 않았다(2026-09-06 로컬 Spring: <c>404 NOT_FOUND</c>). 그래서 이 클라이언트는
    /// 404·405 를 <see cref="EndpointMissing"/> 오류 코드로 **구분해** 돌려준다 — <see cref="ServerFirstSlotMachineClient"/> 가
    /// 그 경우에만 체험판(Mock)으로 넘긴다. 다른 실패(401·409·5xx·네트워크)는 그대로 표면화한다 (T-24).</para>
    ///
    /// <para>필드명이 BE 확정 계약과 다르면 이 파일의 DTO 매핑만 고친다 — 게임 파트는 BE 계약을 우선한다.</para>
    /// </summary>
    public sealed class HttpSlotMachineClient : ISlotMachineClient
    {
        public const string EndpointMissing = "ENDPOINT_MISSING";
        const int TimeoutSeconds = 10;

        readonly string _baseUrl;
        readonly IAccessTokenProvider _tokenProvider;

        public HttpSlotMachineClient(string baseUrl, IAccessTokenProvider tokenProvider)
        {
            _baseUrl = (baseUrl ?? string.Empty).TrimEnd('/');
            _tokenProvider = tokenProvider ?? new EmptyAccessTokenProvider();
        }

        [System.Serializable] class SpinRequest { public int bet; }
        [System.Serializable] class SpinResponse { public string sessionId; public int bet; public int payout; public int tier; public int balanceAfter; }
        [System.Serializable] class ErrorBody { public string code; public string message; public int balance = -1; }

        public async Task<SlotSpinResultDto> SpinAsync(string machineId, int bet)
        {
            var url = $"{_baseUrl}/api/v1/minigames/slot-machines/{UnityWebRequest.EscapeURL(machineId ?? string.Empty)}/spins";
            var payload = JsonUtility.ToJson(new SpinRequest { bet = bet });
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
            var fail = new SlotSpinResultDto { accepted = false, bet = bet, balanceAfter = -1, simulated = false };

            switch (request.result)
            {
                case UnityWebRequest.Result.Success:
                    try
                    {
                        var r = JsonUtility.FromJson<SpinResponse>(body);
                        if (r == null || string.IsNullOrEmpty(r.sessionId))
                        {
                            fail.error = "BAD_RESPONSE";
                            Debug.LogError($"[HttpSlotMachine] 응답에 sessionId 가 없다: {body}");
                            return fail;
                        }
                        return new SlotSpinResultDto
                        {
                            accepted = true,
                            sessionId = r.sessionId,
                            bet = r.bet > 0 ? r.bet : bet,
                            payout = r.payout,
                            tier = Mathf.Clamp(r.tier, 0, 3),
                            balanceAfter = r.balanceAfter,
                            simulated = false,
                        };
                    }
                    catch (System.Exception ex)
                    {
                        fail.error = "BAD_RESPONSE";
                        Debug.LogError($"[HttpSlotMachine] 응답 파싱 실패: {ex.Message} — {body}");
                        return fail;
                    }

                case UnityWebRequest.Result.ProtocolError when request.responseCode == 404 || request.responseCode == 405:
                    // BE 가 아직 경로를 만들지 않았다. 호출자가 체험판으로 넘길지 결정한다.
                    fail.error = EndpointMissing;
                    return fail;

                case UnityWebRequest.Result.ProtocolError when request.responseCode == 409:
                    fail.error = "INSUFFICIENT_COIN";
                    fail.balanceAfter = ParseBalance(body);
                    Debug.Log($"[HttpSlotMachine] 409 — 코인 부족 (balance={fail.balanceAfter}).");
                    return fail;

                case UnityWebRequest.Result.ProtocolError when request.responseCode == 401:
                    fail.error = "UNAUTHORIZED";
                    Debug.LogWarning("[HttpSlotMachine] 401 — 로그인이 필요하거나 만료됐다.");
                    return fail;

                case UnityWebRequest.Result.ProtocolError when request.responseCode == 403:
                    fail.error = "FORBIDDEN";
                    Debug.Log("[HttpSlotMachine] 403 — 게스트는 베팅할 수 없다.");
                    return fail;

                case UnityWebRequest.Result.ProtocolError when request.responseCode == 429:
                    fail.error = "DAILY_LIMIT";
                    Debug.Log("[HttpSlotMachine] 429 — 일일 한도.");
                    return fail;

                case UnityWebRequest.Result.ProtocolError:
                    fail.error = $"HTTP_{request.responseCode}";
                    Debug.LogError($"[HttpSlotMachine] POST spins → HTTP {request.responseCode}: {body}");
                    return fail;

                default:
                    fail.error = "NETWORK";
                    Debug.LogError($"[HttpSlotMachine] POST spins 실패: {request.error}");
                    return fail;
            }
        }

        static int ParseBalance(string body)
        {
            if (string.IsNullOrEmpty(body)) return -1;
            try { return JsonUtility.FromJson<ErrorBody>(body)?.balance ?? -1; }
            catch { return -1; }
        }
    }

    /// <summary>
    /// 표시 잔액을 실제 지갑과 맞출 수 있는 판정 클라이언트 — 체험판(Mock) 경로에서만 의미가 있다.
    /// <see cref="Festa.Minigame.Slot.SlotMachineSession"/> 이 지갑을 읽은 뒤 부른다.
    /// </summary>
    public interface IBalanceSeedable
    {
        void SeedBalance(int balance);
    }

    /// <summary>
    /// **서버 우선, 없으면 체험판.** 실서버 모드에서 slot machine 판정을 먼저 BE 에 묻고,
    /// BE 가 경로를 아직 만들지 않았을 때(<see cref="HttpSlotMachineClient.EndpointMissing"/>)만 Mock 으로 넘긴다.
    ///
    /// <para>이렇게 두는 이유 — BE 엔드포인트가 사용자 테스트 직전에 붙을 수 있다(GitLab #134). 붙는 순간 Unity 는
    /// 코드·설정 변경 없이 실판정으로 넘어가야 하고, 그 전에는 "체험판 · 코인 미반영" 배지가 계속 보여야 한다
    /// (Mock 결과의 <c>simulated=true</c> 가 HUD 배지를 켠다). 조용한 대체가 아니다: 첫 폴백 때 경고를 남기고,
    /// 다른 실패(401·409·5xx)는 폴백하지 않고 그대로 사용자에게 보인다 (T-24).</para>
    /// </summary>
    public sealed class ServerFirstSlotMachineClient : ISlotMachineClient, IBalanceSeedable
    {
        readonly ISlotMachineClient _server;
        readonly MockSlotMachineClient _mock;
        bool _warned;

        /// <summary>마지막 호출이 체험판으로 처리됐는가(진단용).</summary>
        public bool LastWasSimulated { get; private set; }

        public ServerFirstSlotMachineClient(ISlotMachineClient server, MockSlotMachineClient mock)
        {
            _server = server ?? throw new System.ArgumentNullException(nameof(server));
            _mock = mock ?? throw new System.ArgumentNullException(nameof(mock));
        }

        public void SeedBalance(int balance) => _mock.SeedBalance(balance);

        public async Task<SlotSpinResultDto> SpinAsync(string machineId, int bet)
        {
            var result = await _server.SpinAsync(machineId, bet);
            if (result != null && result.error == HttpSlotMachineClient.EndpointMissing)
            {
                if (!_warned)
                {
                    _warned = true;
                    Debug.LogWarning("[ServerFirstSlotMachine] BE 에 slot machine 스핀 API 가 아직 없다(404) — 체험판(Mock) 판정으로 진행한다. " +
                                     "코인 원장에는 반영되지 않는다. BE 가 GitLab #134 계약을 올리면 자동으로 실판정으로 바뀐다 (S15P21A604-439).");
                }
                LastWasSimulated = true;
                return await _mock.SpinAsync(machineId, bet);
            }

            LastWasSimulated = false;
            return result;
        }
    }
}
