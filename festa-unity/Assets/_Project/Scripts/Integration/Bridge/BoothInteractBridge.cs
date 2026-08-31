using System.Runtime.InteropServices;
using System.Text;
using UnityEngine;

namespace Festa.Integration
{
    /// <summary>
    /// Unity 부스 상호작용을 FE 의 `window.FestaUnity.onBoothInteract(json)` 계약으로 전달한다.
    /// WebGL 플레이어에서만 브라우저 콜백을 호출하며 에디터·데디케이티드 서버에서는 로그만 남긴다.
    ///
    /// **이벤트 종류를 인자로 받는다** (S15P21A604-303). 이전에는 `BOOTH_LAPTOP_INTERACT` 가
    /// JSON 문자열에 박혀 있어 노트북 말고는 이 경로를 쓸 수 없었다. AI 직원 상호작용은
    /// 계약(`AI_AGENT_INTERACT`)이 2026-08-20 에 3파트 확정됐는데도 보낼 방법이 없어
    /// Unity 안에서 Mock AI 를 직접 부르고 있었다.
    ///
    /// payload 는 종류마다 다르다.
    /// <list type="bullet">
    /// <item><c>BOOTH_LAPTOP_INTERACT</c> — <c>{type, boothId, objectId}</c></item>
    /// <item><c>AI_AGENT_INTERACT</c> — <c>{type, boothId, objectId, configId}</c></item>
    /// </list>
    ///
    /// <c>configId → agentId</c> 이름 변환은 **FE 가 `AI_CHAT` payload 를 만들 때 한다.**
    /// Unity 는 Layout 계약·DTO 와 같은 이름인 `configId` 로 보낸다 — 한쪽에서만 쓰는 이름을
    /// 경계 너머로 흘리지 않기 위해서다.
    /// </summary>
    public static class BoothInteractBridge
    {
        /// <summary>FE `events.ts` 와 일치해야 하는 값. 여기서 바꾸면 계약이 깨진다.</summary>
        public const string LaptopInteract = "BOOTH_LAPTOP_INTERACT";

        /// <summary>2026-08-20 FE·Unity·AI 3파트 확정 (Issue #2).</summary>
        public const string AiAgentInteract = "AI_AGENT_INTERACT";

#if UNITY_WEBGL && !UNITY_EDITOR && !UNITY_SERVER
        [DllImport("__Internal")]
        static extern void FestaNotifyBoothInteract(string json);
#endif

        /// <summary>
        /// 노트북 → 홈페이지 열기.
        ///
        /// **URL 은 보내지 않는다** (S15P21A604-297). 홈페이지 주소는 `booths.homepage_url` 에
        /// 있고 Layout 에는 없다 — **Unity 는 그 값을 알 수 없다**(헌법 25조). FE 가 `boothId` 로
        /// 조회한다. 주소가 미등록이면 FE 가 안내를 띄운다 (FR-009) — Unity 는 등록 여부와
        /// 무관하게 트리거만 발생시킨다 (FR-005).
        ///
        /// 예전에는 `url?` 선택 필드가 있었지만 **값이 실린 적이 한 번도 없다.** 채울 출처가
        /// 없는데 남겨 두면 "Unity 가 URL 을 보낼 수도 있다" 고 읽힌다. FE 도 제거에 동의했고
        /// (`homepage-api.md` §4, #97) 실제 전송 JSON 은 바뀌지 않는다 — 비어 있으면 원래
        /// 키를 넣지 않았기 때문이다.
        /// </summary>
        public static void SendLaptopInteract(int boothId, string objectId)
        {
            if (!HasObjectId(LaptopInteract, objectId)) return;
            Send(BuildJson(LaptopInteract, boothId, objectId));
        }

        /// <summary>
        /// AI 직원 → 대화 시작.
        ///
        /// <paramref name="configId"/> 0 은 **콘텐츠 미연결**이다. 그대로 보내면 FE 가
        /// agentId 0 으로 대화 생성을 호출해 겉보기에는 동작하는 오브젝트가 조용히 실패한다 —
        /// 빈 오브젝트보다 나쁘다. 여기서 막고 이유를 남긴다.
        /// </summary>
        public static void SendAiAgentInteract(int boothId, string objectId, int configId)
        {
            if (!HasObjectId(AiAgentInteract, objectId)) return;
            if (configId == 0)
            {
                Debug.LogWarning($"[BoothInteractBridge] configId 가 0 이라 {AiAgentInteract} 를 건너뜁니다 " +
                                 $"(booth={boothId} object={objectId}) — AI 직원이 연결되지 않았습니다.");
                return;
            }
            Send(BuildJson(AiAgentInteract, boothId, objectId, configId: configId));
        }

        static bool HasObjectId(string type, string objectId)
        {
            if (!string.IsNullOrEmpty(objectId)) return true;
            Debug.LogWarning($"[BoothInteractBridge] objectId 가 없어 {type} 이벤트를 건너뜁니다.");
            return false;
        }

        static void Send(string json)
        {
#if UNITY_WEBGL && !UNITY_EDITOR && !UNITY_SERVER
            try
            {
                FestaNotifyBoothInteract(json);
            }
            catch (System.Exception ex)
            {
                // 브리지 하나의 실패가 다른 부스 오브젝트 상호작용을 막지 않는다.
                Debug.LogError($"[BoothInteractBridge] onBoothInteract 송신 실패: {ex.Message}");
            }
#elif !UNITY_SERVER
            Debug.Log($"[BoothInteractBridge] onBoothInteract → {json}");
#endif
        }

        /// <summary>
        /// 종류별 payload 를 만든다. 값이 없는 선택 필드는 **키 자체를 넣지 않는다** —
        /// 빈 문자열이나 0 을 보내면 받는 쪽이 "값이 있다" 로 읽는다.
        /// </summary>
        static string BuildJson(string type, int boothId, string objectId, int? configId = null)
        {
            var json = new StringBuilder(128)
                .Append("{\"type\":\"").Append(type)
                .Append("\",\"boothId\":").Append(boothId)
                .Append(",\"objectId\":\"").Append(EscapeJson(objectId)).Append('"');

            if (configId.HasValue)
                json.Append(",\"configId\":").Append(configId.Value);

            return json.Append('}').ToString();
        }

        static string EscapeJson(string value) => value
            .Replace("\\", "\\\\")
            .Replace("\"", "\\\"")
            .Replace("\r", "\\r")
            .Replace("\n", "\\n")
            .Replace("\t", "\\t");
    }
}
