// 관리자 라우트 가드 (S15P21A604-828). RequireAuth(member-only) 위에 "관리자인가" 를 한 번 더 묻는다.
//
// UX 보조다. 토큰의 role 은 관리자도 MEMBER 라 FE 가 스스로 알 수 없고, 답은 서버(/users/me 의
// admin 칸 — 아직 [FE contract])에서 온다. 여기서 통과해도 실제 조치는 매 요청 BE AdminGuard 가 판정한다.
// bootstrapped 전에는 판정하지 않는다 — RequireAuth 와 같은 레이스(T012)를 그대로 물려받는다.
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useAdminCapability } from '../../features/admin/model/capability';
import { RequireAuth } from './RequireAuth';
import './guardScreens.css';

function AdminGate({ children }: { children: ReactNode }) {
  const capability = useAdminCapability();
  if (capability.isPending) return <p className="festa-boot">관리자 권한을 확인하고 있어요...</p>;
  if (capability.isError) {
    return (
      <div className="festa-blocked">
        <p>관리자 권한을 확인하지 못했습니다.</p>
        <button type="button" className="sc-btn" onClick={() => void capability.refetch()}>다시 시도</button>
      </div>
    );
  }
  if (!capability.data.admin) {
    return (
      <div className="festa-blocked">
        <p>관리자만 이용할 수 있는 화면입니다.</p>
        <Link to="/app/world">월드로 돌아가기</Link>
      </div>
    );
  }
  return <>{children}</>;
}

export function RequireAdmin({ children }: { children: ReactNode }) {
  return (
    <RequireAuth level="member-only">
      <AdminGate>{children}</AdminGate>
    </RequireAuth>
  );
}

