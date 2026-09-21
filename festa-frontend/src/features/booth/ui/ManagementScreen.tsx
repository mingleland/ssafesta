// 관리 화면의 껍데기 어댑터 (S15P21A604-755).
//
// 같은 화면이 세 자리에 선다 — 월드 위 오버레이(관리에서 들어간 경우), 독립 route(deep-link),
// 그리고 관리자 콘솔 안(S15P21A604-951).
// **본문을 복제하지 않는 것이 이 파일의 목적이다.** 페이지는 이 컴포넌트를 쓰고, 어느 표면인지는
// context 가 정한다. route 로 들어오면 `PageShell`, 오버레이면 `OverlayFrame`, 콘솔 안이면
// 껍데기 없이 제목줄만 얹는다.
//
// 상세를 route 로 두던 때는 관리 화면에서 나가면 월드가 화면에서 사라졌다. 오버레이로 열면
// 월드가 계속 보이고, 닫으면 관리 화면으로 돌아온다.
import { createContext, useContext } from 'react';
import type { ReactNode } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { PageShell } from '../../shell/ui/PageShell';
import { OverlayFrame } from '../../overlay/ui/OverlayFrame';
import { WORLD_RETURN_TO_MANAGEMENT } from '../../world/model/gameClientUi';
import type { ManagementPanel } from '../../world/model/managementPanel';
import './managementScreen.css';

interface Surface {
  panel: ManagementPanel;
  onClose: () => void;
  /**
   * 본문을 무엇으로 감쌀지 (S15P21A604-951).
   *
   * `overlay` 가 기본이라 기존 두 경로는 값을 주지 않아도 종전과 같다. `inline` 은 이미 떠 있는
   * 화면(관리자 콘솔) 안에 본문만 놓는 자리다 — 거기서 `OverlayFrame` 을 또 씌우면 오버레이가
   * 겹쳐 뜨고 ESC·바깥 클릭 주인이 둘이 된다.
   */
  chrome?: 'overlay' | 'inline';
}

/** 오버레이로 열렸을 때만 채워진다. route 로 들어오면 null 이고 페이지가 useParams 를 읽는다 */
const SurfaceContext = createContext<Surface | null>(null);

export function ManagementSurfaceProvider({ value, children }: { value: Surface; children: ReactNode }) {
  return <SurfaceContext.Provider value={value}>{children}</SurfaceContext.Provider>;
}

/**
 * 이 화면이 다루는 부스 번호.
 *
 * 오버레이면 payload 에서, route 면 URL 에서 온다. 페이지가 두 경로를 알 필요가 없다.
 */
export function useManagementBoothId(): number {
  const surface = useContext(SurfaceContext);
  const { boothId } = useParams<{ boothId: string }>();
  return surface?.panel.boothId ?? Number(boothId);
}

/**
 * "부스 관리로 돌아가기" 한 가지 동작 — 표면에 따라 수단만 다르다.
 *
 * 오버레이면 상세만 닫아 관리 화면이 드러나고, route 면 월드로 이동해 관리 화면이 다시 열린다.
 * 페이지는 어느 쪽인지 알 필요가 없다.
 */
export function useManagementClose(): () => void {
  const surface = useContext(SurfaceContext);
  const navigate = useNavigate();
  if (surface !== null) return surface.onClose;
  return () => navigate(WORLD_RETURN_TO_MANAGEMENT);
}

interface Props {
  title: string;
  subtitle?: string;
  actions?: ReactNode;
  children: ReactNode;
  size?: 's' | 'm' | 'l' | 'xl';
}

export function ManagementScreen({ title, subtitle, actions, children, size = 'l' }: Props) {
  const surface = useContext(SurfaceContext);

  if (surface === null) {
    return (
      <PageShell title={title} subtitle={subtitle} backTo={WORLD_RETURN_TO_MANAGEMENT} actions={actions}>
        {children}
      </PageShell>
    );
  }

  // 콘솔 안 — 감싸는 창이 이미 있다. 돌아가는 길은 호출부(콘솔)가 자기 머리글에 두므로 여기서
  // 닫기 버튼을 만들지 않는다. 두 개가 되면 어느 것이 무엇을 닫는지 읽히지 않는다.
  if (surface.chrome === 'inline') {
    return (
      <section className="ms-inline" aria-label={title}>
        <header className="ms-inline-head">
          <h3 className="ms-inline-title">{title}</h3>
          {subtitle !== undefined && <p className="ms-inline-sub">{subtitle}</p>}
        </header>
        {children}
        {actions !== undefined && <div className="ms-inline-actions">{actions}</div>}
      </section>
    );
  }

  return (
    <OverlayFrame title={title} subtitle={subtitle} size={size} onClose={surface.onClose} footer={actions}>
      {children}
    </OverlayFrame>
  );
}
