// Runtime asset 배포 전 무결성과 같은 입력의 변환 안정성을 확인한다 (S15P21A604-741).
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { createIO, measure } from './compiler/toolchain/gltfPipeline.mjs';

const root = resolve(import.meta.dirname, '../..');

function fail(message) {
  throw new Error(`[assets:verify] ${message}`);
}

function safeAssetFile(value, kind) {
  if (typeof value !== 'string' || basename(value) !== value || !value) fail(`${kind} 경로가 안전한 파일명이 아니다`);
  return value;
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
    assets.push({
      assetCode: asset.assetCode,
      geometry: measure(document),
      bounds: asset.bounds,
      material: asset.material,
      triangles: asset.triangles,
    });
  }
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
