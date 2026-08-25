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
        [SerializeField] KeyCode _toggleKey = KeyCode.F3;
        [SerializeField] KeyCode _resetKey = KeyCode.F4;
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

        // ── GC ──
        int _lastGcCount;
        int _gcPerWindow;
        long _heapBytes;

        // ── ProfilerRecorder — 개발 빌드/에디터에서 유효 ──
        ProfilerRecorder _drawCalls, _setPass, _batches, _tris, _sysMemory;

        static readonly StringBuilder Sb = new StringBuilder(512);
        static Texture2D _bg;
        static GUIStyle _style;

        void OnEnable()
        {
            _visible = _visibleOnStart;
            _lastGcCount = System.GC.CollectionCount(0);
            _drawCalls = ProfilerRecorder.StartNew(ProfilerCategory.Render, "Draw Calls Count");
            _setPass = ProfilerRecorder.StartNew(ProfilerCategory.Render, "SetPass Calls Count");
            _batches = ProfilerRecorder.StartNew(ProfilerCategory.Render, "Batches Count");
            _tris = ProfilerRecorder.StartNew(ProfilerCategory.Render, "Triangles Count");
            _sysMemory = ProfilerRecorder.StartNew(ProfilerCategory.Memory, "System Used Memory");
        }

        void OnDisable()
        {
            _drawCalls.Dispose(); _setPass.Dispose(); _batches.Dispose();
            _tris.Dispose(); _sysMemory.Dispose();
        }

        void Update()
        {
            if (Input.GetKeyDown(_toggleKey)) _visible = !_visible;
            if (Input.GetKeyDown(_resetKey)) ResetStats();

            float ms = Time.unscaledDeltaTime * 1000f;
            _windowFrames++;
            _windowElapsed += Time.unscaledDeltaTime;
            if (ms > _windowWorstMs) _windowWorstMs = ms;
            if (ms > _sessionWorstMs) _sessionWorstMs = ms;

            if (_windowElapsed >= _window)
            {
                _fps = _windowFrames / _windowElapsed;
                _avgMs = _windowElapsed * 1000f / _windowFrames;
                _worstMs = _windowWorstMs;

                int gc = System.GC.CollectionCount(0);
                _gcPerWindow = gc - _lastGcCount;
                _lastGcCount = gc;
                _heapBytes = System.GC.GetTotalMemory(false);

                _windowElapsed = 0f; _windowFrames = 0; _windowWorstMs = 0f;
            }
        }

        void ResetStats()
        {
            _sessionWorstMs = 0f;
            _windowElapsed = 0f; _windowFrames = 0; _windowWorstMs = 0f;
        }

        void OnGUI()
        {
            if (!_visible) return;
            EnsureStyle();

            var nm = NetworkManager.Singleton;
            Sb.Clear();
            Sb.Append("── PERF (F3 토글 / F4 리셋) ──\n");
            Sb.AppendFormat("FPS {0,6:F1}   평균 {1,5:F1} ms   최악 {2,5:F1} ms\n", _fps, _avgMs, _worstMs);
            Sb.AppendFormat("세션 최악 프레임 {0:F1} ms\n", _sessionWorstMs);
            Sb.AppendFormat("관리 힙 {0,6:F1} MB   GC/{1:F0}s {2}\n",
                _heapBytes / 1048576f, _window, _gcPerWindow);
            if (_sysMemory.Valid)
                Sb.AppendFormat("시스템 사용 메모리 {0:F0} MB\n", _sysMemory.LastValue / 1048576f);

            if (_drawCalls.Valid)
                Sb.AppendFormat("드로우콜 {0}   SetPass {1}   배치 {2}\n",
                    _drawCalls.LastValue, _setPass.Valid ? _setPass.LastValue : -1,
                    _batches.Valid ? _batches.LastValue : -1);
            if (_tris.Valid)
                Sb.AppendFormat("삼각형 {0:N0}\n", _tris.LastValue);

            if (nm != null && (nm.IsClient || nm.IsServer))
            {
                Sb.AppendFormat("접속 {0}명   스폰 오브젝트 {1}\n",
                    nm.IsServer ? nm.ConnectedClientsIds.Count : 1,
                    nm.SpawnManager != null ? nm.SpawnManager.SpawnedObjects.Count : 0);
                if (nm.IsClient && !nm.IsServer && nm.NetworkConfig?.NetworkTransport != null)
                    Sb.AppendFormat("RTT {0} ms\n",
                        nm.NetworkConfig.NetworkTransport.GetCurrentRtt(NetworkManager.ServerClientId));
            }
            else Sb.Append("네트워크 미연결\n");

            Sb.Append("네트워크 트래픽은 RNSM 오버레이 참조");

            float w = 380f * Mathf.Max(1f, Screen.height / 1080f);
            float h = 210f * Mathf.Max(1f, Screen.height / 1080f);
            var rect = new Rect(10f, 10f, w, h);
            GUI.DrawTexture(rect, Bg(), ScaleMode.StretchToFill);
            GUI.Label(new Rect(rect.x + 10f, rect.y + 8f, rect.width - 20f, rect.height - 16f), Sb.ToString(), _style);
        }

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
    }
}
