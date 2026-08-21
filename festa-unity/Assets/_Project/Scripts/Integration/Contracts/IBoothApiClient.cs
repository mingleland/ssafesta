using System.Threading.Tasks;
using Festa.Booth;

namespace Festa.Integration
{
    /// <summary>Spring Booth API 경계. Mock ↔ Http 교체 가능해야 한다 (POC C).</summary>
    public interface IBoothApiClient
    {
        /// <summary>Published Layout 조회. 없으면 null.</summary>
        Task<BoothLayoutDto> GetPublishedLayoutAsync(int boothId);
    }
}
