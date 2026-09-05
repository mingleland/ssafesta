// /auth/callback이 마운트하는 화면(T007) — plan.md 상태 기계 그대로.
// URL query·fragment는 읽지 않는다(FR-021b, oauth-completion.md) — handoff는 서버가 cookie로만 전달한다.
//
// StrictMode 이중 effect 방어: handoff는 complete() 1회 호출로 소비되므로, 컴포넌트 로컬
// state/ref로는 mount→cleanup→mount 사이의 중복 호출을 막을 수 없다(unity/host/sessionManager.ts와
// 동일 문제). 모듈 레벨 guard + 지연 리셋으로 막는다: cleanup은 즉시 리셋하지 않고 한 틱 미루고,
// StrictMode의 재mount가 그 틱 안에 오면 리셋을 취소해 재호출을 막는다. 진짜 재마운트(로그아웃 후
// 재로그인 등, mock에서는 SPA 내비게이션이라 실제로 벌어진다)라면 취소하는 쪽이 없어 리셋이
// 실행되고, 다음 방문은 새로 complete()를 호출한다. 실 OAuth 흐름은 전체 페이지 이동이라
// 재방문마다 모듈이 새로 로드되므로 이 가드가 실제로 필요한 건 StrictMode 개발 환경뿐이다.
import { useEffect, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../../entities/auth/api.select';
import { setMemberSession } from '../../features/auth/model/session';
import { consumeReturnTo } from '../../features/auth/model/returnTo';
import { NicknameForm } from '../../features/auth/ui/NicknameForm';
import { warmUpUnityAssets } from '../../unity/host/warmup';
import ssafestaLogoUrl from '../../assets/festa/brand/ssafesta-logo.png';
import landingBackgroundUrl from '../../assets/festa/backgrounds/landing-background.png';
import './callbackPage.css';

type Phase = 'completing' | 'nickname-required' | 'restart';

let guardPhase: 'idle' | 'running' | 'done' = 'idle';
let pendingReset: ReturnType<typeof setTimeout> | null = null;

function cancelPendingReset(): void {
  if (pendingReset !== null) {
    clearTimeout(pendingReset);
    pendingReset = null;
  }
}

function scheduleReset(): void {
  pendingReset = setTimeout(() => {
    pendingReset = null;
    guardPhase = 'idle';
  }, 0);
}

export function CallbackPage() {
  const navigate = useNavigate();
  const [phase, setPhase] = useState<Phase>('completing');

  // warm-up 3단계 (S15P21A604-430). 여기까지 왔으면 기본 목적지가 /app/world 라 진입이 사실상 확정이다.
  // 큰 파일(wasm·data)까지 넓힌다 — 이 화면은 서버 왕복을 기다리는 구간이라 대역폭이 놀고 있다.
  // Unity 인스턴스는 여전히 만들지 않는다. 닉네임 입력이 남아 있어도 다운로드는 계속되는 편이 낫다.
  useEffect(() => warmUpUnityAssets('authenticated'), []);

  useEffect(() => {
    cancelPendingReset();
    if (guardPhase !== 'idle') return; // StrictMode 재mount거나 이미 처리 중/완료
    guardPhase = 'running';

    void (async () => {
      try {
        const result = await authApi.complete(); // body 없이 1회(oauth-completion.md — "선택 사항")
        guardPhase = 'done';
        if (result.status === 'AUTHENTICATED') {
          setMemberSession(result.accessToken, result.expiresAt);
          navigate(consumeReturnTo(), { replace: true });
          return;
        }
        setPhase('nickname-required');
      } catch {
        guardPhase = 'done';
        setPhase('restart');
      }
    })();

    return () => {
      scheduleReset();
    };
  }, [navigate]);

  // 게임 진입 Transition — OAuth handoff·세션 확정 같은 기술 용어를 사용자에게 노출하지 않는다.
  // 진행률 퍼센트나 단계 목록도 만들지 않는다(실제로 알 수 없다).
  if (phase === 'completing') {
    return (
      <Boot>
        <p className="boot-line">축제에 입장하고 있어요...</p>
        <span className="boot-bar" aria-hidden="true">
          <span className="boot-bar-fill" />
        </span>
      </Boot>
    );
  }

  if (phase === 'nickname-required') {
    return (
      <Boot>
        <p className="boot-line">처음 오셨네요</p>
        <p className="boot-sub">축제에서 쓸 이름을 알려주세요</p>
        <NicknameForm
          onAuthenticated={(accessToken, expiresAt) => {
            setMemberSession(accessToken, expiresAt);
            navigate(consumeReturnTo(), { replace: true });
          }}
          onHandoffExpired={() => setPhase('restart')}
        />
      </Boot>
    );
  }

  // phase === 'restart' — 400(handoff 누락)·410(만료·재사용) 공통 처리(FE 의무 3)
  return (
    <Boot>
      <p className="boot-line">입장 정보가 만료되었어요</p>
      <p className="boot-sub">처음부터 다시 로그인해 주세요.</p>
      <button type="button" className="boot-btn" onClick={() => navigate('/login', { replace: true })}>
        로그인 화면으로
      </button>
    </Boot>
  );
}

/** 세 상태가 공유하는 부팅 화면 껍데기 — Landing/Login 과 같은 세계관을 유지한다 */
function Boot({ children }: { children: ReactNode }) {
  return (
    <div className="boot-root">
      <img className="boot-bg" src={landingBackgroundUrl} alt="" />
      <div className="boot-panel">
        <img className="boot-logo" src={ssafestaLogoUrl} alt="SSAFESTA" />
        {children}
      </div>
    </div>
  );
}
