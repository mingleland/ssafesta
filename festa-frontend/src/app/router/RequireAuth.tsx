// 보호 라우트 가드(T014a) — 판정 로직은 guard.ts(순수 함수, 단위 테스트 대상). 이 컴포넌트는
// useSession 구독 + 분기 렌더링만 담당한다.
//
// UI 표시 책임은 여기 없다(D-08). 계정 표시·로그아웃은 ESC Game Menu 소관이며, 가드가 모든
// 화면 위에 계정 헤더를 덧그리던 구조는 제거했다 — World 는 상주 플레이 화면이라 화면 고정
// 계정 칩이 붙으면 안 된다.
import type { ReactNode } from 'react';
import { Link, Navigate, useLocation } from 'react-router-dom';
import { useSession } from '../../features/auth/model/session';
import { saveReturnTo } from '../../features/auth/model/returnTo';
import { evaluateGuard, type GuardLevel } from './guard';
import './guardScreens.css';

export function RequireAuth({ level, children }: { level: GuardLevel; children: ReactNode }) {
  const { kind, bootstrapped } = useSession();
  const location = useLocation();

  // 부트스트랩(새로고침 복원) 완료 전의 anonymous는 "미확인"이다 — 여기서 redirect를 확정하면
  // refresh가 이길 수 없는 레이스가 돼 로그인 유지가 항상 깨진다(T012, quickstart §6 실측 발견)
  if (!bootstrapped) return <p className="festa-boot">입장 정보를 확인하고 있어요...</p>;

  const decision = evaluateGuard(kind, level);

  if (decision === 'redirect-login') {
    // 딥링크로 들어온 원래 경로를 저장한다(G-2) — 로그인 완료 후 여기로 되돌아간다.
    saveReturnTo(location.pathname + location.search + location.hash);
    return <Navigate to="/login" replace />;
  }
  if (decision === 'block-member-only') {
    return (
      <div className="festa-blocked">
        <p>소셜 로그인 회원만 이용할 수 있는 기능입니다.</p>
        <Link to="/login">로그인하러 가기</Link>
      </div>
    );
  }
  return <>{children}</>;
}
