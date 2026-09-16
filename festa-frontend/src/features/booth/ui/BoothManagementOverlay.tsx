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
//
// 2026-09-16 개편 (S15P21A604-817): 부스 공간·기능형 에셋 배치는 Unity 의 부스별 고정 구성이
// 정본이라 관리창에서 부스 스튜디오로 가는 길만 끊었다. 스튜디오·2.5D·게시 파이프라인은 코드째
// 보존돼 있고 deep-link(/app/studio/:boothId)·ManagementPanel 'studio' 멤버는 그대로 남아 있다.
// 이 화면은 layout publish/rebuild 를 부르지 않는다. 외관 편집은 부스 이름만 인라인으로 남겼다.
import { useEffect, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { leaseApi } from '../../../entities/booth/leaseApi.select';
import { facadeApi } from '../../../entities/booth/facadeApi.select';
import { projectApi } from '../../../entities/project/api.select';
import { getAiAgent } from '../../../entities/aiAgent/api';
import { formatRemaining, remainingMs } from '../../../entities/booth/remaining';
import { OverlayEmpty, OverlayError, OverlayFrame, OverlayLoading } from '../../overlay/ui/OverlayFrame';
import { useSession } from '../../auth/model/session';
import { openManagementDetail } from '../../world/model/worldScreen';
import type { ManagementPanel, ManagementPanelKind } from '../../world/model/managementPanel';
import { SHOW_AI_ASSET_SECTION } from '../model/boothManagementFlags';
import { AiAssetSection } from './AiAssetSection';
import { BoothNameField } from './BoothNameField';
import { BoothPreview } from './BoothPreview';
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

const IcClock = (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <circle cx="12" cy="12" r="9" />
    <path d="M12 7v5l3 2" />
  </svg>
);

const IcReturn = (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M9 14L4 9l5-5M4 9h11a5 5 0 0 1 0 10h-3" />
  </svg>
);

// 운영 관리 4카드의 아이콘 타일 — 색은 사진 레퍼런스(docs/LJH/ui-design/00_context/sources/booth-management.png)
const OPS_ICONS: Record<'project' | 'survey' | 'consultation' | 'ai-agent', ReactNode> = {
  project: (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M6 3h8l4 4v14H6zM14 3v4h4M9 12h6M9 16h6" />
    </svg>
  ),
  survey: (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true">
      <path d="M6 20V12M12 20V5M18 20v-9" />
    </svg>
  ),
  consultation: (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" aria-hidden="true">
      <path d="M4 5h16v11H9l-5 4z" />
    </svg>
  ),
  'ai-agent': (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M12 4a5 5 0 0 1 5 5v3a5 5 0 0 1-10 0V9a5 5 0 0 1 5-5zM9 12h6M5 10v4M19 10v4M12 17v3" />
    </svg>
  ),
};

interface Props {
  onClose: () => void;
}

/**
 * 운영 관리 카드 하나 — 아이콘 타일 + 제목 + 부제 + chevron. 상세는 별도 화면이다.
 *
 * `summary` 를 배열로 받는 이유는 줄바꿈 때문이다. 한 문자열로 두면 열 너비가 좁을 때
 * "근거 문/서 관리" 처럼 낱말 가운데가 끊긴다 — 어디서 끊을지를 값이 정하게 한다.
 */
function OpsCard({
  kind,
  label,
  summary,
  onOpen,
}: {
  kind: keyof typeof OPS_ICONS;
  label: string;
  summary: string[];
  onOpen: () => void;
}) {
  return (
    <button type="button" className="bm-card" data-kind={kind} onClick={onOpen}>
      <span className="bm-card-icon">{OPS_ICONS[kind]}</span>
      <span className="bm-card-text">
        <strong>{label}</strong>
        {/* 부제를 한 덩어리로 묶는다 — 2줄 높이를 예약해 네 카드의 제목 줄이 같은 높이에 선다 */}
        <span className="bm-card-sub">
          {summary.map((line) => (
            <span key={line}>{line}</span>
          ))}
        </span>
      </span>
      {IcChevron}
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
  // 임대 남은 시간을 1초마다 다시 그린다 — endsAt 절대시각 기준이라 백그라운드 복귀에도 어긋나지
  // 않는다(SlotListPage.RemainingTime 과 같은 방식, FR-007). 만료 "판정"의 권위는 서버 status 다.
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  const myBoothQuery = useQuery({
    queryKey: ['my-booth'],
    queryFn: leaseApi.getMyBooth,
    enabled: isMember,
  });
  const myBooth = myBoothQuery.data ?? null;
  const boothId = myBooth?.boothId ?? null;

  // facade 는 부스가 있을 때만 — 이름 저장 PUT 이 facade 4필드 전체를 실어야 해서 그 확보 여부가
  // 이름 편집의 잠금 조건이다(BoothNameField)
  const boothQuery = useQuery({
    queryKey: ['booth-detail', boothId],
    queryFn: () => facadeApi.getBooth(boothId as number),
    enabled: boothId !== null,
  });

  // 정체성 카드의 프로젝트명·썸네일. 부스 스코프 endpoint 라 0~1개다(DB 유니크 ux_projects_booth)
  const projectQuery = useQuery({
    queryKey: ['booth-projects', boothId],
    queryFn: () => projectApi.getMyProjects(boothId as number),
    enabled: boothId !== null,
  });
  const project = projectQuery.data?.projects[0] ?? null;

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
    const expired = remainingMs(lease.endsAt, now) === 0;
    const endsAt = new Intl.DateTimeFormat('ko-KR', {
      timeZone: 'Asia/Seoul',
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(lease.endsAt));

    return (
      <div className="bm-body">
        <div className="bm-main">
          {/* key — 슬롯이 바뀌면 폴백 단계를 처음부터 */}
          <BoothPreview key={lease.slotCode ?? ''} slotCode={lease.slotCode} />
          {SHOW_AI_ASSET_SECTION && <AiAssetSection />}
        </div>

        <div className="bm-side">
          <section className="bm-identity">
            <div className="bm-identity-head">
              <img
                className="bm-thumb"
                src={project?.thumbnailUrl ?? '/favicon.png'}
                alt=""
              />
              <div className="bm-identity-text">
                <h3 className="bm-name">{project?.name ?? '프로젝트 미등록'}</h3>
                <span className="bm-slot">부스 번호 {lease.slotCode ?? '미연결'}</span>
                <span className={'bm-status' + (expired ? ' bm-status-off' : '')}>
                  <span className="bm-dot" aria-hidden="true" />
                  {expired ? '임대 만료' : '운영 중'}
                </span>
                <span className="ov-note bm-remaining">
                  {IcClock}
                  {expired ? '임대가 끝났습니다' : `임대 ${formatRemaining(remainingMs(lease.endsAt, now))} 남음`}
                  <br />
                  ({endsAt} 종료)
                </span>
              </div>
            </div>
            <BoothNameField
              boothId={myBooth.boothId}
              initialName={myBooth.name}
              ready={!!boothQuery.data?.facade}
            />
          </section>

          <section className="bm-ops">
            <h3 className="bm-card-title">부스 운영 관리</h3>
            <p className="ov-note">방문객과의 소통을 위한 주요 부스 운영 기능을 설정하고 관리하세요.</p>
            <div className="bm-ops-grid">
              <OpsCard kind="project" label="프로젝트" summary={['등록 · 수정']} onOpen={() => openPanel('project', myBooth.boothId)} />
              <OpsCard kind="survey" label="설문" summary={['설문 편집', '응답 결과']} onOpen={() => openPanel('survey', myBooth.boothId)} />
              <OpsCard kind="consultation" label="상담" summary={['상담 요청 운영']} onOpen={() => openPanel('consultation', myBooth.boothId)} />
              {/* AI 직원도 같은 drill-down — 문서 업로드가 있어 화면이 길어지므로 별도 화면으로 연다 */}
              <OpsCard kind="ai-agent" label="AI 직원" summary={['답변 설정', '근거 문서 관리']} onOpen={openAiAgentSection} />
            </div>
          </section>

          {/* 만료된 임대에는 반납할 것이 없다 — 서버도 404 를 준다 */}
          {!expired && (
            <button type="button" className="bm-card bm-return" onClick={() => setShowCancel(true)}>
              <span className="bm-card-icon">{IcReturn}</span>
              <span className="bm-card-text">
                <strong>부스 반납하기</strong>
                <span>운영을 종료하고 부스를 반납합니다.</span>
              </span>
              {IcChevron}
            </button>
          )}
          {/* 404 는 오류가 아니라 낡은 화면이라 배너를 만들지 않는다(cancelLease.ts). 그 밖의
              실패만 말한다 — 401·403 MEMBER_ONLY 가 여기로 온다 */}
          {cancelLease.isError && !isStaleViewError(cancelLease.error) && (
            <span className="ov-note bm-cancel-error" role="alert">
              반납하지 못했습니다. 잠시 후 다시 시도해 주세요.
            </span>
          )}
        </div>
      </div>
    );
  })();

  return (
    <>
      <OverlayFrame
        title="내 부스 관리"
        subtitle="나만의 특별한 부스를 운영하고 방문객들과 소통해보세요."
        size="xl"
        icon={IcBooth}
        onClose={onClose}
      >
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
              <button
                type="button"
                className="bm-gate-btn bm-gate-btn-primary"
                onClick={() => {
                  setShowAgentGate(false);
                  openPanel('ai-agent', myBooth.boothId);
                }}
              >
                직원 등록
              </button>
              <button type="button" className="bm-gate-btn" onClick={() => setShowAgentGate(false)}>
                닫기
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
