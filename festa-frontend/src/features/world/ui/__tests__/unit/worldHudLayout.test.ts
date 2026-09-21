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

// 선택자 하나의 블록만 떼어 본다. `hud` 전체에 대고 확인하면 **다른 규칙이 가진 값**으로도
// 통과한다 — 조작 안내가 `right` 를 잃고 왼쪽 끝으로 갔을 때 이 파일이 green 이던 이유다.
const blockOf = (css: string, selector: string) =>
  css.slice(css.indexOf(selector + ' {')).split('}')[0];

describe('World HUD layout contract', () => {
  // 조작 안내 카드는 2026-09-16 에 HUD 에서 걷었다(ESC 메뉴 오버레이로 이동). 좌하단은 채팅만
  // 쓰지만, 하단 중앙 Exit 은 여전히 채팅 높이를 피해야 하므로 그 연결은 그대로 잠근다.
  it('최소 16px 안전 여백과 좌하단 채팅 자리를 쓴다', () => {
    expect(tokens).toContain('--festa-hud-margin-x: max(16px, 2vw);');
    expect(tokens).toContain('--festa-hud-margin-y: max(16px, 3vh);');
    expect(tokens).toContain('--festa-hud-context-bottom: max(72px, calc(var(--festa-hud-margin-y) + var(--festa-world-chat-height, 0px) + var(--festa-hud-stack-gap)));');
    expect(chat).toContain('bottom: var(--festa-hud-margin-y);');
  });

  it('우상단은 상담·전체화면, 상단 중앙은 Toast, 하단 중앙은 클릭형 Exit으로 분리한다', () => {
    // 우상단은 한 줄이다 — 상담이 왼쪽 칸, 전체화면이 오른쪽 끝이다(2026-09-16)
    expect(blockOf(hud, '.world-hud-fullscreen')).toContain('right: var(--festa-hud-margin-x);');
    // 조작 안내는 우하단이다 — 가로·세로 **둘 다** 자기 블록에서 잡아야 한다. 하나라도 비면
    // absolute 가 static 위치로 떨어져 왼쪽 끝에 붙는다.
    const guide = blockOf(hud, '.world-hud-guide');
    expect(guide).toContain('right: var(--festa-hud-margin-x);');
    expect(guide).toContain('bottom: var(--festa-hud-margin-y);');
    expect(toast).toContain('.world-active .toast-host');
    expect(toast).toContain('left: 50%;');
    expect(exit).toContain('bottom: var(--festa-hud-context-bottom);');
    expect(exit).not.toContain('.booth-exit-key');
  });
});
