// 피드백 목록 — 최초 발견 표시만 여기서 한다. 보상 지급은 코인 조정 화면으로 넘긴다(같은 사건을
// 두 화면이 각자 지급하면 어긋난다).
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../../entities/admin/api.select';
import { openAdminSection, selectAdminUser } from '../model/consoleState';
import { showToast } from '../../../shared/ui/toast/toastStore';
import { Empty, ErrorBanner, Loading, Pager, fmtTime } from './common';

export function FeedbackSection() {
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const feedback = useQuery({ queryKey: ['admin', 'feedback', page], queryFn: () => adminApi.listFeedback(page, 20) });

  const toggleFirstFound = useMutation({
    mutationFn: (args: { feedbackId: number; firstFound: boolean }) =>
      adminApi.setFeedbackFirstFound(args.feedbackId, args.firstFound),
    onSuccess: (view) => {
      showToast(view.firstFound ? `#${view.feedbackId}를 최초 발견으로 표시했습니다` : `#${view.feedbackId} 최초 발견 표시를 해제했습니다`, 'success');
      void qc.invalidateQueries({ queryKey: ['admin', 'feedback'] });
    },
  });

  function goRewardThisMember(userId: number) {
    selectAdminUser(userId);
    openAdminSection('wallets');
  }

  return (
    <section className="sc-card ac-work" aria-label="피드백">
      <p className="sc-note">최초 발견 보상은 여기서 지급하지 않습니다 — "코인 조정"으로 넘어가 해당 회원에게 직접 지급하세요.</p>
      {feedback.isPending && <Loading />}
      {feedback.isError && <ErrorBanner error={feedback.error} onRetry={() => void feedback.refetch()} />}
      {feedback.isSuccess && feedback.data.content.length === 0 && <Empty title="피드백이 없습니다" />}
      {feedback.isSuccess && feedback.data.content.length > 0 && (
        <>
          <table className="ac-table">
            <thead><tr><th>작성자</th><th>내용</th><th>최초 발견</th><th>시각</th><th /></tr></thead>
            <tbody>
              {feedback.data.content.map((f) => (
                <tr key={f.feedbackId}>
                  <td>{f.nickname ?? '알 수 없음'} <span className="ac-muted">#{f.userId}</span></td>
                  <td>{f.content}</td>
                  <td>
                    <label className="ac-toggle">
                      <input
                        type="checkbox"
                        checked={f.firstFound}
                        disabled={toggleFirstFound.isPending}
                        onChange={(e) => toggleFirstFound.mutate({ feedbackId: f.feedbackId, firstFound: e.target.checked })}
                      />
                      {f.firstFound ? <span className="ac-chip ac-chip-gold">최초 발견</span> : <span className="ac-muted">표시</span>}
                    </label>
                  </td>
                  <td>{fmtTime(f.createdAt)}</td>
                  <td><button type="button" className="sc-btn sc-btn-sm" onClick={() => goRewardThisMember(f.userId)}>코인 조정</button></td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pager page={feedback.data.page} totalPages={feedback.data.totalPages} onChange={setPage} />
        </>
      )}
    </section>
  );
}
