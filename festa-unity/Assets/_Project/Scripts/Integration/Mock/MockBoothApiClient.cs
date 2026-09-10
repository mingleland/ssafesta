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

        public async Task<BoothDetailDto> GetBoothDetailAsync(int boothId)
        {
            await Awaitable.WaitForSecondsAsync(0.05f);
            var detail = BoothFacadeParser.Parse(MockDetailJson);
            if (detail != null) detail.boothId = boothId;
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
    }
}
