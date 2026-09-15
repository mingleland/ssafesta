// World HUD 위치 계약 — jsdom은 실제 화면 배치를 재지 못하므로, WorldPage가 연결하는 CSS 변수와
// 각 영역의 소비 위치를 직접 잠근다(S15P21A604-740).
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

const read = (path: string) => readFileSync(join(process.cwd(), path), 'utf8');
const tokens = read('src/index.css');
const hud = read('src/features/world/ui/worldHud.css');
const chat = read('src/features/worldChat/ui/worldChat.css');
const toast = read('src/shared/ui/toast/toast.css');
const exit = read('src/features/world/ui/boothExitButton.css');

describe('World HUD layout contract', () => {
  it('최소 16px 안전 여백과 좌하단 chat → guide 스택을 쓴다', () => {
    expect(tokens).toContain('--festa-hud-margin-x: max(16px, 2vw);');
    expect(tokens).toContain('--festa-hud-margin-y: max(16px, 3vh);');
    expect(tokens).toContain('--festa-hud-context-bottom: max(72px, calc(var(--festa-hud-margin-y) + var(--festa-world-chat-height, 0px) + var(--festa-hud-stack-gap)));');
    expect(chat).toContain('bottom: var(--festa-hud-margin-y);');
    expect(hud).toContain('bottom: calc(var(--festa-hud-margin-y) + var(--festa-world-chat-height, 0px) + var(--festa-hud-stack-gap));');
  });

  it('우상단은 상담·전체화면, 상단 중앙은 Toast, 하단 중앙은 클릭형 Exit으로 분리한다', () => {
    expect(hud).toContain('top: calc(var(--festa-hud-margin-y) + var(--festa-control-size) + var(--festa-hud-stack-gap));');
    expect(toast).toContain('.world-active .toast-host');
    expect(toast).toContain('left: 50%;');
    expect(exit).toContain('bottom: var(--festa-hud-context-bottom);');
    expect(exit).not.toContain('.booth-exit-key');
    expect(hud).toContain('@media (max-width: 720px)');
  });
});
