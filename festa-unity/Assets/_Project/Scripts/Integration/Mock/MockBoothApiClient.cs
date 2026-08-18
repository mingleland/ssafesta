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
    { ""id"": ""screen-1"", ""type"": ""VIDEO_SCREEN"",
      ""position"": { ""x"": 2.1, ""y"": 0, ""z"": 3.4 }, ""rotationY"": 90, ""configId"": 152 },
    { ""id"": ""ai-1"", ""type"": ""AI_AGENT"",
      ""position"": { ""x"": 1.2, ""y"": 0, ""z"": 1.5 }, ""rotationY"": 0, ""configId"": 78 },
    { ""id"": ""panel-1"", ""type"": ""PROJECT_PANEL"",
      ""position"": { ""x"": -1.5, ""y"": 0, ""z"": 2.0 }, ""rotationY"": 45, ""configId"": 33 },
    { ""id"": ""survey-1"", ""type"": ""SURVEY_KIOSK"",
      ""position"": { ""x"": -0.5, ""y"": 0, ""z"": 3.5 }, ""rotationY"": 180, ""configId"": 12 },
    { ""id"": ""recruit-1"", ""type"": ""RECRUITMENT_BOARD"",
      ""position"": { ""x"": 0.5, ""y"": 0, ""z"": 4.0 }, ""rotationY"": 10, ""configId"": 21 },
    { ""id"": ""desk-1"", ""type"": ""CONSULTATION_DESK"",
      ""position"": { ""x"": 0.5, ""y"": 0, ""z"": 5.0 }, ""rotationY"": 0, ""configId"": 9 },
    { ""id"": ""laptop-1"", ""type"": ""LAPTOP"",
      ""position"": { ""x"": 1.0, ""y"": 0.8, ""z"": 5.0 }, ""rotationY"": 15, ""configId"": 16 },
    { ""id"": ""vote-1"", ""type"": ""LIKE_VOTE"",
      ""position"": { ""x"": 2.0, ""y"": 0, ""z"": 4.5 }, ""rotationY"": 20, ""configId"": 18 },
    { ""id"": ""chair-1"", ""type"": ""FURNITURE"",
      ""position"": { ""x"": -2.0, ""y"": 0, ""z"": 4.5 }, ""rotationY"": 30, ""configId"": 0 },
    { ""id"": ""plant-1"", ""type"": ""DECORATION"",
      ""position"": { ""x"": -2.5, ""y"": 0, ""z"": 5.5 }, ""rotationY"": 40, ""configId"": 0 },
    { ""id"": ""legacy-survey"", ""type"": ""SURVEY"",
      ""position"": { ""x"": 2.5, ""y"": 0, ""z"": 5.5 }, ""rotationY"": 50, ""configId"": 12 },
    { ""id"": ""legacy-desk"", ""type"": ""CONSULT_DESK"",
      ""position"": { ""x"": 3.0, ""y"": 0, ""z"": 4.5 }, ""rotationY"": 60, ""configId"": 9 },
    { ""id"": ""future-1"", ""type"": ""HOLOGRAM"",
      ""position"": { ""x"": 3.0, ""y"": 0, ""z"": 5.0 }, ""rotationY"": 0, ""configId"": 999 }
  ]
}";

        public async Task<BoothLayoutDto> GetPublishedLayoutAsync(int boothId)
        {
            await Awaitable.WaitForSecondsAsync(0.15f); // 네트워크 지연 흉내 (WebGL 호환)
            var layout = BoothLayoutParser.Parse(MockLayoutJson);
            if (layout != null) layout.boothId = boothId;
            return layout;
        }
    }
}
