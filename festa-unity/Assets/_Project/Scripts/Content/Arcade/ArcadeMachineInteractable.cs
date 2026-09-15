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

        /// <summary>호스트가 이 시간 안에 화면을 열지 않으면 초점을 스스로 푼다.</summary>
        const float HostResponseTimeout = 3f;

        public void Interact()
        {
            if (InteractionFocusCamera.IsFocused) return;
            InteractionFocusCamera.Focus(transform, _cameraLocal, _lookLocal);
            BoothInteractBridge.SendArcadeInteract(_machineId);
            StartCoroutine(ReleaseIfHostNeverAnswers());
        }

        /// <summary>
        /// FE 수신부가 없거나 게임 resolve 에 실패하면, 화면은 확 들어간 채 아무 일도 일어나지 않는다.
        /// 문서상 Esc 로 나갈 수 있지만 **화면에 그 안내가 없어서** 사용자는 갇혔다고 느낀다
        /// (2026-09-08 조사). 호스트가 응답하지 않으면 스스로 풀고 이유를 말한다.
        ///
        /// <para>판정 기준은 <b>호스트가 입력 잠금을 쥐었는가</b>다. 오버레이를 여는 쪽은 반드시
        /// <c>SetInputLocked('1')</c> 을 보내므로(G-8, GitLab #132), 그 주인이 나타나면 정상 진행이다.</para>
        /// </summary>
        System.Collections.IEnumerator ReleaseIfHostNeverAnswers()
        {
            float deadline = Time.unscaledTime + HostResponseTimeout;
            while (Time.unscaledTime < deadline)
            {
                if (!InteractionFocusCamera.IsFocused) yield break;                    // 사용자가 Esc 로 나갔다
                if (InputBridge.HoldersDescription().Contains("host")) yield break;    // 호스트가 화면을 열었다
                yield return null;
            }
            if (!InteractionFocusCamera.IsFocused) yield break;

            Debug.LogWarning($"[Arcade] {HostResponseTimeout:F0}초 동안 호스트가 게임 화면을 열지 않았다 " +
                             $"(machineId={_machineId}). 초점을 풀고 사용자에게 알린다 — " +
                             "FE 의 WORLD_ARCADE_INTERACT 수신부를 확인해야 한다 (GitLab #135).");
            InteractionFocusCamera.Release();
            BoothInteractionInput.Toast("이 게임기는 아직 열 수 없어요", 3f);
        }
    }
}
