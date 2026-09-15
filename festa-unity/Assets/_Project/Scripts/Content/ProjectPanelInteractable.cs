using Festa.Booth;
using Festa.Integration;
using UnityEngine;

namespace Festa.Content
{
    /// <summary>PROJECT_PANEL 상호작용(F 키)을 Unity→React 부스 이벤트로 변환한다 (S15P21A604-343).</summary>
    [RequireComponent(typeof(BoothRuntimeObject))]
    public sealed class ProjectPanelInteractable : MonoBehaviour, IBoothInteractable
    {
        BoothRuntimeObject _runtimeObject;

        void Awake()
        {
            _runtimeObject = GetComponent<BoothRuntimeObject>();

            // 레이캐스트 대상이 되도록 콜라이더를 보장한다 — LaptopInteractable 과 같은 이유.
            // 실제 프리팹은 자식에 콜라이더가 있고 디스패처가 부모에서 이 컴포넌트를 찾는다.
            if (GetComponentsInChildren<Collider>(true).Length == 0)
                gameObject.AddComponent<BoxCollider>();

            // 클릭 감지는 중앙 디스패처가 한다. OnMouseDown 은 WebGL 에서 발생하지 않는다 (T-166).
            BoothInteractionInput.Ensure();
        }

        /// <summary>
        /// 전시 등록 여부와 <b>무관하게</b> 트리거만 발생시킨다. 프로젝트 데이터는
        /// <c>GET /booths/{id}/projects</c> 소유라 Unity 는 있는지조차 모른다 — 노트북이
        /// 홈페이지 URL 을 모르는 것과 같은 구조다(헌법 25조). 전시가 없으면 FE 가 빈 상태를
        /// 안내한다.
        ///
        /// payload 에 projectId 를 넣지 않는 이유: 부스당 프로젝트가 1개라 boothId 만으로
        /// 조회가 끝난다(spec 009 C-01, 계약 #110 note 2754197).
        /// </summary>
        public void Interact()
        {
            if (_runtimeObject == null)
            {
                Debug.LogWarning("[ProjectPanelInteractable] BoothRuntimeObject가 없어 이벤트를 건너뜁니다.", this);
                return;
            }

            Festa.World.InteractionFocusCamera.FocusOn(gameObject, 1.6f);
            BoothInteractBridge.SendProjectInteract(_runtimeObject.BoothId, _runtimeObject.ObjectId);
        }
    }
}
