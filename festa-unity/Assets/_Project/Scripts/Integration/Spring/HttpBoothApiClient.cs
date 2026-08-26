using System.Threading.Tasks;
using Festa.Booth;
using UnityEngine;
using UnityEngine.Networking;

namespace Festa.Integration
{
    /// <summary>
    /// Spring Booth API 실 HTTP 구현. Endpoint는 doc 08 Draft 기준 — Backend 확정 시 조정.
    /// 실패 시 예외를 던지지 않고 null 반환 + 로그 (Booth 로딩 실패가 클라이언트 전체를 깨지 않게).
    /// WebGL: 同一 오리진이 아니면 서버 측 CORS 헤더 필요. HTTPS 페이지에서는 HTTPS API만 호출 가능.
    /// </summary>
    public class HttpBoothApiClient : IBoothApiClient
    {
        const int TimeoutSeconds = 10;

        readonly string _baseUrl;
        readonly IAccessTokenProvider _tokenProvider;

        public HttpBoothApiClient(string baseUrl, IAccessTokenProvider tokenProvider = null)
        {
            _baseUrl = baseUrl.TrimEnd('/');
            _tokenProvider = tokenProvider ?? new EmptyAccessTokenProvider();
        }

        public async Task<BoothDetailDto> GetBoothDetailAsync(int boothId)
        {
            var url = $"{_baseUrl}/api/v1/booths/{boothId}";
            var body = await GetAsync(url, boothId, "booth detail");
            if (body == null) return null;

            var detail = BoothFacadeParser.Parse(body);
            if (detail == null)
            {
                Debug.LogError($"[HttpBoothApiClient] Booth {boothId}: 상세 응답 JSON 파싱 실패");
                return null;
            }
            return detail;
        }

        public async Task<BoothLayoutDto> GetPublishedLayoutAsync(int boothId)
        {
            var url = $"{_baseUrl}/api/v1/booths/{boothId}/layouts/published";
            var body = await GetAsync(url, boothId, "published layout");
            if (body == null) return null;

            var layout = BoothLayoutParser.Parse(body);
            if (layout == null)
            {
                Debug.LogError($"[HttpBoothApiClient] Booth {boothId}: 응답 JSON 파싱 실패");
                return null;
            }

            if (layout.objects.Length == 0)
                Debug.LogWarning($"[HttpBoothApiClient] Booth {boothId}: 빈 layout (objects 0개)");

            return layout;
        }

        /// <summary>GET 공통부. 실패는 예외 대신 null + 로그 (부스 로딩 실패가 클라이언트를 깨지 않게).</summary>
        async Task<string> GetAsync(string url, int boothId, string what)
        {
            using var request = UnityWebRequest.Get(url);
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
                // WebGL에서 실패한 요청의 await가 예외를 던질 수 있음 — 아래 result 분기에서 처리
            }

            switch (request.result)
            {
                case UnityWebRequest.Result.Success:
                    break;

                case UnityWebRequest.Result.ProtocolError when request.responseCode == 404:
                    Debug.LogWarning($"[HttpBoothApiClient] Booth {boothId}: {what} 없음 (404)");
                    return null;

                case UnityWebRequest.Result.ProtocolError:
                    Debug.LogError($"[HttpBoothApiClient] GET {url} → HTTP {request.responseCode}");
                    return null;

                default: // ConnectionError, DataProcessingError, timeout
                    Debug.LogError($"[HttpBoothApiClient] GET {url} 실패: {request.error} (CORS/네트워크/타임아웃 확인)");
                    return null;
            }

            return request.downloadHandler.text;
        }
    }
}
