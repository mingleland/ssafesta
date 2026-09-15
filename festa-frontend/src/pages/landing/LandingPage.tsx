// 게임 타이틀 화면 (S15P21A604-379) — 도메인 루트 `/` 진입점.
// 화면 어디를 눌러도 진행한다. 목적지는 World 다 — 로그인 후 사용자가 상주하는 기본 상태(D-08).
// 인증 판정은 가드에 위임한다: /app/world 는 guest-allowed 라 비로그인이면 RequireAuth 가
// /login(returnTo 저장)으로 보낸다.
// 세계관 비주얼(야경·부스·관람차·로고)은 이미지 자산이 정본이다 — CSS/SVG 로 재현하지 않는다(D 계열 결정).
import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { warmUpUnityAssets } from '../../unity/host/warmup';
import { unlockAndPlay } from '../../features/audio/model/screenAudio';
import { ScreenControls } from '../../features/audio/ui/ScreenControls';
// WebP 로 바꾼 이유는 크기다 (S15P21A604-733) — 배경 2.52MB→239KB, 로고 1.77MB→246KB.
// 첫 화면 표시 시간이 그대로 이 두 파일의 다운로드 시간이었다. PNG 원본은 디자인 자산으로 남겨 둔다.
import landingBackgroundUrl from '../../assets/festa/backgrounds/landing-background.webp';
import ssafestaLogoUrl from '../../assets/festa/brand/ssafesta-logo.webp';
import './LandingPage.css';

export function LandingPage() {
  const navigate = useNavigate();

  // Unity 정적 자산 warm-up 1단계 (S15P21A604-430). 첫 페인트와 경쟁하지 않게 idle 에서 시작하고,
  // 이 화면에서는 가장 작은 loader 까지만 받는다. Unity 인스턴스는 만들지 않는다 — 다운로드뿐이다.
  useEffect(() => {
    let abort: (() => void) | null = null;
    const start = () => { abort = warmUpUnityAssets('landing'); };
    const idle = window.requestIdleCallback;
    // requestIdleCallback 이 없는 브라우저(Safari 등)는 타이머로 한 틱 미룬다
    const handle = typeof idle === 'function' ? idle(start, { timeout: 2000 }) : window.setTimeout(start, 1200);
    return () => {
      if (typeof idle === 'function') window.cancelIdleCallback?.(handle as number);
      else window.clearTimeout(handle as number);
      abort?.();
    };
  }, []);

  // 시작 클릭이 곧 브라우저의 오디오 제스처다 — 자동재생 정책이 요구하는 유일한 unlock 지점이다.
  function start() {
    unlockAndPlay();
    navigate('/app/world');
  }

  return (
    <>
      {/* 전체화면 <button> 의 형제로 둔다 — 안에 넣으면 중첩 버튼이 되어 무효 마크업이다 */}
      <ScreenControls />
      <button type="button" className="landing-root" onClick={start} aria-label="화면을 클릭해 시작하기">
        {/* React 19 는 어디에 그리든 <link> 를 head 로 올린다. 배경은 이 화면의 가장 큰 자산이라
            먼저 받게 한다 — 자기 페이지 배경만 건다. */}
        <link rel="preload" as="image" href={landingBackgroundUrl} fetchPriority="high" />
        {/* width/height 는 원본 치수다. 없으면 이미지가 도착하기 전 높이가 0 이라 CTA 가 튄다(CLS). */}
        <img className="landing-bg" src={landingBackgroundUrl} alt="" width={1672} height={941} fetchPriority="high" decoding="async" />
        <img className="landing-logo" src={ssafestaLogoUrl} alt="SSAFESTA" width={1986} height={792} decoding="async" />
        <span className="landing-cta" aria-hidden="true">
          <span className="landing-cta-star">✦</span>
          화면을 클릭해 시작하기
          <span className="landing-cta-star">✦</span>
        </span>
      </button>
    </>
  );
}
