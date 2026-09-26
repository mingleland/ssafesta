// 경품 등록·수정 폼 (S15P21A604-951, GitLab #255).
//
// **콘솔 안에서 목록과 자리를 바꾼다.** 별도 화면으로 빼지 않는 이유는 운영자가 재고를 보고 고치고
// 다시 목록으로 돌아오는 일을 반복하기 때문이다.
//
// 수정은 **덮어쓰기**다(BE PUT) — 그래서 현재 값을 먼저 채워 넣고 통째로 보낸다. 비워 둔 칸이
// "그대로 둬 달라" 가 아니라 "비워 달라" 로 읽히는 것을 막는다.
import { useState } from 'react';
import type { PrizeDraft, PrizeView } from '../../../entities/admin/types';

/** `datetime-local` 입력과 ISO-8601 사이의 왕복. 초 단위는 쓰지 않는다 */
function toLocalInput(iso: string | null): string {
  if (iso === null) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function fromLocalInput(value: string): string | null {
  if (value.trim() === '') return null;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
}

export interface PrizeFormProps {
  /** null 이면 신규 등록 */
  prize: PrizeView | null;
  busy: boolean;
  onSubmit: (draft: PrizeDraft) => void;
  onCancel: () => void;
}

export function PrizeForm({ prize, busy, onSubmit, onCancel }: PrizeFormProps) {
  const [name, setName] = useState(prize?.name ?? '');
  const [priceText, setPriceText] = useState(String(prize?.priceCoin ?? 0));
  // 빈 문자열 = 무제한. 0 과 구분해야 해서 문자열로 든다.
  const [stockText, setStockText] = useState(prize === null || prize.stock === null ? '' : String(prize.stock));
  const [active, setActive] = useState(prize?.active ?? true);
  const [closesAt, setClosesAt] = useState(toLocalInput(prize?.closesAt ?? null));
  const [winnerText, setWinnerText] = useState(String(prize?.winnerCount ?? 0));

  const stock = stockText.trim() === '' ? null : Number(stockText);
  const winnerCount = Number(winnerText);
  const priceCoin = Number(priceText);
  // BE 검증과 같은 표다 — 서버에 가서야 처음 거절당하지 않게 버튼을 먼저 잠근다.
  const problem =
    name.trim() === '' ? '경품 이름을 입력해 주세요.'
    : name.trim().length > 200 ? '경품 이름은 200자 이하여야 합니다.'
    : !Number.isInteger(priceCoin) || priceCoin < 0 ? '가격은 0 이상의 정수여야 합니다.'
    : stock !== null && (!Number.isInteger(stock) || stock < 0) ? '재고는 0 이상의 정수여야 합니다.'
    : !Number.isInteger(winnerCount) || winnerCount < 0 ? '당첨자 수는 0 이상의 정수여야 합니다.'
    : winnerCount > 0 && stock === null ? '응모형은 응모권 수(재고)가 필요합니다.'
    : stock !== null && winnerCount > stock ? '당첨자 수는 응모권 수보다 많을 수 없습니다.'
    : null;

  return (
    <form
      className="ac-form"
      onSubmit={(e) => {
        e.preventDefault();
        if (problem === null) onSubmit({ name: name.trim(), priceCoin, stock, active, closesAt: fromLocalInput(closesAt), winnerCount });
      }}
    >
      <label className="ac-field" htmlFor="prize-name">
        <span className="ac-label">경품 이름</span>
        <input id="prize-name" className="ac-input" value={name} maxLength={200} onChange={(e) => setName(e.target.value)} placeholder="예: 치킨 응모권" />
      </label>
      <label className="ac-field" htmlFor="prize-price">
        <span className="ac-label">가격(코인)</span>
        <input id="prize-price" className="ac-input" inputMode="numeric" value={priceText} onChange={(e) => setPriceText(e.target.value)} />
      </label>
      <label className="ac-field" htmlFor="prize-stock">
        <span className="ac-label">재고 <em>비우면 무제한 · 응모형이면 응모권 수</em></span>
        <input id="prize-stock" className="ac-input" inputMode="numeric" value={stockText} onChange={(e) => setStockText(e.target.value)} placeholder="무제한" />
      </label>
      <label className="ac-field" htmlFor="prize-winners">
        <span className="ac-label">당첨자 수 <em>0 이면 즉시교환 상품</em></span>
        <input id="prize-winners" className="ac-input" inputMode="numeric" value={winnerText} onChange={(e) => setWinnerText(e.target.value)} />
      </label>
      <label className="ac-field" htmlFor="prize-closes">
        <span className="ac-label">응모 마감 <em>비우면 마감 없음</em></span>
        <input id="prize-closes" className="ac-input" type="datetime-local" value={closesAt} onChange={(e) => setClosesAt(e.target.value)} />
      </label>
      <label className="ac-check" htmlFor="prize-active">
        <input id="prize-active" type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
        판매 중
      </label>

      {problem !== null && <p className="ac-muted" role="alert">{problem}</p>}
      <div className="ac-actions">
        <button type="submit" className="sc-btn sc-btn-primary" disabled={busy || problem !== null}>
          {prize === null ? '등록' : '저장'}
        </button>
        <button type="button" className="sc-btn" disabled={busy} onClick={onCancel}>취소</button>
      </div>
    </form>
  );
}
