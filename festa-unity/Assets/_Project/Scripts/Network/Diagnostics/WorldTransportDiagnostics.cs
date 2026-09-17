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
            TrackManagerChange();
            SampleServerLoad();
        }

        void TrackManagerChange()
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

        // ──────────────────────────────────────────────────────────────────────────
        // 서버 부하 계측 — 데디케이티드 서버가 스스로 stdout 에 남긴다 (docker logs 로 읽힌다).
        //
        // 왜 여기 있나 — 부하를 걸어도 "서버가 버텼는지" 를 판단할 수치가 로그에 하나도 없었다.
        // PerfHud 는 `#if UNITY_EDITOR || !UNITY_SERVER` 라 서버 빌드에서 컴파일 자체가 빠진다.
        // 접속·해제 줄만 남아서, 인프라가 로그 구간을 떠 줘도 볼 것이 없다.
        //
        // 클라이언트 비용은 0 이다 — WebGL 은 batchmode 로 뜨지 않으므로 아래 가드에서 즉시 빠진다.
        // 한 줄도 돌지 않지만 **코드는 모든 플랫폼에서 컴파일**되므로 CI 의 EditMode 가 오류를 잡는다
        // (`#if UNITY_SERVER` 로 감싸면 서버 빌드에서만 깨져 뒤늦게 발견된다).
        // ──────────────────────────────────────────────────────────────────────────

        /// <summary>한 줄을 남기는 주기(초). `-serverMetricsInterval` 로 바꿀 수 있다.</summary>
        float _metricsInterval = -1f;
        const int MaxFrameSamples = 8192;
        readonly float[] _frameMs = new float[MaxFrameSamples];
        int _frameCount;
        float _metricsWindow;

        object _metrics;
        System.Reflection.FieldInfo _sentField, _recvField;
        System.Reflection.PropertyInfo _counterValue;
        bool _metricsLookedUp;
        long _sentAccum, _recvAccum;

        void SampleServerLoad()
        {
            // 데디케이티드 서버(=batchmode)에서만 돈다. 브라우저 클라이언트는 여기서 끝난다.
            if (!Application.isBatchMode) return;
            var nm = _manager;
            if (nm == null || !nm.IsServer) return;

            if (_metricsInterval < 0f) _metricsInterval = ReadIntervalArg();

            float dt = Time.unscaledDeltaTime;
            if (_frameCount < MaxFrameSamples) _frameMs[_frameCount++] = dt * 1000f;
            AccumulateTransportBytes(nm);
            _metricsWindow += dt;
            if (_metricsWindow < _metricsInterval) return;

            int clients = nm.ConnectedClientsIds != null ? nm.ConnectedClientsIds.Count : 0;

            // 아무도 없으면 남기지 않는다 — 로그가 하루치 빈 줄로 덮이면 구간을 못 찾는다.
            // 덕분에 사람이 들어와 있는 동안만 줄이 생겨 부하 구간이 저절로 끊어진다.
            if (clients > 0) Debug.Log(BuildMetricsLine(nm, clients));

            _frameCount = 0;
            _metricsWindow = 0f;
            _sentAccum = 0;
            _recvAccum = 0;
        }

        string BuildMetricsLine(NetworkManager nm, int clients)
        {
            Array.Sort(_frameMs, 0, _frameCount);
            float p50 = Percentile(0.50f), p95 = Percentile(0.95f);
            float max = _frameCount > 0 ? _frameMs[_frameCount - 1] : 0f;

            float sentKbps = _sentAccum / _metricsWindow / 1024f;
            float recvKbps = _recvAccum / _metricsWindow / 1024f;
            int objects = nm.SpawnManager?.SpawnedObjectsList?.Count ?? -1;
            long heapMb = GC.GetTotalMemory(false) / 1048576;

            // 앞머리를 고정한다 — 인프라가 `docker logs -t | grep '[Festa/서버측정]'` 로 구간만 떠낼 수 있어야 한다.
            // utc 를 줄 안에 같이 넣는 이유는 복사해 옮겨도 시각이 살아남게 하려는 것이다.
            return $"[Festa/서버측정] utc={DateTime.UtcNow:yyyy-MM-ddTHH:mm:ssZ} clients={clients} " +
                   $"tick p50={p50:F1} p95={p95:F1} max={max:F1} ms | " +
                   $"net sent={sentKbps:F1} recv={recvKbps:F1} KB/s | " +
                   $"objects={objects} | heap={heapMb}MB | window={_metricsWindow:F1}s";
        }

        float Percentile(float q)
        {
            if (_frameCount == 0) return 0f;
            int i = Mathf.Clamp(Mathf.FloorToInt(_frameCount * q), 0, _frameCount - 1);
            return _frameMs[i];
        }

        /// <summary>
        /// NGO 의 transport 바이트 카운터를 누적한다. 카운터는 dispatch 마다 0 으로 리셋되므로
        /// 매 프레임 읽어 더하지 않으면 초당 값을 만들 수 없다 — <c>PerfHud</c> 와 같은 방식이다.
        /// 서버에서 읽으면 전 클라이언트 합계라 인원수에 따른 증가가 그대로 보인다.
        /// </summary>
        void AccumulateTransportBytes(NetworkManager nm)
        {
            if (!_metricsLookedUp)
            {
                _metricsLookedUp = true;
                const System.Reflection.BindingFlags BF =
                    System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.NonPublic |
                    System.Reflection.BindingFlags.Instance;
                _metrics = nm.GetType().GetProperty("NetworkMetrics", BF)?.GetValue(nm);
                if (_metrics == null) return;
                _sentField = _metrics.GetType().GetField("m_TransportBytesSent", BF);
                _recvField = _metrics.GetType().GetField("m_TransportBytesReceived", BF);
                _counterValue = _sentField?.GetValue(_metrics)?.GetType()
                    .GetProperty("Value", BF);
            }
            if (_counterValue == null || _sentField == null || _recvField == null) return;

            _sentAccum += (long)_counterValue.GetValue(_sentField.GetValue(_metrics));
            _recvAccum += (long)_counterValue.GetValue(_recvField.GetValue(_metrics));
        }

        static float ReadIntervalArg()
        {
            var args = Environment.GetCommandLineArgs();
            for (int i = 0; i < args.Length - 1; i++)
                if (args[i] == "-serverMetricsInterval" && float.TryParse(args[i + 1], out var v) && v >= 1f)
                    return v;
            return 10f;
        }

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
