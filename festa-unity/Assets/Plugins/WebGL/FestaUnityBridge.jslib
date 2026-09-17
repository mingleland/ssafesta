mergeInto(LibraryManager.library, {
  // 호스트(React) UI 가 있는가 — window.FestaUnity 수신부가 있으면 FE 임베드다. Unity 는 FE 가 이미 그리는
  // 화면(조작 안내 카드 등)을 중복해서 그리지 않는다 (S15P21A604-456). 단독 실행·probe 에서는 0.
  FestaHostHasUi: function () {
    try {
      return (window.FestaUnity && typeof window.FestaUnity.onBoothInteract === 'function') ? 1 : 0;
    } catch (error) {
      return 0;
    }
  },

  // 호스트가 런타임에 주입한 API base URL — FE 의 window.__FESTA_CONFIG__(=/runtime-config.js) 와 **같은 값**을 읽는다
  // (S15P21A604-459). 없으면 0 을 돌려 Unity 가 빌드 타임 값으로 내려가게 한다. 버퍼는 호출자(C#)가 준다.
  // stringToUTF8·lengthBytesUTF8 은 Unity 의 jslib 문자열 예제가 그대로 쓰는 런타임 헬퍼다 — __deps 로 따로 걸지 않는다
  // (심볼명이 Emscripten 버전마다 달라 링크가 깨질 수 있다).
  FestaHostApiBaseUrl: function (buffer, bufferLength) {
    try {
      var cfg = window.__FESTA_CONFIG__;
      var url = (cfg && cfg.apiBaseUrl) ? String(cfg.apiBaseUrl) : '';
      if (!url) return 0;
      var needed = lengthBytesUTF8(url);
      if (needed + 1 > bufferLength) {
        console.warn('[FestaUnityBridge] apiBaseUrl 이 버퍼보다 길다 — 무시한다', needed, bufferLength);
        return 0;
      }
      stringToUTF8(url, buffer, bufferLength);
      return needed;
    } catch (error) {
      console.error('[FestaUnityBridge] apiBaseUrl 주입 읽기 실패', error);
      return 0;
    }
  },

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

  // Unity 모달 상태 (GitLab #132 G-8-2 반대 방향, 2026-09-10): '{"focus":bool,"minigame":bool}'.
  // 값이 바뀔 때만 온다. FE 는 이걸 보고 ESC 를 중재한다(FE 레이어 → Unity 모달 → GameMenu).
  // 닫기는 별도 명령 SendMessage('WorldUiBridge','RequestExitWorldUi','esc') — 상태로 남의 기능을 닫지 않는다.
  FestaNotifyWorldUiState: function (jsonPtr) {
    var json = UTF8ToString(jsonPtr);
    try {
      if (window.FestaUnity && typeof window.FestaUnity.onWorldUiState === 'function') {
        window.FestaUnity.onWorldUiState(json);
        return;
      }
      console.warn('[FestaUnityBridge] window.FestaUnity.onWorldUiState is not ready', json);
    } catch (error) {
      console.error('[FestaUnityBridge] onWorldUiState callback failed', json, error);
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
  },

  // 디스플레이의 **실제** 주사율(Hz) 추정치. 아직 표본이 모자라면 0.
  //
  // 왜 필요한가. Unity 는 WebGL 에서 Screen.currentResolution.refreshRateRatio 를 화면과 무관하게
  // 항상 60 으로 보고한다(자리표시자). 그런데 프레임 상한의 유일한 실동 노브인 QualitySettings.vSyncCount 는
  // '원시 rAF 몇 틱마다 한 프레임을 그리는가' 라서, 주사율을 모르면 목표 fps 를 정할 수 없다 —
  // 120Hz 에서 vSyncCount=2 는 60fps 지만 60Hz 에서는 30fps 다. 그래서 여기서 직접 잰다.
  //
  // Unity 의 렌더 루프와 **무관한** 자체 rAF 프로브를 돌린다. Unity 가 vSyncCount 로 스스로를 늦춰도
  // 이 프로브는 원시 vsync 간격을 계속 보므로 추정이 오염되지 않는다. 창을 다른 모니터로 옮기면
  // 값이 따라 바뀌도록 링 버퍼로 계속 갱신한다(비용은 프레임당 push 하나).
  FestaDisplayRefreshHz: function () {
    try {
      if (!window.__festaRefresh) {
        var st = { buf: [], hz: 0, prev: 0, since: 0 };
        window.__festaRefresh = st;
        var probe = function (now) {
          if (st.prev > 0) {
            var dt = now - st.prev;
            // 탭 전환·리사이즈가 만든 이상치는 버린다. 1ms 미만은 합성 이벤트, 100ms 초과는 정지다.
            if (dt > 1 && dt < 100) {
              st.buf.push(dt);
              if (st.buf.length > 180) st.buf.shift();
              st.since++;
            }
          }
          st.prev = now;
          // 60 프레임마다 다시 추정한다.
          //
          // **중앙값이 아니라 하위 10% 분위수를 쓴다.** 디스플레이는 주사율보다 빨리 틱할 수 없지만
          // 메인 스레드가 바쁘면 얼마든지 늦게 틱한다. 그래서 중앙값은 '화면 주사율' 이 아니라
          // '지금 실제로 나오는 프레임률' 을 재게 된다 — 2026-09-08 배포본 실측에서 120Hz 화면을
          // 로딩 중에 60Hz 로 잘못 읽어 상한이 풀렸다(vSyncCount 2 → 1). 가장 빠른 쪽이 진짜 주기다.
          if (st.buf.length >= 60 && st.since >= 60) {
            st.since = 0;
            var s = st.buf.slice().sort(function (a, b) { return a - b; });
            var fast = s[Math.floor(s.length * 0.10)];
            if (fast > 0) st.hz = Math.round(1000 / fast);
          }
          window.requestAnimationFrame(probe);
        };
        window.requestAnimationFrame(probe);
      }
      return window.__festaRefresh.hz | 0;
    } catch (error) {
      return 0;
    }
  },

  // ── 월드 전광판 시제품 (GitLab #194 ② 후속, 2026-09-18) ───────────────────────────────
  // Unity 가 스크린 네 꼭짓점을 **화면좌표**로 넘기면 여기서 그 사각형에 iframe 을 맞춘다.
  // 캔버스 위에 얹히는 DOM 이라 깊이가 없다 — 앞에 선 아바타를 덮는다. 그 한계를 눈으로
  // 확인하려고 만든 것이고, 판단이 서면 지우거나 정식화한다.
  FestaScreenShow: function (videoIdPtr, x0, y0, x1, y1, x2, y2, x3, y3) {
    try {
      var videoId = UTF8ToString(videoIdPtr);
      var host = window.__festaScreen;
      if (!host) {
        host = window.__festaScreen = {};
        var box = document.createElement('div');
        box.id = 'festa-world-screen';
        box.style.cssText = 'position:fixed;left:0;top:0;width:100px;height:100px;' +
          'transform-origin:0 0;pointer-events:none;z-index:5;overflow:hidden;background:#000';
        var frame = document.createElement('iframe');
        frame.width = '100'; frame.height = '100';
        frame.style.cssText = 'width:100px;height:100px;border:0;display:block';
        frame.allow = 'autoplay; encrypted-media';
        frame.setAttribute('frameborder', '0');
        box.appendChild(frame);
        document.body.appendChild(box);
        host.box = box; host.frame = frame; host.videoId = '';
      }

      if (host.videoId !== videoId) {
        host.videoId = videoId;
        // mute=1 은 선택이 아니다 — 브라우저 자동재생 정책상 소리가 있으면 재생 자체가 막힌다.
        host.frame.src = 'https://www.youtube-nocookie.com/embed/' + videoId +
          '?autoplay=1&mute=1&loop=1&controls=0&playsinline=1&modestbranding=1&rel=0&playlist=' + videoId;
      }

      // Unity 는 **정규화 좌표(0~1, 좌하단 원점)** 로 넘긴다. 캔버스의 CSS 크기·위치·devicePixelRatio 를
      // 여기서 곱해 화면 픽셀로 바꾼다 — Unity 의 Screen.width 는 드로잉 버퍼 크기라 CSS 픽셀과 다르다.
      var canvas = document.querySelector('canvas');
      if (!canvas) { host.box.style.display = 'none'; return; }
      var rect = canvas.getBoundingClientRect();
      var toPxX = function (n) { return rect.left + n * rect.width; };
      var toPxY = function (n) { return rect.top + (1 - n) * rect.height; };
      x0 = toPxX(x0); y0 = toPxY(y0);
      x1 = toPxX(x1); y1 = toPxY(y1);
      x2 = toPxX(x2); y2 = toPxY(y2);
      x3 = toPxX(x3); y3 = toPxY(y3);

      // 100×100 기준 사각형을 네 꼭짓점으로 보내는 호모그래피. CSS matrix3d 는 열 우선이다.
      var s = 100;
      var dx1 = x1 - x2, dx2 = x3 - x2, sx = x0 - x1 + x2 - x3;
      var dy1 = y1 - y2, dy2 = y3 - y2, sy = y0 - y1 + y2 - y3;
      var den = dx1 * dy2 - dx2 * dy1;
      if (!den) { host.box.style.display = 'none'; return; }
      var g = (sx * dy2 - dx2 * sy) / den;
      var h = (dx1 * sy - sx * dy1) / den;
      var a = x1 - x0 + g * x1, b = x3 - x0 + h * x3, c = x0;
      var d = y1 - y0 + g * y1, e = y3 - y0 + h * y3, f = y0;
      var m = [a / s, d / s, 0, g / s, b / s, e / s, 0, h / s, 0, 0, 1, 0, c, f, 0, 1];
      host.box.style.display = 'block';
      host.box.style.transform = 'matrix3d(' + m.join(',') + ')';
    } catch (error) {
      console.error('[FestaUnityBridge] 전광판 표시 실패', error);
    }
  },

  FestaScreenHide: function () {
    try {
      if (window.__festaScreen && window.__festaScreen.box)
        window.__festaScreen.box.style.display = 'none';
    } catch (error) { /* 숨기기 실패는 조용히 넘어가도 안전하다 */ }
  }
});
