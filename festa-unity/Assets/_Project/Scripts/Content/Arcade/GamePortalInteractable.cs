using Festa.Booth;
using Festa.Integration;
using Festa.World;
using UnityEngine;

namespace Festa.Content.Arcade
{
    /// <summary>
    /// 부스에 배치된 GAME_PORTAL 게임기 (S15P21A604-440, spec 019 contracts/game-portal-bridge.md).
    ///
    /// <para>F → zoom-in + 입력 잠금 → <c>BOOTH_GAME_INTERACT {boothId, objectId, configId}</c>. configId 는 Layout 의
    /// Portal Binding 공개 ID(1 이상)이고 0 은 미연결이라 이벤트를 보내지 않는다. 공개·임대·Binding 판정은 전부 서버 —
    /// Unity 는 트리거만 한다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    [RequireComponent(typeof(BoothRuntimeObject))]
    public sealed class GamePortalInteractable : MonoBehaviour, IBoothInteractable
    {
        [Header("초점 카메라 (게임기 로컬 좌표 — 스케일 포함)")]
        [SerializeField] Vector3 _cameraLocal = new(0f, 1.45f, 1.9f);
        [SerializeField] Vector3 _lookLocal = new(0f, 1.3f, 0.2f);

        BoothRuntimeObject _runtimeObject;

        void Awake()
        {
            _runtimeObject = GetComponent<BoothRuntimeObject>();
            if (GetComponentsInChildren<Collider>(true).Length == 0)
                gameObject.AddComponent<BoxCollider>();
            BoothInteractionInput.Ensure();
        }

        public void Interact()
        {
            if (_runtimeObject == null)
            {
                Debug.LogWarning("[GamePortal] BoothRuntimeObject 가 없어 상호작용을 건너뜁니다.");
                return;
            }
            if (!_runtimeObject.HasConfig)
            {
                // 미연결 게임기 — 계약상 이벤트 금지. 조용히 넘기지 않고 로그로 드러낸다.
                Debug.LogWarning($"[GamePortal] configId 가 0 이라 실행 요청을 보내지 않는다 (booth={_runtimeObject.BoothId}, object={_runtimeObject.ObjectId}). 스튜디오에서 게임을 연결해야 한다.");
                return;
            }
            if (InteractionFocusCamera.IsFocused) return;

            InteractionFocusCamera.Focus(transform, _cameraLocal, _lookLocal);
            BoothInteractBridge.SendGameInteract(_runtimeObject.BoothId, _runtimeObject.ObjectId, _runtimeObject.ConfigId);
        }
    }
}
