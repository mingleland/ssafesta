using System.Threading.Tasks;
using UnityEngine;
using UnityEngine.Networking;

namespace Festa.Integration
{
    /// <summary>
    /// <c>GET /api/v1/wallets/me</c> (spec 003, BE <c>WalletController.balance</c>). 회원 전용 — 게스트는 403.
    /// 실패는 null 로 돌려주고 사유는 로그·<see cref="LastError"/> 에 남긴다. 0 으로 꾸미지 않는다 (T-24).
    /// </summary>
    public sealed class HttpWalletClient : IWalletClient
    {
        const int TimeoutSeconds = 10;
        readonly string _baseUrl;
        readonly IAccessTokenProvider _tokenProvider;

        public string LastError { get; private set; }

        public HttpWalletClient(string baseUrl, IAccessTokenProvider tokenProvider)
        {
            _baseUrl = (baseUrl ?? string.Empty).TrimEnd('/');
            _tokenProvider = tokenProvider ?? new EmptyAccessTokenProvider();
        }

        public async Task<WalletBalanceDto> GetMyBalanceAsync()
        {
            using var request = UnityWebRequest.Get($"{_baseUrl}/api/v1/wallets/me");
            request.timeout = TimeoutSeconds;
            var token = _tokenProvider.GetAccessToken();
            if (!string.IsNullOrEmpty(token))
                request.SetRequestHeader("Authorization", $"Bearer {token}");

            try { await request.SendWebRequest(); }
            catch { /* result 로 판정한다 */ }

            switch (request.result)
            {
                case UnityWebRequest.Result.Success:
                    LastError = null;
                    try { return JsonUtility.FromJson<WalletBalanceDto>(request.downloadHandler.text); }
                    catch (System.Exception ex)
                    {
                        LastError = "응답 파싱 실패";
                        Debug.LogError($"[HttpWallet] wallets/me 파싱 실패: {ex.Message}");
                        return null;
                    }
                case UnityWebRequest.Result.ProtocolError when request.responseCode == 401:
                    LastError = string.IsNullOrEmpty(token) ? "로그인이 필요합니다" : "로그인이 만료되었습니다";
                    Debug.LogWarning($"[HttpWallet] GET /wallets/me → 401. {LastError}");
                    return null;
                case UnityWebRequest.Result.ProtocolError when request.responseCode == 403:
                    LastError = "게스트는 코인을 쓸 수 없어요 — 로그인하면 코인을 받아요";
                    Debug.Log("[HttpWallet] GET /wallets/me → 403 (게스트).");
                    return null;
                case UnityWebRequest.Result.ProtocolError:
                    LastError = $"서버 오류 {request.responseCode}";
                    Debug.LogError($"[HttpWallet] GET /wallets/me → HTTP {request.responseCode}: {request.downloadHandler?.text}");
                    return null;
                default:
                    LastError = "네트워크 오류";
                    Debug.LogError($"[HttpWallet] GET /wallets/me 실패: {request.error}");
                    return null;
            }
        }
    }
}
