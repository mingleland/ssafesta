// Facade 후보 선별 (S15P21A604-552).
//
//   node tools/assets/facade-triage.mjs
//
// 18종을 **최종 제공 목록으로 그냥 확정하지 않는다.** 실측에서 둘이 이미 걸렸다 —
// `PRIZE_WALL` 은 벽 없이 인형 15개뿐이고, `HOT_DOG` 는 카트 본체가 원점에서 10 m 떨어져 있다.
//
// 여기서 보는 것은 "예뻐 보이는가" 가 아니라 **하나의 정상적인 외관 단위인가** 다.
//
//   cluster   부품이 한 덩어리인가 (떨어져 있으면 부스 슬롯에 못 놓는다)
//   pivot     바닥 중앙 기준으로 자연스러운가
//   size      부스 슬롯(6 × 6 m)에 들어가고, 사람이 다가갈 크기인가
//   massing   구조물이 있는가, 아니면 소품만 모아 둔 것인가
//
// 판정은 사람이 한다. 이 스크립트는 **판정에 필요한 수치를 한 화면에 모은다.**
import { resolve } from 'node:path';
import * as THREE from 'three';
import { CARNIVAL_PREFAB_ROOT, CM_TO_M, FACADE_ASSETS, FACADE_GROUP_LABEL } from './facade-assets.config.mjs';
import { boundsOf, loadFbx, projectRoot, sharedGuidIndex } from './source.mjs';
import { loadPrefab } from './unity-prefab.mjs';

/** 부스 슬롯 한 칸. 이보다 크면 놓을 자리가 없다 */
const SLOT_M = 6;
/** 이만큼 떨어져 있으면 다른 덩어리로 본다 */
const CLUSTER_GAP_M = 2;

const f2 = (n) => n.toFixed(2);

function analyze(source, guidIndex) {
  const root = loadPrefab(resolve(projectRoot, CARNIVAL_PREFAB_ROOT, source.prefab), {
    guidIndex,
    unitScale: CM_TO_M,
    loadFbx,
    warn: () => {},
  });
  root.updateMatrixWorld(true);

  const v = new THREE.Vector3();
  const meshes = [];
  root.traverse((o) => {
    if (!o.isMesh) return;
    o.getWorldPosition(v);
    const bb = new THREE.Box3().setFromObject(o);
    const s = bb.getSize(new THREE.Vector3());
    meshes.push({ name: o.name, pos: [v.x, v.y, v.z], span: Math.max(s.x, s.y, s.z) });
  });

  // 덩어리 판정 — x 축 정렬 후 간격이 벌어지는 곳을 센다. 1차원이지만 실측에서 걸린
  // 사례(HOT_DOG, 10.96 m)는 전부 x 로 떨어져 있었고, 더 정교한 군집화가 필요할 만큼
  // 애매한 자산은 없었다
  const xs = meshes.map((m) => m.pos[0]).sort((a, b) => a - b);
  let clusters = 1;
  let maxGap = 0;
  for (let i = 1; i < xs.length; i += 1) {
    const gap = xs[i] - xs[i - 1];
    if (gap > maxGap) maxGap = gap;
    if (gap > CLUSTER_GAP_M) clusters += 1;
  }

  const b = boundsOf(root);
  const size = [b.max[0] - b.min[0], b.max[1] - b.min[1], b.max[2] - b.min[2]];
  // 가장 큰 부품이 전체의 얼마를 차지하나 — 소품만 모아 둔 것은 이 값이 작다
  const biggest = meshes.reduce((m, x) => Math.max(m, x.span), 0);
  const massing = biggest / Math.max(size[0], size[1], size[2]);

  return {
    assetCode: source.assetCode,
    displayName: source.displayName,
    group: source.group,
    meshes: meshes.length,
    clusters,
    maxGap,
    size,
    biggest,
    massing,
    footprint: Math.max(size[0], size[2]),
    // 바닥에 붙어 있나 — 0 에서 크게 뜨면 pivot 이 이상하다
    floorGap: b.min[1],
  };
}

/**
 * 결격 사유는 **파이프라인이 못 고치는 것**만이다.
 *
 * 처음에는 pivot(바닥에서 뜸)과 slot 초과도 FIX 로 잡았는데 둘 다 오탐이었다.
 *   pivot   Compiler 가 바닥 중앙으로 맞춘다(`loadFacade`) — prefab 원본 좌표는 결격이 아니다
 *   크기    Unity 부스 앵커가 균등 스케일(실측 13.26)을 건다 — 원본 미터를 슬롯과 직접 비교할 수 없다
 * 그래서 둘은 판정에서 빼고 **정보로만** 낸다. 판정 축을 임의로 만들면 멀쩡한 자산이 떨어진다.
 */
function verdict(r) {
  if (r.clusters > 1) return ['FIX', `부품이 ${r.clusters} 덩어리 (최대 간격 ${f2(r.maxGap)} m) — 하나의 외관 단위가 아니다`];
  if (r.massing < 0.35) return ['PROP_ONLY', `최대 부품이 전체의 ${(r.massing * 100).toFixed(0)}% — 구조물 없이 소품만 있다`];
  return ['KEEP', ''];
}

async function main() {
  const guidIndex = sharedGuidIndex();
  const rows = FACADE_ASSETS.map((s) => analyze(s, guidIndex));

  console.log('\n■ Facade 후보 선별 — 하나의 정상적인 외관 단위인가\n');
  console.log('  assetCode                        그룹      덩어리  바닥(m)  높이   뜸(m)  최대부품비  판정');
  console.log('  ' + '-'.repeat(100));

  const counts = {};
  for (const r of rows) {
    const [v, why] = verdict(r);
    counts[v] = (counts[v] ?? 0) + 1;
    console.log(
      '  ' +
        r.assetCode.padEnd(32) +
        FACADE_GROUP_LABEL[r.group].padEnd(9) +
        String(r.clusters).padStart(5) +
        f2(r.footprint).padStart(9) +
        f2(r.size[1]).padStart(7) +
        f2(r.floorGap).padStart(7) +
        `${(r.massing * 100).toFixed(0)}%`.padStart(11) +
        '  ' +
        v.padEnd(10) +
        why,
    );
  }

  console.log('  ' + '-'.repeat(100));
  console.log(
    '  ' +
      Object.entries(counts)
        .map(([k, n]) => `${k} ${n}`)
        .join(' · '),
  );
  console.log('\n  KEEP 은 자동 판정이 문제를 못 찾았다는 뜻이지 "좋다" 는 뜻이 아니다 — 육안 확인이 남는다.');
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
