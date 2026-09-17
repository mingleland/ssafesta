// 회원 관리 — 검색 → 선택 → 상세(상태·권한·지갑·이력) → 정지/해제.
// 조치가 끝나면 상세·이력·검색 목록을 함께 무효화해 세 화면이 같은 상태를 말하게 한다.
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { adminApi } from '../../../entities/admin/api.select';
import { showToast } from '../../../shared/ui/toast/toastStore';
import { ConfirmDialog, Empty, ErrorBanner, GatedButton, KeyValue, Loading, Pager, ReasonField, StatusChip, fmtTime, reasonProblem } from './common';
import { MemberSearch } from './MemberSearch';

export function MembersSection({ userId, onSelect }: { userId: number | null; onSelect: (userId: number | null) => void }) {
  return (
    <div className="ad-split">
      <MemberSearch selectedUserId={userId} onSelect={onSelect} />
      {userId === null ? (
        <section className="sc-card"><Empty title="회원을 선택하세요" hint="왼쪽에서 검색해 고르면 상태·이력·지갑을 여기서 봅니다." /></section>
      ) : (
        <MemberDetail userId={userId} />
      )}
    </div>
  );
}

function MemberDetail({ userId }: { userId: number }) {
  const qc = useQueryClient();
  const member = useQuery({ queryKey: ['admin', 'member', userId], queryFn: () => adminApi.getMember(userId) });
  const balance = useQuery({ queryKey: ['admin', 'balance', userId], queryFn: () => adminApi.getBalance(userId) });
  const [historyPage, setHistoryPage] = useState(0);
  const history = useQuery({ queryKey: ['admin', 'history', userId, historyPage], queryFn: () => adminApi.getStatusHistory(userId, historyPage, 10) });

  const [suspendOpen, setSuspendOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [unsuspendOpen, setUnsuspendOpen] = useState(false);

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ['admin', 'member', userId] });
    void qc.invalidateQueries({ queryKey: ['admin', 'history', userId] });
    void qc.invalidateQueries({ queryKey: ['admin', 'members'] });
    void qc.invalidateQueries({ queryKey: ['admin', 'admins'] });
  };

  const suspend = useMutation({
    mutationFn: () => adminApi.suspendMember(userId, reason.trim()),
    onSuccess: () => { setSuspendOpen(false); setReason(''); showToast('계정을 정지했습니다', 'success'); invalidate(); },
  });
  const unsuspend = useMutation({
    mutationFn: () => adminApi.unsuspendMember(userId),
    onSuccess: () => { setUnsuspendOpen(false); showToast('정지를 해제했습니다', 'success'); invalidate(); },
  });

  if (member.isPending) return <section className="sc-card"><Loading /></section>;
  if (member.isError) return <section className="sc-card"><ErrorBanner error={member.error} onRetry={() => void member.refetch()} /></section>;
  const m = member.data;
  const masterGate = m.master ? '마스터 계정은 변경할 수 없습니다.' : null;

  return (
    <section className="sc-card ad-work" aria-label="회원 상세">
      <div className="ad-head">
        <h2>{m.nickname} <span className="ad-muted">#{m.userId}</span></h2>
        <div className="ad-actions">
          <StatusChip status={m.status} />
          {m.master && <span className="ad-chip ad-chip-master">마스터</span>}
          {m.admin && !m.master && <span className="ad-chip ad-chip-gold">관리자</span>}
        </div>
      </div>
      <KeyValue rows={[
        ['가입', fmtTime(m.joinedAt)],
        ['로그인', m.providers.join(', ') || '—'],
        ['코인 잔액', balance.isSuccess ? `${balance.data.balance.toLocaleString('ko-KR')} 코인` : balance.isError ? '조회 실패' : '...'],
      ]} />
      <div className="ad-actions">
        {m.status === 'SUSPENDED' ? (
          <GatedButton gate={masterGate} onClick={() => setUnsuspendOpen(true)}>정지 해제</GatedButton>
        ) : (
          <GatedButton gate={masterGate} className="sc-btn ad-btn-danger" onClick={() => setSuspendOpen(true)}>계정 정지</GatedButton>
        )}
        <Link className="sc-btn" to={`/app/admin/wallets?userId=${m.userId}`}>코인 조정</Link>
      </div>
      {suspend.isError && <ErrorBanner error={suspend.error} />}
      {unsuspend.isError && <ErrorBanner error={unsuspend.error} />}

      <h3 className="sc-section-title">상태 이력</h3>
      {history.isPending && <Loading />}
      {history.isError && <ErrorBanner error={history.error} onRetry={() => void history.refetch()} />}
      {history.isSuccess && history.data.content.length === 0 && <Empty title="이력이 없습니다" />}
      {history.isSuccess && history.data.content.length > 0 && (
        <>
          <table className="ad-table">
            <thead><tr><th>시각</th><th>변경</th><th>사유</th><th>처리자</th></tr></thead>
            <tbody>
              {history.data.content.map((h, i) => (
                <tr key={`${h.createdAt}-${i}`}>
                  <td>{fmtTime(h.createdAt)}</td>
                  <td><StatusChip status={h.previousStatus} /> → <StatusChip status={h.currentStatus} /></td>
                  <td>{h.reason ?? <span className="ad-muted">—</span>}</td>
                  <td className="num">{h.actorUserId === 0 ? '시스템' : `#${h.actorUserId}`}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pager page={history.data.page} totalPages={history.data.totalPages} onChange={setHistoryPage} />
        </>
      )}

      <ConfirmDialog
        open={suspendOpen}
        title="계정을 정지합니다"
        target={`${m.nickname} (#${m.userId})`}
        confirmLabel="정지"
        danger
        busy={suspend.isPending}
        onCancel={() => { if (!suspend.isPending) setSuspendOpen(false); }}
        onConfirm={() => { if (reasonProblem(reason) === null) suspend.mutate(); }}
      >
        <p className="sc-note">정지되면 즉시 모든 요청이 거절되고 월드에서도 나갑니다. 사유는 이력과 감사 기록에 그대로 남습니다.</p>
        <ReasonField id="suspend-reason" label="정지 사유" value={reason} onChange={setReason} placeholder="예: 부스 내 욕설 신고 3건" />
        {reason !== '' && reasonProblem(reason) !== null && <span className="ad-field-error">{reasonProblem(reason)}</span>}
      </ConfirmDialog>
      <ConfirmDialog
        open={unsuspendOpen}
        title="정지를 해제합니다"
        target={`${m.nickname} (#${m.userId})`}
        confirmLabel="해제"
        busy={unsuspend.isPending}
        onCancel={() => { if (!unsuspend.isPending) setUnsuspendOpen(false); }}
        onConfirm={() => unsuspend.mutate()}
      >
        <p className="sc-note">해제 즉시 로그인과 월드 입장이 다시 열립니다.</p>
      </ConfirmDialog>
    </section>
  );
}

