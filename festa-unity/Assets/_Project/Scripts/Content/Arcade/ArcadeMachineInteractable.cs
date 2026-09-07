using Festa.Booth;
using Festa.Integration;
using Festa.World;
using UnityEngine;

namespace Festa.Content.Arcade
{
    /// <summary>
    /// 광장 게임기 (S15P21A604-440, GitLab #56 안 1).
    ///
    /// <para>F → 카메라가 게임기 화면 앞으로 zoom-in 하고 월드 입력을 잠근 뒤 <c>WORLD_ARCADE_INTERACT {machineId}</c> 를
    /// 호스트(React)로 보낸다. 어떤 Game Studio 게임이 걸렸는지는 FE 가 machineId 로 서버에서 resolve 한다 —
    /// Unity 는 gameId 를 모르고 GameProject 를 해석하지 않는다 (spec 019 FR-017).</para>
    ///
    /// <para>FE 가 게임 오버레이를 닫으며 <c>SetInputLocked('0')</c> 을 보내면 초점이 풀린다. FE 수신부가 없어도
    /// Esc 로 언제든 나갈 수 있다 — 갇힘 방지(spec 019 FR-020).</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class ArcadeMachineInteractable : MonoBehaviour, IBoothInteractable
    {
        [Tooltip("씬이 정한 canonical id. FE 가 GET /api/v1/arcade-machines/{machineId} 로 resolve 한다.")]
        [SerializeField] string _machineId = "plaza-arcade-01";

        [Header("초점 카메라 (게임기 로컬 좌표 — 스케일 포함)")]
        [SerializeField] Vector3 _cameraLocal = new(0f, 1.45f, 1.9f);
        [SerializeField] Vector3 _lookLocal = new(0f, 1.3f, 0.2f);

        public string MachineId => _machineId;

        void Awake()
        {
            if (GetComponentsInChildren<Collider>(true).Length == 0)
                gameObject.AddComponent<BoxCollider>();
            if (GetComponent<BoothInteractionTarget>() == null)
                gameObject.AddComponent<BoothInteractionTarget>();
            BoothInteractionInput.Ensure();
        }

        public void Interact()
        {
            if (InteractionFocusCamera.IsFocused) return;
            InteractionFocusCamera.Focus(transform, _cameraLocal, _lookLocal);
            BoothInteractBridge.SendArcadeInteract(_machineId);
        }
    }
}
