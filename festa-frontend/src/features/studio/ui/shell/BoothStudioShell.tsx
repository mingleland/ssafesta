// Booth Studio Shell — 월드 위 큰 Overlay 로 뜨는 편집기 골격 (S15P21A604-405).
// 슬롯만 배치한다: Toolbar / ModeRail / Palette / Canvas / Inspector / Status. 내용은 모드가 채운다.
// 배경은 목업용 이미지(landing-background) — Persistent GameShell 도달 시 Unity World 로 교체된다.
import type { ReactNode } from 'react';
import worldBackdropUrl from '../../../../assets/festa/backgrounds/landing-background.png';
import { layoutConfigStyle } from '../../model/studioLayoutConfig';
import './boothStudio.css';

interface Props {
  toolbar: ReactNode;
  rail: ReactNode;
  palette: ReactNode;
  canvas: ReactNode;
  inspector: ReactNode;
  status: ReactNode;
  overlay?: ReactNode;
}

export function BoothStudioShell({ toolbar, rail, palette, canvas, inspector, status, overlay }: Props) {
  return (
    <div className="booth-studio" style={layoutConfigStyle()}>
      <div className="studio-world" aria-hidden="true">
        <img src={worldBackdropUrl} alt="" />
      </div>
      <div className="studio-shell">
        {toolbar}
        {rail}
        {palette}
        {canvas}
        {inspector}
        {status}
      </div>
      {overlay}
    </div>
  );
}

/** 게이트 화면(소유자 확인·로딩·오류)도 같은 월드 위에 띄운다 — 흰 페이지로 빠지지 않게 */
export function StudioGate({ children }: { children: ReactNode }) {
  return (
    <div className="booth-studio" style={layoutConfigStyle()}>
      <div className="studio-world" aria-hidden="true">
        <img src={worldBackdropUrl} alt="" />
      </div>
      <div className="studio-gate">
        <div className="studio-gate-card">{children}</div>
      </div>
    </div>
  );
}
