// `.world-scene` 가 Unity 캔버스를 가리지 않는지 지킨다 (S15P21A604-620 회귀).
//
// 이 컨테이너는 라우트 안에 있고 캔버스를 그리는 `.persistent-world` 는 라우트 밖이다. 같은
// 자리(fixed inset:0)를 차지하면서 DOM 상 뒤에 오므로 **항상 캔버스 위에 그려진다.** 그래서
// 두 가지를 하면 안 된다 — 불투명 배경(월드가 검은 화면이 된다)과 포인터 수신(조작이 죽는다).
// 둘 다 실제로 demo 에 나갔고, 화면 테스트로는 안 잡혔다(jsdom 은 레이아웃도 페인트도 없다).
// 그래서 스타일시트 자체를 읽어 계약으로 잠근다.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

const css = readFileSync(join(process.cwd(), 'src/pages/world/worldPage.css'), 'utf8');

/** 주석을 걷어낸 뒤 해당 선택자의 선언 블록만 꺼낸다. */
function block(selector: string): string {
  const body = css.replace(/\/\*[\s\S]*?\*\//g, '');
  const at = body.indexOf(`${selector} {`);
  expect(at, `${selector} 규칙이 없다`).toBeGreaterThanOrEqual(0);
  return body.slice(at, body.indexOf('}', at));
}

describe('.world-scene 는 월드를 가리지 않는다', () => {
  it('배경을 칠하지 않는다 — 칠하면 Unity 캔버스가 통째로 덮인다', () => {
    expect(block('.world-scene')).not.toMatch(/background/);
  });

  it('포인터를 받지 않는다 — 받으면 클릭·시야 회전이 캔버스에 닿지 않는다', () => {
    expect(block('.world-scene')).toMatch(/pointer-events:\s*none/);
  });

  it('HUD 를 뺀 레이어는 포인터를 다시 연다 — pointer-events 는 상속된다', () => {
    expect(block('.world-scene > *:not(.world-hud)')).toMatch(/pointer-events:\s*auto/);
  });
});
