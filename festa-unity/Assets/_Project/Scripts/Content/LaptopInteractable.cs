using Festa.Booth;
using Festa.Integration;
using UnityEngine;

namespace Festa.Content
{
    /// <summary>LAPTOP 클릭을 Unity→React 부스 상호작용 이벤트로 변환한다.</summary>
    [RequireComponent(typeof(BoothRuntimeObject))]
    public sealed class LaptopInteractable : MonoBehaviour
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
