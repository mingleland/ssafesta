// 관리자 콘솔 공통 조각 — 섹션 일곱이 같은 어휘로 상태·오류·확인을 말하게 한다 (S15P21A604-828).
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { describeAdminError, type AdminErrorView } from '../model/adminErrors';

export function fmtTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString('ko-KR', { year: '2-digit', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

export function fmtCoin(n: number): string {
  return `${n > 0 ? '+' : ''}${n.toLocaleString('ko-KR')}`;
}

/** 오류를 운영자 문장으로. 재시도 버튼은 다시 물어 답이 달라질 수 있을 때만 */
export function ErrorBanner({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const view: AdminErrorView = describeAdminError(error);
  return (
    <div className="ad-error" role="alert" data-code={view.code}>
      <strong>{view.title}</strong>
      {view.message !== '' && <span>{view.message}</span>}
      {onRetry !== undefined && view.retryable && (
        <button type="button" className="sc-btn sc-btn-sm" onClick={onRetry}>다시 시도</button>
      )}
    </div>
  );
}

export function Loading({ label = '불러오는 중...' }: { label?: string }) {
  return (
    <div className="ad-state" role="status">
      <span className="sc-spinner" />
      {label}
    </div>
  );
}

export function Empty({ title, hint }: { title: string; hint?: string }) {
  return (
    <div className="ad-state">
      <strong>{title}</strong>
      {hint !== undefined && <span>{hint}</span>}
    </div>
  );
}

export function Pager({ page, totalPages, onChange }: { page: number; totalPages: number; onChange: (next: number) => void }) {
  if (totalPages <= 1) return null;
  return (
    <nav className="ad-pager" aria-label="페이지">
      <button type="button" className="sc-btn sc-btn-sm" disabled={page <= 0} onClick={() => onChange(page - 1)}>이전</button>
      <span>{page + 1} / {totalPages}</span>
      <button type="button" className="sc-btn sc-btn-sm" disabled={page >= totalPages - 1} onClick={() => onChange(page + 1)}>다음</button>
    </nav>
  );
}

const STATUS_LABEL: Record<string, string> = { ACTIVE: '활성', SUSPENDED: '정지', PURCHASED: '구매 완료', PENDING: '지급 대기', FULFILLED: '지급 완료', CANCELLED: '취소' };
const STATUS_TONE: Record<string, string> = { ACTIVE: 'ok', SUSPENDED: 'bad', PURCHASED: 'plain', PENDING: 'gold', FULFILLED: 'ok', CANCELLED: 'bad' };

export function statusLabel(status: string): string {
  return STATUS_LABEL[status] ?? status;
}

export function StatusChip({ status }: { status: string }) {
  return <span className={`ad-chip ad-chip-${STATUS_TONE[status] ?? 'plain'}`}>{statusLabel(status)}</span>;
}

/**
 * 막힌 버튼은 이유를 말한다. disabled 속성을 쓰면 클릭도 focus 도 죽어 "고장" 으로 읽힌다 —
 * aria-disabled 로 보이게만 막고 누르면 이유를 띄운다 (#187 게스트 게이팅과 같은 어휘).
 */
export function GatedButton({ gate, onClick, className = 'sc-btn', children }: { gate: string | null; onClick: () => void; className?: string; children: ReactNode }) {
  const [shown, setShown] = useState(false);
  return (
    <span className="ad-gated">
      <button
        type="button"
        className={className + (gate !== null ? ' ad-gated-btn' : '')}
        aria-disabled={gate !== null || undefined}
        onClick={() => (gate === null ? onClick() : setShown(true))}
      >
        {children}
      </button>
      {gate !== null && shown && <span className="ad-gate-note" role="note">{gate}</span>}
    </span>
  );
}

export const REASON_MAX = 500;

export function reasonProblem(value: string): string | null {
  if (value.trim() === '') return '사유를 입력해야 합니다.';
  if (value.length > REASON_MAX) return `사유는 ${REASON_MAX}자 이하여야 합니다.`;
  return null;
}

export function ReasonField({ id, label, value, onChange, placeholder }: { id: string; label: string; value: string; onChange: (v: string) => void; placeholder?: string }) {
  const problem = value === '' ? null : reasonProblem(value);
  return (
    <label className="ad-field" htmlFor={id}>
      <span className="ad-label">{label} <em>{value.length}/{REASON_MAX}</em></span>
      <textarea id={id} className="ad-textarea" value={value} maxLength={REASON_MAX + 50} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} rows={3} />
      {problem !== null && <span className="ad-field-error">{problem}</span>}
    </label>
  );
}

/**
 * 파괴적 조치 앞의 확인. 대상을 다시 보여 주고, 진행 중에는 닫히지 않는다.
 * 네이티브 dialog 라 ESC 는 브라우저가 처리하고 focus 도 안에 갇힌다.
 */
export function ConfirmDialog({ open, title, target, children, confirmLabel, busy, danger, onConfirm, onCancel }: {
  open: boolean; title: string; target?: string; children?: ReactNode; confirmLabel: string; busy?: boolean; danger?: boolean; onConfirm: () => void; onCancel: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (el === null) return;
    // showModal 이 없는 런타임(jsdom 등)에서는 비모달로라도 연다 — 닫힌 dialog 는 접근성 트리에서 빠진다
    if (open && !el.open) {
      if (typeof el.showModal === 'function') el.showModal();
      else el.open = true;
    }
    if (!open && el.open) {
      if (typeof el.close === 'function') el.close();
      else el.open = false;
    }
  }, [open]);
  return (
    <dialog ref={ref} className="ad-dialog" onCancel={(e) => { e.preventDefault(); if (!busy) onCancel(); }} aria-labelledby="ad-dialog-title">
      <h2 id="ad-dialog-title">{title}</h2>
      {target !== undefined && <p className="ad-dialog-target">{target}</p>}
      {children}
      <div className="ad-dialog-actions">
        <button type="button" className="sc-btn" disabled={busy} onClick={onCancel}>취소</button>
        <button type="button" className={`sc-btn ${danger ? 'ad-btn-danger' : 'sc-btn-primary'}`} disabled={busy} onClick={onConfirm}>{busy ? '처리 중...' : confirmLabel}</button>
      </div>
    </dialog>
  );
}

export function KeyValue({ rows }: { rows: [string, ReactNode][] }) {
  return (
    <dl className="ad-kv">
      {rows.map(([k, v]) => (
        <div key={k}><dt>{k}</dt><dd>{v}</dd></div>
      ))}
    </dl>
  );
}
