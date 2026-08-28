import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import type { GameProject } from '../../contracts/gameProject.ts';
import { createPreviewGameSessionPort } from '../../runtime/ports/gameSessionPort.ts';
import { ReferenceGamePlayer } from '../../runtime/reference/ReferenceGamePlayer.tsx';
import { PublishedGameSurface } from '../../runtime/ui/PublishedGameSurface.tsx';
import { createBrowserDraftRepository } from '../../studio/ports/draftRepository.ts';
import { createBrowserAssetRepository } from '../../studio/assets/localAssetRepository.ts';
import { createApiGameAssetRepository } from '../../studio/assets/remoteAssetRepository.ts';
import { createBrowserPublicationPorts } from '../../studio/ports/localPublicationRepository.ts';
import { useResolvedAssetUrls } from '../../studio/assets/useResolvedAssetUrls.ts';

const NO_ASSETS = [] as const;
const browserPublicationEnabled = import.meta.env.VITE_USE_MOCK === 'true'
  && import.meta.env.VITE_GAME_STUDIO_API_ENABLED !== 'true';
const browserPublicationPorts = browserPublicationEnabled ? createBrowserPublicationPorts() : null;

// 익명 Published 플레이의 자산 해석기. 컴포넌트 밖에 두는 이유는 PlayGamePage 가 gameId 검증·
// source=local 분기로 조기 return 하는 구조라 훅을 쓸 수 없어서다. 생성자가 상태를 만들지 않고
// (모듈 참조만 묶는다) 참조가 고정돼야 useResolvedAssetUrls 의 useEffect 가 재해석 루프에 빠지지 않는다.
// local 을 주지 않는다 — 공개된 게임이 보는 것은 서버 Asset 뿐이고, asset://local 은 여기서 null 이 맞다.
const publishedAssetResolver = createApiGameAssetRepository();

interface LocalPreviewSurfaceProps {
  readonly gameId: number;
  readonly onExit: () => void;
  readonly showPerformanceMonitor: boolean;
}

const LocalPreviewSurface = ({ gameId, onExit, showPerformanceMonitor }: LocalPreviewSurfaceProps) => {
  const repository = useMemo(() => createBrowserDraftRepository(), []);
  const sessionPort = useMemo(() => createPreviewGameSessionPort(), []);
  // 편집기 미리보기는 두 참조가 섞인다 — 아직 업로드 안 한 asset://local 과 업로드된 asset://game.
  // 합성은 어댑터 안에 있다: resolve 가 local prefix 면 위임하고 stable 이면 서버로 간다.
  const assetRepository = useMemo(
    () => createApiGameAssetRepository({ local: createBrowserAssetRepository() }),
    [],
  );
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
      assetResolver={publishedAssetResolver}
      gameId={parsedGameId}
      onExit={() => void navigate('/app/world')}
      repository={browserPublicationPorts?.repository}
    />
  );
};
