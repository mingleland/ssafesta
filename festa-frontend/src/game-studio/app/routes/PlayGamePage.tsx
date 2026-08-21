import { useParams } from 'react-router-dom';

export const PlayGamePage = () => {
  const { gameId } = useParams();
  return (
    <main data-game-studio-route="play">
      <h1>FESTA Game Player</h1>
      <p>게임 {gameId}의 Web Runtime을 불러오는 중입니다.</p>
    </main>
  );
};
