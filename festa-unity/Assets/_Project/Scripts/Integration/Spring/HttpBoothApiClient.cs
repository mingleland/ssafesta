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

        public async Task<BoothLayoutDto> GetPublishedLayoutAsync(int boothId)
        {
            var url = $"{_baseUrl}/api/v1/booths/{boothId}/layouts/published";

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
                    Debug.LogWarning($"[HttpBoothApiClient] Booth {boothId}: published layout 없음 (404)");
                    return null;

                case UnityWebRequest.Result.ProtocolError:
                    Debug.LogError($"[HttpBoothApiClient] GET {url} → HTTP {request.responseCode}");
                    return null;

                default: // ConnectionError, DataProcessingError, timeout
                    Debug.LogError($"[HttpBoothApiClient] GET {url} 실패: {request.error} (CORS/네트워크/타임아웃 확인)");
                    return null;
            }

            var layout = BoothLayoutParser.Parse(request.downloadHandler.text);
            if (layout == null)
            {
                Debug.LogError($"[HttpBoothApiClient] Booth {boothId}: 응답 JSON 파싱 실패");
                return null;
            }

            if (layout.objects.Length == 0)
                Debug.LogWarning($"[HttpBoothApiClient] Booth {boothId}: 빈 layout (objects 0개)");

            return layout;
        }
    }
}
