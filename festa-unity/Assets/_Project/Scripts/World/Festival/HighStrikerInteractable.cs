using Festa.Booth;
using Festa.Content;
using Festa.Network;
using Unity.Netcode;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 하이 스트라이커에 F — 망치를 내리찍는다 (S15P21A604-585).
    ///
    /// <para>여기서 하는 일은 셋이다: 기계를 향해 돌려 세우고, 원샷 이모트
    /// <see cref="PlayerEmoteId.Strike"/> 를 재생하고(이모트는 NetworkVariable 이라 남에게도 보인다),
    /// 서버에 스윙을 청한다. <b>점수는 서버가 굴린다</b> — 여기서 굴리면 사람마다 다른 결과가 보인다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class HighStrikerInteractable : MonoBehaviour, IBoothInteractable
    {
        [Tooltip("이 상호작용이 움직일 기계. 비우면 씬의 첫 기계를 쓴다")]
        [SerializeField] HighStrikerMachine _machine;

        /// <summary>작동 중인지 — <see cref="Festa.Content.BoothInteractionInput"/> 가 프롬프트·링을 아예 끄는 데 쓴다.</summary>
        public bool IsBusy
        {
            get
            {
                var m = _machine != null ? _machine : HighStrikerMachine.Any();
                return m != null && m.IsBusy;
            }
        }

        void Awake()
        {
            if (GetComponentsInChildren<Collider>(true).Length == 0) gameObject.AddComponent<BoxCollider>();
            if (GetComponent<BoothInteractionTarget>() == null) gameObject.AddComponent<BoothInteractionTarget>();
            BoothInteractionInput.Ensure();
        }

        public void Interact()
        {
            var nm = NetworkManager.Singleton;
            var po = nm != null && nm.LocalClient != null ? nm.LocalClient.PlayerObject : null;
            if (po == null) { Debug.LogWarning("[HighStriker] 로컬 플레이어가 없다 — 접속 전"); return; }

            var machine = _machine != null ? _machine : HighStrikerMachine.Any();
            if (machine == null) { Debug.LogWarning("[HighStriker] 씬에 기계가 없다"); return; }
            if (machine.IsBusy) { BoothInteractionInput.Toast("아직 종이 울리는 중이에요", 1.5f); return; }

            var emotes = po.GetComponent<PlayerEmoteController>();
            var striker = po.GetComponent<HighStrikerNetwork>();
            if (striker == null)
            {
                Debug.LogWarning("[HighStriker] 플레이어 프리팹에 HighStrikerNetwork 가 없다 — 프리팹 배선을 확인해야 한다");
                return;
            }

            var prop = po.GetComponent<AvatarStrikeProp>();
            if (prop == null || !prop.TryAlign(machine))
            {
                BoothInteractionInput.Toast("타격판 정면에서 다시 시도해 주세요", 1.5f);
                return;
            }

            // 서버 왕복 전에 먼저 멈춘다. PlayerMovement와 이 컴포넌트의 Update 순서에 따라
            // 걷기 블렌드가 한 프레임 남을 수 있으므로 입력 잠금만 하지 않고 잔류값도 즉시 0으로 만든다.
            striker.BeginLocalInteractionLock();

            // 내 화면은 먼저 잠근다 — 서버 왕복을 기다리는 동안 연타되면 이모트만 여러 번 나간다.
            // 남들은 SwingClientRpc 를 받는 순간 잠긴다.
            //
            // **연출 길이가 아니라 승인 대기 시간만 잠근다** (S15P21A604-967). 승인되면 SwingClientRpc 가
            // 실제 연출 길이로 늘리고, 거절되면 SwingRejectedClientRpc 가 즉시 푼다. 예전처럼 연출 길이로
            // 미리 잠그면 거절된 입력 한 번이 화면을 2초 넘게 가려서 연타가 계속 씹히는 것으로 보였다.
            machine.BeginBusy(HighStrikerNetwork.RequestLockTimeout);
            striker.RequestSwing(machine.MachineId);
        }
    }
}
