using System.Collections;
using System.Collections.Generic;
using System.Text;
using Unity.Profiling;
using Unity.Profiling.LowLevel;
using Unity.Profiling.LowLevel.Unsafe;
using UnityEngine;
using UnityEngine.Rendering;
using UnityEngine.Rendering.Universal;

namespace Festa.Diagnostics
{
    /// <summary>
    /// **카메라를 고정하고** 구성을 A/B 하는 벤치마크 (S15P21A604-508).
    ///
    /// 왜 필요한가: 지금까지의 F9 스윕은 구성마다 사용자가 걷는 위치가 달라 **시야가 통제되지
    /// 않았다.** 같은 스윕 안에서 삼각형이 430k~1,229k 로 3배 흔들렸고, 그래서 "조명 병합으로
    /// 드로우콜 987 → 310" 같은 결론이 실제로는 시야 차이였다. 이 맵에서 위치를 통제하지 않은
    /// A/B 는 어떤 것도 근거가 되지 못한다.
    ///
    /// 그래서 측정 동안 카메라를 **고정 포즈에 못 박는다**. 추적 카메라(PlayerCameraFollow)와
    /// 상호작용 카메라(InteractionFocusCamera)를 잠그고, 매 LateUpdate 에 카메라 트랜스폼을
    /// 지정 포즈로 되돌린다. 끝나면 전부 원복한다.
    ///
    /// ## vsync 바닥 문제 — 이 측정의 한계를 먼저 밝힌다
    ///
    /// WebGL 은 requestAnimationFrame 이 구동해서 **프레임 상한을 풀 수 없다.** 그래서 어떤
    /// 구성이 예산(16.7ms) 아래로 충분히 내려가면 두 구성 모두 16.7ms 로 눌려 **차이가 0 으로
    /// 보인다.** 그건 "차이가 없다" 가 아니라 "이 지표로는 못 잰다" 다.
    ///
    /// 두 가지로 대응한다.
    ///   1. **무거운 포즈를 함께 쓴다** — 예산을 넘는 시야에서는 차이가 프레임 시간에 드러난다.
    ///   2. **바닥에 붙은 프레임 비율을 같이 찍는다** — 두 구성이 모두 바닥이면 그 사실을
    ///      숨기지 않고 "바닥 100% / 판정 불가" 로 보고한다.
    ///   3. CPU 마커가 잡히면 함께 낸다. 어떤 마커가 WebGL 에서 유효한지는 기기마다 달라서
    ///      **F12 로 사용 가능한 통계를 열거**해 확인한 뒤 쓴다(추측하지 않는다).
    ///
    /// F5 — 고정 포즈 A/B 실행. 사용자는 아무것도 하지 않아도 된다(카메라가 고정된다).
    /// F12 — 이 기기에서 실제로 잡히는 ProfilerRecorder 통계 이름을 전부 찍는다.
    ///
    /// 개발 빌드·에디터 전용.
    /// </summary>
    public class FixedPoseBenchmark : MonoBehaviour
    {
        const KeyCode _runKey = KeyCode.Comma;               // ,  F5 는 브라우저 새로고침이다
        const KeyCode _listStatsKey = KeyCode.Period;        // .  F12 는 개발자도구 + AvatarMergeIntegrity 와 중복이었다
        const KeyCode _histogramKey = KeyCode.Backslash;     //   F2 는 DevConnectionHud 가 신규 Input System 으로 이미 쓴다
        [Tooltip("프레임 간격 히스토그램 수집 시간(초).")]
        [SerializeField] float _histogramSeconds = 20f;
        [Tooltip("구성 적용 후 버리는 시간(초). 셰이더 변형·섀도맵이 자리잡기를 기다린다.")]
        [SerializeField] float _settle = 1.5f;
        [Tooltip("프레임을 수집하는 시간(초).")]
        [SerializeField] float _measure = 3f;
        [Tooltip("각 구성을 몇 번 반복할지. 구성 간 교차로 돌려 드리프트를 드러낸다.")]
        [SerializeField] int _repeats = 3;

        // ── 포즈 ────────────────────────────────────────────────
        // 11F 홀은 천장 다운라이트가 실제로 비추는 자리다(조명 A/B 의 본 무대).
        // 축제 서쪽 끝 동향은 실측에서 가장 무거웠던 시야다 — vsync 바닥을 넘기므로 차이가 드러난다.
        struct Pose { public string Name; public Vector3 Pos; public float Yaw; }
        static readonly Pose[] Poses =
        {
            new Pose { Name = "11F홀 동향",    Pos = new Vector3(-29.5f, 22.5f, -248f), Yaw = 90f },
            new Pose { Name = "11F홀 북향",    Pos = new Vector3(-93f,   22.5f, -225f), Yaw = 0f },
            new Pose { Name = "축제서끝 동향", Pos = new Vector3(-754f,  22.5f,  173f), Yaw = 85f },
        };

        // ── 구성 ────────────────────────────────────────────────
        static readonly string[] ConfigNames = { "원본 90등", "구역병합 18등" };

        GameObject _downRoot, _mergedRoot;
        Light[] _mergedLights;
        float _mergedIntensity = 840f;
        bool _origDownActive, _origMergedActive;

        Camera _cam;
        Behaviour[] _lockedCameraDrivers;
        bool _running;
        bool _pin;
        Vector3 _pinPos; Quaternion _pinRot;
        Vector3 _origCamPos; Quaternion _origCamRot;

        ProfilerRecorder _draws, _setPass, _batches, _tris;
        readonly List<ProfilerRecorder> _timers = new List<ProfilerRecorder>();
        readonly List<string> _timerNames = new List<string>();

        readonly List<float> _frames = new List<float>(512);
        static readonly StringBuilder Sb = new StringBuilder(512);

        void OnEnable()
        {

            // 키 충돌은 조용히 넘어가면 다음 사람이 같은 함정을 밟는다 — 여기서 신고하고 DiagnosticKeys 가 에러로 드러낸다.
            DiagnosticKeys.Claim(nameof(FixedPoseBenchmark), _runKey);
            DiagnosticKeys.Claim(nameof(FixedPoseBenchmark), _listStatsKey);
            DiagnosticKeys.Claim(nameof(FixedPoseBenchmark), _histogramKey);
            if (!PerfHud.ToolsEnabled) { enabled = false; return; }
            Collect();
            _draws = ProfilerRecorder.StartNew(ProfilerCategory.Render, "Draw Calls Count");
            _setPass = ProfilerRecorder.StartNew(ProfilerCategory.Render, "SetPass Calls Count");
            _batches = ProfilerRecorder.StartNew(ProfilerCategory.Render, "Batches Count");
            _tris = ProfilerRecorder.StartNew(ProfilerCategory.Render, "Triangles Count");
            StartTimers();
            LogSystemReport();
            Debug.Log($"[Bench] 준비 — 쉼표(,) 고정 포즈 A/B (포즈 {Poses.Length} × 구성 {ConfigNames.Length} " +
                      $"× 반복 {_repeats}) / 마침표(.) 사용 가능 통계 열거 / 역슬래시(\\) 프레임 간격 히스토그램. " +
                      $"유효 타이머 {_timerNames.Count}개");
        }

        void OnDisable()
        {
            RestoreAll();
            _draws.Dispose(); _setPass.Dispose(); _batches.Dispose(); _tris.Dispose();
            foreach (var t in _timers) t.Dispose();
            _timers.Clear();
        }

        void Collect()
        {
            var ceiling = GameObject.Find("@World_11F/WorldCeilingLights");
            if (ceiling != null)
            {
                var d = ceiling.transform.Find("Downlights");
                if (d != null) _downRoot = d.gameObject;
                var m = ceiling.transform.Find("DownlightsMerged");
                if (m != null)
                {
                    _mergedRoot = m.gameObject;
                    _mergedLights = m.GetComponentsInChildren<Light>(true);
                    if (_mergedLights.Length > 0) _mergedIntensity = _mergedLights[0].intensity;
                }
            }
            if (_mergedLights == null) _mergedLights = new Light[0];
            _origDownActive = _downRoot != null && _downRoot.activeSelf;
            _origMergedActive = _mergedRoot != null && _mergedRoot.activeSelf;
        }

        /// <summary>
        /// 후보 마커를 전부 시도해 **Valid 인 것만** 남긴다. 어떤 것이 유효한지는 플랫폼·빌드
        /// 설정마다 달라서 이름을 하드코딩하면 조용히 0 이 찍힌다.
        /// </summary>
        void StartTimers()
        {
            var candidates = new (ProfilerCategory, string)[]
            {
                (ProfilerCategory.Internal, "Main Thread"),
                (ProfilerCategory.Render,   "RenderLoop"),
                (ProfilerCategory.Render,   "Camera.Render"),
                (ProfilerCategory.Render,   "Inl_RenderCameraStack"),
                (ProfilerCategory.Render,   "GPU Frame Time"),
                (ProfilerCategory.Scripts,  "Behaviour Update"),
            };
            foreach (var c in candidates)
            {
                var r = ProfilerRecorder.StartNew(c.Item1, c.Item2);
                if (r.Valid) { _timers.Add(r); _timerNames.Add(c.Item2); }
                else r.Dispose();
            }
        }

        void Update()
        {
            if (Input.GetKeyDown(_listStatsKey)) ListAvailableStats();
            if (Input.GetKeyDown(_histogramKey) && !_running) StartCoroutine(Histogram());
            if (Input.GetKeyDown(_runKey) && !_running) StartCoroutine(Run());
        }

        /// <summary>
        /// **어느 GPU 로 돌고 있는지**를 남긴다. 이 프로젝트는 지금까지 이걸 한 번도 기록하지 않았다.
        ///
        /// 이 개발 머신에는 GPU 가 둘이다 — NVIDIA RTX 4050 과 Intel Arc(내장). 내부 디스플레이는
        /// **Intel Arc 에 물려 있고**(2880x1800 120Hz), 어느 쪽으로 그릴지는 Windows 가 자동으로 고른다
        /// (`HKCU\Software\Microsoft\DirectX\UserGpuPreferences` 에 Chrome 항목이 없다 = 고정 안 됨).
        /// 즉 **프로젝트를 하나도 안 건드려도 드라이버·브라우저 업데이트나 전원 상태로 렌더 GPU 가 바뀔 수 있다.**
        /// 사용자가 "설정을 바꾼 적이 없는데 갑자기 느려졌다" 고 하는 상황에서 이건 반드시 남겨야 할 값이다.
        /// </summary>
        static void LogSystemReport()
        {
            Sb.Clear();
            Sb.AppendLine("[Bench] 시스템 —");
            Sb.AppendLine($"  GPU        {SystemInfo.graphicsDeviceName}");
            Sb.AppendLine($"  API        {SystemInfo.graphicsDeviceType} / {SystemInfo.graphicsDeviceVersion}");
            Sb.AppendLine($"  VRAM       {SystemInfo.graphicsMemorySize} MB · 벤더 {SystemInfo.graphicsDeviceVendor}");
            Sb.AppendLine($"  화면       {Screen.width}x{Screen.height} · dpi {Screen.dpi} · " +
                          $"주사율 {Screen.currentResolution.refreshRateRatio.value:F1} Hz");
            Sb.AppendLine($"  품질레벨   {QualitySettings.GetQualityLevel()} ({QualitySettings.names[QualitySettings.GetQualityLevel()]}) · " +
                          $"vSync {QualitySettings.vSyncCount} · targetFrameRate {Application.targetFrameRate}");
            Sb.AppendLine($"  빌드       개발={Debug.isDebugBuild} · 플랫폼={Application.platform}");
            var rp = GraphicsSettings.currentRenderPipeline as UniversalRenderPipelineAsset;
            if (rp != null)
                Sb.AppendLine($"  RP         {rp.name} · renderScale {rp.renderScale} · 그림자 {rp.shadowDistance}/캐스{rp.shadowCascadeCount}");
            Debug.Log(Sb.ToString());
        }

        /// <summary>
        /// 프레임 간격 **분포**를 찍는다. 중앙값 하나로는 못 가르는 것을 가르기 위해서다.
        ///
        /// 이 화면은 120 Hz 이고 가변 새로 고침(24~120 Hz)을 지원한다. 그래서 프레임이 늘어지는 이유가
        /// 두 가지로 갈린다.
        ///   (A) **GPU 가 못 따라간다** — 간격이 8.3 ms 근처에 몰려 있다가 넘어가는 것들이 연속 분포로 퍼진다
        ///   (B) **주사율 자체가 계단식으로 내려간다** — 간격이 8.3 / 16.7 / 25 / 33 ms 같은 **배수에 뭉친다**
        /// 분포가 봉우리 여러 개로 뭉치면 (B), 매끄럽게 퍼지면 (A) 다. 조치가 완전히 다르다.
        ///
        /// 브라우저가 performance.now() 를 1 ms 로 양자화해서 값이 정수로 나온다 — 그래서 1 ms 폭 빈으로 센다.
        /// </summary>
        IEnumerator Histogram()
        {
            _running = true;
            Debug.Log($"[Bench] 프레임 간격 히스토그램 {_histogramSeconds:F0}초 — **평소처럼 걸어다녀라.** " +
                      "정지 상태와 이동 상태를 각각 재려면 두 번 돌려라.");
            var bins = new int[64];   // 0~63 ms, 1 ms 폭
            int over = 0, n = 0;
            float t = 0f;
            while (t < _histogramSeconds)
            {
                float ms = Time.unscaledDeltaTime * 1000f;
                t += Time.unscaledDeltaTime;
                int b = Mathf.FloorToInt(ms);
                if (b < 0) b = 0;
                if (b < bins.Length) bins[b]++; else over++;
                n++;
                yield return null;
            }
            Sb.Clear();
            Sb.AppendLine($"[Bench] ===== 프레임 간격 분포 ({n} 프레임, {_histogramSeconds:F0}초) =====");
            Sb.AppendLine("  120Hz=8.3ms · 60Hz=16.7 · 40Hz=25 · 30Hz=33.3 — 이 값들에 뭉치면 주사율이 계단식으로 바뀌는 것이다");
            for (int i = 0; i < bins.Length; i++)
            {
                if (bins[i] == 0) continue;
                int barLen = Mathf.Clamp(bins[i] * 50 / Mathf.Max(1, n), 0, 50);
                Sb.Append("  ").Append(i.ToString().PadLeft(2)).Append(" ms |");
                for (int k = 0; k < barLen; k++) Sb.Append('#');
                Sb.Append(' ').Append(bins[i]).Append(" (").Append((bins[i] * 100f / n).ToString("F1")).AppendLine("%)");
            }
            if (over > 0) Sb.AppendLine($"  64+ ms : {over}회");
            Debug.Log(Sb.ToString());
            _running = false;
        }

        /// <summary>측정 중에는 카메라를 못 박는다. 추적 스크립트를 껐어도 마지막 값이 남을 수 있다.</summary>
        void LateUpdate()
        {
            if (_pin && _cam != null)
            {
                _cam.transform.SetPositionAndRotation(_pinPos, _pinRot);
            }
        }

        /// <summary>이 기기에서 실제로 잡히는 통계 이름을 전부 찍는다 — 추측 대신 확인용.</summary>
        static void ListAvailableStats()
        {
            var handles = new List<ProfilerRecorderHandle>();
            ProfilerRecorderHandle.GetAvailable(handles);
            var byCat = new Dictionary<string, List<string>>();
            foreach (var h in handles)
            {
                var d = ProfilerRecorderHandle.GetDescription(h);
                string cat = d.Category.Name;
                if (!byCat.TryGetValue(cat, out var list)) { list = new List<string>(); byCat[cat] = list; }
                list.Add(d.Name + (d.UnitType == ProfilerMarkerDataUnit.TimeNanoseconds ? "(ns)" : ""));
            }
            Sb.Clear();
            Sb.AppendLine($"[Bench] 이 기기에서 사용 가능한 통계 {handles.Count}개");
            foreach (var kv in byCat)
            {
                Sb.Append("  [").Append(kv.Key).Append("] ");
                for (int i = 0; i < kv.Value.Count; i++)
                {
                    Sb.Append(kv.Value[i]);
                    if (i < kv.Value.Count - 1) Sb.Append(" · ");
                }
                Sb.AppendLine();
            }
            Debug.Log(Sb.ToString());
        }

        // ── 실행 ────────────────────────────────────────────────

        IEnumerator Run()
        {
            _cam = Camera.main;
            if (_cam == null) { Debug.LogWarning("[Bench] Camera.main 이 없다 — 월드에 들어간 뒤 실행하라"); yield break; }
            if (_mergedLights.Length == 0) { Debug.LogWarning("[Bench] 구역병합 리그가 없다"); yield break; }

            _running = true;
            LockCameraDrivers();
            _origCamPos = _cam.transform.position;
            _origCamRot = _cam.transform.rotation;

            Debug.Log($"[Bench] 시작 — 카메라를 고정한다. 조작하지 않아도 된다. " +
                      $"예상 {Poses.Length * ConfigNames.Length * _repeats * (_settle + _measure):F0}초");

            // 결과: [pose][config][repeat]
            var p50 = new float[Poses.Length][][];
            var floorPct = new float[Poses.Length][][];
            var drawsArr = new float[Poses.Length][][];
            var trisArr = new float[Poses.Length][][];
            for (int p = 0; p < Poses.Length; p++)
            {
                p50[p] = new float[ConfigNames.Length][];
                floorPct[p] = new float[ConfigNames.Length][];
                drawsArr[p] = new float[ConfigNames.Length][];
                trisArr[p] = new float[ConfigNames.Length][];
                for (int c = 0; c < ConfigNames.Length; c++)
                {
                    p50[p][c] = new float[_repeats];
                    floorPct[p][c] = new float[_repeats];
                    drawsArr[p][c] = new float[_repeats];
                    trisArr[p][c] = new float[_repeats];
                }
            }

            for (int p = 0; p < Poses.Length; p++)
            {
                var pose = Poses[p];
                _pinPos = pose.Pos;
                _pinRot = Quaternion.Euler(0f, pose.Yaw, 0f);
                _pin = true;

                // 예열 — 이 포즈에서 두 구성을 한 번씩 돌려 셰이더 변형·섀도맵을 링크시키고 버린다.
                for (int c = 0; c < ConfigNames.Length; c++)
                {
                    ApplyConfig(c);
                    float w = 0f;
                    while (w < _settle) { w += Time.unscaledDeltaTime; yield return null; }
                }

                // 교차 반복: A B A B A B — 드리프트가 있으면 반복 간 값이 한 방향으로 흐른다.
                for (int r = 0; r < _repeats; r++)
                {
                    for (int c = 0; c < ConfigNames.Length; c++)
                    {
                        ApplyConfig(c);
                        float t = 0f;
                        while (t < _settle) { t += Time.unscaledDeltaTime; yield return null; }

                        _frames.Clear();
                        double dSum = 0, tSum = 0; int n = 0;
                        t = 0f;
                        while (t < _measure)
                        {
                            float ms = Time.unscaledDeltaTime * 1000f;
                            t += Time.unscaledDeltaTime;
                            _frames.Add(ms);
                            if (_draws.Valid) dSum += _draws.LastValue;
                            if (_tris.Valid) tSum += _tris.LastValue;
                            n++;
                            yield return null;
                        }
                        _frames.Sort();
                        p50[p][c][r] = _frames[Mathf.Clamp(_frames.Count / 2, 0, _frames.Count - 1)];
                        int floor = 0;
                        foreach (var f in _frames) if (f <= 17.4f) floor++;   // vsync 바닥에 붙은 프레임
                        floorPct[p][c][r] = _frames.Count > 0 ? floor * 100f / _frames.Count : 0f;
                        drawsArr[p][c][r] = n > 0 ? (float)(dSum / n) : 0f;
                        trisArr[p][c][r] = n > 0 ? (float)(tSum / n) : 0f;
                    }
                }
                _pin = false;
            }

            RestoreAll();
            Report(p50, floorPct, drawsArr, trisArr);
            _running = false;
        }

        void Report(float[][][] p50, float[][][] floorPct, float[][][] draws, float[][][] tris)
        {
            Sb.Clear();
            Sb.AppendLine("[Bench] ===== 고정 포즈 A/B 결과 =====");
            Sb.AppendLine("  (카메라 고정 · 구성 교차 반복 " + _repeats + "회 · 창 " + _measure.ToString("F1") + "초)");
            for (int p = 0; p < Poses.Length; p++)
            {
                Sb.AppendLine("  ── " + Poses[p].Name + " ──");
                for (int c = 0; c < ConfigNames.Length; c++)
                {
                    Sb.AppendFormat("    {0,-14} p50 {1} | 바닥 {2} | draws {3} | tris {4}\n",
                        ConfigNames[c], Series(p50[p][c], "F1"), Series(floorPct[p][c], "F0"),
                        Series(draws[p][c], "F0"), Series(tris[p][c], "F0"));
                }
                // 판정: 두 구성이 모두 바닥에 붙어 있으면 이 포즈로는 못 가른다.
                bool aFloor = Mean(floorPct[p][0]) > 95f, bFloor = Mean(floorPct[p][1]) > 95f;
                float dP50 = Mean(p50[p][1]) - Mean(p50[p][0]);
                float dDraw = Mean(draws[p][1]) - Mean(draws[p][0]);
                if (aFloor && bFloor)
                    Sb.AppendLine("    → 두 구성 모두 vsync 바닥. **이 포즈로는 판정 불가**(차이가 있어도 안 보인다)");
                else
                    Sb.AppendFormat("    → 병합−원본: p50 {0:+0.0;-0.0;0} ms, draws {1:+0;-0;0}\n", dP50, dDraw);
            }
            if (_timerNames.Count == 0)
                Sb.AppendLine("  CPU 타이머: 이 기기에서 유효한 마커 없음 (마침표(.) 로 목록 확인)");
            Debug.Log(Sb.ToString());
        }

        static float Mean(float[] a) { float s = 0f; foreach (var v in a) s += v; return a.Length > 0 ? s / a.Length : 0f; }

        /// <summary>반복값을 평균과 범위로 낸다 — 반복 간 흔들림이 구성 간 차이보다 크면 그게 보여야 한다.</summary>
        static string Series(float[] a, string fmt)
        {
            if (a.Length == 0) return "-";
            float min = a[0], max = a[0];
            foreach (var v in a) { if (v < min) min = v; if (v > max) max = v; }
            return Mean(a).ToString(fmt) + " [" + min.ToString(fmt) + "~" + max.ToString(fmt) + "]";
        }

        void ApplyConfig(int index)
        {
            bool useMerged = index == 1;
            if (_downRoot != null) _downRoot.SetActive(!useMerged);
            if (_mergedRoot != null) _mergedRoot.SetActive(useMerged);
            if (useMerged)
                foreach (var l in _mergedLights)
                    if (l != null) l.intensity = _mergedIntensity;
        }

        /// <summary>추적 카메라·상호작용 카메라를 잠근다. 컴포넌트만 끄고 오브젝트는 건드리지 않는다.</summary>
        void LockCameraDrivers()
        {
            var list = new List<Behaviour>();
            foreach (var mb in Object.FindObjectsByType<MonoBehaviour>(FindObjectsInactive.Exclude, FindObjectsSortMode.None))
            {
                if (mb == null || !mb.enabled) continue;
                string n = mb.GetType().Name;
                if (n == "PlayerCameraFollow" || n == "InteractionFocusCamera")
                {
                    mb.enabled = false;
                    list.Add(mb);
                }
            }
            _lockedCameraDrivers = list.ToArray();
        }

        void RestoreAll()
        {
            _pin = false;
            if (_lockedCameraDrivers != null)
            {
                foreach (var b in _lockedCameraDrivers) if (b != null) b.enabled = true;
                _lockedCameraDrivers = null;
            }
            if (_cam != null) _cam.transform.SetPositionAndRotation(_origCamPos, _origCamRot);
            if (_downRoot != null) _downRoot.SetActive(_origDownActive);
            if (_mergedRoot != null) _mergedRoot.SetActive(_origMergedActive);
            foreach (var l in _mergedLights) if (l != null) l.intensity = _mergedIntensity;
        }

        void OnGUI()
        {
            if (!PerfHud.ToolsEnabled) return;
            GUI.Label(new Rect(10f, Screen.height - 44f, 760f, 20f),
                _running ? "FIXED-POSE BENCH RUNNING - camera is pinned, no input needed"
                         : ",  fixed-pose A/B  /  .  list available profiler stats  /  \\  frame-interval histogram");
        }
    }
}
