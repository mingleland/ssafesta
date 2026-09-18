// Runtime asset 배포 전 무결성과 같은 입력의 변환 안정성을 확인한다 (S15P21A604-741).
//
// 치수 정본은 **커밋된 `asset-bounds.lock.json`** 이다 (S15P21A604-785, GitLab #181).
// manifest 는 커밋되지 않는 빌드 산출물이라 CI·fresh clone 에서 대조 대상이 사라진다 —
// 그래서 lock 을 정본으로 두고 manifest 가 거기서 벗어나면 여기서 멈춘다.
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { createIO, measure } from './compiler/toolchain/gltfPipeline.mjs';

const root = resolve(import.meta.dirname, '../..');

const BOUNDS_LOCK = JSON.parse(readFileSync(resolve(import.meta.dirname, 'asset-bounds.lock.json'), 'utf8'));

function fail(message) {
  throw new Error(`[assets:verify] ${message}`);
}

function safeAssetFile(value, kind) {
  if (typeof value !== 'string' || basename(value) !== value || !value) fail(`${kind} 경로가 안전한 파일명이 아니다`);
  return value;
}

/**
 * manifest 의 치수를 lock 과 대조한다.
 *
 * 정확히 같아야 한다. manifest 치수는 이미 소수 4자리로 반올림돼 있고, 같은 입력이면 같은 값이
 * 나온다는 것은 아래 deterministic 검사가 따로 보장한다. 그래서 여기서 오차를 허용하면
 * "언제 바뀐 것인지" 를 놓친다 — 값이 바뀌면 파이프라인이나 프리팹이 바뀐 것이고 사람이 볼 일이다.
 */
function verifyBounds(assetCode, bounds) {
  const locked = BOUNDS_LOCK.assets[assetCode];
  if (locked === undefined) {
    fail(`${assetCode} 가 asset-bounds.lock.json 에 없다 — 새 자산이면 실측값을 lock 에 추가하라`);
  }
  const same = JSON.stringify(bounds) === JSON.stringify(locked);
  if (!same) {
    fail(`${assetCode} 치수가 lock 과 다르다. lock=${JSON.stringify(locked)} manifest=${JSON.stringify(bounds)}`);
  }
}

export async function readRuntimeAssetSummary(dir) {
  const manifestPath = resolve(dir, 'manifest.json');
  if (!existsSync(manifestPath)) fail(`manifest 없음: ${manifestPath}`);
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  if (manifest.version !== 1 || manifest.kind !== 'ssafesta-runtime-asset-manifest') fail('지원하지 않는 manifest 형식');
  if (manifest.emission !== 'productionCandidate') fail(`배포 불가 emission: ${manifest.emission}`);
  if (!Array.isArray(manifest.assets) || manifest.assets.length === 0) fail('runtime asset이 없다');

  const io = createIO();
  const assets = [];
  for (const asset of manifest.assets) {
    if (!asset?.assetCode) fail('assetCode가 없다');
    const glb = safeAssetFile(asset.url, `${asset.assetCode} GLB`);
    const thumbnail = safeAssetFile(asset.thumbnail, `${asset.assetCode} thumbnail`);
    const glbPath = resolve(dir, glb);
    const thumbnailPath = resolve(dir, thumbnail);
    if (!existsSync(glbPath) || statSync(glbPath).size === 0) fail(`${asset.assetCode} GLB가 없다`);
    if (!existsSync(thumbnailPath) || statSync(thumbnailPath).size === 0) fail(`${asset.assetCode} WebP가 없다`);
    const document = await io.readBinary(readFileSync(glbPath));
    verifyBounds(asset.assetCode, asset.bounds);
    assets.push({
      assetCode: asset.assetCode,
      geometry: measure(document),
      bounds: asset.bounds,
      material: asset.material,
      triangles: asset.triangles,
    });
  }
  // 반대 방향도 본다 — lock 에만 있고 산출물에 없는 자산은 파이프라인에서 빠진 것이다.
  const built = new Set(manifest.assets.map((a) => a.assetCode));
  const missing = Object.keys(BOUNDS_LOCK.assets).filter((code) => !built.has(code));
  if (missing.length > 0) fail(`lock 에 있으나 산출물에 없는 자산: ${missing.join(', ')}`);
  return assets.sort((a, b) => a.assetCode.localeCompare(b.assetCode));
}

function runBuild(runtimeDir, intermediateDir) {
  const env = { ...process.env, FESTA_RUNTIME_DIR: runtimeDir, FESTA_INTERMEDIATE_DIR: intermediateDir };
  for (const script of ['tools/assets/build-booth-assets.mjs', 'tools/assets/compile-runtime-assets.mjs']) {
    console.log(`[assets:verify] ${script}`);
    const result = spawnSync(process.execPath, [script], { cwd: root, env, stdio: 'inherit' });
    if (result.status !== 0) fail(`${script} 변환 실패`);
  }
}

async function verifyDeterministicBuild() {
  const temp = mkdtempSync(resolve(tmpdir(), 'festa-runtime-verify-'));
  try {
    const first = resolve(temp, 'first');
    const second = resolve(temp, 'second');
    runBuild(resolve(first, 'runtime'), resolve(first, 'intermediate'));
    runBuild(resolve(second, 'runtime'), resolve(second, 'intermediate'));
    const before = await readRuntimeAssetSummary(resolve(first, 'runtime'));
    const after = await readRuntimeAssetSummary(resolve(second, 'runtime'));
    if (JSON.stringify(before) !== JSON.stringify(after)) fail('동일 입력의 runtime GLB semantic 결과가 다르다');
    console.log(`[assets:verify] deterministic semantic check passed (${before.length} assets)`);
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }
}

const targetIndex = process.argv.indexOf('--dir');
if (targetIndex >= 0) {
  const target = process.argv[targetIndex + 1];
  if (!target) fail('--dir 값이 없다');
  const assets = await readRuntimeAssetSummary(resolve(target));
  console.log(`[assets:verify] runtime manifest integrity passed (${assets.length} assets)`);
} else {
  await verifyDeterministicBuild();
}
