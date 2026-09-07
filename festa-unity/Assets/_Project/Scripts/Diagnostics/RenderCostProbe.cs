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
    /// "걸으면 느려졌다 빨라졌다" 의 **원인을 실측으로 가른** 프로브 (S15P21A604-508).
    ///
    /// 왜 필요했나: HitchLogger 로 얻은 결론은 "끊긴 프레임 24건 중 20건이 34~42ms 인데
    /// 드로우콜·삼각형·GC 증분이 전부 0" 이었다. CPU 가 새로 하는 일이 없는데 프레임만 길어진다는
    /// 것은 스파이크가 아니라 **기본 비용이 16.7ms 경계에 걸쳐 있다**는 뜻이고, 그래서 vsync 가
    /// 60↔30 을 왕복한다. 찾을 것은 "튀는 순간" 이 아니라 "상시로 비싼 것" 이었다.
    ///
    /// 1차 스윕(10구성)에서 범인을 좁혔다.
    ///   기준 p50 18.0ms · 예산초과 64% · draws 988
    ///   그림자 끔        14.0ms ·  21% · draws 343   ← 그림자가 최대 상시 비용
    ///   추가조명 전부 끔  13.0ms ·   7% · draws 521   ← 조명이 그 다음
    ///   풍선 127개 끔    17.0ms ·  51% · draws 983   ← 장식은 범인이 아니다(지울 필요 없다)
    ///
    /// 2차 결정 스윕에서 조치를 확정했다.
    ///   0 기준                   p50 24.0ms · 100% · draws 987
    ///   1 그림자 60m/캐스1        p50 20.0ms ·  84% · draws 597
    ///   2 구역병합 18등(x3)       p50 16.0ms ·  41% · draws 310
    ///   3 그림자60 + 구역병합18    p50 14.0ms ·  13% · draws 127   ← 채택
    ///
    /// 채택안이 씬·RP 애셋에 반영돼 있다. 이 프로브는 **회귀 감시용**으로 남긴다 — 나중에 누가
    /// 조명이나 그림자를 되돌려 놓으면 F9 한 번으로 드러난다.
    ///
    /// F9 — 결정 스윕(4구성 × 4초 = 16초). 걸어다니는 동안 돌린다.
    /// F7 — 조명 A/B(정지 상태로 본다). 천장 다운라이트를 원본 90개 / 구역병합 18개(밝기 배율별)로
    ///      바꿔가며 멈춰 세운다. 프레임 수치가 아니라 **눈으로 보는 밝기·얼룩**을 판정하기 위한 것이다.
    ///
    /// 구역병합이 왜 품질을 해치지 않는가: 원본 90개는 8m 간격인데 조명 하나의 바닥 반경이 35m 라
    /// 한 점을 수십 개가 덮는다. 그런데 AdditionalLightsPerObjectLimit 이 4 라 오브젝트마다 그중
    /// 4개만 뽑아 쓴다 — **어느 4개가 뽑히는지가 오브젝트마다 달라 원래도 들쭉날쭉했다.**
    /// 바닥 직교 렌더로 잰 결과 병합 후 얼룩(휘도 표준편차)은 원본과 사실상 같았고(0.162→0.169),
    /// 밝기만 배율 3배로 맞추면 원본 대비 10% 안쪽이었다.
    ///
    /// 측정이 끝나면 **들어올 때의 상태로** 원복한다. 기본값을 하드코딩하지 않는다 —
    /// 그렇게 두면 기본값을 바꾼 날 프로브가 씬을 옛 상태로 되돌려 놓는다.
    /// 개발 빌드·에디터 전용.
    /// </summary>
    public class RenderCostProbe : MonoBehaviour
    {
        [SerializeField] KeyCode _sweepKey = KeyCode.F9;
        [SerializeField] KeyCode _lightAbKey = KeyCode.F7;
        [Tooltip("시나리오를 적용하고 나서 버리는 시간(초). 카메라·컬링이 새 상태에 안정되기를 기다린다.")]
        [SerializeField] float _settle = 0.7f;
        [Tooltip("실제로 프레임을 수집하는 시간(초).")]
        [SerializeField] float _measure = 3.3f;
        [Tooltip("구역병합 조명의 채택 밝기 배율. 씬에 반영된 값과 같아야 A/B 가 의미 있다.")]
        [SerializeField] float _adoptedMultiplier = 3f;

        UniversalRenderPipelineAsset _rp;
        float _origShadowDistance;
        int _origCascades;

        GameObject _downRoot;      // 원본 90등 부모 (지금은 비활성이 기본)
        GameObject _mergedRoot;    // 구역병합 18등 부모 (지금은 활성이 기본)
        Light[] _mergedLights;
        float _mergedUnitIntensity = 420f;   // 배율 1.0 에 해당하는 값

        // 들어올 때의 상태 — 측정이 끝나면 여기로 돌린다.
        bool _origDownActive, _origMergedActive;

        readonly List<float> _frames = new List<float>(512);
        readonly List<string> _report = new List<string>();
        ProfilerRecorder _draws, _tris;
        bool _running;
        string _status = "F9 sweep (16s, keep walking) / F7 light A/B (stand still)";

        int _abIndex;
        static readonly float[] AbMultipliers = { 0f, 1f, 2f, 3f, 4f };   // 0 = 원본 90등
        static readonly string[] AbNames =
            { "original 90", "merged 18 x1.0", "merged 18 x2.0", "merged 18 x3.0", "merged 18 x4.0" };

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
            Debug.Log($"[Probe] 준비 — 원본 90등 활성={_origDownActive}, 구역병합 18등 활성={_origMergedActive}, " +
                      $"채택 배율 x{_adoptedMultiplier:F1}. F9=결정 스윕(16초, 걸으면서) / F7=조명 A/B(정지)");
            // 채택안이 씬에 반영돼 있어야 정상이다. 아니면 누가 되돌려 놓은 것이니 눈에 띄게 알린다.
            if (!_origMergedActive || _origDownActive)
                Debug.LogWarning("[Probe] 조명이 채택안(구역병합 18등)과 다르다 — 회귀했는지 확인하라");
            if (_rp != null && (_origShadowDistance > 100f || _origCascades > 1))
                Debug.LogWarning($"[Probe] 그림자가 채택안(60m/캐스1)과 다르다 — 지금 {_origShadowDistance}m/캐스{_origCascades}");
        }

        void OnDisable()
        {
            RestoreAll();
            _draws.Dispose(); _tris.Dispose();
        }

        /// <summary>대상 수집은 한 번만 한다 — 매 시나리오마다 씬을 훑으면 그 비용이 측정에 섞인다.</summary>
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
                    _mergedLights = m.GetComponentsInChildren<Light>(true);   // 비활성일 수 있어 includeInactive
                }
            }
            if (_mergedLights == null) _mergedLights = new Light[0];

            _origDownActive = _downRoot != null && _downRoot.activeSelf;
            _origMergedActive = _mergedRoot != null && _mergedRoot.activeSelf;

            // 배율 1.0 의 기준값은 채택 배율로 역산한다 — 씬에 1260 이 들어 있고 채택이 x3 이면 단위는 420.
            if (_mergedLights.Length > 0 && _adoptedMultiplier > 0f)
                _mergedUnitIntensity = _mergedLights[0].intensity / _adoptedMultiplier;
        }

        void Update()
        {
            if (_running) return;
            if (Input.GetKeyDown(_sweepKey)) StartCoroutine(Sweep());
            else if (Input.GetKeyDown(_lightAbKey)) StepLightAb();
        }

        // ── F7: 조명 A/B ────────────────────────────────────────────
        // 프레임 수치가 아니라 보이는 품질을 판정하기 위한 것이다. 정지 상태로 같은 자리에서 눌러
        // 비교한다. 사용자는 앞선 최적화에서 글자가 얼룩덜룩해진 적이 있어 품질에 민감하다.

        void StepLightAb()
        {
            if (_mergedLights.Length == 0) { Debug.LogWarning("[Probe] 구역병합 리그가 씬에 없다"); return; }
            _abIndex = (_abIndex + 1) % AbMultipliers.Length;
            SetLighting(AbMultipliers[_abIndex]);
            _status = "LIGHT A/B: " + AbNames[_abIndex];
            Debug.Log($"[Probe][조명A/B] {AbNames[_abIndex]} — 같은 자리에서 밝기·얼룩을 비교하라");
        }

        /// <summary>mul 이 0 이면 원본 90등, 아니면 구역병합 18등을 그 배율로 켠다.</summary>
        void SetLighting(float mul)
        {
            bool useMerged = mul > 0f;
            if (_downRoot != null) _downRoot.SetActive(!useMerged);
            if (_mergedRoot != null) _mergedRoot.SetActive(useMerged);
            if (useMerged)
                foreach (var l in _mergedLights)
                    if (l != null) l.intensity = _mergedUnitIntensity * mul;
        }

        // ── F9: 결정 스윕 ───────────────────────────────────────────

        IEnumerator Sweep()
        {
            _running = true;
            _report.Clear();
            Debug.Log("[Probe] 결정 스윕 시작 — 16초. 끝날 때까지 **계속 걸어다녀라**.");

            yield return Run("0 되돌린 상태(원본90+그림자400)", () => { SetShadow(400f, 2); SetLighting(0f); });
            yield return Run("1 그림자만 60m/캐스1", () => { SetShadow(60f, 1); SetLighting(0f); });
            yield return Run("2 구역병합만 18등", () => { SetShadow(400f, 2); SetLighting(_adoptedMultiplier); });
            yield return Run("3 채택안(둘 다)", () => { SetShadow(60f, 1); SetLighting(_adoptedMultiplier); });

            Sb.Clear();
            Sb.AppendLine("[Probe] ===== 결정 스윕 결과 =====");
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
            int over = 0;
            foreach (var f in _frames) if (f > 16.9f) over++;
            float overPct = _frames.Count > 0 ? over * 100f / _frames.Count : 0f;

            string line = string.Format(
                "{0,-30} p50 {1,5:F1}ms ({2,4:F1}fps)  p95 {3,5:F1}  worst {4,5:F1}  >16.9ms {5,4:F0}%  draws {6}  tris {7}k",
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

        void RestoreAll()
        {
            if (_rp != null) { _rp.shadowDistance = _origShadowDistance; _rp.shadowCascadeCount = _origCascades; }
            if (_downRoot != null) _downRoot.SetActive(_origDownActive);
            if (_mergedRoot != null) _mergedRoot.SetActive(_origMergedActive);
            foreach (var l in _mergedLights)
                if (l != null) l.intensity = _mergedUnitIntensity * _adoptedMultiplier;
        }

        // IMGUI 기본 폰트에 한글 글리프가 없어 한글 라벨은 빈칸으로 나온다 — 화면 문구만 영어로 둔다.
        void OnGUI()
        {
            if (!PerfHud.ToolsEnabled) return;
            var r = new Rect(10f, Screen.height - 26f, 760f, 20f);
            GUI.Label(r, _running ? "PROBE RUNNING - keep walking - " + _status : _status);
        }
    }
}
