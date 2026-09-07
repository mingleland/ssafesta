using System.Text;
using Unity.Profiling;
using UnityEngine;

namespace Festa.Diagnostics
{
    /// <summary>
    /// 프레임 끊김(hitch)이 난 **그 순간의 컨텍스트**를 로그로 남긴다 (S15P21A604-493).
    ///
    /// 왜 필요한가: 브라우저에서 rAF 간격만 재면 "언제" 끊겼는지는 알아도 "무엇 때문"인지
    /// 모른다. 실측에서 정지 20초는 max 25 ms 로 완벽한데 이동·회전 23.8초에서는
    /// 33 ms 초과가 27회, 100 ms 초과가 3회(최대 158 ms) 났다. 걸을 때만 터진다는 것까지는
    /// 알았지만 원인을 못 갈랐다.
    ///
    /// 브라우저에서 console·fetch 를 후킹해 조사하는 방법은 쓰지 않는다 — 후킹 자체가 부하가
    /// 돼서 켰을 때와 껐을 때 측정값이 달랐다. 계측은 유니티 안에서 하고 브라우저는 로그만 받는다.
    ///
    /// 무엇을 찍나: 끊긴 프레임의 길이와 함께, **직전 프레임 대비 무엇이 늘었는지**를 찍는다.
    ///   · 드로우콜·삼각형 증분 → 시야에 새 오브젝트가 들어온 렌더 급증인가
    ///   · GC 발생·힙 증분      → 할당이 몰려 수집이 돈 것인가
    ///   · 플레이어 위치·시야   → 어느 자리에서 나는가 (재현 지점을 찾는다)
    /// 이 넷이 갈라 주지 못하면 원인은 그 밖(텍스처 업로드·에셋 첫 로드)에 있다는 뜻이고,
    /// 그때는 다른 도구가 필요하다.
    ///
    /// 개발 빌드·에디터 전용. 로그는 브라우저 콘솔로 나가므로 그대로 수집해 분석한다.
    /// </summary>
    public class HitchLogger : MonoBehaviour
    {
        [Tooltip("이 시간을 넘긴 프레임을 끊김으로 보고 기록한다(ms). 60fps 예산 16.7ms 의 두 배.")]
        [SerializeField] float _thresholdMs = 33.3f;
        [Tooltip("연속으로 쏟아지는 것을 막는 최소 간격(초). 0 이면 전부 찍는다.")]
        [SerializeField] float _minInterval = 0.15f;
        [Tooltip("이 시간을 넘긴 프레임 간격은 끊김이 아니라 **정지**로 본다(ms). 탭 전환·창 최소화.")]
        [SerializeField] float _pauseMs = 1000f;
        [SerializeField] KeyCode _toggleKey = KeyCode.F8;

        bool _enabled = true;
        float _lastLogTime;
        int _count;

        // 브라우저가 탭을 백그라운드로 돌리면 rAF 가 멈춘다. 다시 앞으로 오면 그 공백이 통째로
        // 한 프레임의 deltaTime 으로 들어와 "42초 끊김" 같은 값이 찍힌다 — 실측에서 42,583ms 와
        // 15,903ms 가 그렇게 잡혔고, 둘 다 draws·tris 증분이 0 이었다(아무것도 그리지 않았다는 뜻).
        // 이걸 끊김으로 세면 통계가 통째로 망가지므로 정지로 분류하고 카운트에서 뺀다.
        bool _skipNextFrame;
        int _pauseCount;

        // 직전 프레임 값 — 증분을 내려면 이전 값을 들고 있어야 한다.
        long _prevDraws, _prevTris, _prevHeap;
        int _prevGc;

        ProfilerRecorder _draws, _tris;
        static readonly StringBuilder Sb = new StringBuilder(256);

        void OnEnable()
        {
            if (!PerfHud.ToolsEnabled) { enabled = false; return; }
            _draws = ProfilerRecorder.StartNew(ProfilerCategory.Render, "Draw Calls Count");
            _tris = ProfilerRecorder.StartNew(ProfilerCategory.Render, "Triangles Count");
            _prevGc = System.GC.CollectionCount(0);
            _prevHeap = System.GC.GetTotalMemory(false);
            Application.focusChanged += OnFocusChanged;
            Debug.Log($"[HitchLogger] 준비 — 임계 {_thresholdMs:F0}ms, 정지 임계 {_pauseMs:F0}ms, F8 로 토글");
        }

        void OnDisable()
        {
            Application.focusChanged -= OnFocusChanged;
            _draws.Dispose(); _tris.Dispose();
        }

        /// <summary>포커스가 돌아온 직후 한 프레임은 버린다 — 그 프레임의 간격은 자리를 비운 시간이다.</summary>
        void OnFocusChanged(bool focused)
        {
            if (focused) _skipNextFrame = true;
        }

        void LateUpdate()
        {
            if (Input.GetKeyDown(_toggleKey))
            {
                _enabled = !_enabled;
                Debug.Log($"[HitchLogger] {(_enabled ? "ON" : "OFF")} (지금까지 {_count}건)");
            }

            long draws = _draws.Valid ? _draws.LastValue : -1;
            long tris = _tris.Valid ? _tris.LastValue : -1;
            int gc = System.GC.CollectionCount(0);
            long heap = System.GC.GetTotalMemory(false);

            float ms = Time.unscaledDeltaTime * 1000f;

            // 포커스 복귀 직후 프레임, 그리고 정지 임계를 넘긴 간격은 끊김이 아니다.
            // 정지도 로그로는 남긴다 — 측정 구간에 자리를 비웠다는 사실 자체가 해석에 필요하다.
            if (_skipNextFrame || ms > _pauseMs)
            {
                if (ms > _pauseMs)
                {
                    _pauseCount++;
                    Debug.Log($"[HitchLogger][정지#{_pauseCount}] {ms / 1000f:F1}초 — 끊김 아님" +
                              $"(탭 전환·최소화 추정, draws 증분 {draws - _prevDraws}, tris 증분 {tris - _prevTris})");
                }
                _skipNextFrame = false;
                _prevDraws = draws; _prevTris = tris; _prevGc = gc; _prevHeap = heap;
                return;
            }

            bool hitch = _enabled && ms > _thresholdMs
                         && (_minInterval <= 0f || Time.unscaledTime - _lastLogTime >= _minInterval);

            if (hitch)
            {
                _lastLogTime = Time.unscaledTime;
                _count++;

                Sb.Clear();
                Sb.AppendFormat("[Hitch#{0}] {1:F0}ms", _count, ms);
                // 증분이 핵심이다. 절대값만으로는 "원래 많은 자리" 와 "방금 늘어난 자리" 를 못 가른다.
                if (draws >= 0) Sb.AppendFormat(" draws {0}({1:+#;-#;0})", draws, draws - _prevDraws);
                if (tris >= 0) Sb.AppendFormat(" tris {0}({1:+#;-#;0})", tris, tris - _prevTris);
                Sb.AppendFormat(" gc {0}", gc - _prevGc);
                Sb.AppendFormat(" heap {0:F1}MB({1:+0.0;-0.0;0})",
                    heap / 1048576f, (heap - _prevHeap) / 1048576f);

                var ear = LocalPlayerPos();
                if (ear.HasValue)
                    Sb.AppendFormat(" pos({0:F0},{1:F0},{2:F0})", ear.Value.x, ear.Value.y, ear.Value.z);
                var cam = Camera.main;
                if (cam != null) Sb.AppendFormat(" yaw {0:F0}", cam.transform.eulerAngles.y);

                Debug.Log(Sb.ToString());
            }

            _prevDraws = draws; _prevTris = tris; _prevGc = gc; _prevHeap = heap;
        }

        /// <summary>재현 지점을 찾으려면 어디서 났는지가 있어야 한다. 없으면 위치는 생략한다.</summary>
        static Vector3? LocalPlayerPos()
        {
            var nm = Unity.Netcode.NetworkManager.Singleton;
            var obj = (nm != null && nm.IsClient) ? nm.LocalClient?.PlayerObject : null;
            if (obj != null) return obj.transform.position;
            var cam = Camera.main;
            return cam != null ? cam.transform.position : (Vector3?)null;
        }
    }
}
