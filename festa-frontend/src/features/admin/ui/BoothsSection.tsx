// 부스 관리 — 관리자 부스 목록, 행에서 그 부스의 운영 관리, 강제 비공개.
//
// **운영 관리를 콘솔 안에서 연다** (S15P21A604-951, GitLab #252). 월드 오버레이로 전환하면
// `openManagementDetail` 이 메뉴 패널(=이 콘솔)을 닫아 버려, 부스를 여러 개 훑으려면 매번 콘솔을
// 다시 열어야 한다. 본문은 `ManagementPanelHost` 가 쓰는 것과 같은 컴포넌트이고 껍데기만 inline 이다.
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../../entities/admin/api.select';
import type { AdminBoothView } from '../../../entities/admin/types';
import type { ManagementPanelKind } from '../../world/model/managementPanel';
import { ManagementPanelHost } from '../../booth/ui/ManagementPanelHost';
import { notifyBoothSlotChanged } from '../../../unity/host/boothLayoutBridge';
import { showToast } from '../../../shared/ui/toast/toastStore';
import { ConfirmDialog, Empty, ErrorBanner, Loading, ReasonField, fmtTime, reasonProblem } from './common';

/** 행에서 바로 갈 수 있는 운영 화면. `ManagementPanel` 의 네 종류를 그대로 쓴다 */
const PANELS: { kind: ManagementPanelKind; label: string }[] = [
  { kind: 'project', label: '프로젝트' },
  { kind: 'survey', label: '설문' },
  { kind: 'consultation', label: '상담' },
  { kind: 'ai-agent', label: 'AI 직원' },
];

interface Opened {
  booth: AdminBoothView;
  kind: ManagementPanelKind;
}

export function BoothsSection() {
  const qc = useQueryClient();
  const booths = useQuery({ queryKey: ['admin', 'booths'], queryFn: () => adminApi.listBooths() });
  const [target, setTarget] = useState<AdminBoothView | null>(null);
  const [reason, setReason] = useState('');
  const [opened, setOpened] = useState<Opened | null>(null);
  const unpublish = useMutation({
    mutationFn: (b: AdminBoothView) => adminApi.unpublishBooth(b.boothId, reason.trim()),
    onSuccess: (_v, b) => {
      showToast(`${b.name} 를 비공개했습니다`, 'success');
      // 강제 비공개는 임대까지 회수한다(S15P21A604-927) — 상주 월드가 게시본을 한 번만 읽으므로
      // 슬롯 변경을 알리지 않으면 그 칸이 옛 모습으로 남는다(GitLab #254).
      // 자리 없는 부스(임대 만료·회수 뒤 콘텐츠만 남은 회원 부스, #263)는 월드에 칸이 없어 알릴 것도 없다.
      if (b.slotId !== null) notifyBoothSlotChanged(b.slotId);
      // 그 부스를 열어 둔 채였다면 함께 닫는다 — 목록에서 사라진 부스의 운영 화면이 남으면
      // 없는 대상에 대고 저장하게 된다.
      setOpened((o) => (o !== null && o.booth.boothId === b.boothId ? null : o));
      setTarget(null); setReason(''); void qc.invalidateQueries({ queryKey: ['admin', 'booths'] });
    },
  });

  if (opened !== null) {
    return (
      <section className="sc-card ac-work" aria-label="부스 운영 관리">
        <div className="ac-toolbar">
          <button type="button" className="sc-btn sc-btn-sm" onClick={() => setOpened(null)}>← 부스 목록</button>
          <span className="ac-muted">{opened.booth.slotCode} · {opened.booth.name} (#{opened.booth.boothId})</span>
        </div>
        <ManagementPanelHost
          panel={{ kind: opened.kind, boothId: opened.booth.boothId }}
          onClose={() => setOpened(null)}
          chrome="inline"
        />
      </section>
    );
  }

  return (
    <section className="sc-card ac-work" aria-label="부스 목록">
      {/* 목록은 관리자 부스와 회원 부스를 함께 준다(#263, S15P21A604-959). 필터 없이 그대로 그린다 —
          운영 관리 4종은 `BoothAccessGuard` 가 관리자를 통과시키므로 회원 부스에서도 동작한다. */}
      <p className="sc-note">관리자 부스와 회원 부스를 모두 다룹니다. 미게시 부스도 목록에 남고, 강제 비공개는 공개 포인터를 내리면서 자리까지 회수합니다 — 되돌릴 수 없습니다.</p>
      {booths.isPending && <Loading />}
      {booths.isError && <ErrorBanner error={booths.error} onRetry={() => void booths.refetch()} />}
      {booths.isSuccess && booths.data.length === 0 && <Empty title="운영 중인 부스가 없습니다" />}
      {booths.isSuccess && booths.data.length > 0 && (
        <table className="ac-table">
          <thead><tr><th>슬롯</th><th>부스</th><th>공개</th><th>설치</th><th>운영 관리</th><th /></tr></thead>
          <tbody>
            {booths.data.map((b) => (
              <tr key={b.boothId}>
                {/* 자리가 없는 부스 — 임대 전이거나 회수된 뒤다. 빈 칸으로 두면 열이 밀려 보인다 */}
                <td>{b.slotCode ?? '—'}</td>
                <td>{b.name} <span className="ac-muted">#{b.boothId}</span></td>
                <td>{b.published ? <span className="ac-chip ac-chip-ok">공개</span> : <span className="ac-chip ac-chip-plain">비공개</span>}</td>
                <td>{fmtTime(b.leaseStartedAt)}{b.installedBy !== null && <> <span className="ac-muted">{b.installedBy}</span></>}</td>
                <td>
                  <div className="ac-toolbar">
                    {PANELS.map((p) => (
                      <button key={p.kind} type="button" className="sc-btn sc-btn-sm" onClick={() => setOpened({ booth: b, kind: p.kind })}>{p.label}</button>
                    ))}
                  </div>
                </td>
                <td>
                  <button type="button" className="sc-btn sc-btn-sm ac-btn-danger" disabled={!b.published} onClick={() => { setTarget(b); unpublish.reset(); }}>강제 비공개</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <ConfirmDialog
        open={target !== null}
        title="부스를 강제 비공개합니다"
        target={target === null ? undefined : `${target.slotCode ?? '자리 없음'} · ${target.name} (#${target.boothId})`}
        confirmLabel="비공개"
        danger
        busy={unpublish.isPending}
        onCancel={() => { if (!unpublish.isPending) { setTarget(null); setReason(''); } }}
        onConfirm={() => { if (target !== null && reasonProblem(reason) === null) unpublish.mutate(target); }}
      >
        {/* 같은 버튼이 두 종류에서 다르게 끝난다(#263) — 회원 부스는 콘텐츠가 보존되지만 관리자 부스는
            반납이 곧 삭제다. 되돌리는 길은 어느 쪽도 없다: 게시가 유효한 임대를 요구하므로 자리가 빈
            뒤에는 재임대만이 경로다. */}
        <p className="sc-note">
          {(target?.adminOwned ?? true)
            ? '방문자에게 즉시 보이지 않게 되고 자리도 회수됩니다. 관리자 부스는 반납이 곧 삭제라 프로젝트·설문·AI 문서가 함께 사라지고 되살릴 수 없습니다.'
            : '방문자에게 즉시 보이지 않게 되고 자리도 회수됩니다. 소유자의 Draft 와 게시 이력은 남지만, 다시 세우려면 그 회원이 재임대해야 합니다.'}
          {' '}사유는 감사 기록에 그대로 남습니다.
        </p>
        <ReasonField id="unpublish-reason" label="비공개 사유" value={reason} onChange={setReason} placeholder="예: 신고 접수 — 부적절한 이미지" />
        {unpublish.isError && <ErrorBanner error={unpublish.error} />}
      </ConfirmDialog>
    </section>
  );
}
