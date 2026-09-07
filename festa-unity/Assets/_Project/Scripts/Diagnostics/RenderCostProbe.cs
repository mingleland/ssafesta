using System.Collections;
using System.Collections.Generic;
using System.Text;
using Unity.Profiling;
using UnityEngine;
using UnityEngine.Rendering;
using UnityEngine.Rendering.Universal;

namespace Festa.Diagnostics
{
    /// <summary>
    /// "걸으면 느려졌다 빨라졌다" 의 **원인을 실측으로 가르는** 프로브 (S15P21A604-508).
    ///
    /// 왜 필요한가: HitchLogger 로 얻은 결론은 "끊긴 프레임 24건 중 20건이 34~42ms 인데
    /// 드로우콜·삼각형·GC 증분이 전부 0" 이었다. CPU 가 새로 하는 일이 없는데 프레임만 길어진다는
    /// 것은 스파이크가 아니라 **기본 비용이 16.7ms 경계에 걸쳐 있다**는 뜻이고, 그래서 vsync 가
    /// 60↔30 을 왕복한다. 즉 찾을 것은 "튀는 순간" 이 아니라 "상시로 비싼 것" 이다.
    ///
    /// 상시 비용 후보는 추측으로 못 가른다 — 지금까지 추측으로 텍스처 예산·오클루전 컬링·
    /// Screen.SetResolution 을 건드렸다가 전부 되돌렸다. 그래서 **한 빌드 안에서 구성을 바꿔가며
    /// 같은 조건으로 재는** 방식으로 바꾼다.
    ///
    /// 쓰는 법: F9 로 스윕 시작. 시나리오 하나당 0.7초 안정화 + 3.3초 측정이고, 그동안
    /// **계속 걸어다녀야 한다** — 정지 상태는 20초를 재도 최대 25ms 로 멀쩡해서 아무것도 안 나온다.
    /// 결과는 브라우저 콘솔에 한 줄씩 쌓이고, 끝나면 요약표가 한 번에 찍힌다.
    ///
    /// 측정이 끝나면 모든 변경은 원복된다. 에디터에서도 RP 애셋 값을 되돌려 놓는다.
    /// 개발 빌드·에디터 전용.
    /// </summary>
    public class RenderCostProbe : MonoBehaviour
    {
        [SerializeField] KeyCode _startKey = KeyCode.F9;
        [Tooltip("시나리오를 적용하고 나서 버리는 시간(초). 카메라·컬링이 새 상태에 안정되기를 기다린다.")]
        [SerializeField] float _settle = 0.7f;
        [Tooltip("실제로 프레임을 수집하는 시간(초).")]
        [SerializeField] float _measure = 3.3f;

        // 불꽃놀이는 축제의 핵심 연출이라 후보에서 뺀다 — 지워서 측정할 대상이 아니다.
        static readonly string[] FireworkMarks = { "fire", "firework", "불꽃", "vfx_fire" };
        static readonly string[] BalloonMarks = { "balloon", "풍선" };
        static readonly string[] TrinketMarks = { "bulb", "festoon", "confetti", "garland", "lantern" };

        UniversalRenderPipelineAsset _rp;
        float _origShadowDistance;
        int _origCascades;

        Light[] _downlights;       // @World_11F/WorldCeilingLights/Downlights — 90개, 최대 군집
        Light[] _additionalLights; // Directional 을 뺀 전부
        Renderer[] _balloons;
        Renderer[] _trinkets;
        ParticleSystemRenderer[] _particles; // 불꽃놀이 제외

        readonly List<float> _frames = new List<float>(512);
        readonly List<string> _report = new List<string>();
        ProfilerRecorder _draws, _tris;
        bool _running;
        string _status = "F9: render cost sweep";

        static readonly StringBuilder Sb = new StringBuilder(256);

        void OnEnable()
        {
            if (!PerfHud.ToolsEnabled) { enabled = false; return; }
            _rp = GraphicsSettings.currentRenderPipeline as UniversalRenderPipelineAsset;
            if (_rp != null)
            {
                _origShadowDistance = _rp.shadowDistance;
                _origCascades = _rp.shadowCascadeCount;
            }
            _draws = ProfilerRecorder.StartNew(ProfilerCategory.Render, "Draw Calls Count");
            _tris = ProfilerRecorder.StartNew(ProfilerCategory.Render, "Triangles Count");
            Collect();
            Debug.Log($"[Probe] 준비 — 다운라이트 {_downlights.Length}, 추가조명 {_additionalLights.Length}, " +
                      $"풍선 {_balloons.Length}, 잡장식 {_trinkets.Length}, 파티클(불꽃제외) {_particles.Length}. F9 로 시작.");
        }

        void OnDisable()
        {
            RestoreAll();
            _draws.Dispose(); _tris.Dispose();
        }

        /// <summary>대상 수집은 한 번만 한다 — 매 시나리오마다 씬을 훑으면 그 비용이 측정에 섞인다.</summary>
        void Collect()
        {
            var downs = new List<Light>();
            var adds = new List<Light>();
            foreach (var l in Object.FindObjectsByType<Light>(FindObjectsInactive.Exclude, FindObjectsSortMode.None))
            {
                if (l.type == LightType.Directional) continue;
                adds.Add(l);
                var p = l.transform.parent;
                if (p != null && p.name == "Downlights") downs.Add(l);
            }
            _downlights = downs.ToArray();
            _additionalLights = adds.ToArray();

            var balloons = new List<Renderer>();
            var trinkets = new List<Renderer>();
            foreach (var r in Object.FindObjectsByType<Renderer>(FindObjectsInactive.Exclude, FindObjectsSortMode.None))
            {
                if (r is ParticleSystemRenderer) continue;
                string path = PathOf(r.transform).ToLowerInvariant();
                if (HasMark(path, FireworkMarks)) continue;
                if (HasMark(path, BalloonMarks)) balloons.Add(r);
                else if (HasMark(path, TrinketMarks)) trinkets.Add(r);
            }
            _balloons = balloons.ToArray();
            _trinkets = trinkets.ToArray();

            var parts = new List<ParticleSystemRenderer>();
            foreach (var pr in Object.FindObjectsByType<ParticleSystemRenderer>(FindObjectsInactive.Exclude, FindObjectsSortMode.None))
            {
                if (HasMark(PathOf(pr.transform).ToLowerInvariant(), FireworkMarks)) continue;
                parts.Add(pr);
            }
            _particles = parts.ToArray();
        }

        static string PathOf(Transform t)
        {
            Sb.Clear();
            while (t != null) { Sb.Insert(0, "/").Insert(0, t.name); t = t.parent; }
            return Sb.ToString();
        }

        static bool HasMark(string lowerPath, string[] marks)
        {
            foreach (var m in marks) if (lowerPath.Contains(m)) return true;
            return false;
        }

        void Update()
        {
            if (Input.GetKeyDown(_startKey) && !_running) StartCoroutine(Sweep());
        }

        IEnumerator Sweep()
        {
            _running = true;
            _report.Clear();
            Debug.Log("[Probe] 스윕 시작 — 끝날 때까지 **계속 걸어다녀라**. 정지 상태는 원래 안 끊긴다.");

            yield return Run("00 기준", () => { });
            yield return Run("01 그림자 120m", () => SetShadow(120f, 2));
            yield return Run("02 그림자 60m", () => SetShadow(60f, 1));
            yield return Run("03 그림자 끔", () => SetShadow(0f, 1));
            yield return Run("04 천장 다운라이트 끔", () => SetLights(_downlights, false));
            yield return Run("05 추가조명 전부 끔", () => SetLights(_additionalLights, false));
            yield return Run("06 풍선 끔", () => SetRenderers(_balloons, false));
            yield return Run("07 잡장식 끔", () => SetRenderers(_trinkets, false));
            yield return Run("08 파티클 끔(불꽃유지)", () => SetParticles(false));
            yield return Run("09 복합: 그림자60+다운라이트+풍선", () =>
            {
                SetShadow(60f, 1);
                SetLights(_downlights, false);
                SetRenderers(_balloons, false);
            });

            Sb.Clear();
            Sb.AppendLine("[Probe] ===== 결과 (같은 이동 조건에서 비교) =====");
            foreach (var line in _report) Sb.AppendLine("  " + line);
            Debug.Log(Sb.ToString());
            _status = "sweep done - see console";
            _running = false;
        }

        /// <summary>시나리오 하나: 원복 → 적용 → 안정화 → 측정 → 기록.</summary>
        IEnumerator Run(string label, System.Action apply)
        {
            RestoreAll();
            apply();

            float t = 0f;
            while (t < _settle) { t += Time.unscaledDeltaTime; yield return null; }

            _frames.Clear();
            long drawSum = 0, triSum = 0; int n = 0;
            t = 0f;
            while (t < _measure)
            {
                float ms = Time.unscaledDeltaTime * 1000f;
                t += Time.unscaledDeltaTime;
                _frames.Add(ms);
                if (_draws.Valid) drawSum += _draws.LastValue;
                if (_tris.Valid) triSum += _tris.LastValue;
                n++;
                yield return null;
            }

            _frames.Sort();
            float p50 = Pick(0.50f), p95 = Pick(0.95f), worst = _frames[_frames.Count - 1];
            float fps = p50 > 0f ? 1000f / p50 : 0f;
            // 60fps 예산을 넘긴 프레임의 비율 — "느려졌다 빨라졌다" 는 이 비율로 드러난다.
            int over = 0;
            foreach (var f in _frames) if (f > 16.9f) over++;
            float overPct = _frames.Count > 0 ? over * 100f / _frames.Count : 0f;

            string line = string.Format(
                "{0,-32} p50 {1,5:F1}ms ({2,4:F1}fps)  p95 {3,5:F1}  worst {4,5:F1}  >16.9ms {5,4:F0}%  draws {6}  tris {7}k",
                label, p50, fps, p95, worst, overPct,
                n > 0 ? drawSum / n : 0, n > 0 ? triSum / n / 1000 : 0);
            _report.Add(line);
            _status = label;
            Debug.Log("[Probe] " + line);
        }

        float Pick(float q)
        {
            if (_frames.Count == 0) return 0f;
            int i = Mathf.Clamp(Mathf.FloorToInt(_frames.Count * q), 0, _frames.Count - 1);
            return _frames[i];
        }

        void SetShadow(float dist, int cascades)
        {
            if (_rp == null) return;
            _rp.shadowDistance = dist;
            _rp.shadowCascadeCount = Mathf.Clamp(cascades, 1, 4);
        }

        void SetLights(Light[] arr, bool on) { foreach (var l in arr) if (l != null) l.enabled = on; }
        void SetRenderers(Renderer[] arr, bool on) { foreach (var r in arr) if (r != null) r.enabled = on; }
        void SetParticles(bool on) { foreach (var p in _particles) if (p != null) p.enabled = on; }

        void RestoreAll()
        {
            if (_rp != null) { _rp.shadowDistance = _origShadowDistance; _rp.shadowCascadeCount = _origCascades; }
            if (_additionalLights != null) SetLights(_additionalLights, true);
            if (_balloons != null) SetRenderers(_balloons, true);
            if (_trinkets != null) SetRenderers(_trinkets, true);
            if (_particles != null) SetParticles(true);
        }

        // IMGUI 기본 폰트에 한글 글리프가 없어 한글 라벨은 빈칸으로 나온다 — 화면 문구만 영어로 둔다.
        void OnGUI()
        {
            if (!PerfHud.ToolsEnabled) return;
            var r = new Rect(10f, Screen.height - 26f, 700f, 20f);
            GUI.Label(r, _running ? "PROBE RUNNING - keep walking - " + _status : _status);
        }
    }
}
