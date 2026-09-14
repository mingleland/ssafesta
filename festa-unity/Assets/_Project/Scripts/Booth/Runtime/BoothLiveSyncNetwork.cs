using Festa.Network;
using Unity.Netcode;
using UnityEngine;

namespace Festa.Booth
{
    /// <summary>
    /// <b>부스 임대·게시를 접속자 전원에게 즉시 퍼뜨린다</b> (S15P21A604-716, 사용자 확정 2026-09-14:
    /// "부스가 임대됐다는 통신이 오면 그 즉시 런타임에서 조립되야 해. 이게 우리 프로젝트 기본 전제 조건이야").
    ///
    /// <para><b>무엇이 빠져 있었나.</b> 런타임 조립 경로는 이미 완성돼 있다 —
    /// <see cref="WorldBoothPublishedBootstrap.RequestReload"/> 가 간판·외벽을 다시 읽고
    /// <c>BoothRuntime.Rebuild</c> 로 집기를 새로 세운다. 그런데 그걸 <b>부르는 신호</b>가
    /// FE 브리지 하나뿐이었고(<see cref="Festa.Integration.BoothLayoutBridge"/>), 그 브리지는
    /// <c>getReadyUnityInstance()</c> 로 <b>자기 브라우저 탭의 Unity 인스턴스에만</b> SendMessage 한다.
    /// 그래서 임대한 본인 화면에서는 즉시 조립되고, 같은 월드에 있는 다른 사람에게는 아무 신호도 가지 않아
    /// 새로고침 전까지 빈 자리로 남았다. <b>렌더링 결함이 아니라 신호가 한 탭을 못 벗어난 것이다.</b></para>
    ///
    /// <para><b>왜 NGO 인가.</b> 부스 오브젝트 자체는 <c>NetworkObject</c> 가 아니라 클라이언트별 Local Spawn 이라
    /// 서버가 복제해 줄 수 없다 — 그래서 "월드 서버가 알려 줄 길도 없다" 고 적혀 있었는데, <b>그 전제가 틀렸다.</b>
    /// 오갈 것은 오브젝트가 아니라 <b>슬롯 번호 하나(int)</b> 다. 번호만 퍼뜨리면 각 클라이언트가 스스로
    /// 조회해 조립한다. 이미 열려 있는 월드 연결을 그대로 쓰므로 새 인프라도, 새 엔드포인트도 필요 없다.</para>
    ///
    /// <para><b>왜 플레이어 프리팹에 붙는가.</b> 씬에는 <c>NetworkObject</c> 가 하나도 없고 기준선인
    /// <see cref="Festa.Network.ConnectionManager"/> 는 동결이라 새 네트워크 프리팹을 등록할 수 없다.
    /// <see cref="Festa.World.NappingStaffNetwork"/>·<c>HighStrikerNetwork</c> 가 쓰는 방식 그대로 —
    /// 플레이어에 붙은 <see cref="NetworkBehaviour"/> 가 RPC 통로가 된다. ClientRpc 는 <b>보낸 사람의
    /// NetworkObject</b> 를 타고 각 클라이언트에 한 번씩만 도착하므로, 접속자가 40명이어도 중복 조립은 없다.</para>
    ///
    /// <para><b>서버는 중계만 한다.</b> 전용 서버는 <c>-batchmode</c> 라 부스 비주얼이 없고
    /// (<see cref="WorldBoothPublishedBootstrap"/> 가 배치 모드에서 설치 자체를 건너뛴다)
    /// API 클라이언트도 들지 않는다. 그래서 "정말 임대됐는가" 를 서버가 되묻지는 않는다 —
    /// 대신 <b>번호 범위와 도배 빈도</b>만 막는다. 거짓 번호가 와도 각 클라이언트의 조회가 404 로 끝나
    /// 기존 모습이 유지되므로(<see cref="WorldBoothPublishedBootstrap.RequestReload"/>), 피해는 헛조회 한 번이다.</para>
    /// </summary>
    [RequireComponent(typeof(NetworkPlayer))]
    public sealed class BoothLiveSyncNetwork : NetworkBehaviour
    {
        /// <summary>부스 슬롯은 1~12 실이다. 벗어난 번호는 서버가 버린다.</summary>
        const int MaxSlotId = 12;

        /// <summary>
        /// 한 클라이언트가 이 간격보다 자주 알리면 버린다. 임대·게시는 사람이 버튼을 누르는 일이라
        /// 초당 한 번도 과하다 — 실수로 연타해도 월드 전체가 조회를 반복하지 않게 막는다.
        /// </summary>
        const double MinAnnounceInterval = 1.0d;

        /// <summary>서버에만 있는 도배 방지 기록 (clientId → 마지막 수락 시각).</summary>
        static readonly System.Collections.Generic.Dictionary<ulong, double> s_lastAnnounce = new();

        /// <summary>내 플레이어에 붙은 것. 브리지가 여기로 들어온다.</summary>
        static BoothLiveSyncNetwork s_local;

        public override void OnNetworkSpawn()
        {
            if (IsOwner) s_local = this;
            if (IsServer) s_lastAnnounce.Clear();
        }

        public override void OnNetworkDespawn()
        {
            if (s_local == this) s_local = null;
            if (IsServer) s_lastAnnounce.Remove(OwnerClientId);
        }

        /// <summary>
        /// 이 슬롯이 바뀌었다고 월드 전체에 알린다. 부르는 곳은 <see cref="Festa.Integration.BoothLayoutBridge"/> —
        /// 임대(<c>useLeaseSlot</c>)·게시(<c>useLayoutMutations</c>) 성공 직후 FE 가 SendMessage 한 그 자리다.
        ///
        /// <para><b>조용히 실패하지 않는다.</b> 월드에 붙기 전이면 보낼 곳이 없는데, 그때 아무 말도 없으면
        /// "남에게 안 보인다" 가 다시 원인 불명이 된다 (T-24 의 교훈). 못 보낸 이유를 로그로 남긴다.</para>
        /// </summary>
        /// <returns>서버로 보냈으면 true.</returns>
        public static bool Announce(int slotId)
        {
            if (slotId <= 0 || slotId > MaxSlotId)
            {
                Debug.LogWarning($"[BoothLiveSync] 슬롯 번호가 범위 밖이라 알리지 않는다: {slotId}");
                return false;
            }
            if (s_local == null || !s_local.IsSpawned)
            {
                Debug.LogWarning($"[BoothLiveSync] 월드에 접속하지 않아 슬롯 {slotId} 변경을 남에게 알리지 못했다 — " +
                                 "내 화면만 갱신된다. 다른 사람은 다음 월드 진입 때 보게 된다");
                return false;
            }
            s_local.AnnounceSlotServerRpc(slotId);
            return true;
        }

        [ServerRpc]
        void AnnounceSlotServerRpc(int slotId, ServerRpcParams p = default)
        {
            if (slotId <= 0 || slotId > MaxSlotId)
            {
                Debug.LogWarning($"[BoothLiveSync] 클라이언트 {p.Receive.SenderClientId} 가 범위 밖 슬롯 {slotId} 를 보냈다 — 버린다");
                return;
            }

            ulong sender = p.Receive.SenderClientId;
            double now = NetworkManager.ServerTime.Time;
            if (s_lastAnnounce.TryGetValue(sender, out var last) && now - last < MinAnnounceInterval)
            {
                Debug.LogWarning($"[BoothLiveSync] 클라이언트 {sender} 의 알림이 너무 잦다 ({now - last:F2}s) — 버린다");
                return;
            }
            s_lastAnnounce[sender] = now;

            Debug.Log($"[BoothLiveSync] 슬롯 {slotId} 변경을 접속자 전원에게 중계한다 (보낸 쪽 {sender})");
            AnnounceSlotClientRpc(slotId, sender);
        }

        [ClientRpc]
        void AnnounceSlotClientRpc(int slotId, ulong senderClientId)
        {
            // 보낸 사람은 FE 브리지로 이미 조립했다. 다시 부르면 서명 비교에 걸려 Rebuild 는 안 되지만
            // 조회 한 번이 그냥 버려지므로 건너뛴다.
            if (NetworkManager != null && NetworkManager.LocalClientId == senderClientId) return;

            Debug.Log($"[BoothLiveSync] 슬롯 {slotId} 변경 수신 → 런타임 조립");
            WorldBoothPublishedBootstrap.RequestReload(slotId);
        }
    }
}
