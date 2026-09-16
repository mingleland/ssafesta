using System;
using Unity.Netcode;
using Unity.Netcode.Transports.UTP;
using UnityEngine;

namespace Festa.Network
{
    /// <summary>
    /// World WebSocket이 Close reason 없이 끊겼을 때 서버·프록시 로그와 맞출 최소 transport 증거를 남긴다.
    /// </summary>
    public sealed class WorldTransportDiagnostics : MonoBehaviour
    {
        const string ObjectName = "WorldTransportDiagnostics";

        NetworkManager _manager;
        NetworkTransport _transport;
        UnityTransport _utp;
        float _connectedAt = -1f;
        float _lastDataAt = -1f;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        static void AutoRegister()
        {
            if (GameObject.Find(ObjectName) != null) return;
            var go = new GameObject(ObjectName);
            go.AddComponent<WorldTransportDiagnostics>();
            DontDestroyOnLoad(go);
        }

        void Update()
        {
            var manager = NetworkManager.Singleton;
            if (manager == _manager) return;

            Unsubscribe();
            _manager = manager;
            if (_manager == null) return;

            _transport = _manager.NetworkConfig?.NetworkTransport;
            _utp = _transport as UnityTransport;
            if (_transport != null) _transport.OnTransportEvent += OnTransportEvent;
        }

        void OnDestroy() => Unsubscribe();

        void Unsubscribe()
        {
            if (_transport != null) _transport.OnTransportEvent -= OnTransportEvent;
            _manager = null;
            _transport = null;
            _utp = null;
            _connectedAt = -1f;
            _lastDataAt = -1f;
        }

        void OnTransportEvent(NetworkEvent eventType, ulong clientId, ArraySegment<byte> payload, float receiveTime)
        {
            float now = Time.realtimeSinceStartup;
            if (eventType == NetworkEvent.Data)
            {
                _lastDataAt = now;
                return;
            }

            if (eventType == NetworkEvent.Connect)
            {
                _connectedAt = now;
                _lastDataAt = now;
                Debug.Log($"[WorldTransport] CONNECT {Context(clientId)}");
                return;
            }

            if (eventType != NetworkEvent.Disconnect) return;

            string connectedFor = _connectedAt >= 0f ? $"{now - _connectedAt:F1}s" : "unknown";
            string idleFor = _lastDataAt >= 0f ? $"{now - _lastDataAt:F1}s" : "unknown";
            string ngoReason = _manager != null ? _manager.DisconnectReason ?? "" : "";
            Debug.LogWarning(
                $"[WorldTransport] DISCONNECT utc={DateTime.UtcNow:O} connectedFor={connectedFor} " +
                $"lastDataAgo={idleFor} ngoReason='{ngoReason}' {Context(clientId)}");
        }

        string Context(ulong clientId)
        {
            if (_manager == null) return "manager=null";

            string endpoint = "unknown";
            long rtt = -1;
            if (_utp != null)
            {
                try { endpoint = _utp.GetEndpoint(clientId).ToString(); }
                catch { /* 접속 종료 직후 endpoint가 먼저 제거될 수 있다. */ }
                try { rtt = (long)_utp.GetCurrentRtt(clientId); }
                catch { /* 같은 이유로 RTT 조회가 실패할 수 있다. */ }
            }

            string config = _utp == null
                ? "transport=non-UTP"
                : $"heartbeat={_utp.HeartbeatTimeoutMS}ms disconnect={_utp.DisconnectTimeoutMS}ms " +
                  $"connect={_utp.ConnectTimeoutMS}ms attempts={_utp.MaxConnectAttempts}";

            return $"role={(_manager.IsServer ? "server" : "client")} clientId={clientId} " +
                   $"endpoint={endpoint} rtt={rtt}ms {config}";
        }
    }
}
