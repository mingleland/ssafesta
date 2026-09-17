// 이벤트 상점 — 구매 내역 표가 중심이다. 코인 차감(원장)과 경품 지급(처리 상태)은 다른 사건이라 열을 따로 둔다.
// BE 가 `!1064`(S15P21A604-836)로 이 도메인을 develop 에 넣었다 — FE 가 먼저 낸 계약과 응답 필드가
// 그대로 맞아 어댑터는 손대지 않았다. 다른 것은 전이 규칙 하나다(S15P21A604-853, GitLab #217 4번).
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../../entities/admin/api.select';
import { nextFulfillmentOptions } from '../../../entities/admin/types';
import type { PrizeFulfillmentStatus, PrizePurchaseView } from '../../../entities/admin/types';
import { showToast } from '../../../shared/ui/toast/toastStore';
import { ConfirmDialog, Empty, ErrorBanner, Loading, Pager, StatusChip, fmtTime, statusLabel } from './common';

const FILTERS: (PrizeFulfillmentStatus | 'ALL')[] = ['ALL', 'PURCHASED', 'PENDING', 'FULFILLED', 'CANCELLED'];

export function EventShopSection() {
  const qc = useQueryClient();
  const prizes = useQuery({ queryKey: ['admin', 'prizes'], queryFn: () => adminApi.listPrizes() });
  const [filter, setFilter] = useState<PrizeFulfillmentStatus | 'ALL'>('ALL');
  const [page, setPage] = useState(0);
  const purchases = useQuery({ queryKey: ['admin', 'purchases', filter, page], queryFn: () => adminApi.listPurchases(filter, page, 10) });

  const [target, setTarget] = useState<PrizePurchaseView | null>(null);
  const [next, setNext] = useState<PrizeFulfillmentStatus | ''>('');
  const [note, setNote] = useState('');
  const update = useMutation({
    mutationFn: (p: PrizePurchaseView) => adminApi.updateFulfillment(p.purchaseId, next as PrizeFulfillmentStatus, note.trim() === '' ? undefined : note.trim()),
    onSuccess: (view) => { showToast(`구매 #${view.purchaseId} → ${statusLabel(view.fulfillment)}`, 'success'); setTarget(null); setNext(''); setNote(''); void qc.invalidateQueries({ queryKey: ['admin', 'purchases'] }); },
  });

  return (
    <div className="ad-work">
      <section className="sc-card">
        <div className="ad-head"><h3 className="sc-section-title">경품</h3></div>
        {prizes.isPending && <Loading />}
        {prizes.isError && <ErrorBanner error={prizes.error} onRetry={() => void prizes.refetch()} />}
        {prizes.isSuccess && (
          <div className="ad-stats">
            {prizes.data.map((p) => (
              <div key={p.prizeId} className="ad-stat">
                <strong>{p.name}</strong>
                <span>{p.priceCoin.toLocaleString('ko-KR')} 코인 · 재고 {p.stock === null ? '무제한' : p.stock} · {p.active ? '판매 중' : '판매 종료'}</span>
              </div>
            ))}
          </div>
        )}
      </section>

      <section className="sc-card ad-work" aria-label="구매 내역">
        <div className="ad-head">
          <h3 className="sc-section-title">구매 내역</h3>
          <div className="ad-toolbar">
            <select aria-label="처리 상태 필터" value={filter} onChange={(e) => { setFilter(e.target.value as PrizeFulfillmentStatus | 'ALL'); setPage(0); }}>
              {FILTERS.map((f) => <option key={f} value={f}>{f === 'ALL' ? '전체' : statusLabel(f)}</option>)}
            </select>
          </div>
        </div>
        <p className="sc-note">코인 차감과 경품 지급은 다른 사건입니다 — 원장에 차감이 남았다고 물건을 받은 것이 아닙니다.</p>
        {purchases.isPending && <Loading />}
        {purchases.isError && <ErrorBanner error={purchases.error} onRetry={() => void purchases.refetch()} />}
        {purchases.isSuccess && purchases.data.content.length === 0 && <Empty title="구매 내역이 없습니다" />}
        {purchases.isSuccess && purchases.data.content.length > 0 && (
          <>
            <table className="ad-table">
              <thead><tr><th>구매</th><th>구매자</th><th>경품</th><th className="num">수량</th><th className="num">코인</th><th>코인 차감</th><th>지급 상태</th><th>시각</th><th /></tr></thead>
              <tbody>
                {purchases.data.content.map((p) => (
                  <tr key={p.purchaseId}>
                    <td className="num">#{p.purchaseId}</td>
                    <td>{p.buyerNickname} <span className="ad-muted">#{p.buyerUserId}</span></td>
                    <td>{p.prizeName}</td>
                    <td className="num">{p.quantity}</td>
                    <td className="num">{p.coinSpent.toLocaleString('ko-KR')}</td>
                    <td>{p.ledgerEntryId === null ? <span className="ad-chip ad-chip-bad">미확인</span> : <span className="ad-chip ad-chip-ok">원장 #{p.ledgerEntryId}</span>}</td>
                    <td><StatusChip status={p.fulfillment} />{p.note !== null && <div className="ad-muted" style={{ fontSize: 12 }}>{p.note}</div>}</td>
                    <td>{fmtTime(p.purchasedAt)}</td>
                    <td>
                      <button type="button" className="sc-btn sc-btn-sm" disabled={nextFulfillmentOptions(p.fulfillment).length === 0}
                        onClick={() => { setTarget(p); setNext(nextFulfillmentOptions(p.fulfillment)[0] ?? ''); setNote(''); update.reset(); }}>처리</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Pager page={purchases.data.page} totalPages={purchases.data.totalPages} onChange={setPage} />
          </>
        )}
      </section>

      <ConfirmDialog
        open={target !== null}
        title="지급 처리 상태를 바꿉니다"
        target={target === null ? undefined : `구매 #${target.purchaseId} · ${target.buyerNickname} · ${target.prizeName} × ${target.quantity}`}
        confirmLabel="상태 변경"
        danger={next === 'CANCELLED'}
        busy={update.isPending}
        onCancel={() => { if (!update.isPending) setTarget(null); }}
        onConfirm={() => { if (target !== null && next !== '') update.mutate(target); }}
      >
        {target !== null && (
          <div className="ad-form">
            <label className="ad-field" htmlFor="fulfill-next">
              <span className="ad-label">다음 상태 <em>현재: {statusLabel(target.fulfillment)}</em></span>
              <select id="fulfill-next" className="ad-select" value={next} onChange={(e) => setNext(e.target.value as PrizeFulfillmentStatus)}>
                {nextFulfillmentOptions(target.fulfillment).map((s) => <option key={s} value={s}>{statusLabel(s)}</option>)}
              </select>
            </label>
            {(next === 'CANCELLED' || next === 'FULFILLED') && (
              <p className="sc-note">
                {next === 'CANCELLED' ? '취소' : '지급 완료'}는 되돌릴 수 없습니다. 코인 환불은 이 화면이 하지 않습니다 — 지갑 관리에서 따로 조정합니다.
              </p>
            )}
            <label className="ad-field" htmlFor="fulfill-note">
              <span className="ad-label">메모 <em>선택</em></span>
              <input id="fulfill-note" className="ad-input" value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} placeholder="예: 현장 수령 완료" />
            </label>
            {update.isError && <ErrorBanner error={update.error} />}
          </div>
        )}
      </ConfirmDialog>
    </div>
  );
}

