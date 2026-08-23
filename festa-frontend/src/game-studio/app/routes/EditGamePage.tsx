import { useParams } from 'react-router-dom';
import { GameStudioShell } from '../../studio/ui/GameStudioShell.tsx';

export const EditGamePage = () => {
  const { gameId } = useParams();
  const parsedGameId = Number(gameId);
  if (!Number.isSafeInteger(parsedGameId) || parsedGameId < 1) {
    return <main>올바르지 않은 게임 ID입니다.</main>;
  }
  return <GameStudioShell gameId={parsedGameId} />;
};
