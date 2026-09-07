// 텍스처 런타임화 — 리사이즈 · 채널 재패킹 · webp 인코딩 (S15P21A604-480).
//
// Unity 와 glTF 는 metallic/roughness 를 다르게 담는다. 그대로 넘기면 값이 뒤집힌 채
// 렌더된다 — 광택 재질이 거칠어 보이거나 그 반대가 된다.
//
//   Unity  _MetallicGlossMap   R = metallic, A = smoothness
//   glTF   metallicRoughness   G = roughness, B = metallic
//
// 그래서 이 파일이 하는 일의 절반은 채널을 옮기는 것이고, 나머지 절반은 크기를 줄이는 것이다.
import sharp from 'sharp';

/** 웹 런타임 기본 해상도. 편집기 캔버스에서 오브젝트가 화면의 일부만 차지한다 */
export const DEFAULT_MAX_SIZE = 512;

export async function describeSource(path) {
  const meta = await sharp(path).metadata();
  return { width: meta.width, height: meta.height, format: meta.format, channels: meta.channels };
}

/** baseColor·normal 처럼 채널 배치가 그대로인 텍스처 */
export async function encodeDirect(path, { maxSize = DEFAULT_MAX_SIZE, quality = 82 } = {}) {
  return sharp(path)
    .resize(maxSize, maxSize, { fit: 'inside', withoutEnlargement: true })
    .webp({ quality })
    .toBuffer();
}

/**
 * Unity metallic-gloss → glTF metallicRoughness.
 *
 * 알파(smoothness)를 뒤집어 roughness 로, 빨강(metallic)을 파랑으로 옮긴다.
 * 알파가 없는 원본이면 smoothness 정보가 없다는 뜻이라 재질 스칼라 roughness 를 그대로 쓴다.
 */
export async function encodeMetallicRoughness(path, { maxSize = DEFAULT_MAX_SIZE, quality = 82, fallbackRoughness = 0.5 } = {}) {
  const resized = sharp(path).resize(maxSize, maxSize, { fit: 'inside', withoutEnlargement: true });
  const { data, info } = await resized.raw().toBuffer({ resolveWithObject: true });
  const pixels = info.width * info.height;
  const out = Buffer.alloc(pixels * 3);
  const hasAlpha = info.channels === 4;
  for (let i = 0; i < pixels; i += 1) {
    const src = i * info.channels;
    const metallic = data[src];
    const smoothness = hasAlpha ? data[src + 3] : Math.round((1 - fallbackRoughness) * 255);
    out[i * 3] = 0; // R 은 glTF 가 쓰지 않는다(occlusion 이 들어갈 자리)
    out[i * 3 + 1] = 255 - smoothness; // G = roughness
    out[i * 3 + 2] = metallic; // B = metallic
  }
  return {
    buffer: await sharp(out, { raw: { width: info.width, height: info.height, channels: 3 } }).webp({ quality }).toBuffer(),
    width: info.width,
    height: info.height,
    repacked: hasAlpha ? 'A(smoothness)→G(1-roughness) · R(metallic)→B' : 'A 없음 — 재질 스칼라 roughness 사용',
  };
}

/** 채널별 처리기 선택 — 런타임이 아는 채널 이름만 받는다 */
export async function encodeForChannel(channel, path, options) {
  if (channel === 'metallicRoughness') return encodeMetallicRoughness(path, options);
  const buffer = await encodeDirect(path, options);
  const meta = await sharp(buffer).metadata();
  return { buffer, width: meta.width, height: meta.height, repacked: null };
}
