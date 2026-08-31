using Festa.Booth;
using Festa.Integration;
using UnityEngine;

namespace Festa.Content
{
    /// <summary>
    /// AI 직원 상호작용. F 키로 상호작용하면 `AI_AGENT_INTERACT` 를 FE 로 보내고,
    /// **채팅 UI 는 React 오버레이가 연다.**
    ///
    /// 이전에는 Unity 안에서 `ApiServices.Ai`(Mock)를 직접 불러 응답을 로그로 찍었다
    /// (S15P21A604-304·-77). 그건 POC 였고, 두 가지가 잘못돼 있었다.
    /// <list type="number">
    /// <item>계약은 2026-08-20 에 3파트 확정됐다 — `{boothId, objectId, configId}` (Issue #2).
    ///       보낼 길이 없어서(브리지가 LAPTOP 전용) 확정된 계약이 구현되지 않고 있었다.</item>
    /// <item>텍스트 입력 UI 를 Unity 안에 만들 수 없다 — 한글 IME 문제 (ADR 결정 4).
    ///       대화는 처음부터 React 몫이었다.</item>
    /// </list>
    ///
    /// `configId → agentId` 이름 변환은 FE 가 `AI_CHAT` payload 를 만들 때 한다.
    /// Unity 는 Layout 계약·DTO 와 같은 이름인 `configId` 로 보낸다.
    /// </summary>
    [RequireComponent(typeof(BoothRuntimeObject))]
    public class AiNpcInteractable : MonoBehaviour, IBoothInteractable
    {
        BoothRuntimeObject _runtimeObject;

        void Awake()
        {
            _runtimeObject = GetComponent<BoothRuntimeObject>();

            // 클릭 감지는 중앙 디스패처가 한다. `OnMouseDown` 은 Unity 6 WebGL 에서
            // 발생하지 않는다 (T-166) — 여기 있던 `OnMouseDown` 은 배포 환경에서 죽은 코드였고,
            // 존재만으로 Unity 가 매 프레임 레거시 마우스 디스패처를 돌려 경고를 뿜었다 (T-176).
            BoothInteractionInput.Ensure();
        }

        public void Interact()
        {
            if (_runtimeObject == null)
            {
                Debug.LogWarning("[AiNpc] BoothRuntimeObject 가 없어 상호작용을 건너뜁니다.");
                return;
            }

            // 콘텐츠 미연결(configId 0) 판정은 브리지가 한다 — 같은 규칙을 두 곳에 두면
            // 한쪽만 고치게 된다. 여기서는 값을 그대로 넘긴다.
            BoothInteractBridge.SendAiAgentInteract(
                _runtimeObject.BoothId, _runtimeObject.ObjectId, _runtimeObject.ConfigId);
        }
    }
}
