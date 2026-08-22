import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import type { GameProject } from '../../contracts/gameProject.ts';
import { createPreviewGameSessionPort } from '../../runtime/ports/gameSessionPort.ts';
import { ReferenceGamePlayer } from '../../runtime/reference/ReferenceGamePlayer.tsx';
import { createBrowserDraftRepository } from '../../studio/ports/draftRepository.ts';
import { createBrowserAssetRepository } from '../../studio/assets/localAssetRepository.ts';
import { useResolvedAssetUrls } from '../../studio/assets/useResolvedAssetUrls.ts';

export const PlayGamePage = () => {
  const { gameId } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const parsedGameId = Number(gameId);
  const source = searchParams.get('source');
  const repository = useMemo(() => createBrowserDraftRepository(), []);
  const sessionPort = useMemo(() => createPreviewGameSessionPort(), []);
  const assetRepository = useMemo(() => createBrowserAssetRepository(), []);
  const [project, setProject] = useState<GameProject | null>(null);
  const [error, setError] = useState<string | null>(null);
  const assetUrls = useResolvedAssetUrls(project?.assets ?? [], assetRepository);

  useEffect(() => {
    let active = true;
    if (!Number.isSafeInteger(parsedGameId) || parsedGameId < 1) {
      setError('올바르지 않은 게임 ID입니다.');
      return () => { active = false; };
    }
    if (source !== 'local') {
      setError('게시본 조회 포트가 아직 연결되지 않았습니다. 편집기의 플레이 테스트를 이용하세요.');
      return () => { active = false; };
    }
    if (repository === null) {
      setError('이 브라우저에서는 로컬 플레이 테스트를 사용할 수 없습니다.');
      return () => { active = false; };
    }
    repository.load(parsedGameId)
      .then((draft) => {
        if (!active) return;
        if (draft === null) setError('저장된 초안이 없습니다. 편집기에서 먼저 저장하세요.');
        else setProject(draft);
      })
      .catch((reason: unknown) => {
        if (active) setError(reason instanceof Error ? reason.message : '게임을 불러오지 못했습니다.');
      });
    return () => { active = false; };
  }, [parsedGameId, repository, source]);

  if (error !== null) {
    return <main className="grp-loading"><strong>FESTA Game Player</strong><p>{error}</p><button onClick={() => void navigate(`/app/games/${parsedGameId}/edit`)} type="button">편집기로 돌아가기</button></main>;
  }
  if (project === null) return <main className="grp-loading">게임 데이터를 불러오는 중입니다.</main>;
  return (
    <ReferenceGamePlayer
      mode="PREVIEW"
      assetUrls={assetUrls}
      onExit={() => void navigate(`/app/games/${parsedGameId}/edit`)}
      project={project}
      sessionPort={sessionPort}
    />
  );
};
