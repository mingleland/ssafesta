import { describe, expect, it } from 'vitest';
import { estimateGameProjectJsonBytes, GAME_PROJECT_LIMITS } from '../../contracts/gameProject.ts';
import { moveObject } from '../../studio/model/authoringCommands.ts';
import { createEditorStressProject } from '../../studio/model/createEditorStressProject.ts';
import { createGameProjectStore } from '../../studio/store/gameProjectStore.ts';

describe('Game Studio editor performance budget', () => {
  it('keeps the reproducible maximum fixture inside every current project contract', () => {
    const project = createEditorStressProject(97);
    const scene = project.scenes.find((candidate) => candidate.id === 'library');
    if (scene?.type !== 'TOP_DOWN') throw new Error('stress fixture missing');

    expect(scene.objects).toHaveLength(GAME_PROJECT_LIMITS.maxObjectsPerScene);
    expect(scene.tileLayers[0]?.data).toHaveLength(GAME_PROJECT_LIMITS.maxTileCellsPerLayer);
    expect(estimateGameProjectJsonBytes(project)).toBeLessThan(GAME_PROJECT_LIMITS.maxJsonBytes);
  });

  // 판정 통계를 최댓값에서 **중앙값**으로 바꾼다 (GitLab #168).
  //
  // 재는 것은 commit 한 번의 지연이 맞다. 다만 그 비용의 거의 전부가 부대 작업이다 —
  // 실측(단독 실행 median):
  //
  //   moveObject 알고리즘만            0.01ms   ← 0.14%
  //   structuredClone(present)         0.82ms
  //   moveObject 안의 parseGameProject 1.20ms
  //   snapshot: parse+clone+deepFreeze 3.32ms
  //   sameProject: JSON.stringify x2   0.58ms
  //   store.update 전체                7.31ms
  //
  // 할당이 많아 GC 를 부르고 측정 구간이 한 자릿수 ms 라, 스케줄러 대기 하나가 그대로 배수로
  // 들어온다. 같은 코드·같은 머신에서 worker 부하만 바꾼 실측(22코어):
  //
  //   worker 1개    median  7.25ms   max  9.65ms
  //   전체 스위트    median 16.41ms   max 45.46ms   ← max 만 4.7배
  //
  // 최댓값을 기준으로 두면 그 스파이크 하나가 곧 red 다. 중앙값은 그 노이즈를 걸러 내면서
  // 실제 회귀는 그대로 잡는다 — 알고리즘이나 store 경로가 느려지면 특정 샘플이 아니라 분포
  // 전체가 옮겨 가기 때문이다. 예산 100ms 와 측정 대상은 그대로 둔다.
  it('commits a move in a 500-object Scene within the 100ms interaction budget', () => {
    const store = createGameProjectStore(createEditorStressProject(98));
    const durations: number[] = [];
    for (let index = 0; index < 12; index += 1) {
      const startedAt = performance.now();
      store.update((project) => moveObject(project, 'library', 'stressObject1', index % 2, index % 3));
      if (index >= 2) durations.push(performance.now() - startedAt);
    }
    const sorted = [...durations].sort((left, right) => left - right);
    const median = sorted[Math.floor(sorted.length / 2)]!;
    // 실패하면 분포를 남긴다 — 숫자 하나로는 회귀인지 순간 부하인지 가릴 수 없다
    expect(median, `durations(ms): ${sorted.map((value) => value.toFixed(1)).join(' ')}`).toBeLessThan(100);
  });
});
