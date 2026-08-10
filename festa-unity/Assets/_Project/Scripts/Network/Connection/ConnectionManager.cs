using System.Text;
using Unity.Netcode;
using Unity.Netcode.Transports.UTP;
using UnityEngine;

namespace Festa.Network
{
    /// <summary>
    /// 접속 시작/승인/해제를 담당한다.
    /// BossRoom ConnectionManager(상태 머신 + Approval) 패턴의 단순화 버전 —
    /// 8주 범위에서는 명시적 상태 클래스 대신 NGO 콜백 직접 처리로 충분하다.
    /// 재접속 상태 머신은 정책 확정(doc 12 §9) 후 확장한다.
    /// </summary>
    [RequireComponent(typeof(NetworkManager))]
    public class ConnectionManager : MonoBehaviour
    {
        public const string ReasonInvalidToken = "INVALID_TOKEN";
        public const string ReasonServerFull = "SERVER_FULL";

        NetworkBootstrap _bootstrap;

        void Start()
        {
            _bootstrap = GetComponent<NetworkBootstrap>();
            var nm = NetworkManager.Singleton;
            nm.ConnectionApprovalCallback += ApprovalCheck;
            nm.OnConnectionEvent += OnConnectionEvent;
        }

        void OnDestroy()
        {
            var nm = NetworkManager.Singleton;
            if (nm == null) return;
            nm.ConnectionApprovalCallback -= ApprovalCheck;
            nm.OnConnectionEvent -= OnConnectionEvent;
        }

        // ---------- Client ----------

        /// <summary>
        /// 서버에 접속한다. endpoint/token은 향후 Spring world-sessions API 응답에서 온다.
        /// Unity Client는 endpoint를 하드코딩하지 않는다 (Channel 확장 대비).
        /// </summary>
        public bool StartClient(string address, ushort port, ConnectionPayload payload)
        {
            var nm = NetworkManager.Singleton;
            var transport = nm.GetComponent<UnityTransport>();
            transport.UseWebSockets = true;
            transport.SetConnectionData(address, port);

            nm.NetworkConfig.ConnectionData = Encoding.UTF8.GetBytes(JsonUtility.ToJson(payload));

            bool ok = nm.StartClient();
            Debug.Log($"[ConnectionManager] StartClient {address}:{port} → {ok}");
            return ok;
        }

        public void Shutdown()
        {
            NetworkManager.Singleton.Shutdown();
            SessionDataStore.Clear();
        }

        // ---------- Server ----------

        void ApprovalCheck(NetworkManager.ConnectionApprovalRequest request,
                           NetworkManager.ConnectionApprovalResponse response)
        {
            // Host 자기 자신은 통과
            if (request.ClientNetworkId == NetworkManager.Singleton.LocalClientId)
            {
                Approve(response, request.ClientNetworkId, new ConnectionPayload
                {
                    userId = 0, nickname = "Host", avatarCode = "default"
                });
                return;
            }

            if (NetworkManager.Singleton.ConnectedClientsIds.Count >= _bootstrap.MaxPlayers)
            {
                Deny(response, ReasonServerFull);
                return;
            }

            ConnectionPayload payload = null;
            try
            {
                payload = JsonUtility.FromJson<ConnectionPayload>(
                    Encoding.UTF8.GetString(request.Payload));
            }
            catch { /* malformed payload → deny below */ }

            if (payload == null || !ValidateToken(payload))
            {
                Deny(response, ReasonInvalidToken);
                return;
            }

            Approve(response, request.ClientNetworkId, payload);
        }

        /// <summary>
        /// POC: 비어있지 않으면 통과.
        /// TODO(P0 후속): Spring 내부 API 또는 서명 검증으로 교체 (doc 12 §7).
        /// </summary>
        static bool ValidateToken(ConnectionPayload payload) =>
            !string.IsNullOrEmpty(payload.connectionToken);

        static void Approve(NetworkManager.ConnectionApprovalResponse response,
                            ulong clientId, ConnectionPayload payload)
        {
            SessionDataStore.Set(clientId, payload);
            response.Approved = true;
            response.CreatePlayerObject = true;
            response.Position = GetSpawnPosition(clientId);
            response.Rotation = Quaternion.identity;
            Debug.Log($"[ConnectionManager] Approved client={clientId} nickname={payload.nickname}");
        }

        static void Deny(NetworkManager.ConnectionApprovalResponse response, string reason)
        {
            response.Approved = false;
            response.Reason = reason;
            Debug.LogWarning($"[ConnectionManager] Denied: {reason}");
        }

        static Vector3 GetSpawnPosition(ulong clientId)
        {
            // 스폰 겹침 방지용 간단 분산. 월드 스폰 존 확정 시 교체.
            // Y=1: 캡슐 피벗이 중심이므로 바닥(Y=0) 위에 서려면 1m 올려야 한다.
            float angle = clientId * 0.618034f * Mathf.PI * 2f;
            return new Vector3(Mathf.Cos(angle) * 2f, 1f, Mathf.Sin(angle) * 2f);
        }

        void OnConnectionEvent(NetworkManager nm, ConnectionEventData data)
        {
            if (data.EventType == ConnectionEvent.ClientDisconnected && nm.IsServer)
            {
                SessionDataStore.Remove(data.ClientId);
                Debug.Log($"[ConnectionManager] Client {data.ClientId} disconnected, session cleaned");
            }
        }
    }
}
