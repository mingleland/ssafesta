using Festa.Booth;
using Festa.Integration;
using UnityEngine;

namespace Festa.Content
{
    /// <summary>SURVEY_KIOSK 상호작용(F 키)을 Unity→React 부스 이벤트로 변환한다 (S15P21A604-415).</summary>
    [RequireComponent(typeof(BoothRuntimeObject))]
    public sealed class SurveyKioskInteractable : MonoBehaviour, IBoothInteractable
    {
        BoothRuntimeObject _runtimeObject;

        void Awake()
        {
            _runtimeObject = GetComponent<BoothRuntimeObject>();

            if (GetComponentsInChildren<Collider>(true).Length == 0)
                gameObject.AddComponent<BoxCollider>();

            BoothInteractionInput.Ensure();
        }

        /// <summary>
        /// 설문 발행 여부와 <b>무관하게</b> 트리거만 발생시킨다. 설문 데이터·발행 상태·설문
        /// 식별자는 전부 BE 소유라 Unity 가 알 수 없다 — 없으면 FE 가 빈 상태를 안내한다.
        ///
        /// <para><b>surveyId 를 보내지 않는다.</b> MVP 는 부스당 활성 설문 1개로 다루지만
        /// 그것을 <c>boothId == surveyId</c> 로 모델링하지 않는다. boothId 는 설문의 ID 가 아니라
        /// 설문을 resolve 하기 위한 <b>context</b> 다. 부스에 설문이 여럿이 되는 날에는
        /// 함께 보내는 <c>objectId</c> 로 특정 설문에 binding 하면 되고, 그때 Unity 쪽 계약은
        /// 바뀌지 않는다.</para>
        /// </summary>
        public void Interact()
        {
            if (_runtimeObject == null)
            {
                Debug.LogWarning("[SurveyKioskInteractable] BoothRuntimeObject가 없어 이벤트를 건너뜁니다.", this);
                return;
            }

            Festa.World.InteractionFocusCamera.FocusOn(gameObject, 1.8f);
            BoothInteractBridge.SendSurveyInteract(_runtimeObject.BoothId, _runtimeObject.ObjectId);
        }
    }
}
