// 관리자 관리 — 목록·승격·강등. 마스터 행은 숨기지 않고 조치만 잠근다(이유를 말한다).
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../../entities/admin/api.select';
import type { AdminView } from '../../../entities/admin/types';
import { showToast } from '../../../shared/ui/toast/toastStore';
import { ConfirmDialog, Empty, ErrorBanner, GatedButton, Loading } from './common';

export function AdminsSection() {
  const qc = useQueryClient();
  const admins = useQuery({ queryKey: ['admin', 'admins'], queryFn: () => adminApi.listAdmins() });
  const [userIdText, setUserIdText] = useState('');
  const [note, setNote] = useState('');
  const [demoteTarget, setDemoteTarget] = useState<AdminView | null>(null);

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: ['admin', 'admins'] });
    void qc.invalidateQueries({ queryKey: ['admin', 'members'] });
    void qc.invalidateQueries({ queryKey: ['admin', 'member'] });
  };
  const promote = useMutation({
    mutationFn: () => adminApi.promoteAdmin(Number(userIdText), note.trim() === '' ? undefined : note.trim()),
    onSuccess: (view) => { showToast(`${view.nickname} 님을 관리자로 승격했습니다`, 'success'); setUserIdText(''); setNote(''); invalidate(); },
  });
  const demote = useMutation({
    mutationFn: (target: AdminView) => adminApi.demoteAdmin(target.userId),
    onSuccess: (_v, target) => { showToast(`${target.nickname} 님의 관리자 권한을 거뒀습니다`, 'success'); setDemoteTarget(null); invalidate(); },
  });

  const userIdValid = /^\d+$/.test(userIdText.trim());
  const lastAdmin = admins.isSuccess && admins.data.length <= 1;

  return (
    <div className="ad-split">
      <section className="sc-card ad-work" aria-label="관리자 목록">
        <h3 className="sc-section-title">관리자 목록</h3>
        {admins.isPending && <Loading />}
        {admins.isError && <ErrorBanner error={admins.error} onRetry={() => void admins.refetch()} />}
        {admins.isSuccess && admins.data.length === 0 && <Empty title="관리자가 없습니다" hint="이 상태는 API 로 되돌릴 수 없습니다 — 마이그레이션이 필요합니다." />}
        {admins.isSuccess && admins.data.length > 0 && (
          <table className="ad-table">
            <thead><tr><th>번호</th><th>닉네임</th><th>구분</th><th /></tr></thead>
            <tbody>
              {admins.data.map((a) => (
                <tr key={a.userId}>
                  <td className="num">{a.userId}</td>
                  <td>{a.nickname}</td>
                  <td>{a.master ? <span className="ad-chip ad-chip-master">마스터</span> : <span className="ad-chip ad-chip-gold">관리자</span>}</td>
                  <td>
                    <GatedButton
                      className="sc-btn sc-btn-sm"
                      gate={a.master ? '마스터 계정은 변경할 수 없습니다.' : lastAdmin ? '마지막 관리자는 강등할 수 없습니다. 먼저 다른 관리자를 승격하세요.' : null}
                      onClick={() => setDemoteTarget(a)}
                    >강등</GatedButton>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {demote.isError && <ErrorBanner error={demote.error} />}
      </section>

      <section className="sc-card ad-work" aria-label="관리자 승격">
        <h3 className="sc-section-title">관리자 승격</h3>
        <p className="sc-note">관리자만 관리자를 만들 수 있습니다. 정지된 계정은 승격할 수 없고, 사유는 감사 기록에 그대로 남습니다.</p>
        <form className="ad-form" onSubmit={(e) => { e.preventDefault(); if (userIdValid) promote.mutate(); }}>
          <label className="ad-field" htmlFor="promote-user">
            <span className="ad-label">회원 번호</span>
            <input id="promote-user" className="ad-input" inputMode="numeric" value={userIdText} onChange={(e) => setUserIdText(e.target.value)} placeholder="회원 관리에서 찾은 번호" />
            {userIdText !== '' && !userIdValid && <span className="ad-field-error">숫자만 입력합니다.</span>}
          </label>
          <label className="ad-field" htmlFor="promote-note">
            <span className="ad-label">사유 <em>선택</em></span>
            <input id="promote-note" className="ad-input" value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} placeholder="예: 운영 스태프 합류" />
          </label>
          <div className="ad-actions">
            <button type="submit" className="sc-btn sc-btn-primary" disabled={!userIdValid || promote.isPending}>{promote.isPending ? '처리 중...' : '승격'}</button>
          </div>
          {promote.isError && <ErrorBanner error={promote.error} />}
        </form>
      </section>

      <ConfirmDialog
        open={demoteTarget !== null}
        title="관리자 권한을 거둡니다"
        target={demoteTarget === null ? undefined : `${demoteTarget.nickname} (#${demoteTarget.userId})`}
        confirmLabel="강등"
        danger
        busy={demote.isPending}
        onCancel={() => { if (!demote.isPending) setDemoteTarget(null); }}
        onConfirm={() => { if (demoteTarget !== null) demote.mutate(demoteTarget); }}
      >
        <p className="sc-note">권한은 토큰이 아니라 매 요청 판정이라 다음 요청부터 즉시 막힙니다. 계정은 회원으로 그대로 남습니다.</p>
      </ConfirmDialog>
    </div>
  );
}

