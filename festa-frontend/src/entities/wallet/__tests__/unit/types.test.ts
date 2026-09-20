import { describe, expect, it } from 'vitest';
import { labelForReason } from '../../types';

describe('labelForReason', () => {
  it('MINIGAME_REWARD를 한글 라벨로 바꾼다', () => {
    expect(labelForReason('MINIGAME_REWARD')).toBe('미니게임 보상');
  });

  it('모르는 코드는 원문 그대로 돌려준다 — SC-005', () => {
    expect(labelForReason('UNKNOWN_CODE')).toBe('UNKNOWN_CODE');
  });
});
