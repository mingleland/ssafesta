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
        /// <summary>매표소 위에 띄울 이름. 사람 닉네임과 같은 장치를 쓴다.</summary>
        /// <remarks>
        /// "부스 관리 NPC" 에서 사용자가 줄인 문구다 (2026-09-14). 인스펙터가 아니라 여기 둔다 —
        /// 씬에 박으면 재직렬화 때 색·크기까지 함께 굳어 코드에서 고친 값이 먹지 않는다.
        /// </remarks>
        [SerializeField] string _displayName = "부스 관리";

        [Tooltip("이름이 보이기 시작하는 거리(u).")]
        [SerializeField] float _visibleDistance = 260f;

        /// <summary>
        /// 이름 색. 안내데스크와 같은 골드앰버다 — 두 NPC 이름표는 한 벌로 읽혀야 한다.
        /// 순노랑에서 내린 이유는 <see cref="GuideDeskInteractable"/> 쪽에 적어 뒀다(밝은 배경과 밝기가 같아진다).
        /// </summary>
        [SerializeField] Color _nameColor = new(0.98f, 0.70f, 0.08f, 1f);

        [Tooltip("글자 크기. 매표소 지붕 위라 사람 닉네임보다 멀리서 읽혀야 한다.")]
        [SerializeField] float _characterHeight = 1.9f;

        [Tooltip("외곽선 두께. 0.25 가 한글에서 획 사이가 메워지지 않는 상한이다.")]
        [SerializeField] float _outlineWidth = 0.25f;

        void Awake()
        {
            // 조준 대상이 되려면 콜라이더가 있어야 한다. NPC 프리팹에 이미 있으면 건드리지 않는다.
            if (GetComponentsInChildren<Collider>(true).Length == 0)
                gameObject.AddComponent<BoxCollider>();

            // 사거리·하이라이트 판정을 다른 상호작용과 같은 경로로 태운다.
            if (GetComponent<BoothInteractionTarget>() == null)
                gameObject.AddComponent<BoothInteractionTarget>();

            BoothInteractionInput.Ensure();
            BuildNameplate();
        }

        /// <summary>
        /// 이름표를 세운다. 사람 이름표와 <b>같은 장치</b>(<see cref="WorldNameplate"/>)라 빌보드 회전·
        /// 거리별 크기 보정·벽에 가리면 감추기가 그대로 따라온다.
        ///
        /// <para><b>기준 높이는 사람 머리가 아니라 매표소 지붕이다.</b> 이 데스크는 사람이 부스 안에
        /// 들어가 있는 구조라, 머리 위에 붙이면 이름이 지붕에 파묻힌다 (2026-09-14 사용자 지적).
        /// 그래서 구조물 정점에 고정한다.</para>
        /// </summary>
        void BuildNameplate()
        {
            var plate = GetComponent<WorldNameplate>();
            if (plate == null) plate = gameObject.AddComponent<WorldNameplate>();

            plate.Label = _displayName;
            plate.SetVisibleDistance(_visibleDistance);
            plate.SetColor(_nameColor);
            plate.SetLegibility(_characterHeight, _outlineWidth);
            plate.PinToStructureTop();
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
