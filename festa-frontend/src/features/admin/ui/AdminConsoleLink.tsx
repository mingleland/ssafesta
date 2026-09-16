// 콘솔 진입점 — 관리자에게만 보인다. 내 정보 화면 헤더에 붙는다 (GameMenu 는 -798 소유라 건드리지 않는다).
import { Link } from 'react-router-dom';
import { useAdminCapability } from '../model/capability';

export function AdminConsoleLink() {
  const capability = useAdminCapability();
  if (!capability.isSuccess || !capability.data.admin) return null;
  return <Link className="sc-btn" to="/app/admin">관리자 콘솔</Link>;
}

