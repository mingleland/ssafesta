// 부스 관리 — 점유 슬롯 목록에서 고르고 강제 비공개. 남의 게시물을 내리는 조치라 대상을 다시 보여 주고 사유를 받는다.
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../../entities/admin/api.select';
import type { AdminBoothView } from '../../../entities/admin/types';
import { notifyBoothSlotChanged } from '../../../unity/host/boothLayoutBridge';
import { showToast } from '../../../shared/ui/toast/toastStore';
import { ConfirmDialog, Empty, ErrorBanner, Loading, ReasonField, fmtTime, reasonProblem } from './common';

export function BoothsSection() {
  const qc = useQueryClient();
  const booths = useQuery({ queryKey: ['admin', 'booths'], queryFn: () => adminApi.listBooths() });
  const [target, setTarget] = useState<AdminBoothView | null>(null);
  const [reason, setReason] = useState('');
  const unpublish = useMutation({
    mutationFn: (b: AdminBoothView) => adminApi.unpublishBooth(b.boothId, reason.trim()),
    onSuccess: (_v, b) => {
      showToast(`${b.boothName ?? `부스 #${b.boothId}`} 를 비공개했습니다`, 'success');
      // 강제 비공개는 임대까지 회수한다(S15P21A604-927) — 상주 월드가 게시본을 한 번만 읽으므로
      // 슬롯 변경을 알리지 않으면 그 칸이 옛 모습으로 남는다(GitLab #254).
      notifyBoothSlotChanged(b.slotId);
      setTarget(null); setReason(''); void qc.invalidateQueries({ queryKey: ['admin', 'booths'] });
    },
  });

  return (
    <section className="sc-card ad-work" aria-label="부스 목록">
      <p className="sc-note">공개 슬롯 조회에서 점유 중인 칸만 보입니다. 강제 비공개는 공개 포인터만 내리고 Draft·임대·코인은 그대로 둡니다.</p>
      {booths.isPending && <Loading />}
      {booths.isError && <ErrorBanner error={booths.error} onRetry={() => void booths.refetch()} />}
      {booths.isSuccess && booths.data.length === 0 && <Empty title="점유 중인 부스가 없습니다" />}
      {booths.isSuccess && booths.data.length > 0 && (
        <table className="ad-table">
          <thead><tr><th>슬롯</th><th>부스</th><th>게시</th><th>임대 만료</th><th /></tr></thead>
          <tbody>
            {booths.data.map((b) => (
              <tr key={b.boothId}>
                <td>{b.slotCode}</td>
                <td>{b.boothName ?? <span className="ad-muted">이름 없음</span>} <span className="ad-muted">#{b.boothId}</span></td>
                <td>{b.entryAvailable ? <span className="ad-chip ad-chip-ok">게시됨</span> : <span className="ad-chip ad-chip-plain">미게시</span>}</td>
                <td>{fmtTime(b.leaseEndsAt)}</td>
                <td>
                  <button type="button" className="sc-btn sc-btn-sm ad-btn-danger" disabled={!b.entryAvailable} onClick={() => { setTarget(b); unpublish.reset(); }}>강제 비공개</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <ConfirmDialog
        open={target !== null}
        title="부스를 강제 비공개합니다"
        target={target === null ? undefined : `${target.slotCode} · ${target.boothName ?? '이름 없음'} (#${target.boothId})`}
        confirmLabel="비공개"
        danger
        busy={unpublish.isPending}
        onCancel={() => { if (!unpublish.isPending) { setTarget(null); setReason(''); } }}
        onConfirm={() => { if (target !== null && reasonProblem(reason) === null) unpublish.mutate(target); }}
      >
        <p className="sc-note">방문자에게 즉시 보이지 않게 됩니다. 소유자의 Draft 와 게시 이력은 남습니다. 사유는 감사 기록에 그대로 남습니다.</p>
        <ReasonField id="unpublish-reason" label="비공개 사유" value={reason} onChange={setReason} placeholder="예: 신고 접수 — 부적절한 이미지" />
        {unpublish.isError && <ErrorBanner error={unpublish.error} />}
      </ConfirmDialog>
    </section>
  );
}

