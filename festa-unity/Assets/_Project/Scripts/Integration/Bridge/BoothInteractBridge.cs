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
    /// <item><c>BOOTH_PROJECT_INTERACT</c> — <c>{type, boothId, objectId}</c></item>
    /// <item><c>BOOTH_SURVEY_INTERACT</c> — <c>{type, boothId, objectId}</c></item>
    /// <item><c>AI_AGENT_INTERACT</c> — <c>{type, boothId, objectId, configId}</c></item>
    /// <item><c>WORLD_MANAGEMENT_INTERACT</c> — <c>{type}</c> · <b>필드 없음</b></item>
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

        /// <summary>2026-08-31 계약 확정 (GitLab #110 note 2754197, S15P21A604-343).</summary>
        public const string ProjectInteract = "BOOTH_PROJECT_INTERACT";

        /// <summary>2026-09-04 계약 확정 (S15P21A604-415).</summary>
        public const string SurveyInteract = "BOOTH_SURVEY_INTERACT";

        /// <summary>
        /// 2026-09-04 계약 확정 (S15P21A604-414). <b>부스 종속이 아니다</b> — 그래서 접두사가
        /// <c>BOOTH_</c> 가 아니라 <c>WORLD_</c> 이고 payload 에 boothId 가 없다. 관리 NPC 는
        /// 월드 공용 운영 진입점이고, 관리 대상 부스는 로그인 사용자 기준으로 FE 가
        /// <c>GET /booths/mine</c> 으로 resolve 한다. Unity 가 남의 임대 정보를 알 이유가 없다.
        /// </summary>
        public const string ManagementInteract = "WORLD_MANAGEMENT_INTERACT";

        /// <summary>
        /// 광장 게임기 (S15P21A604-440, GitLab #56 안 1). <c>{type, machineId}</c> — machineId 는 씬이 정한 canonical id 이고
        /// FE 가 <c>GET /api/v1/arcade-machines/{machineId}</c> 로 어떤 게임이 걸렸는지 resolve 한다. Unity 는 gameId 를 모른다.
        /// 이벤트 이름은 게임 파트 제안(docs/26 ③) — FE 수신부 확정 전.
        /// </summary>
        public const string ArcadeInteract = "WORLD_ARCADE_INTERACT";

        /// <summary>부스 배치 GAME_PORTAL (spec 019 contracts/game-portal-bridge.md). <c>{type, boothId, objectId, configId}</c>, configId 는 1 이상.</summary>
        public const string GameInteract = "BOOTH_GAME_INTERACT";

        /// <summary>
        /// 월드 내장 미니게임 (2026-09-10, GitLab #166). <c>{type, gameId, machineId}</c> — 화면은 FE 오버레이가 그리고
        /// Unity 는 F 상호작용만 한다(사용자 지시). <see cref="ArcadeInteract"/> 와 나눈 이유는 해석 주체가 다르기 때문이다 —
        /// 광장 게임기는 FE 가 machineId 로 <b>어떤 Game Studio 게임인지</b> 서버에 물어야 하지만, 내장 미니게임은
        /// gameId 자체가 곧 화면이고 전용 API(<c>/api/v1/minigames/…</c>)를 쓴다. FE 수신부 확정 전이다.
        /// </summary>
        public const string MinigameInteract = "WORLD_MINIGAME_INTERACT";

        /// <summary>
        /// 이벤트 지점 — 경품 상점·이벤트 설문 (GitLab #170). payload 는 <c>{type}</c> <b>하나뿐이다.</b>
        /// FE 가 <c>!613</c>(develop <c>c76b9f49</c>)에서 이미 열어 둔 이벤트라 Unity 는 보내기만 하면 된다.
        ///
        /// <para><b>한때 <c>BOOTH_EVENT_INTERACT</c> 를 새로 만들려 했다가 철회했다</b> (#170, 2026-09-10).
        /// 접두사 규칙을 "payload 에 boothId 가 있으면 <c>BOOTH_</c>" 로 읽었는데 그건 결과를 규칙으로
        /// 착각한 것이고, 실제 기준은 <b>화면이 boothId 를 소비하는가</b> 다. 이벤트 화면은 부스에 속하지
        /// 않아 boothId·slotId 를 쓰지 않으므로 <c>WORLD_</c> 이고, 안 쓰는 값을 실어 보내면 나중에
        /// 의미 있는 값으로 읽힌다(노트북에서 url 을 뺀 것과 같은 이유 — #97, 헌법 25조).</para>
        ///
        /// <para><b>게스트도 그대로 보낸다.</b> Unity 는 게이트를 걸지 않는다 — FE 가 세션 종류로
        /// 요청을 만들기 전에 갈라서, 관리 데스크의 403 무한 재시도 함정(S15P21A604-458)이 재현되지
        /// 않는다는 확인을 받았다(#170 colosair).</para>
        ///
        /// <para>기존 union 은 <c>{ type, npcId? }</c> 지만 지금 이벤트 지점이 하나뿐이라
        /// <b>npcId 를 싣지 않는다.</b> 여러 곳이 생기면 그때 채운다.</para>
        /// </summary>
        public const string EventInteract = "WORLD_EVENT_INTERACT";

        /// <summary>
        /// 부스 안/밖 <b>컨텍스트</b> (GitLab #174, S15P21A604-623). <c>{type, insideBooth, boothId?}</c> —
        /// 상호작용이 아니라 상태 전이라서 다른 <c>*_INTERACT</c> 와 이름 꼴이 다르다. 채널은 같다.
        ///
        /// <para>FE 는 이 값으로 부스 안에서 "나가기" 버튼을 띄운다. <c>WorldUiState</c> 에 넣지 않은 이유는
        /// 그쪽 <c>hasUnityModal()</c> 이 <c>focus || minigame</c> 이라, 부스 안에 있다는 이유만으로 "Unity 모달"
        /// 로 오판해 ESC 가 GameMenu 를 못 여는 회귀가 나기 때문이다 — UI 모달 상태와 월드 컨텍스트는 층이 다르다.</para>
        ///
        /// <para><b>월드 진입 직후 현재 값을 1회 보낸다.</b> 전이만 보내면 재접속·재시도 boot 에서 FE 가 초기값을
        /// 모른다 — AudioBridge 의 mute 동기화(-557)와 같은 이유. 이후는 바뀔 때만이다.</para>
        /// </summary>
        public const string BoothContext = "WORLD_BOOTH_CONTEXT";

        /// <summary>
        /// payload 가 **실제로 송신된** 직후 이벤트 종류를 알린다 (S15P21A604-348).
        /// 노트북 F 의 가시 결과(홈페이지 열기)는 FE 몫이라, FE 가 없는 단독 실행에서는
        /// 발동해도 화면 변화가 없어 "안 된다" 로 보인다 — 월드 쪽이 최소한의 피드백을
        /// 띄울 수 있게 훅을 연다. configId 0 등으로 **건너뛴 경우에는 발화하지 않는다**
        /// (보내지 않았는데 보냈다고 표시하면 거짓 피드백이다).
        /// </summary>
        public static event System.Action<string> OnSent;

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
            OnSent?.Invoke(LaptopInteract);
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
            OnSent?.Invoke(AiAgentInteract);
        }

        /// <summary>
        /// 프로젝트 전시판 → 전시 열기 (S15P21A604-343).
        ///
        /// payload 는 <c>{boothId, objectId}</c> 뿐이다. <b>projectId·configId 를 보내지 않는다</b> —
        /// 부스당 프로젝트는 1개라(spec 009 C-01) boothId 만으로 조회가 끝나고, 그 값은 BE 소유라
        /// Unity 가 알지도 못한다. 노트북이 URL 을 보내지 않는 것과 같은 이유다(헌법 25조).
        ///
        /// 등록 여부와 무관하게 트리거만 발생시킨다 — 전시가 없으면 FE 가 빈 상태를 안내한다.
        /// </summary>
        public static void SendProjectInteract(int boothId, string objectId)
        {
            if (!HasObjectId(ProjectInteract, objectId)) return;
            Send(BuildJson(ProjectInteract, boothId, objectId));
            OnSent?.Invoke(ProjectInteract);
        }

        /// <summary>
        /// 설문 키오스크 → 설문 열기 (S15P21A604-415).
        ///
        /// payload 는 <c>{boothId, objectId}</c> 뿐이다. <b>surveyId 를 보내지 않는다.</b>
        /// 설문은 부스에 배치되는 것이고(spec 010) 발행 상태·설문 식별자는 BE 소유라 Unity 가
        /// 알 수 없다. FE 가 boothId 로 해석하며, 부스에 설문이 여러 개가 되는 날에는
        /// <c>objectId</c> 로 특정 설문에 binding 할 수 있게 두 값을 함께 보낸다.
        ///
        /// 발행 여부와 무관하게 트리거만 발생시킨다 — 없으면 FE 가 빈 상태를 안내한다.
        /// </summary>
        public static void SendSurveyInteract(int boothId, string objectId)
        {
            if (!HasObjectId(SurveyInteract, objectId)) return;
            Send(BuildJson(SurveyInteract, boothId, objectId));
            OnSent?.Invoke(SurveyInteract);
        }

        /// <summary>
        /// 관리 NPC → 내 부스 관리 열기 (S15P21A604-414).
        ///
        /// <b>인자가 없다.</b> 관리 대상은 "로그인한 사람의 부스" 하나이고 그 식별은 FE·BE 몫이다.
        /// 여기서 boothId 를 실어 보내려면 Unity 가 세션 사용자의 임대 정보를 알아야 하는데,
        /// 영구 상태의 Source of Truth 는 Spring 이다(헌법 1조).
        ///
        /// 인증 여부도 판정하지 않는다 — 게스트가 눌러도 이벤트는 나가고, 회원 전용 안내는
        /// FE 가 띄운다. 노트북·프로젝트가 "등록 여부와 무관하게 트리거만" 인 것과 같은 원칙이다.
        /// </summary>
        public static void SendManagementInteract()
        {
            Send(BuildTypeOnlyJson(ManagementInteract));
            OnSent?.Invoke(ManagementInteract);
        }

        /// <summary>광장 게임기 — <see cref="ArcadeInteract"/>.</summary>
        public static void SendArcadeInteract(string machineId)
        {
            if (string.IsNullOrEmpty(machineId))
            {
                Debug.LogWarning($"[BoothInteractBridge] machineId 가 없어 {ArcadeInteract} 이벤트를 건너뜁니다.");
                return;
            }
            Send("{\"type\":\"" + ArcadeInteract + "\",\"machineId\":\"" + EscapeJson(machineId) + "\"}");
            OnSent?.Invoke(ArcadeInteract);
        }

        /// <summary>월드 내장 미니게임 — <see cref="MinigameInteract"/>. gameId 는 화면 식별자(현재 <c>TIMER_STOP</c> 하나).</summary>
        public static void SendMinigameInteract(string gameId, string machineId)
        {
            if (string.IsNullOrEmpty(gameId))
            {
                Debug.LogWarning($"[BoothInteractBridge] gameId 가 없어 {MinigameInteract} 이벤트를 건너뜁니다.");
                return;
            }
            Send("{\"type\":\"" + MinigameInteract + "\",\"gameId\":\"" + EscapeJson(gameId) +
                 "\",\"machineId\":\"" + EscapeJson(machineId ?? string.Empty) + "\"}");
            OnSent?.Invoke(MinigameInteract);
        }

        /// <summary>
        /// 이벤트 지점 — <see cref="EventInteract"/>. <b>필드가 없다.</b> 무엇을 띄울지는 FE 가 정한다.
        /// </summary>
        public static void SendEventInteract()
        {
            Send("{\"type\":\"" + EventInteract + "\"}");
            OnSent?.Invoke(EventInteract);
        }

        /// <summary>
        /// 부스 컨텍스트 — <see cref="BoothContext"/>. 안에 있을 때만 <c>boothId</c> 를 싣는다(FE 는 로그용으로만 쓴다).
        /// 밖일 때 <c>boothId</c> 를 0 으로 실어 보내면 나중에 "0번 부스" 로 읽히므로 키를 뺀다.
        /// </summary>
        public static void SendBoothContext(bool insideBooth, int boothId)
        {
            string json = insideBooth
                ? "{\"type\":\"" + BoothContext + "\",\"insideBooth\":true,\"boothId\":" + boothId + "}"
                : "{\"type\":\"" + BoothContext + "\",\"insideBooth\":false}";
            Send(json);
            OnSent?.Invoke(BoothContext);
        }

        /// <summary>부스 배치 게임 포털 — <see cref="GameInteract"/>. configId 0 은 미연결 sentinel 이라 보내지 않는다(계약).</summary>
        public static void SendGameInteract(int boothId, string objectId, int configId)
        {
            if (!HasObjectId(GameInteract, objectId)) return;
            if (configId <= 0)
            {
                Debug.LogWarning($"[BoothInteractBridge] configId={configId} 는 미연결이라 {GameInteract} 이벤트를 건너뜁니다 (booth={boothId}, object={objectId}).");
                return;
            }
            Send(BuildJson(GameInteract, boothId, objectId, configId: configId));
            OnSent?.Invoke(GameInteract);
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

        /// <summary>
        /// 필드가 없는 이벤트용. <see cref="BuildJson"/> 는 boothId·objectId 를 필수로 받으므로
        /// 0 과 빈 문자열을 억지로 채워 넣게 된다 — 받는 쪽이 "값이 있다" 로 읽는 바로 그 문제다.
        /// </summary>
        static string BuildTypeOnlyJson(string type) => "{\"type\":\"" + type + "\"}";

        static string EscapeJson(string value) => value
            .Replace("\\", "\\\\")
            .Replace("\"", "\\\"")
            .Replace("\r", "\\r")
            .Replace("\n", "\\n")
            .Replace("\t", "\\t");
    }
}
