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

        /// <summary>
        /// 공개된 전시 프로젝트 조회 (방문자 시점). 없거나 조회 불가면 null.
        /// 축제장 부스 간판·전시 카드가 쓴다 (GitLab #171).
        /// </summary>
        Task<BoothProjectsDto> GetPublishedProjectsAsync(int boothId);

        /// <summary>
        /// 축제장 12칸의 임대 상태를 <b>한 번에</b> 조회한다 (<c>GET /api/v1/booth-slots</c>). 실패하면 null.
        ///
        /// <para>이 목록이 <b>slotId ↔ boothId 를 잇는 정본</b>이다. 씬의 간판·포털은 슬롯 번호만 알고,
        /// 어느 부스가 그 칸을 쓰는지는 서버만 안다 — 둘을 같은 값으로 쓰면 남의 부스를 조회한다
        /// (S15P21A604-658). OCCUPIED 슬롯에는 <c>boothName</c>·<c>facade</c> 도 함께 온다.</para>
        /// </summary>
        Task<BoothSlotDto[]> GetSlotsAsync();
    }
}
