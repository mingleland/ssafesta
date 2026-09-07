// Source Package License Gate (S15P21A604-480).
//
// 에셋마다 "Chair01 은 되나? DisplayBox 는?" 을 코드에 조건문으로 박지 않는다.
// 판정 단위는 **source package** 다 — 라이선스는 개별 파일이 아니라 취득한 팩에 붙는다.
//
// 지금 이 저장소의 벤더 팩에는 라이선스 파일이 하나도 없다. 그래서 어느 것도 ALLOW 로
// 선언하지 않았다. 사람이 취득 경로를 확인해 lock 파일을 채우기 전까지 Compiler 는
// **로컬 Spike 산출물까지만** 만들고 production emission 은 만들지 않는다.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { PROJECT_ROOT } from './paths.mjs';

export const LOCK_PATH = resolve(PROJECT_ROOT, 'tools/assets/source-packs.lock.json');

/** 정책 값 — 이 셋 말고는 쓰지 않는다 */
export const POLICY = {
  /** 라이선스 확인 완료. runtime 산출물을 만들고 배포할 수 있다 */
  ALLOW_RUNTIME_COMPILE: 'ALLOW_RUNTIME_COMPILE',
  /** 사람 확인 대기. 로컬 Spike 산출물까지만 만든다 — 배포 대상이 아니다 */
  REVIEW_REQUIRED: 'REVIEW_REQUIRED',
  /** 쓰지 않는다. 입력에서 제외한다 */
  BLOCK: 'BLOCK',
};

/** emission 등급 — 무엇까지 만들어도 되는가 */
export const EMISSION = {
  /** `.generated/` 안에만. 배포 경로로 나가지 않는다 */
  LOCAL_SPIKE_ONLY: 'localSpikeOnly',
  /** 배포 후보로 나갈 수 있다 */
  PRODUCTION_CANDIDATE: 'productionCandidate',
};

let cached = null;

export function loadSourcePacks() {
  if (cached !== null) return cached;
  cached = JSON.parse(readFileSync(LOCK_PATH, 'utf8'));
  return cached;
}

export function __resetSourcePacksCache() {
  cached = null;
}

/**
 * 이 에셋을 compile 해도 되는가, 그리고 결과를 어디까지 내보내도 되는가.
 *
 * 선언되지 않은 package 는 통과시키지 않는다 — 모르는 것을 기본 허용으로 두면
 * lock 파일이 아무 역할도 못 한다.
 */
export function evaluate(packageId, packs = loadSourcePacks()) {
  const entry = packs.packages?.[packageId];
  if (entry === undefined) {
    return {
      compile: false,
      emission: null,
      reason: `source package '${packageId}' 가 ${'source-packs.lock.json'} 에 없다 — 선언되지 않은 팩은 compile 하지 않는다`,
    };
  }
  if (entry.runtimeCompilePolicy === POLICY.BLOCK) {
    return { compile: false, emission: null, reason: `source package '${packageId}' 는 BLOCK 이다` };
  }
  if (entry.runtimeCompilePolicy === POLICY.ALLOW_RUNTIME_COMPILE) {
    return { compile: true, emission: EMISSION.PRODUCTION_CANDIDATE, reason: null };
  }
  if (entry.runtimeCompilePolicy === POLICY.REVIEW_REQUIRED) {
    return {
      compile: true,
      emission: EMISSION.LOCAL_SPIKE_ONLY,
      reason: `source package '${packageId}' 는 REVIEW_REQUIRED — 로컬 Spike 산출물까지만 만든다`,
    };
  }
  return {
    compile: false,
    emission: null,
    reason: `source package '${packageId}' 의 runtimeCompilePolicy 값을 모른다: ${entry.runtimeCompilePolicy}`,
  };
}

/** 배포 경로로 내보내도 되는가 — production emission 은 ALLOW 뿐이다 */
export function canEmitProduction(evaluation) {
  return evaluation.compile === true && evaluation.emission === EMISSION.PRODUCTION_CANDIDATE;
}
