using Festa.Network;
using Unity.Netcode;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 자는 직원의 **월드 단위 상태 전파** (S15P21A604-672). 플레이어 프리팹에 붙는다.
    ///
    /// <para><b>왜 월드 단위인가.</b> 직원은 축제장에 하나뿐이다. 한 사람이 말을 걸어 들키면
    /// <b>모두에게</b> 사라져야 한다 — 내 화면에만 사라지면 옆 사람은 없는 사람에게 말을 걸고,
    /// 나는 없는 줄 아는 사람을 뚫고 지나간다. 처음엔 "각자 보는 것" 으로 만들었는데
    /// 사용자 확정(2026-09-14)으로 공유로 바꿨다.</para>
    ///
    /// <para><b>왜 플레이어에 붙는가.</b> 씬에는 <c>NetworkObject</c> 가 하나도 없고(직원은 정적
    /// 오브젝트다), 기준선인 <see cref="Festa.Network.ConnectionManager"/> 는 동결이라 새 네트워크
    /// 프리팹을 등록하지 않는다. <see cref="HighStrikerNetwork"/> 가 쓰는 방식 그대로 —
    /// 플레이어에 붙은 <see cref="NetworkBehaviour"/> 가 RPC 통로가 되고 공유 상태는 서버 정적 값에 둔다.</para>
    ///
    /// <para><b>복귀는 시각만 나눈다.</b> 서버가 "언제 돌아온다" 를 한 번 정해 보내면 각자 그 시각까지
    /// 자기 화면에서 센다. 5분 동안 아무 트래픽도 흐르지 않고, 늦게 들어온 사람도 남은 시간만 받으면
    /// 같은 그림이 된다.</para>
    /// </summary>
    [RequireComponent(typeof(NetworkPlayer))]
    public sealed class NappingStaffNetwork : NetworkBehaviour
    {
        /// <summary>서버에만 있는 공유 상태. 0 이면 직원은 제자리에서 자고 있다.</summary>
        static double s_returnAtServerTime;

        public override void OnNetworkSpawn()
        {
            // 늦게 들어와도 같은 그림을 보게 — 내 것이 스폰되면 현재 상태를 청한다.
            if (IsOwner) RequestStateServerRpc();
        }

        public override void OnNetworkDespawn()
        {
            // 서버가 내려가면 상태도 사라진다. 다음에 뜨면 직원은 다시 자고 있다.
            if (IsServer && NetworkManager != null && !NetworkManager.IsListening) s_returnAtServerTime = 0d;
        }

        /// <summary>소유자가 직원에게 말을 걸었다. 판정은 서버가 한다 — 두 사람이 동시에 걸어도 한 번만 들킨다.</summary>
        public void RequestWake()
        {
            // **조용히 되돌아 나가지 않는다.** 눌렀는데 아무 일도 안 일어나면 고장인지 원래 그런지
            // 아무도 모른다 (2026-09-14 사용자 지적: "키를 눌러도 반응이 아예 없음").
            if (!IsOwner)
            {
                Debug.LogWarning("[NappingStaff] 내 플레이어가 아니라 요청하지 않는다 — 상호작용이 먹지 않는 원인이다");
                return;
            }
            RequestWakeServerRpc();
        }

        [ServerRpc]
        void RequestWakeServerRpc(ServerRpcParams p = default)
        {
            double now = NetworkManager.ServerTime.Time;
            if (s_returnAtServerTime > now)
            {
                // 이미 들켜서 자리에 없다 — 두 번 깨우지 않는다. 다만 **요청자에게 현재 상태를 돌려준다**:
                // 사라진 사람을 조준할 수 있었다는 것은 그 화면이 어긋나 있다는 뜻이라, 여기서 맞춰 준다.
                Debug.Log($"[NappingStaff] 이미 들킨 상태 — 무시하고 요청자 화면만 맞춘다 (남은 {(s_returnAtServerTime - now):F0}s)");
                var resync = new ClientRpcParams
                {
                    Send = new ClientRpcSendParams { TargetClientIds = new[] { p.Receive.SenderClientId } },
                };
                StateClientRpc(s_returnAtServerTime, resync);
                return;
            }

            float returnAfter = NappingStaffInteractable.ReturnAfterSeconds;
            s_returnAtServerTime = now + returnAfter;
            WakeClientRpc(s_returnAtServerTime, p.Receive.SenderClientId);
        }

        [ClientRpc]
        void WakeClientRpc(double returnAtServerTime, ulong interactorClientId)
        {
            // 대사는 말을 건 사람에게만 띄운다. 화면 평면 토스트라, 멀리 있는 사람에게도 띄우면
            // 보이지도 않는 사람의 말이 화면을 가린다.
            bool mine = NetworkManager != null && NetworkManager.LocalClientId == interactorClientId;
            NappingStaffInteractable.ApplyWakeToAll(returnAtServerTime, mine);
        }

        [ServerRpc]
        void RequestStateServerRpc(ServerRpcParams p = default)
        {
            var target = new ClientRpcParams
            {
                Send = new ClientRpcSendParams { TargetClientIds = new[] { p.Receive.SenderClientId } },
            };
            StateClientRpc(s_returnAtServerTime, target);
        }

        [ClientRpc]
        void StateClientRpc(double returnAtServerTime, ClientRpcParams p = default)
        {
            // 들어온 시점에 이미 사라져 있으면 남은 시간만큼 감춘 채로 시작한다. 대사는 띄우지 않는다 —
            // 내가 건 것이 아니다.
            if (returnAtServerTime > 0d) NappingStaffInteractable.ApplyLateJoin(returnAtServerTime);
        }

        /// <summary>
        /// 시간 기준. 접속 중이면 서버 시각, 아니면 로컬 시각이다.
        ///
        /// <para><b>0 을 돌려주면 안 된다.</b> 접속 전에 0 을 주면 복귀 시각 비교
        /// (<c>ServerNow() >= _returnAtServerTime</c>)가 영원히 거짓이라 직원이 다시는 돌아오지 않는다.
        /// 오프라인에서도 시간은 흘러야 한다.</para>
        /// </summary>
        public static double ServerNow()
        {
            var nm = NetworkManager.Singleton;
            return nm != null && nm.IsListening ? nm.ServerTime.Time : Time.unscaledTimeAsDouble;
        }
    }
}
