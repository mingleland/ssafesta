// 부스 슬롯 평면도 (S15P21A604-606) — 위에서 본 페스타존.
//
// 실사 렌더 자산이 저장소에 없어서 SVG 로 직접 그린다. 11F 는 6열 × 2행 통로형이라 추상
// 평면도로도 "어느 자리인가" 가 읽힌다. 나중에 top-down 렌더가 오면 배경만 그 이미지로 바꾸고
// 핫스팟(SLOT_SPOTS)은 그대로 쓴다.
//
// 카드 목록을 대체하지 않는다 — 평면도 위 핫스팟만 두면 키보드·스크린리더 사용자가 막힌다.
// 이 컴포넌트는 같은 데이터를 그림으로 한 번 더 보여 주는 자리다.
import { Link } from 'react-router-dom';
import { slotSpot } from '../../../entities/booth/slotLayout';
import type { SlotView } from '../../../entities/booth/types';
import './slotFloorPlan.css';

interface Props {
  slots: SlotView[];
  /** 임대 확인을 여는 쪽 — 목록 카드와 같은 흐름을 탄다 */
  onPick: (slot: SlotView) => void;
  isMember: boolean;
  pending: boolean;
}

type SpotKind = 'mine' | 'rentable' | 'occupied' | 'admin';

function kindOf(slot: SlotView): SpotKind {
  if (slot.mine) return 'mine';
  if (slot.type !== 'USER_RENTAL') return 'admin';
  return slot.status === 'AVAILABLE' ? 'rentable' : 'occupied';
}

const KIND_LABEL: Record<SpotKind, string> = {
  mine: '내 부스',
  rentable: '임대 가능',
  occupied: '사용 중',
  admin: '운영 부스',
};

export function SlotFloorPlan({ slots, onPick, isMember, pending }: Props) {
  // 표에 없는 slotId 는 그리지 않는다 — 모르는 자리를 추측해서 찍지 않는다
  const placed = slots.filter((slot) => slotSpot(slot.slotId) !== undefined);
  if (placed.length === 0) return null;

  return (
    <section className="sc-card slot-plan" aria-label="페스타존 평면도">
      <div className="slot-plan-head">
        <strong>페스타존 11층</strong>
        <span className="sc-note">위에서 본 자리 배치 · 통로를 사이에 두고 마주 봅니다</span>
      </div>

      <div className="slot-plan-stage">
        {/* 배경 — 바닥과 가운데 통로. 축제장 그림이 오면 이 svg 만 교체한다 */}
        <svg className="slot-plan-bg" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
          <rect x="0" y="0" width="100" height="100" rx="2" className="slot-plan-floor" />
          <rect x="2" y="42" width="96" height="16" rx="2" className="slot-plan-aisle" />
        </svg>

        <p className="slot-plan-aisle-label" aria-hidden="true">중앙 통로</p>

        {placed.map((slot) => {
          const spot = slotSpot(slot.slotId);
          if (spot === undefined) return null;
          const kind = kindOf(slot);
          const label = `${slot.slotCode} ${KIND_LABEL[kind]}`;
          const style = { left: `${spot.left}%`, top: `${spot.top}%` };

          // 내 부스는 Studio 로, 임대 가능한 자리는 임대 확인으로. 나머지는 읽기 전용이다
          if (kind === 'mine' && slot.boothId !== null) {
            return (
              <Link
                key={slot.slotId}
                className="slot-plan-spot slot-plan-spot-mine"
                style={style}
                to={`/app/studio/${slot.boothId}`}
                aria-label={`${label} — 스튜디오에서 편집`}
              >
                <span className="slot-plan-code">{slot.slotCode}</span>
              </Link>
            );
          }

          if (kind === 'rentable' && isMember) {
            return (
              <button
                key={slot.slotId}
                type="button"
                className="slot-plan-spot slot-plan-spot-rentable"
                style={style}
                onClick={() => onPick(slot)}
                disabled={pending}
                aria-label={`${label} — 임대하기`}
              >
                <span className="slot-plan-code">{slot.slotCode}</span>
              </button>
            );
          }

          return (
            <span
              key={slot.slotId}
              className={`slot-plan-spot slot-plan-spot-${kind}`}
              style={style}
              aria-label={label}
            >
              <span className="slot-plan-code">{slot.slotCode}</span>
            </span>
          );
        })}
      </div>

      <ul className="slot-plan-legend">
        {(['rentable', 'mine', 'occupied', 'admin'] as const).map((kind) => (
          <li key={kind}>
            <i className={`slot-plan-key slot-plan-key-${kind}`} aria-hidden="true" />
            {KIND_LABEL[kind]}
          </li>
        ))}
      </ul>
    </section>
  );
}
