using System.Runtime.InteropServices;
using UnityEngine;

namespace Festa.Integration
{
    /// <summary>
    /// Unity → WebGL 호스트 "월드 로드 시작" 신호 (GitLab #129, S15P21A604-431).
    ///
    /// 로비에서 "월드 입장" 을 누른 직후, main 씬 <c>LoadScene</c> 바로 앞에서 1회 부른다.
    /// WebGL 에서 main 씬 로드는 50~84초가 걸리고 그동안 화면에 아무 변화가 없다 — 호스트(React)는
    /// 이 신호를 받아 "축제장을 불러오고 있어요" 안내를 띄우고, <c>onWorldGateReady</c>(입장 게이트 개방)
    /// 에서 내린다. 진행률은 주지 않는다 — 씬 로드 진행률은 WebGL 에서 믿을 수 없어 가짜 백분율이 된다.
    ///
    /// jslib 패턴은 <c>FestaNotifyWorldGateReady</c> 와 같다(인자 없음). 호스트 수신부가 없으면
    /// 콘솔 경고만 남고 진행에는 영향이 없다 — 신호가 없어도 호스트는 예전처럼 동작한다(순서 비의존).
    /// </summary>
    public static class WorldLoadSignal
    {
#if UNITY_WEBGL && !UNITY_EDITOR && !UNITY_SERVER
        [DllImport("__Internal")]
        static extern void FestaNotifyWorldLoadStart();
#endif

        public static void NotifyWorldLoadStart()
        {
#if UNITY_WEBGL && !UNITY_EDITOR && !UNITY_SERVER
            try
            {
                FestaNotifyWorldLoadStart();
            }
            catch (System.Exception ex)
            {
                // 호스트 알림 실패가 월드 진입을 막으면 안 된다.
                Debug.LogError($"[WorldLoadSignal] onWorldLoadStart 송신 실패: {ex.Message}");
            }
#else
            Debug.Log("[WorldLoadSignal] onWorldLoadStart → (에디터: 송신 생략)");
#endif
        }
    }
}
