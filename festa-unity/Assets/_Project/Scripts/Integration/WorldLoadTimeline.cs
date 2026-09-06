using System.Collections.Generic;
using System.Text;
using UnityEngine;

namespace Festa.Integration
{
    /// <summary>
    /// 로비 "월드 입장" 클릭부터 입장 게이트 개방까지의 단계별 소요 시간 (S15P21A604-431).
    ///
    /// WebGL 에서 이 구간이 50~84초다. 어느 단계가 먹는지 모르면 줄일 수도 없고, "멈춘 줄 알았다" 는
    /// 사용자 보고에 답할 수도 없다. 각 단계가 지나갈 때 한 줄씩, 게이트가 열릴 때 요약 한 줄을 남긴다 —
    /// 브라우저 콘솔·Editor 로그에서 <c>[WorldLoadTimeline]</c> 로 찾는다.
    ///
    /// 측정만 한다. 진입 흐름을 바꾸지 않고, 마크가 빠져도 아무 것도 막지 않는다.
    /// 시계는 <see cref="Time.realtimeSinceStartup"/> — 씬 로드 중에도 흐른다.
    /// </summary>
    public static class WorldLoadTimeline
    {
        public const string EnterClick = "enter_click";        // 로비에서 월드 입장 클릭
        public const string MainLoaded = "main_loaded";        // main 씬 로드 완료(sceneLoaded)
        public const string SessionIssued = "session_issued";  // world-sessions 응답 수신
        public const string StartClient = "start_client";      // NetworkManager.StartClient 호출
        public const string Connected = "connected";           // 서버 승인(ClientConnected)
        public const string PlayerReady = "player_ready";      // 로컬 플레이어 스폰 확인(게이트 기준)
        public const string GateOpen = "gate_open";            // 게이트 개방 시작

        struct Mark { public string Stage; public float At; }

        static readonly List<Mark> s_marks = new List<Mark>(8);
        static bool s_active;

        /// <summary>진입 시작. 이전 기록은 버린다(재진입·재접속마다 새로 잰다).</summary>
        public static void Begin()
        {
            s_marks.Clear();
            s_active = true;
            Record(EnterClick);
        }

        /// <summary>
        /// 단계 통과를 기록한다. <see cref="Begin"/> 전이면 무시한다 — 월드 씬 단독 실행·재접속처럼
        /// 로비를 거치지 않은 경로는 이 계측의 대상이 아니다.
        /// 같은 단계가 두 번 오면 첫 번째만 남긴다(ClientConnected 는 재접속 때 다시 온다).
        /// </summary>
        public static void Record(string stage)
        {
            if (!s_active) return;
            for (int i = 0; i < s_marks.Count; i++)
                if (s_marks[i].Stage == stage) return;

            float now = Time.realtimeSinceStartup;
            s_marks.Add(new Mark { Stage = stage, At = now });

            float sinceStart = now - s_marks[0].At;
            float sincePrev = s_marks.Count > 1 ? now - s_marks[s_marks.Count - 2].At : 0f;
            Debug.Log($"[WorldLoadTimeline] {stage} +{sinceStart:F1}s (구간 {sincePrev:F1}s)");
        }

        /// <summary>게이트 개방 시점에 전체를 한 줄로 남기고 계측을 닫는다.</summary>
        public static void Finish(string reason)
        {
            if (!s_active) return;
            Record(GateOpen);
            s_active = false;

            if (s_marks.Count < 2) return;
            var sb = new StringBuilder(160);
            sb.Append("[WorldLoadTimeline] 요약 — 총 ")
              .Append((s_marks[s_marks.Count - 1].At - s_marks[0].At).ToString("F1"))
              .Append("s (").Append(reason).Append(") : ");
            for (int i = 1; i < s_marks.Count; i++)
            {
                if (i > 1) sb.Append(" → ");
                sb.Append(s_marks[i].Stage).Append(' ')
                  .Append((s_marks[i].At - s_marks[i - 1].At).ToString("F1")).Append('s');
            }
            Debug.Log(sb.ToString());
        }
    }
}
