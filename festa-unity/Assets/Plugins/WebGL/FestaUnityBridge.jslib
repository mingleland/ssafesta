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
  }
});
