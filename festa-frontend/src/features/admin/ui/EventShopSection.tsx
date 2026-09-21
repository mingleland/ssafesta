// 이벤트 상점 — 구매 내역 표가 중심이다. 코인 차감(원장)과 경품 지급(처리 상태)은 다른 사건이라 열을 따로 둔다.
// BE 가 `!1064`(S15P21A604-836)로 이 도메인을 develop 에 넣었다 — FE 가 먼저 낸 계약과 응답 필드가
// 그대로 맞아 어댑터는 손대지 않았다. 다른 것은 전이 규칙 하나다(S15P21A604-853, GitLab #217 4번).
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../../entities/admin/api.select';
import { nextFulfillmentOptions } from '../../../entities/admin/types';
import type { PrizeDraft, PrizeFulfillmentStatus, PrizePurchaseView, PrizeView } from '../../../entities/admin/types';
import { showToast } from '../../../shared/ui/toast/toastStore';
import { Select } from '../../../shared/ui/select/Select';
import { PrizeForm } from './PrizeForm';
import { ConfirmDialog, Empty, ErrorBanner, Loading, Pager, StatusChip, fmtTime, statusLabel } from './common';

const FILTERS: (PrizeFulfillmentStatus | 'ALL')[] = ['ALL', 'PURCHASED', 'PENDING', 'FULFILLED', 'CANCELLED'];

/** 응모 결과 필터. `null` 은 "보내지 않음" 이라 전체다 — 낙첨(false)과 구분된다 */
const DRAWS: { value: string; label: string; won: boolean | null }[] = [
  { value: 'ALL', label: '응모 전체', won: null },
  { value: 'WON', label: '당첨만', won: true },
  { value: 'LOST', label: '낙첨만', won: false },
];

/** 응모형인지 — 서버가 `winnerCount` 로 가른다(0 이면 즉시교환) */
function isRaffle(prize: PrizeView): boolean {
  return prize.winnerCount > 0;
}

export function EventShopSection() {
  const qc = useQueryClient();
  const prizes = useQuery({ queryKey: ['admin', 'prizes'], queryFn: () => adminApi.listPrizes() });
  const [filter, setFilter] = useState<PrizeFulfillmentStatus | 'ALL'>('ALL');
  const [draw, setDraw] = useState('ALL');
  const [page, setPage] = useState(0);
  const won = DRAWS.find((d) => d.value === draw)?.won ?? null;
  const purchases = useQuery({ queryKey: ['admin', 'purchases', filter, draw, page], queryFn: () => adminApi.listPurchases(filter, page, 10, won) });

  // null = 닫힘, 'new' = 등록, PrizeView = 그 경품 수정
  const [editing, setEditing] = useState<PrizeView | 'new' | null>(null);
  const savePrize = useMutation({
    mutationFn: (draft: PrizeDraft) => (editing === 'new' || editing === null
      ? adminApi.createPrize(draft)
      : adminApi.updatePrize(editing.prizeId, draft)),
    onSuccess: (view) => {
      showToast(`${view.name} 을(를) ${editing === 'new' ? '등록' : '저장'}했습니다`, 'success');
      setEditing(null);
      void qc.invalidateQueries({ queryKey: ['admin', 'prizes'] });
    },
  });

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
        <div className="ad-head">
          <h3 className="sc-section-title">경품</h3>
          {editing === null && (
            <button type="button" className="sc-btn sc-btn-sm sc-btn-primary" onClick={() => { savePrize.reset(); setEditing('new'); }}>경품 등록</button>
          )}
        </div>
        {/* 폼이 열리면 목록과 자리를 바꾼다 — 콘솔 밖으로 나가지 않는다 (GitLab #255) */}
        {editing !== null ? (
          <>
            <p className="sc-note">{editing === 'new' ? '새 경품을 등록합니다.' : `${editing.name} 을(를) 수정합니다. 저장하면 입력한 값으로 전부 덮어씁니다.`}</p>
            <PrizeForm
              key={editing === 'new' ? 'new' : editing.prizeId}
              prize={editing === 'new' ? null : editing}
              busy={savePrize.isPending}
              onSubmit={(draft) => savePrize.mutate(draft)}
              onCancel={() => setEditing(null)}
            />
            {savePrize.isError && <ErrorBanner error={savePrize.error} />}
          </>
        ) : (
          <>
            {prizes.isPending && <Loading />}
            {prizes.isError && <ErrorBanner error={prizes.error} onRetry={() => void prizes.refetch()} />}
            {prizes.isSuccess && prizes.data.length === 0 && <Empty title="등록된 경품이 없습니다" />}
            {prizes.isSuccess && prizes.data.length > 0 && (
              <table className="ad-table">
                <thead><tr><th>경품</th><th className="num">가격</th><th className="num">재고</th><th>종류</th><th>마감</th><th>판매</th><th /></tr></thead>
                <tbody>
                  {prizes.data.map((p) => (
                    <tr key={p.prizeId}>
                      <td>{p.name} <span className="ad-muted">#{p.prizeId}</span></td>
                      <td className="num">{p.priceCoin.toLocaleString('ko-KR')}</td>
                      <td className="num">{p.stock === null ? '무제한' : p.stock.toLocaleString('ko-KR')}</td>
                      <td>
                        {isRaffle(p)
                          ? <span className="ad-chip ad-chip-plain">응모형 · {p.winnerCount}명 당첨</span>
                          : <span className="ad-muted">즉시교환</span>}
                      </td>
                      <td>
                        {p.closesAt === null ? <span className="ad-muted">없음</span> : fmtTime(p.closesAt)}
                        {/* 추첨 완료는 되돌릴 수 없는 사건이라 마감 시각과 나란히 둔다 */}
                        {p.drawnAt !== null && <div className="ad-muted" style={{ fontSize: 12 }}>추첨 완료 {fmtTime(p.drawnAt)}</div>}
                      </td>
                      <td>{p.active ? <span className="ad-chip ad-chip-ok">판매 중</span> : <span className="ad-chip ad-chip-plain">판매 종료</span>}</td>
                      <td><button type="button" className="sc-btn sc-btn-sm" onClick={() => { savePrize.reset(); setEditing(p); }}>수정</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </>
        )}
      </section>

      <section className="sc-card ad-work" aria-label="구매 내역">
        <div className="ad-head">
          <h3 className="sc-section-title">구매 내역</h3>
          <div className="ad-toolbar">
            <Select
              aria-label="처리 상태 필터"
              value={filter}
              options={FILTERS.map((f) => ({ value: f, label: f === 'ALL' ? '전체' : statusLabel(f) }))}
              onChange={(v) => { setFilter(v); setPage(0); }}
            />
            <Select
              aria-label="응모 결과 필터"
              value={draw}
              options={DRAWS.map((d) => ({ value: d.value, label: d.label }))}
              onChange={(v) => { setDraw(v); setPage(0); }}
            />
          </div>
        </div>
        <p className="sc-note">코인 차감과 경품 지급은 다른 사건입니다 — 원장에 차감이 남았다고 물건을 받은 것이 아닙니다.</p>
        {purchases.isPending && <Loading />}
        {purchases.isError && <ErrorBanner error={purchases.error} onRetry={() => void purchases.refetch()} />}
        {purchases.isSuccess && purchases.data.content.length === 0 && <Empty title="구매 내역이 없습니다" />}
        {purchases.isSuccess && purchases.data.content.length > 0 && (
          <>
            <table className="ad-table">
              <thead><tr><th>구매</th><th>구매자</th><th>경품</th><th className="num">수량</th><th className="num">코인</th><th>캠퍼스</th><th>조</th><th>받는 분</th><th>코인 차감</th><th>지급 상태</th><th>응모</th><th>시각</th><th /></tr></thead>
              <tbody>
                {purchases.data.content.map((p) => (
                  <tr key={p.purchaseId}>
                    <td className="num">#{p.purchaseId}</td>
                    <td>{p.buyerNickname} <span className="ad-muted">#{p.buyerUserId}</span></td>
                    <td>{p.prizeName}</td>
                    <td className="num">{p.quantity}</td>
                    <td className="num">{p.coinSpent.toLocaleString('ko-KR')}</td>
                    <td>{p.campus ?? '-'}</td>
                    <td>{p.teamName ?? '-'}</td>
                    <td>{p.recipientName ?? '-'}</td>
                    <td>{p.ledgerEntryId === null ? <span className="ad-chip ad-chip-bad">미확인</span> : <span className="ad-chip ad-chip-ok">원장 #{p.ledgerEntryId}</span>}</td>
                    <td><StatusChip status={p.fulfillment} />{p.note !== null && <div className="ad-muted" style={{ fontSize: 12 }}>{p.note}</div>}</td>
                    {/* null 은 추첨 전과 비응모형을 함께 가리킨다 — 둘 다 아직 말할 결과가 없어 구분하지 않는다 */}
                    <td>{p.won === null ? <span className="ad-muted">-</span> : p.won ? <span className="ad-chip ad-chip-gold">당첨</span> : <span className="ad-muted">낙첨</span>}</td>
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
              <Select
                id="fulfill-next"
                className="ad-select-block"
                value={next}
                options={nextFulfillmentOptions(target.fulfillment).map((s) => ({ value: s, label: statusLabel(s) }))}
                onChange={setNext}
              />
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

