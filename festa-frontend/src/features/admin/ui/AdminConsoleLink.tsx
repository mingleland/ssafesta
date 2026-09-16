// 관리자 콘솔 진입점 — 관리자에게만 보인다. 내 정보 본문(MyInfoBody)에 들어가므로
// `/app/profile` 과 ESC 오버레이 양쪽에서 같은 자리에 뜬다. GameMenu 자체는 -798 소유라 건드리지 않는다.
//
// 판정은 서버가 한다(entities/admin/api.ts getCapability). 회원이 아니면 질의조차 하지 않고,
// 관리자가 아니면 아무것도 그리지 않는다.
import { Link } from 'react-router-dom';
import { useAdminCapability } from '../model/capability';

export function AdminConsoleLink() {
  const capability = useAdminCapability();
  if (!capability.isSuccess || !capability.data.admin) return null;
  return (
    <section className="sc-card">
      <h2 className="sc-section-title">운영</h2>
      <Link className="sc-btn sc-btn-primary" to="/app/admin">관리자 콘솔 열기</Link>
      <p className="sc-note" style={{ marginTop: 10 }}>
        회원 · 권한 · 코인 · 경품 · 공식 설문 · 부스를 한 곳에서 처리합니다.
        {capability.data.master && ' 이 계정은 마스터라 다른 관리자의 조치 대상이 되지 않습니다.'}
      </p>
    </section>
  );
}

