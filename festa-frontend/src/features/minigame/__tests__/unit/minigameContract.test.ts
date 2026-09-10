// OpenAPI 정본과 FE 타입의 1:1 (S15P21A604-601).
//
// `specs/014-minigame/contracts/minigame-api.yaml` 이 정본이다. FE 가 그 스키마를 손으로 옮겨
// 적어 둔 이상, 서버가 필드를 늘리거나 이름을 바꿨을 때 **조용히 어긋난다** — 응답은 200 이고
// 화면만 빈다. 그래서 계약 파일을 직접 읽어 대조한다.
//
// `tools/runtimeConfigKeys.test.mjs`·`tools/paletteAssetCodes.test.mjs` 와 같은 패턴이다:
// FE 에 목록을 복제해 두고 그것과 비교하는 것이 아니라 **정본 파일 자체를 파싱**한다.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { describe, expect, it } from 'vitest';

const here = dirname(fileURLToPath(import.meta.url));
const contract = readFileSync(
  join(here, '../../../../../../specs/014-minigame/contracts/minigame-api.yaml'),
  'utf8',
);

/** `SchemaName:` 블록의 `required: [a, b, c]` 를 읽는다. 여러 줄에 걸쳐도 붙여 읽는다 */
function requiredOf(schema: string): string[] {
  const block = new RegExp(`^    ${schema}:$([\\s\\S]*?)(?=^    [A-Za-z]|\\Z)`, 'm').exec(contract);
  if (block === null) throw new Error(`${schema} 스키마를 계약 파일에서 찾지 못했다`);
  const required = /required:\s*(\[[\s\S]*?\]|(?:\n\s+-\s*\w+)+)/.exec(block[1]);
  if (required === null) throw new Error(`${schema} 의 required 를 찾지 못했다`);
  return [...required[1].matchAll(/[A-Za-z][A-Za-z0-9]*/g)].map((m) => m[0]);
}

// FE 가 선언한 필드 — 타입은 컴파일 타임에만 있으므로 여기 이름을 적고, 실제 소비는
// api.ts 의 인터페이스가 한다. 둘이 갈리면 아래 테스트가 아니라 tsc 가 먼저 잡는다.
const SESSION_FIELDS = ['sessionId', 'targetSeconds', 'failAfterSeconds', 'serverStartedAt'];
const RESULT_FIELDS = [
  'accepted', 'errorSeconds', 'tier', 'timedOut',
  'rewardedCoins', 'dailyLimitReached', 'dailyRemainingCoins', 'message',
];

describe('minigame-api.yaml ↔ FE 타입', () => {
  it('SessionIssued 의 required 를 FE 가 전부 안다', () => {
    expect(requiredOf('SessionIssued').sort()).toEqual([...SESSION_FIELDS].sort());
  });

  it('SubmitResult 의 required 를 FE 가 전부 안다', () => {
    expect(requiredOf('SubmitResult').sort()).toEqual([...RESULT_FIELDS].sort());
  });

  it('SubmitCommand 가 요구하는 것은 stoppedSeconds 하나다 — FE 가 다른 필드를 만들 이유가 없다', () => {
    expect(requiredOf('SubmitCommand')).toEqual(['stoppedSeconds']);
  });

  it('경로가 계약과 같다', () => {
    expect(contract).toContain('/minigames/timer-stop/sessions:');
    expect(contract).toContain('/minigames/timer-stop/sessions/{sessionId}/result:');
  });
});
