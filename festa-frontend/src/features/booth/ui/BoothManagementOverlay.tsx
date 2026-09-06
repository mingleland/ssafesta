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
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { leaseApi } from '../../../entities/booth/leaseApi.select';
import { facadeApi } from '../../../entities/booth/facadeApi.select';
import { formatRemaining, remainingMs } from '../../../entities/booth/remaining';
import { OverlayEmpty, OverlayError, OverlayFrame, OverlayLoading } from '../../overlay/ui/OverlayFrame';
import { useSession } from '../../auth/model/session';
import { BoothMiniPreview } from './BoothMiniPreview';
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

  // 관리 작업 화면으로 나간다. 그 화면들은 WORLD_RETURN_TO_MANAGEMENT 로 돌아오므로
  // World 에 도착하면 이 관리 화면이 다시 열린다(user-flow-decisions §18.3·§19).
  function go(path: string) {
    onClose();
    navigate(path);
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
              onClick={() => go(`/app/studio/${myBooth.boothId}`)}
            >
              부스 스튜디오 열기
            </button>
          </div>
        </section>

        {/* A — 기능별 Drill-down. 여기서 Editor 를 펼치지 않는다 */}
        <div className="bm-rows">
          <SectionRow
            label="PROJECT"
            summary="전시 프로젝트 등록·수정"
            onOpen={() => go(`/app/booths/${myBooth.boothId}/project`)}
          />
          <SectionRow
            label="SURVEY"
            summary="설문 편집·응답 결과"
            onOpen={() => go(`/app/booths/${myBooth.boothId}/survey`)}
          />
          <SectionRow
            label="CONSULTATION"
            summary="상담 요청 운영"
            onOpen={() => go(`/app/booths/${myBooth.boothId}/consultation`)}
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
        </section>
      </div>
    );
  })();

  return (
    <OverlayFrame title="내 부스 관리" subtitle="제작·콘텐츠·운영" size="xl" icon={IcBooth} onClose={onClose}>
      {body}
    </OverlayFrame>
  );
}
