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
