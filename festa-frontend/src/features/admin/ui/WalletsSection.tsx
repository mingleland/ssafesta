// 코인·지갑 — 대상 선택 → 잔액 → 거래 내역 → 조정. 멱등키 수명이 이 화면의 핵심이다(model/adjustmentKey).
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../../entities/admin/api.select';
import { labelForReason } from '../../../entities/wallet/types';
import { showToast } from '../../../shared/ui/toast/toastStore';
import { createAdjustmentDraft, draftProblem, editDraft, type AdjustmentDraft } from '../model/adjustmentKey';
import { Empty, ErrorBanner, GatedButton, KeyValue, Loading, Pager, fmtCoin, fmtTime } from './common';
import { MemberSearch } from './MemberSearch';

export function WalletsSection({ userId, onSelect }: { userId: number | null; onSelect: (userId: number | null) => void }) {
  return (
    <div className="ad-split">
      <MemberSearch selectedUserId={userId} onSelect={onSelect} />
      {userId === null ? (
        <section className="sc-card"><Empty title="회원을 선택하세요" hint="잔액·거래 내역을 보고 코인을 조정합니다." /></section>
      ) : (
        <WalletDetail userId={userId} />
      )}
    </div>
  );
}

function WalletDetail({ userId }: { userId: number }) {
  const qc = useQueryClient();
  const member = useQuery({ queryKey: ['admin', 'member', userId], queryFn: () => adminApi.getMember(userId) });
  const balance = useQuery({ queryKey: ['admin', 'balance', userId], queryFn: () => adminApi.getBalance(userId) });
  const [page, setPage] = useState(0);
  const ledger = useQuery({ queryKey: ['admin', 'ledger', userId, page], queryFn: () => adminApi.getLedger(userId, page, 10) });

  // 폼을 열 때 키를 하나 만들고 붙든다. 실패 후 재시도는 같은 키, "새 조정" 만 새 키다.
  const [draft, setDraft] = useState<AdjustmentDraft | null>(null);
  const [amountText, setAmountText] = useState('');

  const adjust = useMutation({
    mutationFn: (d: AdjustmentDraft) => adminApi.adjustCoins(userId, d.key, d.signedAmount, d.note.trim() === '' ? undefined : d.note.trim()),
    onSuccess: (result) => {
      showToast(result.alreadyApplied ? '이미 처리된 조정입니다 — 첫 결과를 그대로 보여 드립니다' : `조정 완료 · 잔액 ${result.balanceAfter.toLocaleString('ko-KR')} 코인`, 'success');
      void qc.invalidateQueries({ queryKey: ['admin', 'balance', userId] });
      void qc.invalidateQueries({ queryKey: ['admin', 'ledger', userId] });
    },
  });

  const startDraft = () => { setDraft(createAdjustmentDraft()); setAmountText(''); adjust.reset(); };
  const closeDraft = () => { setDraft(null); adjust.reset(); };
  const problem = draft === null ? null : draftProblem(draft);
  const conflict = adjust.isError && (adjust.error as { code?: string }).code === 'IDEMPOTENCY_CONFLICT';
  const masterGate = member.data?.master ? '마스터 계정은 변경할 수 없습니다.' : null;

  return (
    <section className="sc-card ad-work" aria-label="지갑 상세">
      <div className="ad-head">
        <h2>{member.data?.nickname ?? `회원 #${userId}`} <span className="ad-muted">#{userId}</span></h2>
        {balance.isSuccess && <strong>{balance.data.balance.toLocaleString('ko-KR')} 코인</strong>}
      </div>
      {balance.isError && <ErrorBanner error={balance.error} onRetry={() => void balance.refetch()} />}

      {draft === null ? (
        <div className="ad-actions">
          <GatedButton gate={masterGate} className="sc-btn sc-btn-primary" onClick={startDraft}>코인 조정 시작</GatedButton>
        </div>
      ) : (
        <form className="ad-form" aria-label="코인 조정" onSubmit={(e) => { e.preventDefault(); if (problem === null && !conflict) adjust.mutate(draft); }}>
          <KeyValue rows={[['요청 키', <code key="k" className="ad-muted">{draft.key}</code>]]} />
          <label className="ad-field" htmlFor="adjust-amount">
            <span className="ad-label">금액 <em>양수 지급 · 음수 회수</em></span>
            <input id="adjust-amount" className="ad-input" inputMode="numeric" value={amountText} disabled={adjust.isSuccess}
              onChange={(e) => { setAmountText(e.target.value); setDraft(editDraft(draft, { signedAmount: Number(e.target.value) })); }} placeholder="예: 100 또는 -50" />
            {amountText !== '' && problem !== null && <span className="ad-field-error">{problem}</span>}
          </label>
          <label className="ad-field" htmlFor="adjust-note">
            <span className="ad-label">사유 <em>감사 기록에 남는다</em></span>
            <input id="adjust-note" className="ad-input" value={draft.note} maxLength={500} disabled={adjust.isSuccess}
              onChange={(e) => setDraft(editDraft(draft, { note: e.target.value }))} placeholder="예: 이벤트 보상 누락 보정" />
          </label>
          <div className="ad-actions">
            {adjust.isSuccess ? (
              <>
                <span className="ad-ok">
                  {adjust.data.alreadyApplied ? '이미 처리된 조정입니다 (같은 키 재요청).' : '반영됐습니다.'} 조정 후 잔액 {adjust.data.balanceAfter.toLocaleString('ko-KR')} 코인 · 원장 #{adjust.data.entryId}
                </span>
                <button type="button" className="sc-btn sc-btn-primary" onClick={startDraft}>새 조정 시작</button>
              </>
            ) : conflict ? (
              <button type="button" className="sc-btn sc-btn-primary" onClick={startDraft}>새 조정 시작</button>
            ) : (
              <>
                <button type="submit" className="sc-btn sc-btn-primary" disabled={problem !== null || adjust.isPending}>
                  {adjust.isPending ? '처리 중...' : adjust.isError ? '같은 요청 다시 시도' : '조정 실행'}
                </button>
                <button type="button" className="sc-btn" disabled={adjust.isPending} onClick={closeDraft}>취소</button>
              </>
            )}
          </div>
          {adjust.isError && <ErrorBanner error={adjust.error} />}
        </form>
      )}

      <h3 className="sc-section-title">거래 내역</h3>
      {ledger.isPending && <Loading />}
      {ledger.isError && <ErrorBanner error={ledger.error} onRetry={() => void ledger.refetch()} />}
      {ledger.isSuccess && ledger.data.content.length === 0 && <Empty title="거래 내역이 없습니다" />}
      {ledger.isSuccess && ledger.data.content.length > 0 && (
        <>
          <table className="ad-table">
            <thead><tr><th>시각</th><th>사유</th><th className="num">금액</th><th className="num">잔액</th><th>참조</th></tr></thead>
            <tbody>
              {ledger.data.content.map((e) => (
                <tr key={e.id}>
                  <td>{fmtTime(e.createdAt)}</td>
                  <td>{labelForReason(e.reasonType)}</td>
                  <td className="num">{fmtCoin(e.amount)}</td>
                  <td className="num">{e.balanceAfter.toLocaleString('ko-KR')}</td>
                  <td className="ad-muted">{e.referenceType === null ? '—' : `${e.referenceType} ${e.referenceId ?? ''}`}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pager page={ledger.data.page} totalPages={ledger.data.totalPages} onChange={setPage} />
        </>
      )}
    </section>
  );
}

