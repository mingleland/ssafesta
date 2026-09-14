using Festa.Booth;
using Festa.Content;
using Festa.Integration;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 11층 안내데스크 NPC (S15P21A604-674, GitLab #184).
    ///
    /// <para>머리 위에 이름을 띄우고, F 를 누르면 FE 의 이용 가이드라인 화면을 연다.
    /// <see cref="ManagementDeskInteractable"/> 과 같은 결이다 — 부스 오브젝트가 아니고
    /// boothId 를 싣지 않는다. 안내 데스크는 축제장에 하나뿐이고 화면이 부스에 매이지 않는다.</para>
    ///
    /// <para><b>안내 문구를 띄우지 않는다 — 이름만 띄운다.</b> 처음에는 두 줄짜리 안내 말풍선
    /// ("SSAFESTA 안내 데스크입니다 / 처음이시면 F 를 눌러…")을 세웠는데, 글자가 커서 뒤 벽과
    /// 다른 사람 이름표를 덮었다(2026-09-14 사용자 지적). 무엇을 누르면 되는지는 가까이 갔을 때
    /// 뜨는 <c>InteractPromptUI</c> 의 "F 상호작용" 이 이미 말해 준다 — 같은 말을 두 번 할 이유가 없다.</para>
    ///
    /// <para>표시는 사람 이름표와 <b>같은 장치</b>(<see cref="WorldNameplate"/>)를 쓴다. 빌보드 회전,
    /// 거리에 따른 크기 보정, 벽에 가리면 감추기가 전부 거기 들어 있다 — 여기서 다시 만들면
    /// 그 셋을 또 틀린다(말풍선이 한 번도 빌보드를 돌지 않던 T-263 이 그 예다).</para>
    ///
    /// <para>조준·사거리·하이라이트·프롬프트는 <see cref="BoothInteractionTarget"/> 과 공용
    /// <c>InteractPromptUI</c> 를 그대로 쓴다. 이 컴포넌트는 "이름을 세우고, F 에 무엇을
    /// 보내는가" 만 담당한다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class GuideDeskInteractable : MonoBehaviour, IBoothInteractable
    {
        /// <summary>머리 위에 띄울 이름. 사람 닉네임과 같은 자리, 같은 크기다.</summary>
        /// <remarks>
        /// "안내데스크 NPC" 에서 사용자가 줄인 문구다 (2026-09-14). 인스펙터가 아니라 여기 둔다 —
        /// 씬에 박으면 재직렬화 때 색·크기까지 함께 굳어 코드에서 고친 값이 먹지 않는다.
        /// </remarks>
        [SerializeField] string _displayName = "안내 데스크";

        [Tooltip("이름이 보이기 시작하는 거리(u).")]
        [SerializeField] float _visibleDistance = 260f;

        /// <summary>
        /// 이름 색. 사람과 구분되는 노란 계열이되 <b>샛노랑이 아니라 골드앰버</b>다.
        ///
        /// <para>처음엔 (1, 0.84, 0.2) 순노랑을 썼는데, 이 데스크 뒤가 밝은 회색 벽이라
        /// <b>글자와 배경의 밝기가 같아져</b>(휘도 0.70 대 0.68, 대비 1.03:1) 뭐라 쓰였는지 읽히지 않았다
        /// (2026-09-14 사용자 지적). 휘도를 0.53 으로 내렸다.</para>
        ///
        /// <para><b>이 색만으로는 부족하다</b> — 대비가 1.26:1 로 올라갈 뿐이다. 밝은 배경에서 실제로
        /// 글자를 세우는 것은 <c>SetLegibility</c> 로 올리는 <b>외곽선</b>이고, 색을 내린 것은 거기에
        /// 얹는 보탬이다. 더 어둡게 내리지 않는 이유는 밤 배경에서 반대로 묻히기 때문이다.</para>
        /// </summary>
        [SerializeField] Color _nameColor = new(0.98f, 0.70f, 0.08f, 1f);

        [Tooltip("글자 크기. 기본(1.35)보다 키운다 — 노란 글자는 흰 닉네임보다 배경에 묻히기 쉽다.")]
        [SerializeField] float _characterHeight = 1.9f;

        [Tooltip("외곽선 두께. 0.25 가 한글에서 획 사이가 메워지지 않는 상한이다.")]
        [SerializeField] float _outlineWidth = 0.25f;

        void Awake()
        {
            // 조준 대상이 되려면 콜라이더가 있어야 한다. NPC 프리팹에 이미 있으면 건드리지 않는다.
            if (GetComponentsInChildren<Collider>(true).Length == 0)
                gameObject.AddComponent<BoxCollider>();

            if (GetComponent<BoothInteractionTarget>() == null)
                gameObject.AddComponent<BoothInteractionTarget>();

            BoothInteractionInput.Ensure();
            BuildNameplate();
        }

        /// <summary>
        /// 이름표를 세운다. **씬을 고치지 않는다** — 부스 대표색·간판과 같은 이유로, 씬 파일을
        /// 건드리면 다른 브랜치의 씬 변경과 충돌한다.
        /// </summary>
        void BuildNameplate()
        {
            var plate = GetComponent<WorldNameplate>();
            if (plate == null) plate = gameObject.AddComponent<WorldNameplate>();

            plate.Label = _displayName;
            plate.SetVisibleDistance(_visibleDistance);
            plate.SetColor(_nameColor);
            plate.SetLegibility(_characterHeight, _outlineWidth);
        }

        /// <summary>
        /// 게스트도 그대로 보낸다 — Unity 는 게이트를 걸지 않는다. 회원 전용 안내가 필요하면
        /// FE 가 띄운다 (#170 에서 정한 원칙과 같다).
        /// </summary>
        public void Interact()
        {
            // 안내를 받는 동안 캐릭터가 뛰어다니지 않게 초점을 잡는다. 값은 관리 데스크와 같다.
            InteractionFocusCamera.FocusOn(gameObject, 2.4f, 0.45f);
            BoothInteractBridge.SendGuideInteract();
        }
    }
}
