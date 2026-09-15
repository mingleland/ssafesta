using System;
using UnityEngine;

namespace Festa.Booth
{
    // ── 슬롯 목록 계약 ───────────────────────────────────────────
    // GET /api/v1/booth-slots — 축제장 12칸의 임대 상태를 한 번에 준다.
    // OCCUPIED 슬롯에는 boothId·boothName 과 facade 가 함께 실린다 (BE S15P21A604-622, GitLab #171 ④).
    //
    // **이 목록이 slotId ↔ boothId 를 잇는 유일한 정본이다.** 둘을 같은 값으로 쓰면
    // 엉뚱한 부스를 조회하게 된다 — 간판이 남의 부스 이름을 띄우던 원인이다 (S15P21A604-658).
    // ───────────────────────────────────────────────────────────

    [Serializable]
    public class BoothSlotDto
    {
        public int slotId;           // 1~12. 씬의 BoothSign·BoothPortal 번호와 같다
        public string slotCode;      // 예: "F11-R03"
        public int floorNo;
        public string type;          // USER_RENTAL · EVENT
        public string status;        // AVAILABLE · OCCUPIED
        public int boothId;          // 미임대면 0 (JSON null → JsonUtility 가 0 으로 읽는다)
        public string boothName;
        public bool entryAvailable;
        public BoothFacadeDto facade;

        /// <summary>임대 중이고 부스 식별자를 받았는가. boothId 0 은 "없음" 이다.</summary>
        public bool HasBooth => boothId > 0;
    }

    public static class BoothSlotListParser
    {
        // JsonUtility 는 최상위 배열을 읽지 못한다 — 객체로 감싸서 넘긴다.
        [Serializable]
        class Wrapper { public BoothSlotDto[] items; }

        /// <summary>JSON 배열 → DTO 배열. 실패하면 null (예외를 삼키지 않고 로그).</summary>
        public static BoothSlotDto[] Parse(string json)
        {
            if (string.IsNullOrWhiteSpace(json)) return null;
            try
            {
                var trimmed = json.TrimStart();
                if (!trimmed.StartsWith("["))
                {
                    Debug.LogError("[BoothSlotListParser] 응답이 배열이 아니다 — 계약이 바뀌었는지 확인하라");
                    return null;
                }

                var wrapper = JsonUtility.FromJson<Wrapper>("{\"items\":" + trimmed + "}");
                if (wrapper?.items == null)
                {
                    Debug.LogError("[BoothSlotListParser] 슬롯 목록을 해석할 수 없다");
                    return null;
                }
                return wrapper.items;
            }
            catch (Exception e)
            {
                Debug.LogError($"[BoothSlotListParser] Parse 실패: {e.Message}");
                return null;
            }
        }
    }
}
