import { useMemo } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { createApiGameDraftRepository, createApiGamePublisher } from '../../studio/ports/gameAuthoringApi.ts';
import { createBrowserPublicationPorts } from '../../studio/ports/localPublicationRepository.ts';
import { GameStudioShell } from '../../studio/ui/GameStudioShell.tsx';
import { createEditorStressProject } from '../../studio/model/createEditorStressProject.ts';

const serverAuthoringEnabled = import.meta.env.VITE_GAME_STUDIO_API_ENABLED === 'true';
const browserPublicationEnabled = import.meta.env.VITE_USE_MOCK === 'true' && !serverAuthoringEnabled;
const serverDraftRepository = serverAuthoringEnabled ? createApiGameDraftRepository() : undefined;
const serverPublisher = serverAuthoringEnabled ? createApiGamePublisher() : undefined;
const browserPublicationPorts = browserPublicationEnabled ? createBrowserPublicationPorts() : null;

export const EditGamePage = () => {
  const { gameId } = useParams();
  const [searchParams] = useSearchParams();
  const parsedGameId = Number(gameId);
  const validGameId = Number.isSafeInteger(parsedGameId) && parsedGameId >= 1;
  const stressFixtureEnabled = import.meta.env.DEV && searchParams.get('fixture') === 'max';
  const initialProject = useMemo(() => (
    stressFixtureEnabled && validGameId ? createEditorStressProject(parsedGameId) : undefined
  ), [parsedGameId, stressFixtureEnabled, validGameId]);
  if (!validGameId) {
    return <main>올바르지 않은 게임 ID입니다.</main>;
  }
  return (
    <GameStudioShell
      assetRepository={serverAuthoringEnabled ? null : undefined}
      gameId={parsedGameId}
      initialProject={initialProject}
      key={parsedGameId}
      persistenceLabel={stressFixtureEnabled ? '최대 부하 검증' : serverAuthoringEnabled ? '서버' : browserPublicationEnabled ? '브라우저(Mock)' : '브라우저'}
      publisher={stressFixtureEnabled ? null : serverPublisher ?? browserPublicationPorts?.publisher}
      repository={stressFixtureEnabled ? null : serverDraftRepository}
    />
  );
};
