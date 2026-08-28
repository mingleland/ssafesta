using System.Threading.Tasks;
using Festa.Booth;

namespace Festa.Integration
{
    /// <summary>Spring Booth API 경계. Mock ↔ Http 교체 가능해야 한다 (POC C).</summary>
    public interface IBoothApiClient
    {
        /// <summary>Published Layout 조회 (owner 시점, boothId 기준). 없으면 null.</summary>
        Task<BoothLayoutDto> GetPublishedLayoutAsync(int boothId);

        /// <summary>
        /// Published Layout 조회 (visitor 시점, **slotId 기준**). 없으면 null.
        /// 월드 내부 Booth 12실은 slot 으로 식별된다 — 어느 부스가 그 슬롯을 임차 중인지는
        /// 서버가 풀고, 응답의 boothId 로 알려준다 (BE BoothSlotController, S15P21A604-103).
        /// </summary>
        Task<BoothLayoutDto> GetPublishedLayoutBySlotAsync(int slotId);

        /// <summary>
        /// 부스 상세 조회 (facade 포함). 없으면 null.
        /// Facade 는 읽기만 가능하다 — 저장 endpoint 는 계약 미결(docs/26).
        /// </summary>
        Task<BoothDetailDto> GetBoothDetailAsync(int boothId);
    }
}
