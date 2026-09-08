using System.Runtime.InteropServices;
using UnityEngine;

namespace Festa.Core
{
    /// <summary>
    /// 브라우저의 **실제** 주사율에 맞춰 <see cref="QualitySettings.vSyncCount"/> 를 정한다.
    /// 목표는 "가장 빠른 프레임" 이 아니라 <b>흔들리지 않는 프레임</b>이다.
    ///
    /// <para><b>무슨 문제를 푸는가 (2026-09-08 실측).</b> 사용자 브라우저에서 rAF 간격을 3,749 프레임 수집한 결과
    /// 91.7% 가 정확히 8ms(120Hz) 아니면 17ms(60Hz) 에 붙어 있었다 — 연속 분포가 아니다. 즉 렌더가 서서히
    /// 느려지는 것이 아니라, 프레임 시간이 8.33ms 를 살짝 넘는 순간 vsync 가 통째로 한 틱을 건너뛰어
    /// <b>즉시 절반 속도</b>가 된다. 여유가 생기면 다시 올라간다. 초당 fps 가 50~107 을 오갔다.
    /// 사용자가 며칠째 말한 "느려졌다 빨라졌다" 의 정체가 이 2단 양자화다.
    /// 지금 이 게임은 120Hz 경계선 위에 걸터앉아 있어서 작은 부하 변동이 2배 스윙으로 증폭된다.</para>
    ///
    /// <para><b>왜 targetFrameRate 가 아니라 vSyncCount 인가.</b> WebGL 플레이어에서
    /// <c>Application.targetFrameRate</c> 는 프레임 상한이 아니라 rAF 스왑 간격으로 번역되는데
    /// 그 식(<c>60 / targetFrameRate</c>)에 <b>60 이 상수로 박혀 있다</b>. 그래서 120Hz 화면에서
    /// <c>targetFrameRate = 60</c> 은 스왑 간격 1 — 지금의 <c>-1</c> 과 동일하고 아무것도 바뀌지 않는다.
    /// 실제로 듣는 노브는 <c>vSyncCount</c>(= 원시 rAF 몇 틱마다 한 프레임) 하나다.</para>
    ///
    /// <para><b>왜 주사율을 직접 재는가.</b> Unity 는 WebGL 에서 <c>Screen.currentResolution.refreshRateRatio</c> 를
    /// 화면과 무관하게 항상 60 으로 보고한다(자리표시자라 신뢰할 수 없다). 주사율을 모르면 vSyncCount 를 정할 수 없다 —
    /// 120Hz 에서 2 는 60fps 지만 60Hz 에서 2 는 30fps 다. 그래서 jslib 의 <c>FestaDisplayRefreshHz</c> 가
    /// Unity 렌더 루프와 무관한 자체 rAF 프로브로 원시 vsync 간격을 재고, 여기서 그 값을 쓴다.
    /// 창을 다른 모니터로 옮기면 추정치가 따라 바뀌므로 주기적으로 다시 확인한다.</para>
    ///
    /// <para>씬에 배치하지 않는다 — <see cref="RuntimeInitializeOnLoadMethod"/> 로 스스로 붙는다.
    /// 진단 도구가 아니라 상시 동작이므로 개발 빌드 게이트를 두지 않는다.</para>
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

        /// <summary>목표 프레임률. 주사율을 이 값으로 나눠 스왑 간격을 정한다.</summary>
        const float TargetFps = 60f;

        const float PollSeconds = 2f;
        const int MinPlausibleHz = 30;
        const int MaxPlausibleHz = 400;

        float _nextPoll;
        int _lastHz;
        int _appliedInterval = -1;

        // 적용 직후 실제로 먹었는지 확인하려고 한 번만 재는 자기 검증.
        bool _verifying;
        int _verifyFrames;
        float _verifyAccum;

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

        void Update()
        {
            if (_verifying) Verify();

            if (Time.unscaledTime < _nextPoll) return;
            _nextPoll = Time.unscaledTime + PollSeconds;

            int hz = FestaDisplayRefreshHz();
            if (hz < MinPlausibleHz || hz > MaxPlausibleHz) return;   // 아직 표본 부족이거나 이상치

            // 주사율이 그대로면 다시 계산할 것이 없다.
            if (hz == _lastHz && _appliedInterval > 0) return;
            _lastHz = hz;

            int interval = Mathf.Clamp(Mathf.RoundToInt(hz / TargetFps), 1, 4);
            if (interval == _appliedInterval) return;

            int before = QualitySettings.vSyncCount;
            QualitySettings.vSyncCount = interval;
            _appliedInterval = interval;

            Debug.Log($"[DisplayRefreshAdapter] 주사율 {hz}Hz 측정 → vSyncCount {before} → {interval} " +
                      $"(목표 {hz / (float)interval:F0}fps). targetFrameRate 는 WebGL 에서 듣지 않아 쓰지 않는다.");

            _verifying = true;
            _verifyFrames = 0;
            _verifyAccum = 0f;
        }

        /// <summary>적용이 실제로 프레임 간격을 바꿨는지 120 프레임 평균으로 확인해 한 줄 남긴다.</summary>
        void Verify()
        {
            _verifyAccum += Time.unscaledDeltaTime;
            if (++_verifyFrames < 120) return;

            _verifying = false;
            float meanMs = _verifyAccum / _verifyFrames * 1000f;
            float expectedMs = _lastHz > 0 ? 1000f / (_lastHz / (float)_appliedInterval) : 0f;
            Debug.Log($"[DisplayRefreshAdapter] 적용 후 실측 — 평균 프레임 {meanMs:F1}ms " +
                      $"({1000f / meanMs:F0}fps), 기대 {expectedMs:F1}ms. " +
                      (Mathf.Abs(meanMs - expectedMs) <= 3f
                          ? "기대와 일치 — vSyncCount 가 먹었다."
                          : "기대와 다르다 — vSyncCount 가 안 먹었거나 렌더가 그 간격을 못 맞춘다."));
        }
    }
}
