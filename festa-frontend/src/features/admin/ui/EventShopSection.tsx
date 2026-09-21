// 이벤트 상점 — 경품 관리(넣고 빼기)와 구매 내역 처리 두 축이다.
// 경품은 winnerCount 하나로 종류가 갈린다: 0 이면 즉시 구매, 1 이상이면 응모형(응모권 stock 필수).
// "빼기" 는 하드 삭제가 아니라 판매 종료(active=false)다 — BE 에 DELETE 가 없고, 이미 구매한 사람의
// 내역이 남아야 하므로 소프트 종료가 맞다. 다시 열려면 판매 재개.
// 코인 차감(원장)과 경품 지급(처리 상태)은 다른 사건이라 구매 내역은 열을 따로 둔다.
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../../entities/admin/api.select';
import { nextFulfillmentOptions, prizeKindLabel } from '../../../entities/admin/types';
import type { PrizeFulfillmentStatus, PrizeInput, PrizePurchaseView, PrizeView } from '../../../entities/admin/types';
import { showToast } from '../../../shared/ui/toast/toastStore';
import { Select } from '../../../shared/ui/select/Select';
import { ConfirmDialog, Empty, ErrorBanner, Loading, Pager, StatusChip, fmtTime, statusLabel } from './common';

const FILTERS: (PrizeFulfillmentStatus | 'ALL')[] = ['ALL', 'PURCHASED', 'PENDING', 'FULFILLED', 'CANCELLED'];

type PrizeKind = 'PURCHASE' | 'RAFFLE';

interface PrizeDraft {
  prizeId: number | null; // null = 새 경품
  kind: PrizeKind;
  name: string;
  priceCoin: string;
  stock: string; // 빈칸 = 무제한(즉시 구매만)
  winnerCount: string;
  closesAt: string; // datetime-local 값
  active: boolean;
}

// ISO ↔ datetime-local. datetime-local 은 로컬 타임존 "YYYY-MM-DDTHH:mm" 을 준다.
function isoToLocalInput(iso: string | null): string {
  if (iso === null) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
function localInputToIso(value: string): string | null {
  if (value.trim() === '') return null;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
}

function draftFor(prize: PrizeView | null): PrizeDraft {
  if (prize === null) {
    return { prizeId: null, kind: 'PURCHASE', name: '', priceCoin: '', stock: '', winnerCount: '1', closesAt: '', active: true };
  }
  const kind: PrizeKind = prize.winnerCount > 0 ? 'RAFFLE' : 'PURCHASE';
  return {
    prizeId: prize.prizeId,
    kind,
    name: prize.name,
    priceCoin: String(prize.priceCoin),
    stock: prize.stock === null ? '' : String(prize.stock),
    winnerCount: String(prize.winnerCount > 0 ? prize.winnerCount : 1),
    closesAt: isoToLocalInput(prize.closesAt),
    active: prize.active,
  };
}

// 드래프트 → BE 입력. 실제 검증은 BE(와 mock)가 하고, 여기서는 명백한 것만 막아 왕복을 아낀다.
function toInput(d: PrizeDraft): PrizeInput | { error: string } {
  const name = d.name.trim();
  if (name === '') return { error: '경품 이름을 입력해 주세요.' };
  const priceCoin = Number(d.priceCoin);
  if (!Number.isInteger(priceCoin) || priceCoin < 0) return { error: '가격은 0 이상의 정수여야 합니다.' };

  if (d.kind === 'RAFFLE') {
    const stock = Number(d.stock);
    if (!Number.isInteger(stock) || stock < 1) return { error: '응모형은 응모권 수를 1 이상으로 정해야 합니다.' };
    const winnerCount = Number(d.winnerCount);
    if (!Number.isInteger(winnerCount) || winnerCount < 1) return { error: '당첨자 수는 1 이상이어야 합니다.' };
    if (winnerCount > stock) return { error: '당첨자 수는 응모권 수보다 많을 수 없습니다.' };
    return { name, priceCoin, stock, winnerCount, closesAt: localInputToIso(d.closesAt), active: d.active };
  }
  // 즉시 구매 — 재고 빈칸이면 무제한, winnerCount 0
  const stock = d.stock.trim() === '' ? null : Number(d.stock);
  if (stock !== null && (!Number.isInteger(stock) || stock < 0)) return { error: '재고는 0 이상의 정수이거나 무제한(빈칸)이어야 합니다.' };
  return { name, priceCoin, stock, winnerCount: 0, closesAt: localInputToIso(d.closesAt), active: d.active };
}

export function EventShopSection() {
  const qc = useQueryClient();
  const prizes = useQuery({ queryKey: ['admin', 'prizes'], queryFn: () => adminApi.listPrizes() });
  const [filter, setFilter] = useState<PrizeFulfillmentStatus | 'ALL'>('ALL');
  const [page, setPage] = useState(0);
  const purchases = useQuery({ queryKey: ['admin', 'purchases', filter, page], queryFn: () => adminApi.listPurchases(filter, page, 10) });

  // ── 경품 등록·수정 ──
  const [draft, setDraft] = useState<PrizeDraft | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const savePrize = useMutation({
    mutationFn: (input: PrizeInput & { prizeId: number | null }) =>
      input.prizeId === null ? adminApi.createPrize(input) : adminApi.updatePrize(input.prizeId, input),
    onSuccess: (view, vars) => {
      showToast(vars.prizeId === null ? `경품 등록: ${view.name}` : `경품 수정: ${view.name}`, 'success');
      setDraft(null);
      void qc.invalidateQueries({ queryKey: ['admin', 'prizes'] });
    },
  });
  function submitPrize() {
    if (draft === null) return;
    const result = toInput(draft);
    if ('error' in result) { setFormError(result.error); return; }
    setFormError(null);
    savePrize.mutate({ ...result, prizeId: draft.prizeId });
  }

  // ── 판매 종료/재개 — updatePrize 로 active 만 뒤집는다 ──
  const [toggle, setToggle] = useState<PrizeView | null>(null);
  const togglePrize = useMutation({
    mutationFn: (p: PrizeView) => adminApi.updatePrize(p.prizeId, {
      name: p.name, priceCoin: p.priceCoin, stock: p.stock, closesAt: p.closesAt, winnerCount: p.winnerCount, active: !p.active,
    }),
    onSuccess: (view) => {
      showToast(view.active ? `판매 재개: ${view.name}` : `판매 종료: ${view.name}`, 'success');
      setToggle(null);
      void qc.invalidateQueries({ queryKey: ['admin', 'prizes'] });
    },
  });

  // ── 구매 처리 상태 변경 ──
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
          <div className="ad-toolbar">
            <button type="button" className="sc-btn sc-btn-primary sc-btn-sm" onClick={() => { setDraft(draftFor(null)); setFormError(null); savePrize.reset(); }}>경품 추가</button>
          </div>
        </div>
        {prizes.isPending && <Loading />}
        {prizes.isError && <ErrorBanner error={prizes.error} onRetry={() => void prizes.refetch()} />}
        {prizes.isSuccess && prizes.data.length === 0 && <Empty title="등록된 경품이 없습니다" hint="‘경품 추가’ 로 첫 경품을 넣으세요." />}
        {prizes.isSuccess && prizes.data.length > 0 && (
          <div className="ad-stats">
            {prizes.data.map((p) => (
              <div key={p.prizeId} className="ad-stat">
                <strong>
                  {p.name}
                  <span className={`ad-chip ad-chip-${p.winnerCount > 0 ? 'gold' : 'plain'}`} style={{ marginLeft: 8 }}>{prizeKindLabel(p.winnerCount)}</span>
                  {!p.active && <span className="ad-chip ad-chip-bad" style={{ marginLeft: 6 }}>판매 종료</span>}
                </strong>
                <span>
                  {p.priceCoin.toLocaleString('ko-KR')} 코인 · {p.winnerCount > 0 ? `응모권 ${p.stock ?? 0} · 당첨 ${p.winnerCount}명` : `재고 ${p.stock === null ? '무제한' : p.stock}`}
                  {p.closesAt !== null && ` · 마감 ${fmtTime(p.closesAt)}`}
                  {p.winnerCount > 0 && ` · ${p.drawnAt === null ? '추첨 전' : `추첨 완료(${fmtTime(p.drawnAt)})`}`}
                </span>
                <div className="ad-toolbar" style={{ marginTop: 8 }}>
                  <button type="button" className="sc-btn sc-btn-sm" onClick={() => { setDraft(draftFor(p)); setFormError(null); savePrize.reset(); }}>수정</button>
                  <button type="button" className="sc-btn sc-btn-sm" onClick={() => { setToggle(p); togglePrize.reset(); }}>{p.active ? '판매 종료' : '판매 재개'}</button>
                </div>
              </div>
            ))}
          </div>
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
          </div>
        </div>
        <p className="sc-note">코인 차감과 경품 지급은 다른 사건입니다 — 원장에 차감이 남았다고 물건을 받은 것이 아닙니다.</p>
        {purchases.isPending && <Loading />}
        {purchases.isError && <ErrorBanner error={purchases.error} onRetry={() => void purchases.refetch()} />}
        {purchases.isSuccess && purchases.data.content.length === 0 && <Empty title="구매 내역이 없습니다" />}
        {purchases.isSuccess && purchases.data.content.length > 0 && (
          <>
            <table className="ad-table">
              <thead><tr><th>구매</th><th>구매자</th><th>경품</th><th className="num">수량</th><th className="num">코인</th><th>캠퍼스</th><th>조</th><th>받는 분</th><th>코인 차감</th><th>지급 상태</th><th>시각</th><th /></tr></thead>
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
        open={draft !== null}
        title={draft?.prizeId === null ? '새 경품을 등록합니다' : '경품을 수정합니다'}
        confirmLabel={draft?.prizeId === null ? '등록' : '저장'}
        busy={savePrize.isPending}
        onCancel={() => { if (!savePrize.isPending) setDraft(null); }}
        onConfirm={submitPrize}
      >
        {draft !== null && (
          <div className="ad-form">
            <label className="ad-field" htmlFor="prize-kind">
              <span className="ad-label">종류</span>
              <Select
                id="prize-kind"
                className="ad-select-block"
                value={draft.kind}
                options={[{ value: 'PURCHASE', label: '즉시 구매' }, { value: 'RAFFLE', label: '응모형' }]}
                onChange={(v) => setDraft({ ...draft, kind: v as PrizeKind })}
              />
            </label>
            <label className="ad-field" htmlFor="prize-name">
              <span className="ad-label">이름</span>
              <input id="prize-name" className="ad-input" value={draft.name} maxLength={200} onChange={(e) => setDraft({ ...draft, name: e.target.value })} placeholder="예: 무선 이어폰" />
            </label>
            <label className="ad-field" htmlFor="prize-price">
              <span className="ad-label">가격(코인)</span>
              <input id="prize-price" className="ad-input" type="number" min={0} value={draft.priceCoin} onChange={(e) => setDraft({ ...draft, priceCoin: e.target.value })} placeholder="예: 500" />
            </label>
            {draft.kind === 'PURCHASE' ? (
              <label className="ad-field" htmlFor="prize-stock">
                <span className="ad-label">재고 <em>빈칸이면 무제한</em></span>
                <input id="prize-stock" className="ad-input" type="number" min={0} value={draft.stock} onChange={(e) => setDraft({ ...draft, stock: e.target.value })} placeholder="무제한" />
              </label>
            ) : (
              <>
                <label className="ad-field" htmlFor="prize-stock">
                  <span className="ad-label">응모권 수</span>
                  <input id="prize-stock" className="ad-input" type="number" min={1} value={draft.stock} onChange={(e) => setDraft({ ...draft, stock: e.target.value })} placeholder="예: 100" />
                </label>
                <label className="ad-field" htmlFor="prize-winners">
                  <span className="ad-label">당첨자 수</span>
                  <input id="prize-winners" className="ad-input" type="number" min={1} value={draft.winnerCount} onChange={(e) => setDraft({ ...draft, winnerCount: e.target.value })} placeholder="예: 1" />
                </label>
              </>
            )}
            <label className="ad-field" htmlFor="prize-closes">
              <span className="ad-label">마감 시각 <em>선택</em></span>
              <input id="prize-closes" className="ad-input" type="datetime-local" value={draft.closesAt} onChange={(e) => setDraft({ ...draft, closesAt: e.target.value })} />
            </label>
            {formError !== null && <p className="ad-field-error">{formError}</p>}
            {savePrize.isError && <ErrorBanner error={savePrize.error} />}
          </div>
        )}
      </ConfirmDialog>

      <ConfirmDialog
        open={toggle !== null}
        title={toggle?.active ? '판매를 종료합니다' : '판매를 재개합니다'}
        target={toggle === null ? undefined : `${toggle.name} · ${prizeKindLabel(toggle.winnerCount)}`}
        confirmLabel={toggle?.active ? '판매 종료' : '판매 재개'}
        danger={toggle?.active === true}
        busy={togglePrize.isPending}
        onCancel={() => { if (!togglePrize.isPending) setToggle(null); }}
        onConfirm={() => { if (toggle !== null) togglePrize.mutate(toggle); }}
      >
        {toggle !== null && (
          <div className="ad-form">
            <p className="sc-note">
              {toggle.active
                ? '판매를 종료하면 회원 상점에서 사라집니다. 이미 구매·응모한 내역은 그대로 남습니다. 삭제가 아니라 언제든 다시 열 수 있습니다.'
                : '다시 회원 상점에 노출됩니다.'}
            </p>
            {togglePrize.isError && <ErrorBanner error={togglePrize.error} />}
          </div>
        )}
      </ConfirmDialog>

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
