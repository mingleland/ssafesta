// 보호 라우트 가드(T014a) — 판정 로직은 guard.ts(순수 함수, 단위 테스트 대상). 이 컴포넌트는
// useSession 구독 + 분기 렌더링만 담당한다. 허용 화면 상단에 로그아웃 버튼(T013, FR-020)을 최소
// 구현으로 함께 그린다 — 별도 공통 헤더 컴포넌트를 새로 만들 만큼의 화면이 아직 없다(plan.md
// §기존 코드와의 접점: "버튼 위치는 가드 적용 화면 공통 헤더 최소 구현").
import type { ReactNode } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useSession, clearSession } from '../../features/auth/model/session';
import { saveReturnTo } from '../../features/auth/model/returnTo';
import { authApi } from '../../entities/auth/api.select';
import type { SessionKind } from '../../entities/auth/types';
import { evaluateGuard, type GuardLevel } from './guard';

function AuthHeader({ kind }: { kind: Exclude<SessionKind, 'anonymous'> }) {
  const navigate = useNavigate();

  async function handleLogout() {
    try {
      await authApi.logout();
    } catch {
      // 서버측 로그아웃이 실패해도 클라이언트 세션은 정리한다 — 남은 서버 상태 불일치는
      // 다음 요청의 401로 자연 정리된다(logout endpoint 경로는 §미결, mock으로 선개발).
    }
    clearSession();
    navigate('/login', { replace: true });
  }

  return (
    <header>
      <span>{kind === 'member' ? '회원' : '게스트'}로 이용 중</span>
      <button type="button" onClick={handleLogout}>
        로그아웃
      </button>
    </header>
  );
}

export function RequireAuth({ level, children }: { level: GuardLevel; children: ReactNode }) {
  const { kind, bootstrapped } = useSession();
  const location = useLocation();

  // 부트스트랩(새로고침 복원) 완료 전의 anonymous는 "미확인"이다 — 여기서 redirect를 확정하면
  // refresh가 이길 수 없는 레이스가 돼 로그인 유지가 항상 깨진다(T012, quickstart §6 실측 발견)
  if (!bootstrapped) return <p>세션 확인 중...</p>;

  const decision = evaluateGuard(kind, level);

  if (decision === 'redirect-login') {
    // 딥링크로 들어온 원래 경로를 저장한다(G-2) — 로그인 완료 후 여기로 되돌아간다.
    saveReturnTo(location.pathname + location.search + location.hash);
    return <Navigate to="/login" replace />;
  }
  if (decision === 'block-member-only') {
    return (
      <div>
        <p>소셜 로그인이 필요한 기능입니다.</p>
        <Link to="/login">로그인하러 가기</Link>
      </div>
    );
  }
  return (
    <>
      <AuthHeader kind={kind as Exclude<SessionKind, 'anonymous'>} />
      {children}
    </>
  );
}
