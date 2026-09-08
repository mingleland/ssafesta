using System.Text;
using Unity.Profiling;
using UnityEngine;
using Unity.Netcode;

namespace Festa.Diagnostics
{
    /// <summary>
    /// 인빌드 성능 계측 오버레이 (S15P21A604-235 / GitLab #89).
    ///
    /// **추측 대신 실측을 위한 도구다.** 에디터 렉을 조사할 때 "렌더가 무겁다"고 추론했지만
    /// 실제로는 렌더 2.9 ms / 프레임 99.5 ms 였고 범인은 메모리였다. 30~40인 부하에서도
    /// 같은 실수를 하지 않으려면 빌드 안에서 프레임·메모리·GC·드로우콜을 함께 봐야 한다.
    ///
    /// 네트워크 트래픽은 이 HUD 가 재구현하지 않는다 — `com.unity.multiplayer.tools` 의
    /// RuntimeNetStatsMonitor(RNSM)가 공식 계측이고 더 정확하다. 이 HUD 는 그것이 다루지
    /// 않는 클라이언트 측 비용(프레임·힙·GC·드로우콜)과 접속 인원만 표시한다.
    ///
    /// 사용: 아무 씬 오브젝트에 붙이면 된다. F3 로 토글, F4 로 리셋.
    /// 로컬 연출 전용 — NetworkObject 없음, 서버로 나가는 상태 없음.
    /// </summary>
    public class PerfHud : MonoBehaviour
    {
        // ── 진단 단축키 배치표 (2026-09-08 정리) ──────────────────────────────
        // 키가 겹치면 **한 번 누를 때 두 가지가 동시에 바뀌어 측정이 조용히 오염된다.**
        // 실제로 F9 가 RenderCostProbe 스윕과 PerfHud 아바타 LOD 토글에 동시에 걸려 있어서,
        // 스윕을 돌릴 때마다 아바타 LOD 가 뒤집히고 있었다(09-07 측정 변동의 원인 중 하나).
        // 새 진단 키를 추가할 때 반드시 이 표를 보고 빈 자리를 쓴다.
        //
        //   F1  PerfHud        아바타 거리 LOD 토글
        //   F2  FixedPoseBenchmark  프레임 간격 히스토그램
        //   F3  PerfHud        표시 토글
        //   F4  PerfHud        통계 리셋
        //   F5  FixedPoseBenchmark  고정 포즈 A/B 실행
        //   F6  AvatarStressSpawner 아바타 추가
        //   F7  AvatarStressSpawner 아바타 제거
        //   F8  AvatarStressSpawner 전부 제거
        //   F9  RenderCostProbe 결정 스윕
        //   F10 PerfHud + AvatarMeshMerge  스킨메시 병합 A/B (둘이 같이 듣는 것은 의도)
        //   F11 RenderCostProbe 조명 A/B
        //   F12 FixedPoseBenchmark  사용 가능 통계 열거
        //   `   HitchLogger    끊김 로그 토글
        const KeyCode _toggleKey = KeyCode.LeftBracket;      // [  진단 키는 구두점만 쓴다 — DiagnosticKeys 참조
        const KeyCode _resetKey = KeyCode.RightBracket;      // ]
        // 아바타 거리 LOD 를 빌드 안에서 껐다 켜기 위한 키. 빌드에서 A/B 를 하려면
        // 한 빌드 안에서 조건을 바꿀 수 있어야 한다 — 빌드를 두 번 떠서 비교하면
        // 빌드 간 차이가 섞여 조건 통제가 무너진다 (T-211).
        const KeyCode _avatarLodKey = KeyCode.Semicolon;     // ;  F9→F1 로 옮겼더니 F1 이 브라우저 도움말이라 또 옮겼다
        // 스킨메시 병합 A/B 키 (S15P21A604-258). 예전 F10 셰이더 판별 실험은 역할이 끝나
        // 제거됐으므로 그 자리를 쓴다.
        const KeyCode _meshMergeKey = KeyCode.Quote;         // '  F10 은 AvatarMeshMerge 와 중복이었다
        [SerializeField] bool _visibleOnStart = true;
        [Tooltip("프레임 통계를 집계하는 창 길이(초). 짧으면 튀고 길면 둔해진다.")]
        [SerializeField] float _window = 1.0f;

        bool _visible;

        // ── 프레임 집계 ──
        float _windowElapsed;
        int _windowFrames;
        float _windowWorstMs;          // 창 안에서 가장 느린 프레임 = 체감 끊김의 정체
        float _fps, _avgMs, _worstMs;
        float _sessionWorstMs;         // 리셋 이후 최악 프레임 (스파이크 흔적)

        // 백분위용 표본. **평균과 최악만으로는 "가끔 튄다" 를 말할 수 없다** — 평균은
        // 스파이크를 묻고 최악은 한 번의 이상치에 끌려간다. p95·p99 가 있어야
        // "100번에 5번은 이만큼 걸린다" 를 수치로 합의할 수 있다.
        // 창 하나(기본 1초)의 프레임 수는 많아야 수백이라 고정 배열로 충분하다.
        readonly float[] _samples = new float[512];
        int _sampleCount;
        float _p95Ms, _p99Ms;

        // ── GC ──
        int _lastGcCount;
        int _gcPerWindow;
        long _heapBytes;

        // ── 네트워크 트래픽 ──
        // NGO 의 TotalBytesSent/Received 카운터는 **매 프레임 dispatch 후 리셋**된다.
        // 따라서 한 번 읽어서는 초당 트래픽을 알 수 없다 — 매 프레임 누적해야 한다.
        // 내부 API(NetworkManager.NetworkMetrics)라 리플렉션으로 잡는다.
        object _metrics;
        System.Reflection.FieldInfo _sentField, _recvField;
        System.Reflection.PropertyInfo _counterValue;
        long _sentAccum, _recvAccum;
        float _netWindow;
        float _sentPerSec, _recvPerSec;

        // ── ProfilerRecorder — 개발 빌드/에디터에서 유효 ──
        ProfilerRecorder _drawCalls, _setPass, _batches, _tris, _sysMemory;
        // GPU 시간은 플랫폼·그래픽 API 에 따라 안 잡힌다. **없는데 있는 척하면 안 된다** —
        // 예전에 "최악 프레임" 을 GPU 시간으로 읽고 최적화 근거로 삼은 적이 있다 (T-133).
        // 유효하지 않으면 화면에 "GPU 계측 없음" 이라고 분명히 적는다.
        ProfilerRecorder _gpuTime;

        // 화면 문자열은 **영문만 쓴다.** IMGUI 기본 폰트에 한글 글리프가 없어서 한글 라벨은
        // 공백으로 렌더된다. 라벨이 안 보이면 값만 남고, 값만 보고 다른 지표로 오독하는 일이
        // 실제로 있었다 (T-133 — "최악 프레임" 을 GPU 시간으로 읽었다).
        // 주석·문서는 한국어를 유지하고 화면에 그리는 문자열만 영문으로 둔다.
        const string FmtNet = "tx {0,7:F1} KB/s   rx {1,7:F1} KB/s";
        static readonly StringBuilder Sb = new StringBuilder(768);

        // 표시 문자열은 **창이 갱신될 때만** 만든다. 예전에는 OnGUI 가 매 프레임
        // AppendFormat 십여 번 + ToString() 을 돌려서, 계측 도구가 스스로 GC 쓰레기를
        // 만들고 그 GC 를 자기가 표시했다. 계측 대상을 계측기가 오염시키면 안 된다.
        string _cachedText = "";
        bool _statsDirty = true;

        // 측정 조건 각인 — 스크린샷 한 장으로 어느 조건의 수치인지 재구성할 수 있어야 한다.
        // 빌드·해상도·품질이 다른 표본을 섞으면 A/B 가 무의미해진다 (T-211, T-133).
        string _conditionLine = "";
        int _lastW, _lastH;
        // IMGUI 전용 자원. OnGUI 와 함께 서버 빌드에서 빠진다 (S15P21A604-314).
#if UNITY_EDITOR || !UNITY_SERVER
        static Texture2D _bg;
        static GUIStyle _style;
#endif

        /// <summary>
        /// 개발 도구는 **개발 빌드·에디터에서만** 살아 있어야 한다.
        /// 이 컴포넌트들은 main 씬에 붙어 있어 Linux 서버 빌드와 WebGL 릴리즈 빌드에도
        /// 함께 실린다 — 릴리즈에서 실사용자가 F6 으로 아바타 40기를 소환할 수 있으면 안 되고,
        /// 서버가 IMGUI 오버레이를 들고 있을 이유도 없다.
        /// </summary>
        public static bool ToolsEnabled =>
            Application.isEditor || Debug.isDebugBuild;

        void OnEnable()
        {

            // 키 충돌은 조용히 넘어가면 다음 사람이 같은 함정을 밟는다 — 여기서 신고하고 DiagnosticKeys 가 에러로 드러낸다.
            DiagnosticKeys.Claim(nameof(PerfHud), _toggleKey);
            DiagnosticKeys.Claim(nameof(PerfHud), _resetKey);
            DiagnosticKeys.Claim(nameof(PerfHud), _avatarLodKey);
            DiagnosticKeys.Claim(nameof(PerfHud), _meshMergeKey);
            if (!ToolsEnabled) { enabled = false; return; }
            _visible = _visibleOnStart;
            _lastGcCount = System.GC.CollectionCount(0);
            _drawCalls = ProfilerRecorder.StartNew(ProfilerCategory.Render, "Draw Calls Count");
            _setPass = ProfilerRecorder.StartNew(ProfilerCategory.Render, "SetPass Calls Count");
            _batches = ProfilerRecorder.StartNew(ProfilerCategory.Render, "Batches Count");
            _tris = ProfilerRecorder.StartNew(ProfilerCategory.Render, "Triangles Count");
            _sysMemory = ProfilerRecorder.StartNew(ProfilerCategory.Memory, "System Used Memory");
            // WebGL 에서는 대개 잡히지 않는다. Valid 를 보고 표시를 가른다.
            _gpuTime = ProfilerRecorder.StartNew(ProfilerCategory.Render, "GPU Frame Time");
        }

        void OnDisable()
        {
            _drawCalls.Dispose(); _setPass.Dispose(); _batches.Dispose();
            _tris.Dispose(); _sysMemory.Dispose(); _gpuTime.Dispose();
        }

        void Update()
        {
            if (Input.GetKeyDown(_toggleKey)) _visible = !_visible;
            if (Input.GetKeyDown(_resetKey)) ResetStats();
            if (Input.GetKeyDown(_avatarLodKey))
            {
                Festa.World.AvatarAnimationLod.Enabled = !Festa.World.AvatarAnimationLod.Enabled;
                ResetStats();   // 조건이 바뀌었으니 이전 창의 표본을 섞지 않는다
            }
            if (Input.GetKeyDown(_meshMergeKey))
            {
                // 스킨메시 병합 A/B (S15P21A604-258). **끄고 켠 뒤 아바타를 다시 조립해야**
                // 반영된다 — 병합은 조립 시점에 일어난다. 로비에서 옷을 갈아입으면 된다.
                // 그게 2026-08-26 에 몸이 사라졌던 바로 그 조작이다.
                Festa.Avatar.AvatarMeshMerge.Enabled = !Festa.Avatar.AvatarMeshMerge.Enabled;
                ResetStats();
            }

            float ms = Time.unscaledDeltaTime * 1000f;
            _windowFrames++;
            _windowElapsed += Time.unscaledDeltaTime;
            if (ms > _windowWorstMs) _windowWorstMs = ms;
            if (ms > _sessionWorstMs) _sessionWorstMs = ms;
            if (_sampleCount < _samples.Length) _samples[_sampleCount++] = ms;

            SampleNetwork();

            if (_windowElapsed >= _window)
            {
                _fps = _windowFrames / _windowElapsed;
                _avgMs = _windowElapsed * 1000f / _windowFrames;
                _worstMs = _windowWorstMs;
                ComputePercentiles();

                int gc = System.GC.CollectionCount(0);
                _gcPerWindow = gc - _lastGcCount;
                _lastGcCount = gc;
                _heapBytes = System.GC.GetTotalMemory(false);

                _windowElapsed = 0f; _windowFrames = 0; _windowWorstMs = 0f; _sampleCount = 0;
                _statsDirty = true;
            }
        }

        /// <summary>
        /// 창 표본에서 p95·p99 를 낸다. 표본이 수백 개뿐이라 정렬 비용은 무시할 만하고,
        /// 창 경계에서 한 번만 돈다. 할당을 만들지 않으려고 고정 배열을 제자리 정렬한다.
        /// </summary>
        void ComputePercentiles()
        {
            if (_sampleCount == 0) { _p95Ms = _p99Ms = 0f; return; }
            System.Array.Sort(_samples, 0, _sampleCount);
            _p95Ms = _samples[Mathf.Min(_sampleCount - 1, Mathf.FloorToInt(_sampleCount * 0.95f))];
            _p99Ms = _samples[Mathf.Min(_sampleCount - 1, Mathf.FloorToInt(_sampleCount * 0.99f))];
        }


        /// <summary>
        /// 매 프레임 NGO 트래픽 카운터를 누적한다. 카운터는 dispatch 때 0 으로 리셋되므로
        /// 프레임마다 읽어 더하지 않으면 초당 값을 만들 수 없다.
        /// 서버(Host)에서 읽으면 **전 클라이언트 합계**라 인원수에 따른 증가를 그대로 볼 수 있다.
        /// </summary>
        void SampleNetwork()
        {
            var nm = NetworkManager.Singleton;
            if (nm == null || (!nm.IsClient && !nm.IsServer)) { _sentPerSec = _recvPerSec = 0f; return; }

            if (_metrics == null)
            {
                const System.Reflection.BindingFlags BF =
                    System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.NonPublic |
                    System.Reflection.BindingFlags.Instance;
                var prop = nm.GetType().GetProperty("NetworkMetrics", BF);
                _metrics = prop?.GetValue(nm);
                if (_metrics == null) return;
                _sentField = _metrics.GetType().GetField("m_TransportBytesSent", BF);
                _recvField = _metrics.GetType().GetField("m_TransportBytesReceived", BF);
                var counter = _sentField?.GetValue(_metrics);
                _counterValue = counter?.GetType().GetProperty("Value", BF);
            }
            if (_counterValue == null) return;

            var s = _sentField.GetValue(_metrics);
            var r = _recvField.GetValue(_metrics);
            _sentAccum += (long)_counterValue.GetValue(s);
            _recvAccum += (long)_counterValue.GetValue(r);

            _netWindow += Time.unscaledDeltaTime;
            if (_netWindow >= 1f)
            {
                _sentPerSec = _sentAccum / _netWindow;
                _recvPerSec = _recvAccum / _netWindow;
                _sentAccum = 0; _recvAccum = 0; _netWindow = 0f;
            }
        }

        void ResetStats()
        {
            _sessionWorstMs = 0f;
            _windowElapsed = 0f; _windowFrames = 0; _windowWorstMs = 0f;
            _sampleCount = 0; _p95Ms = _p99Ms = 0f;
            _statsDirty = true;
        }

        // 서버 빌드는 IMGUI 모듈이 스트립돼, 이 메서드가 **존재하기만 해도** 유니티가
        // "OnGUI function detected on MonoBehaviour, but not called" 경고를 띄운다
        // (S15P21A604-314). 내부 가드로는 못 막으므로 서버 빌드에서 컴파일 제외한다.
        // UNITY_EDITOR 를 함께 두는 이유는 DevConnectionHud 쪽 주석 참조 (T-182).
#if UNITY_EDITOR || !UNITY_SERVER
        void OnGUI()
        {
            if (!_visible) return;
            EnsureStyle();

            // 화면 크기가 바뀌면 조건 줄(백버퍼)도 다시 만들어야 한다.
            if (Screen.width != _lastW || Screen.height != _lastH) { _statsDirty = true; }
            if (_statsDirty) { RebuildText(); _statsDirty = false; }

            float scale = Mathf.Max(1f, Screen.height / 1080f);
            float w = 430f * scale;
            float h = 330f * scale;
            // **좌상단을 피한다** — FE WorldHud 의 조작 안내 카드가 그 자리를 쓴다.
            // 겹치면 라벨이 가려지고, 라벨 없는 값을 다른 지표로 오독하게 된다 (T-133).
            var rect = new Rect(Screen.width - w - 10f, 10f, w, h);
            GUI.DrawTexture(rect, Bg(), ScaleMode.StretchToFill);
            GUI.Label(new Rect(rect.x + 10f, rect.y + 8f, rect.width - 20f, rect.height - 16f), _cachedText, _style);
        }

        /// <summary>
        /// 표시 문자열을 만든다. **창 갱신 시점에만** 불린다 — 매 프레임 만들면 그 할당이
        /// 곧 GC 가 되고, 계측 도구가 자기가 만든 GC 를 계측해 보여주게 된다.
        ///
        /// 배치 원칙: **한 줄에 지표 하나, 라벨을 값 바로 앞에.** 예전에는
        /// `FPS x  평균 y ms  최악 z ms` 처럼 한 줄에 셋을 넣었는데, 다른 UI 가 앞부분을
        /// 가리자 오른쪽 끝 값만 보였고 그것을 GPU 시간으로 오독했다 (T-133).
        /// </summary>
        void RebuildText()
        {
            _lastW = Screen.width; _lastH = Screen.height;
            var nm = NetworkManager.Singleton;

            Sb.Clear();
            Sb.Append("== PERF (F3 hide / F4 reset / F9 LOD / F10 merge) ==\n");

            // ① 측정 조건 — 이게 없으면 스크린샷의 수치가 어느 조건인지 알 수 없다.
            Sb.Append(BuildConditionLines());

            // ② 프레임 — 한 줄에 하나씩.
            Sb.AppendFormat("FPS        {0:F1}\n", _fps);
            Sb.AppendFormat("frame avg  {0:F1} ms\n", _avgMs);
            Sb.AppendFormat("frame p95  {0:F1} ms\n", _p95Ms);
            Sb.AppendFormat("frame p99  {0:F1} ms\n", _p99Ms);
            Sb.AppendFormat("frame worst {0:F1} ms (last {1:F0}s)\n", _worstMs, _window);
            Sb.AppendFormat("session worst {0:F1} ms\n", _sessionWorstMs);

            // ③ GPU — 잡히지 않으면 잡히지 않는다고 적는다. 이 자리를 비워 두면
            //    옆 숫자를 GPU 로 오해하는 일이 또 생긴다 (T-133).
            if (_gpuTime.Valid && _gpuTime.LastValue > 0)
                Sb.AppendFormat("GPU frame  {0:F1} ms\n", _gpuTime.LastValue / 1e6f);
            else
                Sb.Append("GPU frame  NOT MEASURED (unsupported here)\n");

            // ④ 메모리·GC
            Sb.AppendFormat("heap       {0:F1} MB\n", _heapBytes / 1048576f);
            Sb.AppendFormat("GC count   {0} (last {1:F0}s)\n", _gcPerWindow, _window);
            if (_sysMemory.Valid)
                Sb.AppendFormat("sys mem    {0:F0} MB\n", _sysMemory.LastValue / 1048576f);

            // ⑤ 렌더 작업량
            if (_drawCalls.Valid)
            {
                Sb.AppendFormat("draws      {0}\n", _drawCalls.LastValue);
                if (_setPass.Valid) Sb.AppendFormat("SetPass    {0}\n", _setPass.LastValue);
                if (_batches.Valid) Sb.AppendFormat("batches    {0}\n", _batches.LastValue);
            }
            if (_tris.Valid) Sb.AppendFormat("tris       {0:N0}\n", _tris.LastValue);

            // ⑥ 네트워크
            if (nm != null && (nm.IsClient || nm.IsServer))
            {
                Sb.AppendFormat("players    {0}\n",
                    nm.IsServer ? nm.ConnectedClientsIds.Count : 1);
                Sb.AppendFormat("spawned    {0}\n",
                    nm.SpawnManager != null ? nm.SpawnManager.SpawnedObjects.Count : 0);
                if (nm.IsClient && !nm.IsServer && nm.NetworkConfig?.NetworkTransport != null)
                    Sb.AppendFormat("RTT        {0} ms\n",
                        nm.NetworkConfig.NetworkTransport.GetCurrentRtt(NetworkManager.ServerClientId));
            }
            else Sb.Append("net        OFFLINE\n");

            Sb.AppendFormat(FmtNet, _sentPerSec / 1024f, _recvPerSec / 1024f);

            _cachedText = Sb.ToString();
        }

        /// <summary>
        /// 빌드·해상도·품질을 한 번만 조립해 둔다. 해상도만 창 크기 변화에 따라 갱신된다.
        /// A/B 표본을 섞지 않으려면 스크린샷에 조건이 함께 찍혀 있어야 한다 (T-211).
        /// </summary>
        string BuildConditionLines()
        {
            if (_conditionLine.Length == 0)
            {
                var rp = UnityEngine.Rendering.GraphicsSettings.currentRenderPipeline;
                string rpName = rp != null ? rp.name : "Built-in";
                float renderScale = -1f;
#if UNITY_2021_1_OR_NEWER
                if (rp is UnityEngine.Rendering.Universal.UniversalRenderPipelineAsset urp)
                    renderScale = urp.renderScale;
#endif
                _conditionLine =
                    $"build      {Application.version} / {(Debug.isDebugBuild ? "Development" : "Release")}\n" +
                    $"quality    {QualitySettings.names[QualitySettings.GetQualityLevel()]} / {rpName}" +
                    (renderScale > 0f ? $" / renderScale {renderScale:F2}\n" : "\n");
            }
            // 백버퍼는 창 크기·DPR 에 따라 바뀐다 — 매번 다시 만든다.
            return _conditionLine +
                   $"backbuffer {Screen.width}x{Screen.height} ({(Screen.width * (long)Screen.height) / 1e6f:F2} MP)\n" +
                   $"avatar LOD {(Festa.World.AvatarAnimationLod.Enabled ? "ON" : "OFF")}\n";
        }

        // 아래 둘은 OnGUI 에서만 쓰인다. 같이 배제해야 서버 빌드에 IMGUI 참조가 남지 않는다.

        void EnsureStyle()
        {
            if (_style != null) return;
            // 매 프레임 GUIStyle 을 만들면 그 자체가 GC 쓰레기가 된다 — 계측 도구가
            // 계측 대상을 오염시키지 않도록 한 번만 만든다.
            _style = new GUIStyle(GUI.skin.label)
            {
                alignment = TextAnchor.UpperLeft,
                fontSize = Mathf.RoundToInt(14f * Mathf.Max(1f, Screen.height / 1080f)),
                richText = false,
            };
            _style.normal.textColor = new Color(0.85f, 1f, 0.85f);
        }

        static Texture2D Bg()
        {
            if (_bg != null) return _bg;
            _bg = new Texture2D(2, 2, TextureFormat.RGBA32, false);
            var c = new Color(0f, 0f, 0f, 0.72f);
            _bg.SetPixels(new[] { c, c, c, c });
            _bg.Apply();
            return _bg;
        }
#endif
    }
}
