using Festa.Content;
using Festa.Integration;
using UnityEngine;

namespace Festa.Booth
{
    /// <summary>
    /// 12개 부스에 미리 배치한 AI·노트북·설문·프로젝트 패널을 게시 레이아웃의 실제 부스 신원에 연결한다.
    /// </summary>
    public sealed class AuthoredBoothContentBinder : MonoBehaviour
    {
        const string ObjectName = "AuthoredBoothContentBinder";

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        static void AutoRegister()
        {
            if (Application.isBatchMode || GameObject.Find(ObjectName) != null) return;
            var go = new GameObject(ObjectName);
            go.AddComponent<AuthoredBoothContentBinder>();
            DontDestroyOnLoad(go);
        }

        void OnEnable() => WorldBoothPublishedBootstrap.PublishedLayoutApplied += Bind;
        void OnDisable() => WorldBoothPublishedBootstrap.PublishedLayoutApplied -= Bind;

        static void Bind(int slotId, BoothLayoutDto layout)
        {
            if (layout == null || layout.boothId <= 0) return;

            var studio = GameObject.Find($"@BoothInteriors/Interior_{slotId:00}/BoothSlot_{slotId}/Studio")?.transform;
            if (studio == null)
            {
                Debug.LogWarning($"[AuthoredBoothContentBinder] 슬롯 {slotId}: Studio 루트를 찾지 못해 정적 콘텐츠를 연결하지 못했다.");
                return;
            }

            int bound = 0;
            var aiAgent = studio.Find("IX_AiAgent/AiAgent");
            bound += BindOne(aiAgent, layout, BoothObjectType.AiAgent, "ai-agent", true) ? 1 : 0;
            bound += BindOne(studio.Find("IX_Laptop/Laptop"), layout, BoothObjectType.Laptop, "laptop", false) ? 1 : 0;
            bound += BindOne(studio.Find("IX_SurveyKiosk/SurveyKiosk"), layout, BoothObjectType.SurveyKiosk, "survey", false) ? 1 : 0;
            bound += BindOne(studio.Find("ProjectPanel_LED"), layout, BoothObjectType.ProjectPanel, "project", false) ? 1 : 0;

            Debug.Log($"[AuthoredBoothContentBinder] 슬롯 {slotId} → booth {layout.boothId}: 정적 콘텐츠 {bound}/4개 FE 계약 연결");

            // 레이아웃이 AI 직원을 지정하지 않았으면 부스 자체에 물어본다 (아래 주석 참조).
            if (aiAgent != null) ResolveAiAgentFromBoothAsync(aiAgent, layout.boothId);
        }

        /// <summary>
        /// 씬에 고정으로 놓인 AI 직원에게 <b>이 부스의 AI 가 누구인지</b>를 붙인다.
        ///
        /// <para>왜 레이아웃만으로는 안 되는가 — BE 는 <c>AI_AGENT</c> 오브젝트의 <c>configId</c> 하나로만
        /// AI 직원을 묶는다(<c>LayoutConfigResolver</c>: configId 로 콘텐츠를 묶는 유일한 타입). 그런데
        /// 부스 안 AI 직원은 우리가 12부스에 고정 배치한 씬 오브젝트고, 배치 툴을 제공하지 않기로 하면서
        /// 그 <c>AI_AGENT</c> 오브젝트를 레이아웃에 깔 주체가 사라졌다. 실제로 booth 9 의 게시 레이아웃에는
        /// SURVEY_KIOSK·FURNITURE·DECORATION 뿐이고 AI_AGENT 가 없다(2026-09-18 실측).
        /// 필요한 것은 배치가 아니라 식별자 하나뿐이므로, 부스 공개 상세에서 받아 온다.</para>
        ///
        /// <para>⚠ <b>지금은 서버가 그 필드를 내려주지 않아 아무 일도 하지 않는다.</b> 값이 0 이면 지금까지와
        /// 똑같이 "준비 중" 으로 남고, 서버가 <c>aiAgentId</c>(또는 <c>agentId</c>)를 싣는 순간 Unity 재배포
        /// 없이 연결된다. 조회 실패를 조용히 삼키지 않고 무엇 때문에 안 붙었는지 로그로 드러낸다 (T-24).</para>
        /// </summary>
        static async void ResolveAiAgentFromBoothAsync(Transform aiAgent, int boothId)
        {
            var runtime = aiAgent != null ? aiAgent.GetComponent<BoothRuntimeObject>() : null;
            if (runtime == null || runtime.HasConfig) return;   // 레이아웃이 이미 지정했으면 건드리지 않는다

            try
            {
                ApiServices.EnsureInitialized();
                var detail = await ApiServices.Booth.GetBoothDetailAsync(boothId);
                int agentId = detail != null ? detail.ResolvedAiAgentId : 0;
                if (agentId <= 0)
                {
                    Debug.LogWarning($"[AuthoredBoothContentBinder] booth {boothId}: 부스 상세에 AI 직원 식별자가 없어 직원은 준비 중 상태로 둔다 " +
                                     "(GET /api/v1/booths/{id} 에 aiAgentId 가 실려야 한다).");
                    return;
                }
                if (runtime == null) return;   // 조회 중에 부스가 재구성됐다
                if (runtime.BindConfigIfEmpty(agentId))
                    Debug.Log($"[AuthoredBoothContentBinder] booth {boothId}: 씬 AI 직원을 agent {agentId} 에 연결했다.");
            }
            catch (System.Exception exception)
            {
                Debug.LogWarning($"[AuthoredBoothContentBinder] booth {boothId}: 부스 상세를 읽지 못해 AI 직원을 연결하지 못했다 — {exception.Message}");
            }
        }

        static bool BindOne(Transform target, BoothLayoutDto layout, BoothObjectType type, string fallbackName, bool requiresConfig)
        {
            if (target == null) return false;

            BoothObjectDto source = null;
            if (layout.objects != null)
                foreach (var candidate in layout.objects)
                    if (candidate != null && BoothObjectTypes.Parse(candidate.type) == type)
                    {
                        source = candidate;
                        break;
                    }

            // 노트북·설문·프로젝트는 FE가 boothId로 실데이터를 조회하므로 게시 레이아웃에 같은 타입이
            // 없어도 씬 오브젝트의 안정 ID로 이벤트를 보낸다. AI만 configId가 필수라 미연결(0)을 드러낸다.
            if (source == null)
            {
                source = new BoothObjectDto
                {
                    objectId = $"authored-{fallbackName}",
                    type = ContractName(type),
                    configId = 0,
                };
                if (requiresConfig)
                    Debug.LogWarning($"[AuthoredBoothContentBinder] booth {layout.boothId}: AI_AGENT config가 없어 직원은 준비 중 상태로 둔다.");
            }

            var runtime = target.GetComponent<BoothRuntimeObject>();
            if (runtime == null) runtime = target.gameObject.AddComponent<BoothRuntimeObject>();
            runtime.Init(layout.boothId, source, type);

            switch (type)
            {
                case BoothObjectType.AiAgent:
                    if (target.GetComponent<AiNpcInteractable>() == null) target.gameObject.AddComponent<AiNpcInteractable>();
                    break;
                case BoothObjectType.Laptop:
                    if (target.GetComponent<LaptopInteractable>() == null) target.gameObject.AddComponent<LaptopInteractable>();
                    break;
                case BoothObjectType.SurveyKiosk:
                    if (target.GetComponent<SurveyKioskInteractable>() == null) target.gameObject.AddComponent<SurveyKioskInteractable>();
                    break;
                case BoothObjectType.ProjectPanel:
                    if (target.GetComponent<ProjectPanelInteractable>() == null) target.gameObject.AddComponent<ProjectPanelInteractable>();
                    // 부스 안의 큰 검은 화면이 이것이다 — 영상 → 로고 → 썸네일 → 검은 화면 (2026-09-18).
                    BoothScreenSurface.Attach(target.gameObject, allowVideo: true);
                    break;
            }

            var interaction = target.GetComponent<BoothInteractionTarget>();
            if (interaction == null) interaction = target.gameObject.AddComponent<BoothInteractionTarget>();
            interaction.Configure(12f, true);
            return true;
        }

        static string ContractName(BoothObjectType type) => type switch
        {
            BoothObjectType.AiAgent => "AI_AGENT",
            BoothObjectType.Laptop => "LAPTOP",
            BoothObjectType.SurveyKiosk => "SURVEY_KIOSK",
            BoothObjectType.ProjectPanel => "PROJECT_PANEL",
            _ => type.ToString().ToUpperInvariant(),
        };
    }
}
