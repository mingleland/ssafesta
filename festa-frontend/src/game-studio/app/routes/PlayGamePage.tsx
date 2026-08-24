import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import type { GameProject } from '../../contracts/gameProject.ts';
import { createPreviewGameSessionPort } from '../../runtime/ports/gameSessionPort.ts';
import { ReferenceGamePlayer } from '../../runtime/reference/ReferenceGamePlayer.tsx';
import { PublishedGameSurface } from '../../runtime/ui/PublishedGameSurface.tsx';
import { createBrowserDraftRepository } from '../../studio/ports/draftRepository.ts';
import { createBrowserAssetRepository } from '../../studio/assets/localAssetRepository.ts';
import { createBrowserPublicationPorts } from '../../studio/ports/localPublicationRepository.ts';
import { useResolvedAssetUrls } from '../../studio/assets/useResolvedAssetUrls.ts';

const NO_ASSETS = [] as const;
const browserPublicationEnabled = import.meta.env.VITE_USE_MOCK === 'true'
  && import.meta.env.VITE_GAME_STUDIO_API_ENABLED !== 'true';
const browserPublicationPorts = browserPublicationEnabled ? createBrowserPublicationPorts() : null;

interface LocalPreviewSurfaceProps {
  readonly gameId: number;
  readonly onExit: () => void;
  readonly showPerformanceMonitor: boolean;
}

const LocalPreviewSurface = ({ gameId, onExit, showPerformanceMonitor }: LocalPreviewSurfaceProps) => {
  const repository = useMemo(() => createBrowserDraftRepository(), []);
  const sessionPort = useMemo(() => createPreviewGameSessionPort(), []);
  const assetRepository = useMemo(() => createBrowserAssetRepository(), []);
  const [project, setProject] = useState<GameProject | null>(null);
  const [error, setError] = useState<string | null>(null);
  const assetUrls = useResolvedAssetUrls(project?.assets ?? NO_ASSETS, assetRepository);

  useEffect(() => {
    let active = true;
    if (repository === null) {
      setError('이 브라우저에서는 로컬 플레이 테스트를 사용할 수 없습니다.');
      return () => { active = false; };
    }
    repository.load(gameId)
      .then((draft) => {
        if (!active) return;
        if (draft === null) setError('저장된 초안이 없습니다. 편집기에서 먼저 저장하세요.');
        else setProject(draft);
      })
      .catch((reason: unknown) => {
        if (active) setError(reason instanceof Error ? reason.message : '게임을 불러오지 못했습니다.');
      });
    return () => { active = false; };
  }, [gameId, repository]);

  if (error !== null) {
    return <main className="grp-loading"><strong>FESTA Game Player</strong><p>{error}</p><button onClick={onExit} type="button">편집기로 돌아가기</button></main>;
  }
  if (project === null) return <main className="grp-loading">게임 데이터를 불러오는 중입니다.</main>;
  return (
    <ReferenceGamePlayer
      mode="PREVIEW"
      assetUrls={assetUrls}
      onExit={onExit}
      project={project}
      sessionPort={sessionPort}
      showPerformanceMonitor={showPerformanceMonitor}
    />
  );
};

export const PlayGamePage = () => {
  const { gameId } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const parsedGameId = Number(gameId);
  if (!Number.isSafeInteger(parsedGameId) || parsedGameId < 1) {
    return <main className="grp-loading"><strong>FESTA Game Player</strong><p>올바르지 않은 게임 ID입니다.</p><button onClick={() => void navigate('/app/world')} type="button">월드로 돌아가기</button></main>;
  }
  if (searchParams.get('source') === 'local') {
    return (
      <LocalPreviewSurface
        gameId={parsedGameId}
        onExit={() => void navigate(`/app/games/${parsedGameId}/edit`)}
        showPerformanceMonitor={searchParams.get('perf') === '1'}
      />
    );
  }
  return (
    <PublishedGameSurface
      gameId={parsedGameId}
      onExit={() => void navigate('/app/world')}
      repository={browserPublicationPorts?.repository}
    />
  );
};
