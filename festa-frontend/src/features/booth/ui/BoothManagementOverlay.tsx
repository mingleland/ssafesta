// Booth Management Overlay — 내가 운영 중인 하나의 Booth 를 중심으로 제작·콘텐츠·운영을
// 관리하는 통합 인터페이스 (user-flow-decisions §15·§16, D-08).
//
// Dashboard 가 아니다. 방문자 수·전환율 같은 지표를 만들지 않는다 — 있는 데이터만 쓴다.
// 구조는 A+D 혼합: 상단은 Booth 자체가 주인공(D), 각 관리 기능은 상세 화면으로 Drill-down(A).
// Project/Survey Editor 를 이 화면에 펼치지 않는다.
//
// 진입은 World 의 Booth Management NPC + F 다. Unity 이벤트 계약(G-1)이 아직 없어 지금은
// dev trigger 로만 열리며, 계약이 오면 dispatcher 가 openBoothManagement() 를 부르면 된다 —
// 이 컴포넌트는 그대로다.
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { leaseApi } from '../../../entities/booth/leaseApi.select';
import { facadeApi } from '../../../entities/booth/facadeApi.select';
import { getAiAgent } from '../../../entities/aiAgent/api.select';
import { formatRemaining, remainingMs } from '../../../entities/booth/remaining';
import { OverlayEmpty, OverlayError, OverlayFrame, OverlayLoading } from '../../overlay/ui/OverlayFrame';
import { useSession } from '../../auth/model/session';
import { openManagementDetail } from '../../world/model/worldScreen';
import type { ManagementPanel, ManagementPanelKind } from '../../world/model/managementPanel';
import { BoothMiniPreview } from './BoothMiniPreview';
import { LeaseCancelDialog } from './LeaseCancelDialog';
import { isStaleViewError, useCancelLease } from '../model/cancelLease';
import './boothManagement.css';

const IcBooth = (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M4 9h16v11H4zM3 9l2-4h14l2 4M9 20v-6h6v6" />
  </svg>
);

const IcChevron = (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M9 5l7 7-7 7" />
  </svg>
);

interface Props {
  onClose: () => void;
}

/** 관리 섹션 한 줄 — 요약 + [관리 >]. 상세는 별도 화면이다 */
function SectionRow({
  label,
  summary,
  onOpen,
}: {
  label: string;
  summary: string;
  onOpen: () => void;
}) {
  return (
    <button type="button" className="bm-row" onClick={onOpen}>
      <span className="bm-row-label">{label}</span>
      <span className="bm-row-summary">{summary}</span>
      <span className="bm-row-cta">
        관리
        {IcChevron}
      </span>
    </button>
  );
}

export function BoothManagementOverlay({ onClose }: Props) {
  const navigate = useNavigate();
  const [showAgentGate, setShowAgentGate] = useState(false);
  const [showCancel, setShowCancel] = useState(false);
  const cancelLease = useCancelLease(() => setShowCancel(false));

  // 게스트는 GET /booths/mine 이 403 MEMBER_ONLY 다 — 요청 자체를 만들지 않는다. 예전에는
  // 이 가드가 없어 확정 거절을 재시도했고, 스피너만 도는 채로 요청 폭풍이 났다(GitLab #139).
  const { kind } = useSession();
  const isMember = kind === 'member';
  const myBoothQuery = useQuery({
    queryKey: ['my-booth'],
    queryFn: leaseApi.getMyBooth,
    enabled: isMember,
  });
  const myBooth = myBoothQuery.data ?? null;
  const boothId = myBooth?.boothId ?? null;

  // facade 는 부스가 있을 때만 — Mini Preview 의 유일한 데이터원이다
  const boothQuery = useQuery({
    queryKey: ['booth-detail', boothId],
    queryFn: () => facadeApi.getBooth(boothId as number),
    enabled: boothId !== null,
  });

  // AI 직원 등록 여부만 본다 — 탭 내용(문서 등)은 AiAgentManagementTab 이 따로 불러온다.
  // 미등록(data === null)이 확인됐을 때만 행 클릭을 팝업으로 가로챈다. 아직 로딩 중이거나
  // 조회에 실패했으면 원래대로 이동시킨다 — 그 화면이 자기 로딩/에러 상태를 이미 보여준다.
  const aiAgentQuery = useQuery({
    queryKey: ['ai-agent', boothId],
    queryFn: () => getAiAgent(boothId as number),
    enabled: boothId !== null,
  });

  // 관리 상세로 들어간다. **route 로 나가지 않는다** (S15P21A604-755) — 나가면 월드가 화면에서
  // 사라지고, 돌아오는 길을 URL 로 다시 만들어야 했다. 지금은 이 화면 위에 얹히고 ESC 한 번이면
  // 여기로 돌아온다. deep-link 는 route 가 그대로 받는다.
  function openPanel(kind: ManagementPanelKind, boothId: number) {
    openManagementDetail({ kind, boothId } as ManagementPanel);
  }

  // 부스가 없을 때 임대 화면처럼 월드 밖으로 나가는 자리는 그대로 route 다
  function go(path: string) {
    onClose();
    navigate(path);
  }

  function openAiAgentSection() {
    if (aiAgentQuery.data === null) {
      setShowAgentGate(true);
      return;
    }
    openPanel('ai-agent', boothId as number);
  }

  const body = (() => {
    // 게스트에게는 오류가 아니라 사실을 말한다 — 다시 시도해도 결과가 같으므로 재시도 버튼도 주지 않는다
    if (!isMember) {
      return (
        <OverlayEmpty
          title="로그인하면 부스를 빌릴 수 있어요"
          hint="게스트는 축제장을 둘러볼 수 있고, 부스 운영은 회원 계정에서 할 수 있습니다."
        />
      );
    }
    if (myBoothQuery.isLoading) return <OverlayLoading label="부스 정보를 불러오는 중..." />;
    if (myBoothQuery.isError) {
      return (
        <OverlayError
          title="부스 정보를 불러오지 못했습니다"
          message="잠시 후 다시 시도해 주세요."
          onRetry={() => void myBoothQuery.refetch()}
        />
      );
    }

    // 부스 없음 — GET /booths/mine 이 204 를 주는 상태. 임대 흐름으로 보낸다(새 계약 없음)
    if (myBooth === null || myBooth.lease === null) {
      return (
        <div className="bm-empty">
          <span className="bm-empty-icon" aria-hidden="true">
            {IcBooth}
          </span>
          <strong>아직 운영 중인 부스가 없습니다</strong>
          <p className="ov-note">부스를 임대하면 전시 공간을 꾸미고 콘텐츠를 운영할 수 있습니다.</p>
          <button type="button" className="ov-btn ov-btn-primary" onClick={() => go('/app/booths')}>
            부스 임대하기
          </button>
        </div>
      );
    }

    const lease = myBooth.lease;
    const expired = remainingMs(lease.endsAt, Date.now()) === 0;

    return (
      <div className="bm-body">
        {/* D — Booth 자체가 주인공 */}
        <section className="bm-hero">
          <div className="bm-preview">
            {/* facade 실패를 조용히 빈 미리보기로 만들지 않는다 — 없는 것과 못 불러온 것은 다르다 */}
            {boothQuery.isError ? (
              <OverlayError
                title="미리보기를 불러오지 못했습니다"
                onRetry={() => void boothQuery.refetch()}
              />
            ) : (
              <BoothMiniPreview facade={boothQuery.data?.facade ?? null} boothName={myBooth.name} />
            )}
          </div>
          <div className="bm-identity">
            <h3 className="bm-name">{myBooth.name}</h3>
            <span className="bm-slot">{lease.slotCode ?? '슬롯 미연결'}</span>
            <span className={'bm-status' + (expired ? ' bm-status-off' : '')}>
              <span className="bm-dot" aria-hidden="true" />
              {expired ? '임대 만료' : '운영 중'}
            </span>
            <span className="ov-note bm-remaining">
              {expired ? '임대가 끝났습니다' : `임대 ${formatRemaining(remainingMs(lease.endsAt, Date.now()))} 남음`}
            </span>
            <button
              type="button"
              className="ov-btn ov-btn-primary bm-studio"
              onClick={() => openPanel('studio', myBooth.boothId)}
            >
              부스 스튜디오 열기
            </button>
          </div>
        </section>

        <div className="bm-rows">
          <SectionRow
            label="PROJECT"
            summary="전시 프로젝트 등록·수정"
            onOpen={() => openPanel('project', myBooth.boothId)}
          />
          <SectionRow
            label="SURVEY"
            summary="설문 편집·응답 결과"
            onOpen={() => openPanel('survey', myBooth.boothId)}
          />
          <SectionRow
            label="CONSULTATION"
            summary="상담 요청 운영"
            onOpen={() => openPanel('consultation', myBooth.boothId)}
          />
          {/* AI 직원도 다른 세 항목과 같은 drill-down — 문서 업로드가 있어 화면이 길어지므로
              내 부스 관리 카드 안에 펼치지 않고 별도 화면으로 연다. */}
          <SectionRow
            label="AI 직원"
            summary="대화 설정·답변 근거 문서 관리"
            onOpen={openAiAgentSection}
          />
        </div>

        <section className="bm-info">
          <span className="bm-info-title">부스 정보</span>
          <span className="ov-note">
            {lease.slotCode ?? '슬롯 미연결'} · {expired ? '임대 만료' : '임대 중'} ·{' '}
            {new Intl.DateTimeFormat('ko-KR', {
              timeZone: 'Asia/Seoul',
              dateStyle: 'short',
              timeStyle: 'short',
            }).format(new Date(lease.endsAt))}{' '}
            종료
          </span>
          {/* 만료된 임대에는 반납할 것이 없다 — 서버도 404 를 준다 */}
          {!expired && (
            <button type="button" className="ov-btn bm-cancel" onClick={() => setShowCancel(true)}>
              부스 반납하기
            </button>
          )}
          {/* 404 는 오류가 아니라 낡은 화면이라 배너를 만들지 않는다(cancelLease.ts). 그 밖의
              실패만 말한다 — 401·403 MEMBER_ONLY 가 여기로 온다 */}
          {cancelLease.isError && !isStaleViewError(cancelLease.error) && (
            <span className="ov-note bm-cancel-error" role="alert">
              반납하지 못했습니다. 잠시 후 다시 시도해 주세요.
            </span>
          )}
        </section>
      </div>
    );
  })();

  return (
    <>
      <OverlayFrame title="내 부스 관리" subtitle="제작·콘텐츠·운영" size="xl" icon={IcBooth} onClose={onClose}>
        {body}
      </OverlayFrame>
      {showCancel && myBooth?.lease && (
        <LeaseCancelDialog
          slotCode={myBooth.lease.slotCode ?? null}
          pending={cancelLease.isPending}
          onConfirm={() => cancelLease.mutate(myBooth.lease!.slotId)}
          onCancel={() => setShowCancel(false)}
        />
      )}
      {showAgentGate && myBooth && (
        <div className="bm-gate-backdrop" role="presentation" onClick={() => setShowAgentGate(false)}>
          <div
            className="bm-gate-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="bm-gate-title"
            onClick={(event) => event.stopPropagation()}
          >
            <h3 id="bm-gate-title">AI 직원 등록이 필요합니다.</h3>
            <p>먼저 AI 직원을 등록해야 대화 설정과 문서를 관리할 수 있습니다.</p>
            <div className="bm-gate-actions">
              <button type="button" className="ov-btn" onClick={() => setShowAgentGate(false)}>
                닫기
              </button>
              <button
                type="button"
                className="ov-btn ov-btn-primary"
                onClick={() => {
                  setShowAgentGate(false);
                  openPanel('ai-agent', myBooth.boothId);
                }}
              >
                AI 직원 등록하러 가기
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
