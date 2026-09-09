using System.Runtime.InteropServices;
using UnityEngine;

namespace Festa.Core
{
    /// <summary>
    /// 브라우저 주사율에 맞춰 <see cref="QualitySettings.vSyncCount"/> 를 **적응형으로** 정한다.
    /// 여유가 있으면 화면 주사율 그대로 돌고, 못 버티면 절반·1/3 로 내려온다.
    /// 목표는 "가장 빠른 프레임" 이 아니라 <b>흔들리지 않는 프레임</b>이다.
    ///
    /// <para><b>무슨 문제를 푸는가 (2026-09-08 실측).</b> 사용자 브라우저에서 rAF 간격을 3,749 프레임
    /// 수집한 결과 91.7% 가 정확히 8ms(120Hz) 아니면 17ms(60Hz) 에 붙어 있었다 — 연속 분포가 아니다.
    /// 프레임 시간이 8.33ms 를 살짝 넘는 순간 vsync 가 한 틱을 통째로 건너뛰어 <b>즉시 절반 속도</b>가
    /// 되고, 여유가 생기면 다시 올라간다. 초당 fps 가 50~107 을 오갔다. 사용자가 며칠째 말한
    /// "느려졌다 빨라졌다" 의 정체가 이 2단 양자화다. 경계선 위에 걸터앉으면 작은 변동이 2배 스윙이 된다.</para>
    ///
    /// <para><b>왜 targetFrameRate 가 아니라 vSyncCount 인가.</b> WebGL 플레이어에서
    /// <c>Application.targetFrameRate</c> 는 프레임 상한이 아니라 rAF 스왑 간격으로 번역되는데
    /// 그 식(<c>60 / targetFrameRate</c>)에 <b>60 이 상수로 박혀 있다</b>. 120Hz 화면에서
    /// <c>= 60</c> 은 스왑 간격 1 — 기본값 <c>-1</c> 과 동일하고 아무것도 바뀌지 않는다 (T-239).</para>
    ///
    /// <para><b>왜 주사율을 직접 재는가.</b> Unity 는 WebGL 에서 <c>Screen.currentResolution.refreshRateRatio</c> 를
    /// 화면과 무관하게 항상 60 으로 보고한다. jslib <c>FestaDisplayRefreshHz</c> 가 Unity 렌더 루프와
    /// 무관한 자체 rAF 프로브로 재는데, <b>하위 10% 분위수</b>를 쓴다 — 중앙값을 쓰면 로딩 중처럼
    /// 메인 스레드가 바쁠 때 "화면이 60Hz" 로 잘못 읽는다(배포본에서 실제로 발생, 상한이 풀렸다).</para>
    ///
    /// <para><b>릴리스에서도 돈다.</b> 진단 도구(PerfHud 등)는 <c>Debug.isDebugBuild</c> 게이트라
    /// 배포본에서 아무것도 안 보인다. 이 컴포넌트는 게이트가 없고, 주기적으로 프레임 백분위를
    /// 한 줄로 남긴다 — 사용자 브라우저 콘솔만으로 원격 판별이 되게 하려는 것이다.</para>
    ///
    /// <para>씬에 배치하지 않는다 — <see cref="RuntimeInitializeOnLoadMethod"/> 로 스스로 붙는다.</para>
    /// </summary>
    public class DisplayRefreshAdapter : MonoBehaviour
    {
#if UNITY_WEBGL && !UNITY_EDITOR && !UNITY_SERVER
        [DllImport("__Internal")]
        static extern int FestaDisplayRefreshHz();
#else
        static int FestaDisplayRefreshHz() => 0;
#endif

        /// <summary>끄고 싶을 때 부트스트랩보다 먼저 false 로 두면 붙지 않는다.</summary>
        public static bool Enabled = true;

        // ── 판정 상수 ────────────────────────────────────────────
        const float WarmupSeconds = 10f;   // 로딩·셰이더 컴파일이 끝나기를 기다린다. 이 전에는 아무 판단도 하지 않는다
        const float WindowSeconds = 3f;    // 한 번 판정에 쓰는 관측 창
        const float MissSlack = 1.30f;     // 예산의 130% 를 넘으면 '놓친 프레임'. 지터에 여유를 준다
        const float StepDownMissRate = 0.20f;  // 창의 20% 를 놓치면 못 버티는 것 — 내려간다
        const float StepUpMissRate = 0.02f;    // 2% 미만이 3창 연속이면 여유가 있다 — 올라간다
        const int StepUpCleanWindows = 3;
        const float CooldownAfterDown = 12f;   // 내려간 뒤 이만큼은 다시 안 건드린다
        const float CooldownAfterUp = 20f;     // 올라간 뒤는 더 길게 — 올렸다 내렸다가 제일 나쁘다
        const int MinInterval = 1, MaxInterval = 4;

        const float ReportSeconds = 30f;   // 릴리스 관측용 요약 주기
        const int RingSize = 2048;

        static readonly int[] CommonHz = { 60, 75, 90, 100, 120, 144, 165, 240 };

        int _hz;                 // 확정된 화면 주사율
        int _interval = -1;      // 현재 vSyncCount. -1 = 아직 결정 전
        float _cooldownUntil;
        int _cleanWindows;

        // 관측 창
        float _windowStart;
        int _windowFrames, _windowMisses;

        // 릴리스 요약용 링 버퍼
        readonly float[] _ring = new float[RingSize];
        int _ringCount, _ringHead;
        float _nextReport;
        int _gcAtLastReport;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Install()
        {
#if UNITY_WEBGL && !UNITY_EDITOR && !UNITY_SERVER
            if (!Enabled) return;
            var go = new GameObject("@DisplayRefreshAdapter");
            go.AddComponent<DisplayRefreshAdapter>();
            DontDestroyOnLoad(go);
#endif
        }

        void Start()
        {
            _windowStart = Time.unscaledTime;
            _nextReport = Time.unscaledTime + WarmupSeconds + ReportSeconds;
            _gcAtLastReport = System.GC.CollectionCount(0);
        }

        void Update()
        {
            float dt = Time.unscaledDeltaTime;
            _ring[_ringHead] = dt;
            _ringHead = (_ringHead + 1) % RingSize;
            if (_ringCount < RingSize) _ringCount++;

            float now = Time.unscaledTime;
            if (now < WarmupSeconds) { _windowStart = now; return; }   // 로딩 구간은 판단 대상이 아니다

            ResolveRefreshRate();
            if (_hz <= 0) return;

            if (_interval < 0)
            {
                // 첫 결정 — **화면 주사율 그대로 시작한다.** 버틸 수 있으면 그대로 두고,
                // 못 버티면 아래 판정이 내려 준다. 처음부터 60 으로 묶으면 성능을 버리게 된다.
                Apply(1, $"주사율 {_hz}Hz 확정 — 우선 전속으로 시작한다");
                _windowStart = now; _windowFrames = _windowMisses = 0;
            }

            // 창 집계
            float budgetMs = 1000f / _hz * _interval;
            _windowFrames++;
            if (dt * 1000f > budgetMs * MissSlack) _windowMisses++;

            if (now - _windowStart >= WindowSeconds)
            {
                Evaluate(now, budgetMs);
                _windowStart = now; _windowFrames = _windowMisses = 0;
            }

            if (now >= _nextReport) { Report(budgetMs); _nextReport = now + ReportSeconds; }
        }

        /// <summary>jslib 추정치를 흔한 주사율로 스냅한다. 119·121 같은 값이 매번 다른 판정을 만들지 않게.</summary>
        void ResolveRefreshRate()
        {
            int raw = FestaDisplayRefreshHz();
            if (raw < 30 || raw > 400) return;

            int snapped = raw;
            foreach (var c in CommonHz)
                if (Mathf.Abs(raw - c) <= Mathf.Max(3, c * 0.06f)) { snapped = c; break; }   // 125 → 120 도 스냅 (6%)

            if (snapped == _hz) return;

            // **내려가는 변화는 우리가 무거울 때는 믿지 않는다.** 프로브는 메인 스레드가 비어야 화면 틱을
            // 볼 수 있다. 프레임이 18ms 씩 걸리면 rAF 간격도 16.7ms 배수로만 찍혀 120Hz 화면을 60Hz 로
            // 읽는다. 2026-09-09 릴리스 실측: 120→60→120 을 6초 주기로 왕복하며 vSyncCount 가 1↔2 로
            // 튀었다 — 사용자가 며칠째 겪은 "느려졌다 빨라졌다" 를 이 장치가 스스로 만들어 낼 판이었다.
            // 그래서 하향은 **지금 창의 놓친 프레임이 거의 없을 때(프레임이 싸서 프로브가 믿을 만할 때)** 만
            // 받는다. 상향(60→120)은 부하로는 만들 수 없는 값이라 항상 받는다.
            if (_hz > 0 && snapped < _hz)
            {
                float missRate = _windowFrames > 0 ? (float)_windowMisses / _windowFrames : 1f;
                if (_interval < 0 || missRate > StepUpMissRate * 2.5f)
                {
                    if (now_LogThrottle(snapped))
                        Debug.Log($"[DisplayRefreshAdapter] 주사율 {snapped}Hz 로 읽혔지만 무시 — 프레임이 무거워(창 미스 {missRate * 100f:F0}%) 프로브를 믿을 수 없다. {_hz}Hz 유지");
                    return;
                }
            }

            // 주사율이 실제로 바뀌었다(다른 모니터로 창 이동). 처음부터 다시 판단한다.
            if (_hz > 0) Debug.Log($"[DisplayRefreshAdapter] 주사율 변경 {_hz}Hz → {snapped}Hz — 판정을 다시 시작한다");
            _hz = snapped;
            _interval = -1;
            _cleanWindows = 0;
            _cooldownUntil = 0f;
        }

        int _ignoredHzLogged;
        float _ignoredHzLogAt;

        /// <summary>무시한 하향 판독은 같은 값이면 30초에 한 번만 남긴다 — 매 프레임 찍으면 콘솔이 잠긴다.</summary>
        bool now_LogThrottle(int snapped)
        {
            float t = Time.unscaledTime;
            if (snapped == _ignoredHzLogged && t - _ignoredHzLogAt < 30f) return false;
            _ignoredHzLogged = snapped; _ignoredHzLogAt = t;
            return true;
        }

        void Evaluate(float now, float budgetMs)
        {
            if (_windowFrames < 30) return;                 // 표본이 모자라면 판단하지 않는다
            float missRate = _windowMisses / (float)_windowFrames;

            if (now < _cooldownUntil) return;

            if (missRate > StepDownMissRate && _interval < MaxInterval)
            {
                _cleanWindows = 0;
                Apply(_interval + 1,
                    $"예산 {budgetMs:F1}ms 를 {missRate * 100f:F0}% 놓쳤다 — 못 버틴다, 한 단계 내린다");
                _cooldownUntil = now + CooldownAfterDown;
                return;
            }

            if (missRate < StepUpMissRate && _interval > MinInterval)
            {
                // **올라간 뒤의 예산으로도 버틸 수 있는가** 를 본다. 지금 예산(16.7ms)을 1.3배 여유 안에서 지키는
                // 17ms 프레임은 올라간 예산(8.3ms)에서는 전부 미스다 — 2026-09-09 릴리스 d1165eb3 실측: vSync 2 에서
                // 미스 1.1% 라 1 로 올리고, 3초 뒤 100% 미스로 다시 2 로. 23초 주기로 3초씩 120fps 를 헛도는 왕복이었다.
                float nextBudgetMs = 1000f / _hz * (_interval - 1);
                float nextMiss = MissRateAgainst(nextBudgetMs * MissSlack, _windowFrames);
                if (nextMiss > StepUpMissRate) { _cleanWindows = 0; return; }   // 올려도 못 버틴다 — 지금 자리가 착지점

                if (++_cleanWindows < StepUpCleanWindows) return;
                _cleanWindows = 0;
                Apply(_interval - 1,
                    $"{StepUpCleanWindows}창 연속 놓친 프레임 {missRate * 100f:F1}%, 올라간 예산 {nextBudgetMs:F1}ms 기준도 {nextMiss * 100f:F1}% — 여유가 있다, 한 단계 올린다");
                _cooldownUntil = now + CooldownAfterUp;
                return;
            }

            _cleanWindows = 0;
        }

        /// <summary>링 버퍼의 최근 <paramref name="frames"/>개 중 <paramref name="limitMs"/> 를 넘은 비율.</summary>
        float MissRateAgainst(float limitMs, int frames)
        {
            int n = Mathf.Min(frames, _ringCount);
            if (n == 0) return 1f;
            int miss = 0;
            for (int i = 1; i <= n; i++)
            {
                int idx = (_ringHead - i + RingSize) % RingSize;
                if (_ring[idx] * 1000f > limitMs) miss++;
            }
            return miss / (float)n;
        }

        void Apply(int interval, string why)
        {
            interval = Mathf.Clamp(interval, MinInterval, MaxInterval);
            int before = QualitySettings.vSyncCount;
            QualitySettings.vSyncCount = interval;
            _interval = interval;
            Debug.Log($"[DisplayRefreshAdapter] vSyncCount {before} → {interval} " +
                      $"(화면 {_hz}Hz → 목표 {_hz / (float)interval:F0}fps). {why}");
        }

        /// <summary>릴리스에서도 남는 관측 한 줄. 30초에 한 번, 정렬 한 번 — 프레임당 비용 없음.</summary>
        void Report(float budgetMs)
        {
            if (_ringCount < 60) return;
            var buf = new float[_ringCount];
            System.Array.Copy(_ring, buf, _ringCount);
            System.Array.Sort(buf);
            float P(float q) => buf[Mathf.Clamp(Mathf.FloorToInt(_ringCount * q), 0, _ringCount - 1)] * 1000f;

            int gc = System.GC.CollectionCount(0);
            // 릴리스에서 유일하게 살아 있는 프레임 관측 줄이다(QA #6). 힛치 수(50ms 초과)와 최근 위치를 함께 남겨
            // "느리다" 신고를 로그 한 줄로 재현 조건까지 좁힐 수 있게 한다. 진단 6종은 릴리스에서 꺼져 있다.
            int hitches = 0; foreach (var f in buf) if (f > 0.050f) hitches++;
            var cam = Camera.main; string where = cam != null ? cam.transform.position.ToString("F0") + " yaw" + cam.transform.eulerAngles.y.ToString("F0") : "-";
            Debug.Log($"[Festa/프레임] {_hz}Hz vSync{_interval} 예산 {budgetMs:F1}ms | " +
                      $"p50 {P(0.5f):F1} p95 {P(0.95f):F1} p99 {P(0.99f):F1} 최대 {P(1f):F1} ms | " +
                      $"힛치(>50ms) {hitches} | 표본 {_ringCount} | GC {gc - _gcAtLastReport}회/{ReportSeconds:F0}초 | 위치 {where}");
            _gcAtLastReport = gc;
        }
    }
}
