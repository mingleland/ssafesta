namespace Festa.World.UI
{
    /// <summary>
    /// 호스트(React)가 자기 UI 를 그리는지 판정한다.
    ///
    /// <para><b>월드 조작 안내 화면은 제거됐다</b> (사용자 지시 2026-09-10). 원래 이 클래스는
    /// 첫 스폰 뒤 15초 동안 조작 카드를 띄우고, 그 뒤에는 화면 왼쪽 아래에 <c>[H] 조작 안내</c> 알약을
    /// 상주시키고, H 로 다시 여닫는 HUD 였다(S15P21A604-451). 상주 알약을 먼저 뺐고, 이어서
    /// <b>카드와 H 토글까지</b> 뺐다 — 화면을 가린다는 지적이었다.</para>
    ///
    /// <para>클래스를 지우지 않고 남긴 것은 <see cref="HostProvidesUi"/> 때문이다.
    /// <see cref="Festa.Content.BoothInteractionInput"/> 이 "FE 임베드인가" 를 이 값으로 판정한다
    /// (GitLab #141 잔상 게이트). 판정 자체는 조작 안내와 무관하게 계속 필요하다.</para>
    ///
    /// <para>되살릴 일이 생기면 이 파일의 git 이력에서 <c>DrawCard</c>·<c>DrawChip</c> 을 꺼내면 된다.
    /// 되살릴 때 <c>DiagnosticKeys</c> 의 H 키 예약도 같이 되돌려야 한다.</para>
    /// </summary>
    public static class ControlsHintHud
    {
#if UNITY_WEBGL && !UNITY_EDITOR && !UNITY_SERVER
        [System.Runtime.InteropServices.DllImport("__Internal")] static extern int FestaHostHasUi();
#endif
        static bool? s_hostUi;

        /// <summary>
        /// FE(React) 임베드인가 — <c>window.FestaUnity.onBoothInteract</c> 수신부가 있으면 FE 가 자기 화면을 그린다
        /// (2026-09-06 임베드 실측, S15P21A604-456). 단독 실행·에디터·probe 에서는 false.
        /// </summary>
        public static bool HostProvidesUi
        {
            get
            {
                if (s_hostUi.HasValue) return s_hostUi.Value;
#if UNITY_WEBGL && !UNITY_EDITOR && !UNITY_SERVER
                try { s_hostUi = FestaHostHasUi() == 1; } catch (System.Exception) { s_hostUi = false; }
#else
                s_hostUi = false;
#endif
                return s_hostUi.Value;
            }
        }
    }
}
