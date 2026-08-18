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
            var colliders = GetComponentsInChildren<Collider>(true);
            if (colliders.Length == 0)
                colliders = new Collider[] { gameObject.AddComponent<BoxCollider>() };

            // OnMouseDown은 Collider가 붙은 GameObject에만 전달된다.
            // 실제 LAPTOP 프리팹의 Collider가 자식에 있어도 루트 상호작용으로 중계한다.
            foreach (var targetCollider in colliders)
            {
                var clickTarget = targetCollider.GetComponent<LaptopClickTarget>();
                if (clickTarget == null)
                    clickTarget = targetCollider.gameObject.AddComponent<LaptopClickTarget>();
                clickTarget.Bind(this);
            }
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

    /// <summary>Collider가 있는 자식 오브젝트의 클릭을 LAPTOP 루트로 전달한다.</summary>
    public sealed class LaptopClickTarget : MonoBehaviour
    {
        LaptopInteractable _owner;

        public void Bind(LaptopInteractable owner) => _owner = owner;

        void OnMouseDown()
        {
            if (_owner != null)
                _owner.Interact();
        }
    }
}
