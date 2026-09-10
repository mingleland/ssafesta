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

            // 기계를 마주 보게 돌린다. 옆·뒤에서 눌러도 내리찍는 방향이 맞아야 연출이 성립한다.
            var toMachine = machine.transform.position - po.transform.position;
            toMachine.y = 0f;
            if (toMachine.sqrMagnitude > 0.01f) po.transform.rotation = Quaternion.LookRotation(toMachine.normalized, Vector3.up);

            // 내 화면은 먼저 잠근다 — 서버 왕복을 기다리는 동안 연타되면 이모트만 여러 번 나간다.
            // 남들은 SwingClientRpc 를 받는 순간 잠긴다.
            machine.BeginBusy(HighStrikerNetwork.ImpactDelay + machine.SequenceSeconds);
            if (emotes != null) emotes.PlayOneShot(PlayerEmoteId.Strike);
            striker.RequestSwing(machine.MachineId);
        }
    }
}
