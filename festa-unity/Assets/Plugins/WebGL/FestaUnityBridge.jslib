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
  }
});
