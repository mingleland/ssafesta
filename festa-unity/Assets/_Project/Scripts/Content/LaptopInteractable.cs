using Festa.Booth;
using Festa.Integration;
using UnityEngine;

namespace Festa.Content
{
    /// <summary>LAPTOP 상호작용(F 키)을 Unity→React 부스 이벤트로 변환한다.</summary>
    [RequireComponent(typeof(BoothRuntimeObject))]
    public sealed class LaptopInteractable : MonoBehaviour, IBoothInteractable
    {
        BoothRuntimeObject _runtimeObject;

        void Awake()
        {
            _runtimeObject = GetComponent<BoothRuntimeObject>();

            // 레이캐스트 대상이 되도록 콜라이더를 보장한다. 실제 LAPTOP 프리팹은
            // 자식(TableSquare)에 콜라이더가 있고, 디스패처가 부모에서 이 컴포넌트를 찾는다.
            if (GetComponentsInChildren<Collider>(true).Length == 0)
                gameObject.AddComponent<BoxCollider>();

            // 클릭 감지는 중앙 디스패처가 한다. OnMouseDown 은 WebGL 에서 발생하지 않는다 (T-166).
            BoothInteractionInput.Ensure();
        }

        /// <summary>
        /// 디스패처용 진입점. `Interact(string)` 은 기본 인자가 있어도 시그니처가 달라
        /// `void Interact()` 를 만족하지 못하므로 명시적으로 잇는다.
        /// </summary>
        void IBoothInteractable.Interact() => Interact();

        public void Interact(string url = null)
        {
            if (_runtimeObject == null)
            {
                Debug.LogWarning("[LaptopInteractable] BoothRuntimeObject가 없어 이벤트를 건너뜁니다.", this);
                return;
            }

            BoothInteractBridge.SendLaptopInteract(
                _runtimeObject.BoothId,
                _runtimeObject.ObjectId,
                url);
        }
    }
}
