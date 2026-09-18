using Festa.Content;
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
            bound += BindOne(studio.Find("IX_AiAgent/AiAgent"), layout, BoothObjectType.AiAgent, "ai-agent", true) ? 1 : 0;
            bound += BindOne(studio.Find("IX_Laptop/Laptop"), layout, BoothObjectType.Laptop, "laptop", false) ? 1 : 0;
            bound += BindOne(studio.Find("IX_SurveyKiosk/SurveyKiosk"), layout, BoothObjectType.SurveyKiosk, "survey", false) ? 1 : 0;
            bound += BindOne(studio.Find("ProjectPanel_LED"), layout, BoothObjectType.ProjectPanel, "project", false) ? 1 : 0;

            Debug.Log($"[AuthoredBoothContentBinder] 슬롯 {slotId} → booth {layout.boothId}: 정적 콘텐츠 {bound}/4개 FE 계약 연결");
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
                    // 부스 안의 큰 검은 화면이 이것이다 — 유튜브 썸네일 → 대표이미지 → 로고 (2026-09-18).
                    BoothScreenSurface.Attach(target.gameObject);
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
