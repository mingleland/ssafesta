using System;
using System.Collections.Generic;
using System.Globalization;
using Unity.Netcode;
using Unity.Netcode.Transports.UTP;
using UnityEngine;

namespace Festa.Network
{
    /// <summary>
    /// World WebSocket이 Close reason 없이 끊겼을 때 서버·프록시 로그와 맞출 최소 transport 증거를 남긴다.
    ///
    /// <para><b>데디케이티드 서버 부하 계측도 여기서 한다</b> (GitLab #229). 40명 실측을 실제 사용자로 돌리는데
    /// 서버 빌드에는 성능 계측이 하나도 없었다 — <see cref="Festa.Diagnostics.PerfHud"/> 가
    /// <c>#if UNITY_EDITOR || !UNITY_SERVER</c> 라 서버 빌드에서 컴파일 자체가 빠진다. 로그를 떠도 접속·해제 줄만
    /// 나와 "서버가 버텼는가" 를 판단할 수치가 없었다. 이 컴포넌트는 이미 서버·클라이언트 양쪽에서 살아 있고
    /// transport 를 쥐고 있어 계측을 얹을 자리로 맞다.</para>
    ///
    /// <para><b>클라이언트 비용은 0 이다.</b> <see cref="Application.isBatchMode"/> 가드에서 즉시 빠지는데
    /// WebGL 은 batchmode 로 뜨지 않는다. 다만 <c>#if UNITY_SERVER</c> 로 감싸지 <b>않았다</b> — 그러면
    /// 서버 빌드에서만 깨져 뒤늦게 발견된다. 모든 플랫폼에서 컴파일되게 두어 에디터가 오류를 먼저 잡게 한다.</para>
    /// </summary>
    public sealed class WorldTransportDiagnostics : MonoBehaviour
    {
        const string ObjectName = "WorldTransportDiagnostics";

        // ── 서버 부하 계측 (GitLab #229) ──
        /// <summary>인프라가 <c>grep</c> 으로 구간을 떠 가는 표식. 바꾸면 #229 의 추출 명령이 깨진다.</summary>
        const string MetricsTag = "[Festa/서버측정]";
        const string IntervalArg = "-serverMetricsInterval";
        const float DefaultIntervalSeconds = 10f;
        const float MinIntervalSeconds = 1f;
        const float ClientDiagnosticsIntervalSeconds = 10f;
        const float StarvedInterpolationGapMs = 100f;

        /// <summary>
        /// 공백의 이 비율 이상을 메인 스레드 정지가 설명하면 네트워크가 아니라 프레임 기인으로 센다.
        ///
        /// <para><b>왜 나눠야 하는가.</b> transport 의 Data 이벤트는 메인 스레드 폴링에서 꺼내진다.
        /// 그래서 화면이 600ms 멈추면 그 동안 큐에 쌓인 패킷이 한 프레임에 쏟아지고, 첫 패킷 하나가
        /// 600ms 공백으로 찍힌다 — 회선은 멀쩡한데 네트워크 지연처럼 보인다. 실제로 이 값 하나를 두고
        /// 인프라와 게임이 서로 다른 결론을 냈다 (2026-09-23). 공백이 벌어진 그 순간 메인 스레드가
        /// 얼마나 쉬었는지 함께 재면 두 원인이 로그에서 갈린다.</para>
        /// </summary>
        const float FrameAttributionRatio = 0.7f;

        /// <summary>한 창에 담을 프레임 표본 상한. 넘치면 버린다 — 측정 때문에 서버가 메모리를 먹으면 본말전도다.</summary>
        const int MaxTickSamples = 8192;

        bool _metricsEnabled;
        bool _announced;
        float _interval = DefaultIntervalSeconds;
        float _windowStart = -1f;
        readonly List<float> _tickMs = new();

        // NGO 의 바이트 카운터는 **매 프레임 dispatch 뒤 리셋된다** — 한 번 읽어서는 초당 값을 못 만든다.
        // 내부 API 라 리플렉션으로 잡는다 (PerfHud 가 쓰는 경로와 같다).
        object _metrics;
        System.Reflection.FieldInfo _sentField, _recvField;
        System.Reflection.PropertyInfo _counterValue;
        /// <summary>리플렉션 조회를 한 번만 한다 — 실패해도 매 프레임 다시 뒤지지 않는다.</summary>
        bool _metricsProbed;
        /// <summary>송신 카운터를 실제로 잡았는가. 못 잡았으면 0 이 아니라 n/a 로 찍는다 (T-24).</summary>
        bool _byteCountersAvailable;
        long _sentAccum, _recvAccum;
        /// <summary>수신량은 리플렉션에 기대지 않고 transport payload 로 직접 센다 — 항상 맞는 값이다.</summary>
        long _recvBytesObserved;

        NetworkManager _manager;
        NetworkTransport _transport;
        UnityTransport _utp;
        float _connectedAt = -1f;
        float _lastDataAt = -1f;
        float _clientWindowStart = -1f;
        float _clientGapTotalMs;
        float _clientGapMaxMs;
        int _clientGapCount;
        int _clientStarvedGapCount;
        int _clientFrameStalledGapCount;
        int _clientNetworkGapCount;
        float _clientWorstFrameMs;
        /// <summary>마지막으로 Update 가 돈 시각. transport 이벤트 시점의 메인 스레드 정지 길이를 잰다.</summary>
        float _lastUpdateAt = -1f;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        static void AutoRegister()
        {
            if (GameObject.Find(ObjectName) != null) return;
            var go = new GameObject(ObjectName);
            go.AddComponent<WorldTransportDiagnostics>();
            DontDestroyOnLoad(go);
        }

        void Awake()
        {
            // WebGL 클라이언트는 batchmode 가 아니다 — 여기서 걸러지면 아래 계측은 한 줄도 돌지 않는다.
            _metricsEnabled = Application.isBatchMode;
            if (_metricsEnabled) _interval = ReadIntervalArg();
        }

        void Update()
        {
            // 프레임이 얼마나 길었는지 먼저 갱신한다 — 아래 계측과 transport 이벤트가 같이 쓴다.
            float now = Time.realtimeSinceStartup;
            if (_lastUpdateAt >= 0f)
            {
                float frameMs = (now - _lastUpdateAt) * 1000f;
                if (frameMs > _clientWorstFrameMs) _clientWorstFrameMs = frameMs;
            }
            _lastUpdateAt = now;

            TrackManagerChange();
            if (_metricsEnabled) SampleMetrics();
            else SampleClientTransportHealth();
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

        void Unsubscribe()
        {
            if (_transport != null) _transport.OnTransportEvent -= OnTransportEvent;
            _manager = null;
            _transport = null;
            _utp = null;
            _connectedAt = -1f;
            _lastDataAt = -1f;
            _clientWindowStart = -1f;
            ResetClientWindow();
        }

        void OnTransportEvent(NetworkEvent eventType, ulong clientId, ArraySegment<byte> payload, float receiveTime)
        {
            float now = Time.realtimeSinceStartup;
            if (eventType == NetworkEvent.Data)
            {
                // 수신량은 여기서 직접 센다 — 서버·클라이언트 모두 해당하고 리플렉션이 필요 없다.
                _recvBytesObserved += payload.Count;

                if (_manager != null && _manager.IsClient && !_manager.IsServer && _lastDataAt >= 0f)
                {
                    float gapMs = (now - _lastDataAt) * 1000f;
                    _clientGapTotalMs += gapMs;
                    _clientGapMaxMs = Mathf.Max(_clientGapMaxMs, gapMs);
                    _clientGapCount++;
                    if (gapMs > StarvedInterpolationGapMs)
                    {
                        _clientStarvedGapCount++;
                        // 이 공백이 벌어지는 동안 메인 스레드가 쉰 시간. 이벤트는 프레임 앞단에서 꺼내지므로
                        // 마지막 Update 이후 경과가 곧 그 프레임의 정지 길이다.
                        float stallMs = _lastUpdateAt >= 0f ? (now - _lastUpdateAt) * 1000f : 0f;
                        if (stallMs >= gapMs * FrameAttributionRatio) _clientFrameStalledGapCount++;
                        else _clientNetworkGapCount++;
                    }
                }
                _lastDataAt = now;
                return;
            }

            if (eventType == NetworkEvent.Connect)
            {
                _connectedAt = now;
                _lastDataAt = now;
                _clientWindowStart = now;
                ResetClientWindow();
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
                  $"connect={_utp.ConnectTimeoutMS}ms attempts={_utp.MaxConnectAttempts} " +
                  $"maxSendQueue={_utp.MaxSendQueueSize}B";

            return $"role={(_manager.IsServer ? "server" : "client")} clientId={clientId} " +
                   $"endpoint={endpoint} rtt={rtt}ms {config}";
        }

        /// <summary>
        /// 원격 이동 끊김이 렌더 프레임이 아니라 transport 수신 공백에서 왔는지 구분한다.
        /// Data 이벤트는 transform 전용이 아니므로 이 값만으로 원인을 단정하지 않되, 100ms 초과
        /// 공백이 반복되면 기존 0.1초 보간 버퍼가 고갈될 조건이 실제로 있었다는 직접 증거가 된다.
        /// </summary>
        void SampleClientTransportHealth()
        {
            var nm = _manager;
            if (nm == null || !nm.IsClient || nm.IsServer || !nm.IsConnectedClient) return;

            float now = Time.realtimeSinceStartup;
            if (_clientWindowStart < 0f) { _clientWindowStart = now; return; }
            float window = now - _clientWindowStart;
            if (window < ClientDiagnosticsIntervalSeconds) return;

            long rtt = -1;
            if (_utp != null)
            {
                try { rtt = (long)_utp.GetCurrentRtt(NetworkManager.ServerClientId); }
                catch { /* 연결 전환 중에는 서버 endpoint가 먼저 사라질 수 있다. */ }
            }

            float averageGapMs = _clientGapCount > 0 ? _clientGapTotalMs / _clientGapCount : -1f;
            float idleMs = _lastDataAt >= 0f ? (now - _lastDataAt) * 1000f : -1f;
            Debug.Log(
                $"[Festa/원격이동] rtt={rtt}ms dataGap avg={averageGapMs:F1} max={_clientGapMaxMs:F1}ms " +
                $">100ms={_clientStarvedGapCount}/{_clientGapCount} " +
                $"(네트워크 {_clientNetworkGapCount} / 프레임 {_clientFrameStalledGapCount}) " +
                $"최악프레임={_clientWorstFrameMs:F1}ms idle={idleMs:F1}ms window={window:F1}s " +
                "interpolation=250ms");

            _clientWindowStart = now;
            ResetClientWindow();
        }

        void ResetClientWindow()
        {
            _clientGapTotalMs = 0f;
            _clientGapMaxMs = 0f;
            _clientGapCount = 0;
            _clientStarvedGapCount = 0;
            _clientFrameStalledGapCount = 0;
            _clientNetworkGapCount = 0;
            _clientWorstFrameMs = 0f;
        }

        // ────────────────────────────── 서버 부하 계측 (GitLab #229) ──────────────────────────────

        /// <summary>
        /// 프레임마다 표본을 모으고, 창이 차면 한 줄 남긴다.
        ///
        /// <para><b>접속자가 0 이면 남기지 않는다.</b> 하루치 빈 줄로 덮이면 인프라가 부하 구간을 못 찾는다.
        /// 사람이 들어와 있는 동안만 줄이 생기므로 구간이 저절로 끊어진다.</para>
        /// </summary>
        void SampleMetrics()
        {
            var nm = _manager;
            if (nm == null || !nm.IsServer) return;

            if (!_announced)
            {
                _announced = true;
                // 켜졌다는 사실은 접속자 0 이어도 한 번 남긴다 — 로그에 아무것도 없을 때
                // "계측이 꺼진 것" 과 "아무도 안 들어온 것" 을 구분할 수 없으면 원인을 못 찾는다.
                Debug.Log($"{MetricsTag} enabled interval={_interval:F0}s — 접속자가 1명 이상인 동안에만 기록한다.");
            }

            // 틱이 밀리기 시작하는 인원수를 찾는 유일한 지표다.
            if (_tickMs.Count < MaxTickSamples) _tickMs.Add(Time.unscaledDeltaTime * 1000f);
            SampleNetworkBytes(nm);

            float now = Time.realtimeSinceStartup;
            if (_windowStart < 0f) { _windowStart = now; return; }

            float window = now - _windowStart;
            if (window < _interval) return;
            _windowStart = now;

            int clients = nm.ConnectedClientsIds != null ? nm.ConnectedClientsIds.Count : 0;
            if (clients <= 0 || _tickMs.Count == 0) { ResetWindow(); return; }

            _tickMs.Sort();
            float p50 = Percentile(0.50f);
            float p95 = Percentile(0.95f);
            float max = _tickMs[_tickMs.Count - 1];
            int objects = nm.SpawnManager != null && nm.SpawnManager.SpawnedObjects != null
                ? nm.SpawnManager.SpawnedObjects.Count
                : -1;

            // 송신량을 못 재면 0 이 아니라 n/a 로 찍는다. 0.0 KB/s 는 "서버가 보낼 게 없다" 로 읽혀
            // 실제로 대역폭을 원인에서 배제하는 근거로 쓰였다 (2026-09-23).
            string sent = _byteCountersAvailable
                ? (_sentAccum / 1024f / window).ToString("F1", CultureInfo.InvariantCulture)
                : "n/a";
            Debug.Log(
                $"{MetricsTag} utc={DateTime.UtcNow:yyyy-MM-ddTHH:mm:ss}Z clients={clients} " +
                $"tick p50={p50:F1} p95={p95:F1} max={max:F1} ms | " +
                $"net sent={sent} recv={_recvBytesObserved / 1024f / window:F1} KB/s | " +
                $"objects={objects} | heap={GC.GetTotalMemory(false) / 1048576}MB | window={window:F1}s");

            ResetWindow();
        }

        void ResetWindow()
        {
            _tickMs.Clear();
            _sentAccum = 0;
            _recvAccum = 0;
            _recvBytesObserved = 0;
        }

        /// <summary>정렬된 표본에서 백분위. 표본이 적어도 범위를 벗어나지 않게 자른다.</summary>
        float Percentile(float q)
        {
            int i = Mathf.Clamp(Mathf.RoundToInt((_tickMs.Count - 1) * q), 0, _tickMs.Count - 1);
            return _tickMs[i];
        }

        /// <summary>
        /// NGO 의 transport 바이트 카운터를 프레임마다 누적한다. 카운터는 dispatch 뒤 리셋되므로
        /// 한 번 읽어서는 초당 값을 만들 수 없다. 내부 API 라 리플렉션으로 잡는다.
        ///
        /// <para>못 잡으면 <b>조용히 0 을 쓰지 않는다</b> — 0 KB/s 가 찍히면 "대역폭 여유" 로 오독된다.
        /// 한 번만 경고하고 이후에는 누적을 건너뛴다 (T-24).</para>
        /// </summary>
        void SampleNetworkBytes(NetworkManager nm)
        {
            if (_counterValue == null)
            {
                if (_metricsProbed) return;
                _metricsProbed = true;
                const System.Reflection.BindingFlags BF =
                    System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.NonPublic |
                    System.Reflection.BindingFlags.Instance;
                _metrics = nm.GetType().GetProperty("NetworkMetrics", BF)?.GetValue(nm);
                _sentField = _metrics?.GetType().GetField("m_TransportBytesSent", BF);
                _recvField = _metrics?.GetType().GetField("m_TransportBytesReceived", BF);
                _counterValue = _sentField?.GetValue(_metrics)?.GetType()
                    .GetProperty("Value", BF);
                if (_counterValue == null || _recvField == null)
                {
                    Debug.LogWarning($"{MetricsTag} NGO 송신 바이트 카운터를 찾지 못했다 — " +
                                     "net sent 는 n/a 로 남는다 (recv 는 transport payload 로 직접 센다). " +
                                     "Multiplayer Tools 패키지가 빠졌거나 NGO 판올림으로 내부 필드명이 바뀐 것이다.");
                    return;
                }
                _byteCountersAvailable = true;
            }
            if (_counterValue == null) return;

            _sentAccum += (long)_counterValue.GetValue(_sentField.GetValue(_metrics));
            _recvAccum += (long)_counterValue.GetValue(_recvField.GetValue(_metrics));
        }

        /// <summary>실행 인자 <c>-serverMetricsInterval &lt;초&gt;</c>. 없거나 못 읽으면 기본값.</summary>
        static float ReadIntervalArg()
        {
            var args = Environment.GetCommandLineArgs();
            for (int i = 0; i < args.Length - 1; i++)
            {
                if (!string.Equals(args[i], IntervalArg, StringComparison.OrdinalIgnoreCase)) continue;
                if (float.TryParse(args[i + 1], NumberStyles.Float, CultureInfo.InvariantCulture, out float v)
                    && v >= MinIntervalSeconds) return v;
                Debug.LogWarning($"{MetricsTag} {IntervalArg} 값을 읽지 못했다 ('{args[i + 1]}') — " +
                                 $"기본 {DefaultIntervalSeconds:F0}초를 쓴다.");
                return DefaultIntervalSeconds;
            }
            return DefaultIntervalSeconds;
        }
    }
}
