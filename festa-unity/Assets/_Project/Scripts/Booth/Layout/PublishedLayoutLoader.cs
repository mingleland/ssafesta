using System.Collections.Generic;
using System.Threading.Tasks;
using Festa.Integration;
using UnityEngine;

namespace Festa.Booth
{
    /// <summary>
    /// 월드 내부 Booth 슬롯들의 Published Layout 을 **병렬로** 조회한다 (S15P21A604-103).
    ///
    /// 격리 원칙: 슬롯 하나의 실패(미게시 404·네트워크·파싱)는 그 슬롯만 비운다 —
    /// IBoothApiClient 구현이 실패를 예외 대신 null 로 돌려주므로 WhenAll 이 통째로
    /// 죽는 일은 없고, 여기서는 구현 계약이 깨졌을 때를 대비한 방어만 한 겹 더 둔다.
    ///
    /// 소비자(-172, FestaInteriorBuilder)는 결과의 null 슬롯을 "기본 프레임 유지"로
    /// 처리한다. 이 클래스는 조회·집계까지만 책임진다.
    /// </summary>
    public static class PublishedLayoutLoader
    {
        /// <summary>월드 내부 Booth 슬롯 수 (GitLab #62 계약 — boothId 1~12).</summary>
        public const int WorldSlotCount = 12;

        /// <summary>slotId → layout (미게시·실패 슬롯은 null). 호출 순서가 아니라 slotId 로 찾는다.</summary>
        public static async Task<IReadOnlyDictionary<int, BoothLayoutDto>> LoadAllAsync(IReadOnlyList<int> slotIds)
        {
            ApiServices.EnsureInitialized();

            var tasks = new Task<BoothLayoutDto>[slotIds.Count];
            for (int i = 0; i < slotIds.Count; i++)
                tasks[i] = LoadOneGuardedAsync(slotIds[i]);

            await Task.WhenAll(tasks);

            var result = new Dictionary<int, BoothLayoutDto>(slotIds.Count);
            int published = 0;
            for (int i = 0; i < slotIds.Count; i++)
            {
                result[slotIds[i]] = tasks[i].Result;
                if (tasks[i].Result != null) published++;
            }

            Debug.Log($"[PublishedLayoutLoader] {slotIds.Count}슬롯 병렬 조회 완료 — 게시 {published} / 미게시·실패 {slotIds.Count - published}");
            return result;
        }

        /// <summary>1~12 기본 슬롯 전체 조회.</summary>
        public static Task<IReadOnlyDictionary<int, BoothLayoutDto>> LoadWorldSlotsAsync()
        {
            var ids = new int[WorldSlotCount];
            for (int i = 0; i < WorldSlotCount; i++) ids[i] = i + 1;
            return LoadAllAsync(ids);
        }

        static async Task<BoothLayoutDto> LoadOneGuardedAsync(int slotId)
        {
            try
            {
                return await ApiServices.Booth.GetPublishedLayoutBySlotAsync(slotId);
            }
            catch (System.Exception e)
            {
                // 클라이언트 구현이 실패를 null 로 돌려주는 계약을 어겼을 때의 마지막 방어 —
                // 슬롯 하나 때문에 12실 전체 로딩이 죽으면 안 된다 (완료 조건 ②).
                Debug.LogError($"[PublishedLayoutLoader] Slot {slotId} 조회 예외 — 이 슬롯만 비운다: {e.Message}");
                return null;
            }
        }
    }
}
