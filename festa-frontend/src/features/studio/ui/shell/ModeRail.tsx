// 좌측 모드 레일 — 아이콘 + 한국어 라벨. 모드는 Shell 내부 상태 전환이다(화면 이동 아님).
import type { StudioMode } from '../../model/studioMode';
import { IcCube, IcHome, IcTemplate } from './icons';

interface Props {
  mode: StudioMode;
  onChange: (mode: StudioMode) => void;
}

const ITEMS: Array<{ id: StudioMode; label: string; Icon: typeof IcCube }> = [
  { id: 'layout', label: '구조', Icon: IcCube },
  { id: 'facade', label: '외관', Icon: IcHome },
  { id: 'template', label: '템플릿', Icon: IcTemplate },
];

export function ModeRail({ mode, onChange }: Props) {
  return (
    <nav className="studio-panel studio-rail" aria-label="편집 모드">
      {ITEMS.map(({ id, label, Icon }) => (
        <button key={id} type="button" className="studio-rail-item" aria-pressed={mode === id} onClick={() => onChange(id)}>
          <Icon size={18} />
          {label}
        </button>
      ))}
    </nav>
  );
}
