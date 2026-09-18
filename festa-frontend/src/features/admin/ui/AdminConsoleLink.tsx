// 관리자 콘솔 진입점 — 내 정보 본문 안에 있고 관리자에게만 보인다 (S15P21A604-828).
//
// 콘솔은 월드 위 오버레이라 페이지 이동이 아니다. 내 정보도 같은 ESC 메뉴의 자식이므로
// **자매 패널로 갈아타는 것**이 전부다. 월드 밖(`/app/profile`)에서 눌렀을 때만 월드로 돌아가며,
// 그 복귀는 `?panel=admin` 으로 표현한다 — 모듈 상태에 "열어 달라" 를 남기면 StrictMode 재mount 가
// 그것을 소비한 뒤 정리 효과가 다시 닫는다(gameClientUi 의 관리 화면 복귀와 같은 이유).
import { useLocation, useNavigate } from 'react-router-dom';
import { openMenuPanelScreen } from '../../world/model/worldScreen';
import { useAdminCapability } from '../model/capability';

export function AdminConsoleLink() {
  const capability = useAdminCapability();
  const navigate = useNavigate();
  const location = useLocation();
  if (!capability.isSuccess || !capability.data.admin) return null;

  const open = () => {
    if (location.pathname === '/app/world') {
      openMenuPanelScreen('admin');
      return;
    }
    navigate('/app/world?panel=admin');
  };

  return (
    <section className="sc-card">
      <h2 className="sc-section-title">운영</h2>
      <button type="button" className="sc-btn sc-btn-primary" onClick={open}>관리자 콘솔 열기</button>
      <p className="sc-note" style={{ marginTop: 10 }}>
        회원 · 권한 · 코인 · 경품 · 공식 설문 · 부스를 한 곳에서 처리합니다.
        {capability.data.master && ' 이 계정은 마스터라 다른 관리자의 조치 대상이 되지 않습니다.'}
      </p>
    </section>
  );
}

