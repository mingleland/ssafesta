using System.Threading.Tasks;
using Festa.Booth;

namespace Festa.Integration
{
    /// <summary>Spring Booth API 경계. Mock ↔ Http 교체 가능해야 한다 (POC C).</summary>
    public interface IBoothApiClient
    {
        /// <summary>Published Layout 조회. 없으면 null.</summary>
        Task<BoothLayoutDto> GetPublishedLayoutAsync(int boothId);

        /// <summary>
        /// 부스 상세 조회 (facade 포함). 없으면 null.
        /// Facade 는 읽기만 가능하다 — 저장 endpoint 는 계약 미결(docs/26).
        /// </summary>
        Task<BoothDetailDto> GetBoothDetailAsync(int boothId);
    }
}
