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
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../../entities/auth/api.select';
import { setMemberSession } from '../../features/auth/model/session';
import { NicknameForm } from '../../features/auth/ui/NicknameForm';

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
          navigate('/app/home', { replace: true });
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

  if (phase === 'completing') return <p>로그인 처리 중입니다...</p>;

  if (phase === 'nickname-required') {
    return (
      <div>
        <p>처음 오셨네요. 사용할 닉네임을 입력해 주세요.</p>
        <NicknameForm
          onAuthenticated={(accessToken, expiresAt) => {
            setMemberSession(accessToken, expiresAt);
            navigate('/app/home', { replace: true });
          }}
          onHandoffExpired={() => setPhase('restart')}
        />
      </div>
    );
  }

  // phase === 'restart' — 400(handoff 누락)·410(만료·재사용) 공통 처리(FE 의무 3)
  return (
    <div>
      <p>로그인 정보가 만료되었습니다. 처음부터 다시 로그인해 주세요.</p>
      <button type="button" onClick={() => navigate('/login', { replace: true })}>
        로그인 화면으로
      </button>
    </div>
  );
}
