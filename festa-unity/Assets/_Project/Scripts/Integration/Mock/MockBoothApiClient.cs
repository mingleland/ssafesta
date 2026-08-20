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
        // canonical 10종 + 구 별칭 2종 + 미지원 타입 1종을 포함한 계약 회귀 검증용 Layout.
        const string MockLayoutJson = @"{
  ""boothId"": 7,
  ""template"": ""PROJECT_EXHIBITION"",
  ""version"": 1,
  ""objects"": [
    { ""objectId"": ""ai-1"", ""type"": ""AI_AGENT"",
      ""position"": { ""x"": -3, ""y"": 0, ""z"": 1 }, ""rotationY"": 0, ""configId"": 78 },
    { ""objectId"": ""screen-1"", ""type"": ""VIDEO_SCREEN"",
      ""position"": { ""x"": 0, ""y"": 0, ""z"": 1 }, ""rotationY"": 0, ""configId"": 152 },
    { ""objectId"": ""panel-1"", ""type"": ""PROJECT_PANEL"",
      ""position"": { ""x"": 3, ""y"": 0, ""z"": 1 }, ""rotationY"": 15, ""configId"": 33 },
    { ""objectId"": ""survey-1"", ""type"": ""SURVEY_KIOSK"",
      ""position"": { ""x"": -3, ""y"": 0, ""z"": 4 }, ""rotationY"": 0, ""configId"": 12 },
    { ""objectId"": ""recruit-1"", ""type"": ""RECRUITMENT_BOARD"",
      ""position"": { ""x"": 0, ""y"": 0, ""z"": 4 }, ""rotationY"": 0, ""configId"": 21 },
    { ""objectId"": ""desk-1"", ""type"": ""CONSULTATION_DESK"",
      ""position"": { ""x"": 3, ""y"": 0, ""z"": 4 }, ""rotationY"": 0, ""configId"": 9 },
    { ""objectId"": ""laptop-1"", ""type"": ""LAPTOP"",
      ""position"": { ""x"": 3, ""y"": 0.85, ""z"": 4 }, ""rotationY"": 0, ""configId"": 16 },
    { ""objectId"": ""vote-1"", ""type"": ""LIKE_VOTE"",
      ""position"": { ""x"": -3, ""y"": 0, ""z"": 7 }, ""rotationY"": 0, ""configId"": 18 },
    { ""objectId"": ""chair-1"", ""type"": ""FURNITURE"", ""assetCode"": ""FURNITURE_DEFAULT"",
      ""position"": { ""x"": 0, ""y"": 0, ""z"": 7 }, ""rotationY"": 30, ""configId"": 0 },
    { ""objectId"": ""plant-1"", ""type"": ""DECORATION"", ""assetCode"": ""DECORATION_DEFAULT"",
      ""position"": { ""x"": 3, ""y"": 0, ""z"": 7 }, ""rotationY"": 0, ""configId"": 0 },
    { ""id"": ""legacy-survey"", ""type"": ""SURVEY"",
      ""position"": { ""x"": -5.5, ""y"": 0, ""z"": 7 }, ""rotationY"": 0, ""configId"": 12 },
    { ""objectId"": ""legacy-desk"", ""type"": ""CONSULT_DESK"",
      ""position"": { ""x"": 5.5, ""y"": 0, ""z"": 7 }, ""rotationY"": 0, ""configId"": 9 },
    { ""objectId"": ""future-1"", ""type"": ""HOLOGRAM"",
      ""position"": { ""x"": 0, ""y"": 0, ""z"": 10 }, ""rotationY"": 0, ""configId"": 999 }
  ]
}";

        // Facade 회귀 검증용. primaryColor 는 hex 문자열 계약(#17 합의)을 따른다.
        const string MockDetailJson = @"{
  ""boothId"": 7,
  ""slotId"": 5,
  ""name"": ""AI 프로젝트 전시관"",
  ""leaseStatus"": ""ACTIVE"",
  ""entryAvailable"": true,
  ""facade"": {
    ""themeCode"": ""SSAFY_BLUE"",
    ""primaryColor"": ""#1677C8"",
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

        public async Task<BoothDetailDto> GetBoothDetailAsync(int boothId)
        {
            await Awaitable.WaitForSecondsAsync(0.05f);
            var detail = BoothFacadeParser.Parse(MockDetailJson);
            if (detail != null) detail.boothId = boothId;
            return detail;
        }
    }
}
