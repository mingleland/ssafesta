using Festa.Content;
using Festa.Integration;
using TMPro;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 11층 안내데스크 NPC (S15P21A604-674, GitLab #184).
    ///
    /// <para>머리 위에 안내 말풍선을 띄우고, F 를 누르면 FE 의 이용 가이드라인 화면을 연다.
    /// <see cref="ManagementDeskInteractable"/> 과 같은 결이다 — 부스 오브젝트가 아니고
    /// boothId 를 싣지 않는다. 안내 데스크는 축제장에 하나뿐이고 화면이 부스에 매이지 않는다.</para>
    ///
    /// <para><b>말풍선은 월드 안에서 그린다.</b> FE 오버레이를 쓰지 않는 이유는 계약을 늘리지
    /// 않기 위해서다 — 부스 간판·이름표가 이미 같은 방식이고, 이 문구는 상호작용 전에 상시
    /// 떠 있어야 해서 오버레이로 만들면 화면을 가린다.</para>
    ///
    /// <para>조준·사거리·하이라이트·프롬프트는 <see cref="BoothInteractionTarget"/> 과 공용
    /// <c>InteractPromptUI</c> 를 그대로 쓴다. 이 컴포넌트는 "말풍선을 세우고, F 에 무엇을
    /// 보내는가" 만 담당한다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class GuideDeskInteractable : MonoBehaviour, IBoothInteractable
    {
        /// <summary>
        /// 말풍선 문구. 느낌표를 겹치지 않고 **할 일 한 줄**을 남긴다 — 안내문이 들뜨면
        /// 읽히기보다 넘겨진다. 첫 줄은 여기가 어디인지, 둘째 줄은 무엇을 누르면 되는지다.
        /// </summary>
        const string BubbleText = "SSAFESTA 안내 데스크입니다\n처음이시면 F 를 눌러 이용 안내를 보세요";

        [Tooltip("말풍선을 머리 위 얼마에 둘지(u). 1 m = 13.26 u — 24 u ≈ 1.8 m")]
        [SerializeField] float _bubbleHeight = 24f;

        [Tooltip("말풍선이 보이기 시작하는 거리(u). 멀리서 글자가 뭉치는 것을 막는다")]
        [SerializeField] float _visibleDistance = 260f;

        Transform _bubble;
        TMP_Text _label;

        void Awake()
        {
            // 조준 대상이 되려면 콜라이더가 있어야 한다. NPC 프리팹에 이미 있으면 건드리지 않는다.
            if (GetComponentsInChildren<Collider>(true).Length == 0)
                gameObject.AddComponent<BoxCollider>();

            if (GetComponent<BoothInteractionTarget>() == null)
                gameObject.AddComponent<BoothInteractionTarget>();

            BoothInteractionInput.Ensure();
            BuildBubble();
        }

        /// <summary>
        /// 말풍선을 런타임에 세운다. **씬을 고치지 않는다** — 부스 대표색·간판과 같은 이유로,
        /// 씬 파일을 건드리면 다른 브랜치의 씬 변경과 충돌한다.
        /// </summary>
        void BuildBubble()
        {
            var font = Resources.Load<TMP_FontAsset>("Fonts/ChalkboardKR_SDF")
                    ?? Resources.Load<TMP_FontAsset>("Fonts/Jua_SDF")
                    ?? Resources.Load<TMP_FontAsset>("Fonts/NotoSansKRBold_SDF");
            if (font == null)
            {
                // 조용히 넘어가면 말풍선이 없는 이유를 아무도 모른다.
                Debug.LogError("[GuideDesk] Resources/Fonts 에 쓸 글꼴이 없어 말풍선을 만들지 못했다");
                return;
            }

            var go = new GameObject("GuideBubble");
            go.transform.SetParent(transform, false);
            go.transform.localPosition = new Vector3(0f, _bubbleHeight, 0f);
            _bubble = go.transform;

            var tmp = go.AddComponent<TextMeshPro>();
            tmp.font = font;
            tmp.text = BubbleText;
            tmp.color = new Color(0.97f, 0.97f, 0.94f);
            tmp.alignment = TextAlignmentOptions.Center;
            // 줄바꿈은 문구에 직접 넣었다. TMP 에 맡기면 한글을 글자 단위로 끊는다 (간판에서 겪은 것).
            tmp.textWrappingMode = TextWrappingModes.NoWrap;
            tmp.overflowMode = TextOverflowModes.Overflow;
            tmp.fontSize = 28f;   // fontSize 10 = 월드 1 unit (WorldNameplate 실측)
            tmp.outlineWidth = 0.18f;
            tmp.outlineColor = new Color32(24, 28, 38, 220);
            _label = tmp;
        }

        /// <summary>
        /// 말풍선은 **항상 카메라를 본다.** 안내문은 어느 방향에서 와도 읽혀야 한다.
        /// 멀면 끈다 — 글자가 몇 픽셀로 뭉쳐 노이즈만 된다.
        /// </summary>
        void LateUpdate()
        {
            if (_bubble == null) return;
            var cam = Camera.main;
            if (cam == null) return;

            float sqr = (cam.transform.position - _bubble.position).sqrMagnitude;
            bool near = sqr <= _visibleDistance * _visibleDistance;
            if (_label != null && _label.enabled != near) _label.enabled = near;
            if (!near) return;

            // Y 축만 돌린다. 카메라를 통째로 바라보면 위에서 볼 때 글자가 눕는다.
            var to = cam.transform.position - _bubble.position;
            to.y = 0f;
            if (to.sqrMagnitude > 0.0001f)
                _bubble.rotation = Quaternion.LookRotation(-to.normalized, Vector3.up);
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
