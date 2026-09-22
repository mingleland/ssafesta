// 라우터 안에서 끝나는 두 가지 실패 화면 — 없는 주소(NotFound)와 렌더·로딩 오류(AppError).
//
// 둘 다 React Router 기본 영어 화면("Unexpected Application Error! / Hey developer 👋")을
// 사용자에게서 걷어내는 것이 목적이다. 기본 화면은 개발자를 향한 문구라 제품 화면이 아니다.
//
// 표면은 Unity 부팅 게이트(UnityHost 의 `uh-boot-panel`)를 그대로 재사용한다 — 로고 패널이
// 떴다가 사라진 자리에 다른 디자인이 나오면 그게 더 고장 나 보인다. 진행 바는 여기 두지 않는다:
// 게이지는 "진행 중"의 표시라 오류 화면에서는 거짓말이 된다.
//
// CSS 는 UnityHost(lazy chunk) 소유라 이 파일이 반드시 같이 import 한다 — World 에 들어가기도
// 전에 404 에 떨어지면 그 chunk 의 스타일이 없어 벌거벗은 글자로 렌더된다.
import { useEffect } from 'react';
import { useNavigate, useRouteError } from 'react-router-dom';
import landingBackgroundUrl from '../../assets/festa/backgrounds/landing-background.webp';
import ssafestaLogoUrl from '../../assets/festa/brand/ssafesta-logo.webp';
import '../../unity/host/unityHostStatus.css';

/**
 * 부팅 게이트와 같은 카드 — 로고·제목·안내·버튼만 갈아 끼워 쓴다.
 *
 * `hint` 는 ReactNode 다 — 한국어는 기본 줄바꿈이 글자 단위라 한 문장으로 넘기면
 * "이동되었을 수 / 있습니다" 가 어긋나게 끊긴다. 절 경계에서 `<br />` 로 끊어 넘긴다.
 */
function BootPanel({ title, hint, children }: { title: string; hint: React.ReactNode; children?: React.ReactNode }) {
  return (
    <div className="uh-status uh-status--boot">
      <img className="uh-boot-bg" src={landingBackgroundUrl} alt="" />
      <div className="uh-boot-panel">
        <img className="uh-boot-logo" src={ssafestaLogoUrl} alt="SSAFESTA" />
        <strong className="uh-status-title">{title}</strong>
        <span className="uh-status-hint">{hint}</span>
        {children}
      </div>
    </div>
  );
}

/** 어떤 라우트에도 걸리지 않은 주소. splat(`path: '*'`) 이 여기로 보낸다. */
export function NotFound() {
  const navigate = useNavigate();
  return (
    <BootPanel
      title="페이지를 찾을 수 없습니다"
      hint={<>요청하신 페이지가 존재하지 않거나<br />이동되었을 수 있습니다.</>}
    >
      <button type="button" className="uh-status-action" onClick={() => navigate('/', { replace: true })}>
        홈으로 이동
      </button>
    </BootPanel>
  );
}

/**
 * pathless 루트 라우트의 `errorElement`. 라우트 element 렌더 중 throw, lazy chunk 로드 실패,
 * 앞으로 생길 loader/action 오류가 전부 여기로 모인다.
 *
 * **원인 문자열을 화면에 싣지 않는다.** stack·exception message·내부 URL 은 사용자에게 아무
 * 행동도 만들어 주지 못하면서 내부 구조만 드러낸다. 진단은 console 로 보낸다.
 *
 * 재시도는 `window.location.reload()` 다. 라우터 안에서 다시 그려 봐야 실패한 chunk 는 그대로라
 * 같은 오류로 돌아온다 — 문서를 새로 받아야 새 해시를 집는다(preloadRecovery.ts 와 같은 근거).
 */
export function AppError() {
  const error = useRouteError();
  useEffect(() => {
    console.error('[app-error] 라우트에서 처리하지 못한 오류 —', error);
  }, [error]);
  return (
    <BootPanel
      title="페이지를 불러오지 못했습니다"
      hint={<>일시적인 문제가 발생했습니다.<br />잠시 후 다시 시도해 주세요.</>}
    >
      <button type="button" className="uh-status-action" onClick={() => window.location.reload()}>
        다시 시도
      </button>
    </BootPanel>
  );
}
