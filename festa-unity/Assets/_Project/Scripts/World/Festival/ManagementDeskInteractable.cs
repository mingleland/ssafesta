using Festa.Booth;
using Festa.Content;
using Festa.Integration;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 월드 공용 <b>부스 관리 진입점</b> NPC/데스크 (S15P21A604-414).
    ///
    /// <para><b>부스 오브젝트가 아니다.</b> 다른 상호작용(노트북·프로젝트·설문·AI)은 전부 특정
    /// 부스에 배치된 <see cref="BoothRuntimeObject"/> 이고 payload 에 boothId 를 싣는다. 관리
    /// 데스크는 축제 운영 쪽에 하나 서 있고, 관리 대상은 "말을 건 사람의 부스" 다 — 그래서
    /// <see cref="BoothRuntimeObject"/> 를 요구하지 않고 boothId 도 보내지 않는다.</para>
    ///
    /// <para>대상 부스는 FE 가 <c>GET /booths/mine</c> 으로 resolve 한다. Unity 가 그 값을 알려면
    /// 세션 사용자의 임대 정보를 들고 있어야 하는데, 영구 상태의 Source of Truth 는 Spring
    /// 이다(헌법 1조).</para>
    ///
    /// <para>조준·사거리·하이라이트·프롬프트는 <see cref="BoothInteractionTarget"/> 과 공용
    /// <c>InteractPromptUI</c>/<c>InteractRing</c> 을 그대로 쓴다 (S15P21A604-355 에서 통일됨).
    /// 이 컴포넌트는 "F 가 눌렸을 때 무엇을 보내는가" 만 담당한다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class ManagementDeskInteractable : MonoBehaviour, IBoothInteractable
    {
        void Awake()
        {
            // 조준 대상이 되려면 콜라이더가 있어야 한다. NPC 프리팹에 이미 있으면 건드리지 않는다.
            if (GetComponentsInChildren<Collider>(true).Length == 0)
                gameObject.AddComponent<BoxCollider>();

            // 사거리·하이라이트 판정을 다른 상호작용과 같은 경로로 태운다.
            if (GetComponent<BoothInteractionTarget>() == null)
                gameObject.AddComponent<BoothInteractionTarget>();

            BoothInteractionInput.Ensure();
        }

        /// <summary>
        /// 인증 여부를 판정하지 않는다 — 게스트가 눌러도 이벤트는 나가고 회원 전용 안내는
        /// FE 가 띄운다. 다른 상호작용이 "등록 여부와 무관하게 트리거만" 인 것과 같은 원칙이다.
        /// </summary>
        public void Interact()
        {
            // 대화 카메라 — 티켓 부스 창구의 NPC 를 가슴 높이에서 바라본다(2026-09-06 배치 이동).
            InteractionFocusCamera.FocusOn(gameObject, 2.4f, 0.45f);
            BoothInteractBridge.SendManagementInteract();
        }
    }
}
