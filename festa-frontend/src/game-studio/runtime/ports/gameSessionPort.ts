export interface GameSessionStartRequest {
  readonly gameId: number;
  readonly mode: 'PREVIEW' | 'PUBLISHED';
}

export interface GameSessionStartResult {
  readonly sessionToken: string;
  readonly coinCharged: boolean;
}

export interface GameSessionPort {
  start(request: GameSessionStartRequest): Promise<GameSessionStartResult>;
  complete(sessionToken: string): Promise<void>;
  exit(sessionToken: string): Promise<void>;
}

export const createPreviewGameSessionPort = (): GameSessionPort => ({
  start: async ({ gameId }) => ({ sessionToken: `preview-${gameId}`, coinCharged: false }),
  complete: async () => undefined,
  exit: async () => undefined,
});

// v1 Published 게임은 무보상 브라우저 세션이다. Coin/Reward가 도입되기 전까지 서버 세션을 만들지
// 않되 Runtime은 같은 port를 사용해 후속 권위 세션 adapter로 교체할 수 있게 한다.
export const createPublishedGameSessionPort = (): GameSessionPort => ({
  start: async ({ gameId }) => ({ sessionToken: `published-local-${gameId}-${Date.now()}`, coinCharged: false }),
  complete: async () => undefined,
  exit: async () => undefined,
});
