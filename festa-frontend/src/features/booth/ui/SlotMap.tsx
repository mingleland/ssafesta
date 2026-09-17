// 축제장 슬롯맵 (2026-09-17) — 임대 오버레이의 주인공.
//
// 옛 평면도(`SlotFloorPlan`)를 대체한다. 그쪽은 월드 좌표를 %로 굳혀 6열 × 2행으로 눕혀 놓았고,
// 실제 월드(2열 × 6행, 입구 아래)와 90° 어긋나 있었다. 배치 근거는 `entities/booth/slotLayout` 주석에
// 있다 — Unity `FestivalSlot_NN` 실좌표와 `FestivalMinimapArea` 투영식이다.
//
// 실측 재현을 하지 않는다. 사용자가 알아야 하는 것은 "몇 번 자리가 어디쯤인가" 뿐이라, 균등 격자와
// 중앙 통로·입구 표시로 공간을 읽히게 한다.
import type { SlotView } from '../../../entities/booth/types';
import { SLOT_GRID } from '../../../entities/booth/slotLayout';
import './slotMap.css';

type Tone = 'mine' | 'rentable' | 'blocked';

/** 행동 기준 3종으로 줄인다. 운영 부스·사용 중 같은 내부 구분은 보조 라벨로만 남긴다 */
function toneOf(slot: SlotView): Tone {
  if (slot.mine) return 'mine';
  if (slot.type === 'USER_RENTAL' && slot.status === 'AVAILABLE') return 'rentable';
  return 'blocked';
}

const TONE_LABEL: Record<Tone, string> = {
  mine: '내 부스',
  rentable: '임대 가능',
  blocked: '선택 불가',
};

/** 왜 못 고르는지 — 색이 아니라 글자로 말한다 */
function detailOf(slot: SlotView): string {
  if (slot.mine) return '내 부스';
  if (slot.type !== 'USER_RENTAL') return '운영 부스';
  return slot.status === 'AVAILABLE' ? '임대 가능' : '사용 중';
}

interface Props {
  slots: SlotView[];
  selectedId: number | null;
  onSelect: (slot: SlotView) => void;
}

export function SlotMap({ slots, selectedId, onSelect }: Props) {
  const bySlotId = new Map(slots.map((slot) => [slot.slotId, slot]));

  return (
    <div className="slot-map" role="group" aria-label="축제장 부스 자리">
      <div className="slot-map-grid">
        {SLOT_GRID.map(([left, right]) => (
          <div className="slot-map-row" key={left}>
            <Tile slot={bySlotId.get(left)} slotId={left} selectedId={selectedId} onSelect={onSelect} />
            <span className="slot-map-aisle" aria-hidden="true" />
            <Tile slot={bySlotId.get(right)} slotId={right} selectedId={selectedId} onSelect={onSelect} />
          </div>
        ))}
      </div>

      {/* 출입구는 설명문이 아니라 맵의 요소다 — 아래에서 중앙 통로로 이어진다 */}
      <p className="slot-map-entrance" aria-label="출입구는 맵 아래쪽입니다">
        <span className="slot-map-entrance-line" aria-hidden="true" />
        출입구
      </p>
    </div>
  );
}

function Tile({
  slot,
  slotId,
  selectedId,
  onSelect,
}: {
  slot: SlotView | undefined;
  slotId: number;
  selectedId: number | null;
  onSelect: (slot: SlotView) => void;
}) {
  // 서버가 주지 않은 자리 — 번호는 월드에 있으므로 칸은 그리고 상태만 비운다
  if (slot === undefined) {
    return (
      <span className="slot-tile slot-tile-empty">
        <strong className="slot-tile-code">{String(slotId).padStart(2, '0')}</strong>
        <span className="slot-tile-state">준비 중</span>
      </span>
    );
  }

  const tone = toneOf(slot);
  const detail = detailOf(slot);
  return (
    <button
      type="button"
      className={'slot-tile slot-tile-' + tone}
      data-selected={slot.slotId === selectedId}
      aria-pressed={slot.slotId === selectedId}
      onClick={() => onSelect(slot)}
    >
      <strong className="slot-tile-code">{slot.slotCode}</strong>
      <span className="slot-tile-state">{detail}</span>
      {/* 화면 글자와 같은 말이면 두 번 읽히게 두지 않는다 — 다를 때만 행동 기준을 덧붙인다 */}
      {TONE_LABEL[tone] !== detail && <span className="slot-tile-sr">{TONE_LABEL[tone]}</span>}
    </button>
  );
}
