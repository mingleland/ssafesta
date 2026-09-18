using System.Text;
using System.Threading.Tasks;
using UnityEngine;
using UnityEngine.Networking;

namespace Festa.Integration
{
    /// <summary>
    /// <c>POST /api/v1/minigames/slot-machines/{machineId}/spins</c> — GitLab #134 §1 제안 → <b>#205 에서 확정</b> (S15P21A604-439).
    ///
    /// <code>
    /// → 200 { sessionId, bet, payout, tier(0~3), balanceAfter }
    /// → 400 VALIDATION_FAILED       bet 이 서버 설정값(10)과 다르다
    /// → 403 MEMBER_ONLY             게스트
    /// → 404 SLOT_MACHINE_NOT_FOUND  모르는 machineId (서버는 살아 있다 — Mock 폴백 금지)
    /// → 409 INSUFFICIENT_COIN { balance }
    /// </code>
    ///
    /// <para><b>체험판 폴백은 없다 (2026-09-16).</b> BE 가 spec 021 계약을 구현해 develop 에 올렸으므로
    /// 판정은 언제나 서버가 한다. 전에는 본문에 <c>code</c> 가 없는 404·405 를 "경로 미배포" 로 보고 Mock 으로
    /// 넘겼는데, 그 경로가 살아 있으면 <b>서버가 잠깐 죽거나 라우팅이 어긋난 순간에 가짜 판정이 나간다</b> —
    /// 코인 원장과 화면이 갈라지는 자리다. 모든 실패는 그대로 표면화한다 (T-24).</para>
    ///
    /// <para>응답 필드는 #205 확정 계약과 같다. 바뀌면 이 파일의 DTO 매핑만 고친다 — 게임 파트는 BE 계약을 우선한다.</para>
    /// </summary>
    public sealed class HttpSlotMachineClient : ISlotMachineClient
    {
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
                    // 404 는 두 뜻이지만 **어느 쪽도 체험판으로 넘기지 않는다** (#205 확정 + 2026-09-16 폴백 제거).
                    //   · SLOT_MACHINE_NOT_FOUND → 서버는 살아 있고 machineId 를 모른다 → 씬과 화이트리스트를 맞춰야 한다
                    //   · code 없음              → 경로가 없다 → 배포가 어긋난 것이고, 가짜 판정으로 덮으면 안 된다
                    var code404 = ParseCode(body);
                    if (!string.IsNullOrEmpty(code404))
                    {
                        fail.error = code404;
                        Debug.LogError($"[HttpSlotMachine] 404 {code404} — 서버가 machineId '{machineId}' 를 모른다. 씬 machineId 와 서버 화이트리스트를 맞춰라.");
                        return fail;
                    }
                    fail.error = "ENDPOINT_MISSING";
                    Debug.LogError($"[HttpSlotMachine] {request.responseCode} — 슬롯 스핀 경로가 응답하지 않는다 ({url}). " +
                                   "BE 배포 상태를 확인해라. 체험판으로 대체하지 않는다.");
                    return fail;

                case UnityWebRequest.Result.ProtocolError when request.responseCode == 400:
                    fail.error = "VALIDATION_FAILED";
                    Debug.LogError($"[HttpSlotMachine] 400 — 베팅액이 서버 설정과 다르다 (bet={bet}): {body}");
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

        static string ParseCode(string body)
        {
            if (string.IsNullOrEmpty(body)) return null;
            try { return JsonUtility.FromJson<ErrorBody>(body)?.code; }
            catch { return null; }
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
}
