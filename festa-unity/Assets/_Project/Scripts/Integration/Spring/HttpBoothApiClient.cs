using System.Threading.Tasks;
using Festa.Booth;
using UnityEngine;
using UnityEngine.Networking;

namespace Festa.Integration
{
    /// <summary>
    /// Spring 실 HTTP 구현 스켈레톤. Backend API 확정(doc 08) 후 엔드포인트를 맞춘다.
    /// 인증 헤더(Bearer Access Token)는 Auth 기능 spec 확정 후 추가한다.
    /// </summary>
    public class HttpBoothApiClient : IBoothApiClient
    {
        readonly string _baseUrl;

        public HttpBoothApiClient(string baseUrl) => _baseUrl = baseUrl.TrimEnd('/');

        public async Task<BoothLayoutDto> GetPublishedLayoutAsync(int boothId)
        {
            // Draft endpoint — doc 08 확정 시 조정
            var url = $"{_baseUrl}/api/v1/booths/{boothId}/layout/published";

            using var request = UnityWebRequest.Get(url);
            await request.SendWebRequest();

            if (request.result != UnityWebRequest.Result.Success)
            {
                Debug.LogWarning($"[HttpBoothApiClient] GET {url} failed: {request.error}");
                return null;
            }

            return BoothLayoutParser.Parse(request.downloadHandler.text);
        }
    }
}
