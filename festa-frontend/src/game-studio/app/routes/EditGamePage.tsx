import { useParams } from 'react-router-dom';

export const EditGamePage = () => {
  const { gameId } = useParams();
  return (
    <main data-game-studio-route="edit">
      <h1>FESTA Game Studio</h1>
      <p>게임 {gameId} 편집기를 불러오는 중입니다.</p>
    </main>
  );
};
