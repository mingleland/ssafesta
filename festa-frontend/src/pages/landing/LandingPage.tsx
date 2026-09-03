// 게임 타이틀 화면 (S15P21A604-379) — 도메인 루트 `/` 진입점.
// 화면 어디를 눌러도 진행한다. 목적지는 World 다 — 로그인 후 사용자가 상주하는 기본 상태(D-08).
// 인증 판정은 가드에 위임한다: /app/world 는 guest-allowed 라 비로그인이면 RequireAuth 가
// /login(returnTo 저장)으로 보낸다.
// 세계관 비주얼(야경·부스·관람차·로고)은 이미지 자산이 정본이다 — CSS/SVG 로 재현하지 않는다(D 계열 결정).
import { useNavigate } from 'react-router-dom';
import landingBackgroundUrl from '../../assets/festa/backgrounds/landing-background.png';
import ssafestaLogoUrl from '../../assets/festa/brand/ssafesta-logo.png';
import './LandingPage.css';

export function LandingPage() {
  const navigate = useNavigate();

  return (
    <button type="button" className="landing-root" onClick={() => navigate('/app/world')} aria-label="화면을 클릭해 시작하기">
      <img className="landing-bg" src={landingBackgroundUrl} alt="" />
      <img className="landing-logo" src={ssafestaLogoUrl} alt="SSAFESTA" />
      <span className="landing-cta" aria-hidden="true">
        <span className="landing-cta-star">✦</span>
        화면을 클릭해 시작하기
        <span className="landing-cta-star">✦</span>
      </span>
    </button>
  );
}
