// 화면 BGM 을 브라우저 제스처·Unity 생명주기에 잇는 자리 (S15P21A604-463).
// 렌더하는 것이 없다 — 라우터 **밖**(AppProviders)에 마운트해 / → /login → /app/world 전환에도
// 구독이 끊기지 않게 한다. 재생 시작 지점은 화면이 정한다(Landing 첫 클릭 · Login 진입).
import { useEffect } from 'react';
import { subscribeWorldGateReady, subscribeWorldLoadStart } from '../../../unity/bridge/events';
import { beginWorldTransition, handOffToWorld, unlockAndPlay, useScreenAudio } from '../model/screenAudio';

export function ScreenAudioController() {
  const { pendingGesture } = useScreenAudio();

  useEffect(() => {
    // onWorldLoadStart 는 기록만 하고, 11F 진입이 확정되는 onWorldGateReady 에서 이관한다.
    const stopLoadStart = subscribeWorldLoadStart(beginWorldTransition);
    const stopGateReady = subscribeWorldGateReady(handOffToWorld);
    return () => {
      stopLoadStart();
      stopGateReady();
    };
  }, []);

  // 자동재생이 거부된 상태(/login 딥링크 등)라면 다음 상호작용에서 다시 시도한다.
  // once 를 쓰지 않는다 — 재시도가 또 거부되면 pendingGesture 가 그대로라 effect 가 다시 돌지
  // 않고, once 였다면 리스너까지 사라져 영영 못 켠다.
  useEffect(() => {
    if (!pendingGesture) return;
    const retry = () => unlockAndPlay();
    window.addEventListener('pointerdown', retry);
    window.addEventListener('keydown', retry);
    return () => {
      window.removeEventListener('pointerdown', retry);
      window.removeEventListener('keydown', retry);
    };
  }, [pendingGesture]);

  return null;
}
