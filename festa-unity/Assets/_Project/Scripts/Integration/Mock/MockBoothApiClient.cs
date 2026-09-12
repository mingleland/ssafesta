using System.Threading.Tasks;
using Festa.Booth;
using UnityEngine;

namespace Festa.Integration
{
    /// <summary>
    /// POC B용 Mock. 기획서의 예시 Layout JSON을 그대로 반환한다.
    /// 지연 시뮬레이션은 Task.Delay가 아닌 Awaitable 사용 — Task.Delay는 WebGL에서 완료되지 않는다.
    /// </summary>
    public class MockBoothApiClient : IBoothApiClient
    {
        // 서버(LayoutValidator)가 실제로 받아 주는 배치만 담는다 — 오브젝트 ≤12, 좌표 x·z ±3 m(HALF_WIDTH), 회전 AABB 포함.
        // 전에는 15개·x ±4.2·z 10 m 라 에디터에서 보는 부스가 실제 방문객이 볼 그림과 달랐다(QA 2026-09-08 #50).
        // 계약 회귀: canonical 10종 + 구 별칭 1종(SURVEY→SURVEY_KIOSK) + GAME_PORTAL 연결 1종 + assetCode 2종.
        // 빼낸 것: CONSULT_DESK 별칭·HOLOGRAM 미지원 타입·미연결 포털 — 12개 상한에 걸려 정리했고, 그 경로는 단위 테스트 몫이다.
        // 넓은 것(상담 데스크 1.86 m·패널 2 m)은 안쪽 열(±0.8)에 둬 회전 AABB 가 ±3 을 넘지 않게 한다.
        // Tools/mock-api/booth-slot-layout.json 과 같은 내용이어야 한다.
        const string MockLayoutJson = @"{
  ""boothId"": 7,
  ""template"": ""PROJECT_EXHIBITION"",
  ""version"": 1,
  ""objects"": [
    { ""objectId"": ""ai-1"", ""type"": ""AI_AGENT"",
      ""position"": { ""x"": -2.4, ""y"": 0, ""z"": -2.2 }, ""rotationY"": 0, ""configId"": 78 },
    { ""objectId"": ""screen-1"", ""type"": ""VIDEO_SCREEN"",
      ""position"": { ""x"": -0.8, ""y"": 0, ""z"": -2.2 }, ""rotationY"": 0, ""configId"": 152 },
    { ""objectId"": ""panel-1"", ""type"": ""PROJECT_PANEL"",
      ""position"": { ""x"": 0.8, ""y"": 0, ""z"": -2.2 }, ""rotationY"": 15, ""configId"": 33 },
    { ""objectId"": ""portal-1"", ""type"": ""GAME_PORTAL"",
      ""position"": { ""x"": 2.4, ""y"": 0, ""z"": -2.2 }, ""rotationY"": 0, ""configId"": 1 },
    { ""objectId"": ""survey-1"", ""type"": ""SURVEY_KIOSK"",
      ""position"": { ""x"": -2.4, ""y"": 0, ""z"": 0 }, ""rotationY"": 0, ""configId"": 12 },
    { ""objectId"": ""desk-1"", ""type"": ""CONSULTATION_DESK"",
      ""position"": { ""x"": -0.8, ""y"": 0, ""z"": 0 }, ""rotationY"": 0, ""configId"": 9 },
    { ""objectId"": ""laptop-1"", ""type"": ""LAPTOP"",
      ""position"": { ""x"": -0.8, ""y"": 0.85, ""z"": 0 }, ""rotationY"": 0, ""configId"": 16 },
    { ""objectId"": ""recruit-1"", ""type"": ""RECRUITMENT_BOARD"",
      ""position"": { ""x"": 0.8, ""y"": 0, ""z"": 0 }, ""rotationY"": 0, ""configId"": 21 },
    { ""objectId"": ""vote-1"", ""type"": ""LIKE_VOTE"",
      ""position"": { ""x"": 2.4, ""y"": 0, ""z"": 0 }, ""rotationY"": 0, ""configId"": 18 },
    { ""id"": ""legacy-survey"", ""type"": ""SURVEY"",
      ""position"": { ""x"": -2.4, ""y"": 0, ""z"": 2.2 }, ""rotationY"": 0, ""configId"": 12 },
    { ""objectId"": ""chair-1"", ""type"": ""FURNITURE"", ""assetCode"": ""FURN_CHAIR_01_BLUE"",
      ""position"": { ""x"": -0.8, ""y"": 0, ""z"": 2.2 }, ""rotationY"": 30, ""configId"": 0 },
    { ""objectId"": ""plant-1"", ""type"": ""DECORATION"", ""assetCode"": ""STRUCT_PANEL_01"",
      ""position"": { ""x"": 0.8, ""y"": 0, ""z"": 2.2 }, ""rotationY"": 0, ""configId"": 0 }
  ]
}";

        // Facade 회귀 검증용. primaryColor 는 hex 문자열 계약(#17)을 따른다.
        // 값은 팔레트 12색 안에서 고른다 (BLUE = #3B82F6) — 서버가 팔레트 밖을 400 으로 거부하므로
        // 계약 밖 색을 fixture 로 두면 다음 사람이 그 값을 복사한다.
        const string MockDetailJson = @"{
  ""boothId"": 7,
  ""slotId"": 5,
  ""name"": ""AI 프로젝트 전시관"",
  ""leaseStatus"": ""ACTIVE"",
  ""entryAvailable"": true,
  ""facade"": {
    ""themeCode"": ""SSAFY_BLUE"",
    ""primaryColor"": ""#3B82F6"",
    ""signText"": ""AI 프로젝트 전시관"",
    ""logoUrl"": null
  },
  ""publishedLayoutVersion"": 1
}";

        public async Task<BoothLayoutDto> GetPublishedLayoutAsync(int boothId)
        {
            await Awaitable.WaitForSecondsAsync(0.15f); // 네트워크 지연 흉내 (WebGL 호환)
            var layout = BoothLayoutParser.Parse(MockLayoutJson);
            if (layout != null) layout.boothId = boothId;
            return layout;
        }

        public async Task<BoothLayoutDto> GetPublishedLayoutBySlotAsync(int slotId)
        {
            // Mock 은 slot→booth 임차 해석이 없으니 boothId = slotId 로 둔다.
            // 홀수 슬롯만 게시된 것으로 취급 — 12실 병렬 조회에서 "게시/미게시 혼재" 경로를
            // 에디터에서도 지나가게 하기 위해서다 (미게시 = null, S15P21A604-103 완료 조건).
            await Awaitable.WaitForSecondsAsync(0.1f);
            PublishedSlotResolution.Set(slotId, transientFailure: false);   // Mock 은 항상 확정 답
            if (slotId % 2 == 0) return null;
            var layout = BoothLayoutParser.Parse(MockLayoutJson);
            if (layout != null) layout.boothId = slotId;
            return layout;
        }

        /// <summary>부스마다 다른 이름을 붙일 때 쓰는 예시 목록. 축제장 지도·표지판 회귀용이다.</summary>
        static readonly string[] SampleNames =
        {
            "AI 프로젝트 전시관", "스마트팜 모니터링", "실시간 수어 통역", "코드리뷰 어시스턴트",
            "여행 경로 추천", "반려동물 건강 기록", "회의록 자동 요약", "실내 길찾기",
            "중고 거래 사기 탐지", "운동 자세 교정", "재활용 분류 카메라", "학습 습관 트래커",
        };

        public async Task<BoothDetailDto> GetBoothDetailAsync(int boothId)
        {
            await Awaitable.WaitForSecondsAsync(0.05f);
            var detail = BoothFacadeParser.Parse(MockDetailJson);
            if (detail == null) return null;

            detail.boothId = boothId;
            // **부스마다 이름을 다르게 준다.** 전에는 12칸이 전부 같은 이름이라 지도가 고장 난 것처럼
            // 보였다 (2026-09-10). Mock 이 현실을 흉내 내지 못하면 화면 검증이 의미가 없다.
            var name = SampleNames[Mathf.Abs(boothId - 1) % SampleNames.Length];
            detail.name = name;
            if (detail.facade != null) detail.facade.signText = name;
            return detail;
        }

        // 간판·전시 카드 회귀용 (GitLab #171). 썸네일은 **일부러 절반만 채운다** —
        // 그림이 오는 부스와 이름만 오는 부스가 섞여야 폴백 경로가 에디터에서도 지나간다.
        // URL 은 실제로 받을 수 없는 예시 도메인이라, 로드 실패 폴백(이름 카드)까지 같이 밟힌다.
        public async Task<BoothProjectsDto> GetPublishedProjectsAsync(int boothId)
        {
            await Awaitable.WaitForSecondsAsync(0.05f);
            if (boothId % 2 == 0) return null;   // 짝수 슬롯은 미게시 — GetPublishedLayoutBySlotAsync 와 같은 규칙
            return new BoothProjectsDto
            {
                projects = new[]
                {
                    new BoothProjectDto
                    {
                        projectId = boothId,
                        name = $"{boothId}번 팀 프로젝트",
                        thumbnailUrl = boothId % 4 == 1 ? "https://cdn.example.com/thumb.png" : null,
                    },
                },
            };
        }

        public async Task<BoothSlotDto[]> GetSlotsAsync()
        {
            await Awaitable.WaitForSecondsAsync(0.05f);

            // 홀수 슬롯만 임대 중 — 다른 Mock 응답(미게시 규칙)과 같은 결을 유지한다.
            // **슬롯 번호와 부스 식별자를 일부러 다르게 준다**(boothId = slotId + 100). 둘을 같은 값으로 쓰는
            // 회귀를 Mock 에서 먼저 드러내려는 것이다 — 실서버에서도 두 값은 다르다 (S15P21A604-658).
            var slots = new BoothSlotDto[BoothSignSlotCount];
            for (int i = 0; i < slots.Length; i++)
            {
                int slotId = i + 1;
                bool occupied = slotId % 2 == 1;
                slots[i] = new BoothSlotDto
                {
                    slotId = slotId,
                    slotCode = $"F11-R{slotId:00}",
                    floorNo = 11,
                    type = slotId == 1 ? "EVENT" : "USER_RENTAL",
                    status = occupied ? "OCCUPIED" : "AVAILABLE",
                    boothId = occupied ? slotId + 100 : 0,
                    boothName = occupied ? $"{slotId}번 칸 부스" : null,
                    entryAvailable = occupied,
                    facade = occupied
                        ? new BoothFacadeDto { themeCode = "DEFAULT", signText = $"{slotId}번 칸 간판" }
                        : null,
                };
            }
            return slots;
        }

        /// <summary>Mock 이 만들어 주는 슬롯 수. 씬의 간판 수(BoothSignPresenter.SlotCount)와 같은 값이다.</summary>
        const int BoothSignSlotCount = 12;
    }
}
