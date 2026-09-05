mergeInto(LibraryManager.library, {
  FestaNotifyBoothInteract: function (jsonPtr) {
    var json = UTF8ToString(jsonPtr);
    try {
      if (window.FestaUnity && typeof window.FestaUnity.onBoothInteract === 'function') {
        window.FestaUnity.onBoothInteract(json);
        return;
      }
      console.warn('[FestaUnityBridge] window.FestaUnity.onBoothInteract is not ready', json);
    } catch (error) {
      console.error('[FestaUnityBridge] onBoothInteract callback failed', json, error);
    }
  },

  // 입장 게이트가 열린 순간 호스트에 알린다 (spec 002 FR-014, GitLab Issue #31).
  // 호스트는 이 신호를 받으면 자기 로딩 오버레이를 내린다. 신호가 끝내 오지 않는 경우를
  // 대비해 호스트는 별도 타임아웃(잠정 60초 — Unity 강제 개방 30초보다 길게)을 둔다.
  FestaNotifyWorldGateReady: function () {
    try {
      if (window.FestaUnity && typeof window.FestaUnity.onWorldGateReady === 'function') {
        window.FestaUnity.onWorldGateReady();
        return;
      }
      console.warn('[FestaUnityBridge] window.FestaUnity.onWorldGateReady is not ready');
    } catch (error) {
      console.error('[FestaUnityBridge] onWorldGateReady callback failed', error);
    }
  },

  // 로비에서 "월드 입장" 직후, main 씬 LoadScene 직전에 1회 (GitLab #129, S15P21A604-431).
  // 호스트는 이 신호로 "축제장을 불러오고 있어요" 안내를 띄우고 onWorldGateReady 에서 내린다.
  // 수신부가 없으면 경고만 — 신호 유무에 호스트 동작이 묶이지 않는다.
  // 월드 접속 상태 (GitLab #131, S15P21A604-432): 'connected' | 'disconnected' | 'reconnecting' | 'failed'.
  // detail 은 서버 사유 문자열(INVALID_TOKEN·SERVER_FULL·REPLACED_BY_SAME_USER·빈 문자열=무응답) 또는 시도 횟수.
  // 백그라운드 탭에서 rAF 가 멈춰 끊긴 뒤 복귀하면 Unity 가 자동 재접속을 시도하며 이 상태를 밀어 준다.
  FestaNotifyWorldConnectionState: function (statePtr, detailPtr) {
    var state = UTF8ToString(statePtr);
    var detail = UTF8ToString(detailPtr);
    try {
      if (window.FestaUnity && typeof window.FestaUnity.onWorldConnectionState === 'function') {
        window.FestaUnity.onWorldConnectionState(state, detail);
        return;
      }
      console.warn('[FestaUnityBridge] window.FestaUnity.onWorldConnectionState is not ready', state, detail);
    } catch (error) {
      console.error('[FestaUnityBridge] onWorldConnectionState callback failed', state, detail, error);
    }
  },

  FestaNotifyWorldLoadStart: function () {
    try {
      if (window.FestaUnity && typeof window.FestaUnity.onWorldLoadStart === 'function') {
        window.FestaUnity.onWorldLoadStart();
        return;
      }
      console.warn('[FestaUnityBridge] window.FestaUnity.onWorldLoadStart is not ready');
    } catch (error) {
      console.error('[FestaUnityBridge] onWorldLoadStart callback failed', error);
    }
  }
});
