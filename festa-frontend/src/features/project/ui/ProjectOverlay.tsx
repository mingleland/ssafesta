// Project 전시 Overlay — Overlay Family 의 앵커 화면 (S15P21A604-406).
// 데이터는 features/project/model/exhibition 의 실제 VM 을 그대로 소비한다(-134·-135, REAL).
// UI 가 DTO·Port 를 발명하지 않는다 — 여기서는 표현과 상호작용만 한다.
import { useEffect } from 'react';
import { closeOverlay } from '../../../shared/types/overlay';
import { loadExhibition, resetExhibition, toggleLike, useExhibition } from '../model/exhibition';
import type { ExhibitionProjectVM } from '../model/exhibition';
import { OverlayError, OverlayFrame, OverlayLoading, OverlayEmpty } from '../../overlay/ui/OverlayFrame';
import './projectOverlay.css';

interface Props {
  payload: { boothId: number };
}

const IcProject = (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="3" y="4" width="18" height="14" rx="2" />
    <path d="M8 21h8M12 18v3M7 12l3-3 3 3 4-4" />
  </svg>
);

function LikeButton({ project }: { project: ExhibitionProjectVM }) {
  const { like } = project;
  return (
    <button
      type="button"
      className={'proj-like' + (like.likedByMe ? ' proj-like-on' : '')}
      disabled={!like.canToggle || like.pending}
      onClick={() => void toggleLike(project.projectId)}
      title={like.canToggle ? '좋아요' : '회원만 누를 수 있습니다'}
      aria-pressed={like.likedByMe}
    >
      <svg width="16" height="16" viewBox="0 0 24 24" fill={like.likedByMe ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.9" aria-hidden="true">
        <path d="M12 20s-7-4.4-7-9.3A4 4 0 0 1 12 8a4 4 0 0 1 7 2.7c0 4.9-7 9.3-7 9.3Z" />
      </svg>
      {like.count}
    </button>
  );
}

function ProjectCard({ project }: { project: ExhibitionProjectVM }) {
  const { video, links } = project;
  return (
    <article className="proj-card">
      <div className="proj-media">
        {video?.kind === 'EMBED' ? (
          <iframe className="proj-embed" src={video.embedUrl} title={project.name + ' 소개 영상'} allow="encrypted-media; picture-in-picture" allowFullScreen />
        ) : project.thumbnailUrl !== null ? (
          <img className="proj-thumb" src={project.thumbnailUrl} alt="" />
        ) : (
          <div className="proj-media-empty">등록된 미디어가 없습니다</div>
        )}
      </div>

      <div className="proj-body">
        <div className="proj-titlerow">
          <h3 className="proj-name">{project.name}</h3>
          <LikeButton project={project} />
        </div>

        {project.description !== null && <p className="proj-desc">{project.description}</p>}

        {video?.kind === 'LINK_ONLY' && (
          <a className="ov-btn proj-linkbtn" href={video.href} target="_blank" rel="noreferrer noopener">
            새 탭에서 영상 보기
          </a>
        )}
        {video?.kind === 'INVALID' && video.reason !== 'EMPTY' && (
          <p className="ov-note">등록된 영상 주소를 열 수 없습니다.</p>
        )}

        <div className="proj-links">
          {links.deployUrl !== null && (
            <a className="proj-link" href={links.deployUrl} target="_blank" rel="noreferrer noopener">
              서비스 바로가기
            </a>
          )}
          {links.gitUrl !== null && (
            <a className="proj-link" href={links.gitUrl} target="_blank" rel="noreferrer noopener">
              저장소
            </a>
          )}
          {links.portfolioUrl !== null && (
            <a className="proj-link" href={links.portfolioUrl} target="_blank" rel="noreferrer noopener">
              포트폴리오
            </a>
          )}
        </div>

        {project.like.error && <p className="ov-alert">좋아요를 반영하지 못했습니다. 다시 눌러 주세요.</p>}
        {!project.like.canToggle && <p className="ov-note">좋아요는 회원만 누를 수 있습니다.</p>}
      </div>
    </article>
  );
}

export function ProjectOverlay({ payload }: Props) {
  const state = useExhibition();

  useEffect(() => {
    void loadExhibition(payload.boothId);
    return () => resetExhibition();
  }, [payload.boothId]);

  const subtitle = state.status === 'ready' ? state.projects.length + '개 전시 중' : '부스 전시';

  return (
    <OverlayFrame
      title="프로젝트 전시"
      subtitle={subtitle}
      size="l"
      icon={IcProject}
      onClose={closeOverlay}
      status={<span className="ov-note">Esc 또는 바깥을 눌러 월드로 돌아갑니다</span>}
      footer={
        <button type="button" className="ov-btn" onClick={closeOverlay}>
          닫기
        </button>
      }
    >
      {state.status === 'loading' && <OverlayLoading label="전시를 불러오는 중..." />}
      {state.status === 'error' && (
        <OverlayError title="전시를 불러오지 못했습니다" message="잠시 후 다시 시도해 주세요." onRetry={() => void loadExhibition(payload.boothId)} />
      )}
      {state.status === 'empty' && <OverlayEmpty title="아직 등록된 프로젝트가 없습니다" hint="부스 주인이 프로젝트를 등록하면 여기에서 볼 수 있습니다." />}
      {state.status === 'ready' && (
        <div className="proj-list">
          {state.projects.map((p) => (
            <ProjectCard key={p.projectId} project={p} />
          ))}
        </div>
      )}
    </OverlayFrame>
  );
}
