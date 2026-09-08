// Material / Texture stage — Unity `.mat` 에서 런타임 PBR 로 (S15P21A604-480).
//
// 이번 단계에서 실제로 하는 것은 **source resolution** 이다 — Unity 재질이 어떤 값과 어떤
// 텍스처를 참조하는지 읽어 내고, 런타임 PBR 표현으로 정규화하고, 원본 바이트를 실측한다.
//
// 아직 하지 않는 것: bake · atlas · downscale · KTX2 압축. 그 도구들이 이 장비에 없다.
// **없는 단계를 한 것처럼 리포트하지 않는다** — `pending` 으로 남긴다.
import { readFileSync, statSync } from 'node:fs';

/** Unity `.mat` 은 YAML 이지만 필요한 것이 몇 줄이라 문서 전체를 파싱하지 않는다 */
const colorOf = (text, key) => {
  const m = new RegExp(`- ${key}: \\{r: ([-\\d.eE+]+), g: ([-\\d.eE+]+), b: ([-\\d.eE+]+), a: ([-\\d.eE+]+)\\}`).exec(text);
  return m === null ? null : { r: +m[1], g: +m[2], b: +m[3], a: +m[4] };
};
const floatOf = (text, key) => {
  const m = new RegExp(`- ${key}: ([-\\d.eE+]+)`).exec(text);
  return m === null ? null : Number(m[1]);
};
// `\r?` 가 있어야 한다. Unity 가 저장한 `.mat` 은 Windows 에서 **CRLF** 이고, `\n` 만 요구하면
// 키 다음 줄바꿈에서 매치가 통째로 실패한다 — 조용히 "텍스처 없는 재질" 로 읽혀 리포트에는
// `채널 [없음] · 원본 0 B` 만 남는다. LF 로 정규화한 픽스처 테스트는 이것을 잡지 못한다.
const textureOf = (text, key) => {
  const m = new RegExp(`- ${key}:\\r?\\n\\s*m_Texture: \\{fileID: (\\d+)(?:, guid: ([0-9a-f]{32}))?`).exec(text);
  if (m === null || m[2] === undefined) return null;
  return m[2];
};

/** Unity 재질 슬롯 → 런타임 PBR 채널. 런타임이 아는 이름은 이 셋뿐이다 */
const CHANNELS = [
  { runtime: 'baseColor', unity: ['_BaseMap', '_MainTex'] },
  { runtime: 'normal', unity: ['_BumpMap'] },
  { runtime: 'metallicRoughness', unity: ['_MetallicGlossMap', '_SpecGlossMap'] },
];

/**
 * `.mat` 하나를 런타임 재질 서술로 옮긴다.
 *
 * 원본 재질 이름·슬롯 이름은 결과에 넣지 않는다 — 런타임이 authoring 어휘를 알 이유가 없다.
 * 다만 **빌드 리포트에는** 무엇을 읽었는지 남긴다. 그래야 무엇이 빠졌는지 사람이 안다.
 */
export function resolveMaterial(matPath, guidIndex) {
  return parseMaterial(readFileSync(matPath, 'utf8'), guidIndex);
}

/** `.mat` 본문만 받는 순수 함수 — 파일 없이 테스트할 수 있어야 한다 */
export function parseMaterial(text, guidIndex) {
  const baseColor = colorOf(text, '_BaseColor') ?? colorOf(text, '_Color') ?? { r: 1, g: 1, b: 1, a: 1 };
  const metallic = floatOf(text, '_Metallic') ?? 0;
  const smoothness = floatOf(text, '_Smoothness') ?? floatOf(text, '_Glossiness') ?? 0.5;

  const textures = [];
  for (const channel of CHANNELS) {
    for (const slot of channel.unity) {
      const guid = textureOf(text, slot);
      if (guid === null) continue;
      const path = guidIndex.get(guid);
      textures.push({
        channel: channel.runtime,
        sourceBytes: path === undefined ? null : statSync(path).size,
        sourcePath: path ?? null,
        resolved: path !== undefined,
      });
      break;
    }
  }

  return {
    // 런타임 재질 — Unity 어휘가 아니라 PBR 어휘다
    runtime: {
      baseColor: [baseColor.r, baseColor.g, baseColor.b],
      opacity: baseColor.a,
      metalness: metallic,
      // Unity 는 smoothness, glTF 는 roughness 다. 뒤집는 자리가 여기 하나여야 한다
      roughness: Math.max(0, Math.min(1, 1 - smoothness)),
    },
    textures,
  };
}

/**
 * 재질이 참조하는 텍스처의 **원본 바이트만** 실측한다.
 *
 * 실제 downscale·재패킹·webp 인코딩은 emit 단계(`toolchain/textures.mjs`)가 에셋별로 한다.
 * 이 함수는 그 결과를 알지 못하므로 `runtimeBytes` 를 `null` 로 둔다 — **"아직 아무도 안 했다"
 * 가 아니라 "여기서는 모른다"** 다. 예전 문구가 전자로 읽혀, 같은 실행 안에서 emit 이
 * `3,386,787 → 7,392 B` 를 찍는 옆에서 probe 가 `runtime 미적용` 을 찍는 자기모순이 났다.
 */
export function planTextureOptimization(textures) {
  const sourceBytes = textures.reduce((sum, t) => sum + (t.sourceBytes ?? 0), 0);
  return {
    sourceBytes,
    runtimeBytes: null,
    measuredHere: false,
    reason:
      textures.length === 0
        ? '참조하는 텍스처가 없다 — 색만 있는 재질이다'
        : '원본 바이트만 실측한다. 런타임 변환 결과는 emit 단계가 에셋별로 낸다',
  };
}
