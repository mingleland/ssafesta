using System;
using System.Text;
using Festa.Integration;
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
        /// 정식 접속 경로: world-sessions API 응답(endpoint + connectionToken)으로 접속한다.
        /// scheme이 "wss"면 TLS 클라이언트 파라미터를 설정한다 (LB의 ACM 인증서를 브라우저/OS 신뢰 저장소로 검증).
        /// Unity Client는 endpoint를 하드코딩하지 않는다 (Channel 확장 대비, handoff §3).
        /// </summary>
        public bool StartClient(WorldSessionDto session, ConnectionPayload payload)
        {
            if (session?.endpoint == null || string.IsNullOrEmpty(session.endpoint.host))
            {
                Debug.LogError("[ConnectionManager] world session endpoint가 비어있음");
                return false;
            }

            payload.connectionToken = session.connectionToken;

            bool secure = string.Equals(session.endpoint.scheme, "wss", StringComparison.OrdinalIgnoreCase);
            return StartClientInternal(session.endpoint.host, (ushort)session.endpoint.port, secure, payload);
        }

        /// <summary>개발용 직접 접속 (DevConnectionHud 수동 입력, 항상 평문 ws).</summary>
        public bool StartClient(string address, ushort port, ConnectionPayload payload)
            => StartClientInternal(address, port, secure: false, payload);

        bool StartClientInternal(string host, ushort port, bool secure, ConnectionPayload payload)
        {
            var nm = NetworkManager.Singleton;
            var transport = nm.GetComponent<UnityTransport>();

            transport.UseWebSockets = true;
            transport.UseEncryption = secure;
            if (secure)
                transport.SetClientSecrets(host); // 서버 인증서 CN 검증 대상 = 접속 도메인

            transport.SetConnectionData(host, port);
            nm.NetworkConfig.ConnectionData = Encoding.UTF8.GetBytes(JsonUtility.ToJson(payload));

            bool ok = nm.StartClient();
            Debug.Log($"[ConnectionManager] StartClient {(secure ? "wss" : "ws")}://{host}:{port} → {ok}");
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
                    userId = 0, nickname = "Host", avatarCode = "sk_01"
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
            int slot = AcquireSpawnSlot(clientId);
            response.Approved = true;
            response.CreatePlayerObject = true;
            response.Position = GetSpawnPosition(slot);
            response.Rotation = Quaternion.identity;
            Debug.Log($"[ConnectionManager] Approved client={clientId} slot={slot} " +
                      $"pos={response.Position} nickname={payload.nickname}");
        }

        static void Deny(NetworkManager.ConnectionApprovalResponse response, string reason)
        {
            response.Approved = false;
            response.Reason = reason;
            Debug.LogWarning($"[ConnectionManager] Denied: {reason}");
        }

        // ---------- 스폰 ----------
        //
        // 슬롯을 `clientId` 로 계산하면 안 된다. clientId 는 서버 프로세스가 사는 동안
        // 접속마다 증가하므로(1, 2, 3 …) **재접속할 때마다 스폰 자리가 옮겨간다.**
        // 그래서 비어 있는 가장 낮은 슬롯을 배정하고 끊길 때 반납한다 — 혼자 테스트하면
        // 항상 0번, 같은 자리다. 40명이 함께 있어도 슬롯은 겹치지 않는다.

        const int SpawnColumns = 8;
        const int SpawnRows = 5;
        // 아바타 지름이 4.4 unit(반지름 2.2) 이므로 간격이 그보다 커야 겹치지 않는다.
        // 이전 값 2.25 는 지름의 절반이어서 스폰 순간 서로 파묻혔다 — Player↔Player 충돌을
        // 꺼 둔 덕에 통과했을 뿐이다.
        const float SpawnSpacing = 5f;
        // 중심을 (-75, -235) 에서 (-85, -234) 로 1 m 옮겼다. 간격을 2.25 → 5 로 넓히자
        // 격자 동쪽 열이 `SSAFY-center` 구조물을 물었고, 일부 슬롯은 그 **위에** 접지해
        // y 13.7 로 잡혔다. 바닥 전체를 훑어 40 슬롯이 모두 바닥에 닿고 아무것도 물지 않는
        // 중심을 찾은 결과다 (조건 충족 후보 630곳 중 원래 자리에 가장 가까운 곳).
        static readonly Vector3 SpawnCenter = new Vector3(-85f, 0f, -234f);
        const float SpawnProbeHeight = 30f;   // 바닥 탐색 레이 시작 높이
        const float SpawnGroundOffset = 0.1f; // 바닥에 살짝 띄운다 — 첫 프레임 파묻힘 방지
        const float SpawnFallbackY = 0.5f;

        static readonly System.Collections.Generic.Dictionary<ulong, int> SpawnSlots = new();

        static int AcquireSpawnSlot(ulong clientId)
        {
            if (SpawnSlots.TryGetValue(clientId, out int existing)) return existing;

            var used = new System.Collections.Generic.HashSet<int>(SpawnSlots.Values);
            int slot = 0;
            while (used.Contains(slot)) slot++;
            SpawnSlots[clientId] = slot;
            return slot;
        }

        static Vector3 GetSpawnPosition(int slot)
        {
            // 슬롯 수를 넘으면 감싼다 — 정원(MaxPlayers)이 격자보다 커지는 경우의 안전장치.
            slot %= SpawnColumns * SpawnRows;
            int column = slot % SpawnColumns;
            int row = slot / SpawnColumns;
            float x = SpawnCenter.x + (column - (SpawnColumns - 1) * 0.5f) * SpawnSpacing;
            float z = SpawnCenter.z + (row - (SpawnRows - 1) * 0.5f) * SpawnSpacing;

            // 바닥 y 를 물리로 찾는다. 상수로 박으면 모델·임포트 설정이 바뀔 때 조용히 어긋난다 —
            // 실제로 메시 압축 도입에서 바닥이 0.0822 → 0.0855 로 움직였다.
            var probe = new Vector3(x, SpawnCenter.y + SpawnProbeHeight, z);
            if (Physics.Raycast(probe, Vector3.down, out var hit, SpawnProbeHeight * 2f))
                return new Vector3(x, hit.point.y + SpawnGroundOffset, z);

            Debug.LogWarning($"[ConnectionManager] 스폰 슬롯 {slot} 아래에 바닥이 없다 — 폴백 y 사용. " +
                             "월드 콜라이더가 로드됐는지 확인해라.");
            return new Vector3(x, SpawnFallbackY, z);
        }

        void OnConnectionEvent(NetworkManager nm, ConnectionEventData data)
        {
            if (data.EventType == ConnectionEvent.ClientDisconnected && nm.IsServer)
            {
                SessionDataStore.Remove(data.ClientId);
                SpawnSlots.Remove(data.ClientId); // 반납 — 다음 접속이 같은 자리를 다시 쓴다
                Debug.Log($"[ConnectionManager] Client {data.ClientId} disconnected, session cleaned");
            }
        }
    }
}
