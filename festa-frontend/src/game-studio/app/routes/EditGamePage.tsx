import { useParams } from 'react-router-dom';
import { createApiGameDraftRepository, createApiGamePublisher } from '../../studio/ports/gameAuthoringApi.ts';
import { GameStudioShell } from '../../studio/ui/GameStudioShell.tsx';

const serverAuthoringEnabled = import.meta.env.VITE_GAME_STUDIO_API_ENABLED === 'true';
const serverDraftRepository = serverAuthoringEnabled ? createApiGameDraftRepository() : undefined;
const serverPublisher = serverAuthoringEnabled ? createApiGamePublisher() : undefined;

export const EditGamePage = () => {
  const { gameId } = useParams();
  const parsedGameId = Number(gameId);
  if (!Number.isSafeInteger(parsedGameId) || parsedGameId < 1) {
    return <main>올바르지 않은 게임 ID입니다.</main>;
  }
  return (
    <GameStudioShell
      assetRepository={serverAuthoringEnabled ? null : undefined}
      gameId={parsedGameId}
      key={parsedGameId}
      persistenceLabel={serverAuthoringEnabled ? '서버' : '브라우저'}
      publisher={serverPublisher}
      repository={serverDraftRepository}
    />
  );
};
