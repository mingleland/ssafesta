// Booth Mini Preview — 내 부스의 정체성을 4:3 으로 보여주는 경량 시각물 (user-flow-decisions §17).
//
// 데이터는 이미 있는 facade 4필드뿐이다(GET /booths/{id} 의 facade). 새 스크린샷 API 를 만들지
// 않으며, 3D 렌더링도 하지 않는다 — R3F/GLB 는 DEFERRED(05_technical-spikes 참조).
// Booth Studio 에서 외관을 바꾸면 같은 데이터라 자동으로 반영된다.
//
// Project 로고를 부스 대표 이미지로 쓰지 않는다(RULE 11) — Booth ≠ Project.
import type { BoothFacade } from '../../../entities/booth/types';
import './boothMiniPreview.css';

/** themeCode 별 배경/차양 골격. primaryColor 가 없을 때의 기본 강조색도 여기서 준다 */
const THEME: Record<BoothFacade['themeCode'], { sky: string; ground: string; accent: string }> = {
  DEFAULT: { sky: '#1b2350', ground: '#2a3468', accent: '#4d6bff' },
  SSAFY_BLUE: { sky: '#0f2447', ground: '#173463', accent: '#3b82f6' },
  WARM: { sky: '#3a2440', ground: '#4d2f45', accent: '#f97316' },
  MONO: { sky: '#1e2027', ground: '#2b2e37', accent: '#9aa4b2' },
};

interface Props {
  facade: BoothFacade | null;
  boothName: string;
}

export function BoothMiniPreview({ facade, boothName }: Props) {
  const theme = THEME[facade?.themeCode ?? 'DEFAULT'];
  const accent = facade?.primaryColor ?? theme.accent;
  const sign = facade?.signText?.trim() || boothName;

  return (
    <div className="bmp" style={{ background: `linear-gradient(180deg, ${theme.sky}, ${theme.ground})` }}>
      {/* 차양 — primaryColor 가 부스의 인상을 만든다 */}
      <div className="bmp-awning" style={{ background: accent }} aria-hidden="true">
        <span className="bmp-awning-edge" />
      </div>

      <div className="bmp-body">
        {facade?.logoUrl != null ? (
          // 소유자가 등록한 외부 URL — 실패하면 자리를 비우고 간판 문구만 남긴다
          <img className="bmp-logo" src={facade.logoUrl} alt="" onError={(e) => (e.currentTarget.style.display = 'none')} />
        ) : (
          <span className="bmp-logo bmp-logo-fallback" style={{ borderColor: accent }} aria-hidden="true">
            {sign.slice(0, 1)}
          </span>
        )}
        <span className="bmp-sign" title={sign}>
          {sign}
        </span>
      </div>

      <div className="bmp-floor" aria-hidden="true" />
    </div>
  );
}
