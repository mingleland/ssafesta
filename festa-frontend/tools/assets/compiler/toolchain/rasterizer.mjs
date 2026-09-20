// 썸네일·parity 렌더러 (S15P21A604-480).
//
// Node 에 GL 컨텍스트가 없다. 네이티브 바인딩(`gl`)을 넣는 것은 CI 로 이어지지 않고,
// 브라우저를 띄워 캡처하는 것은 "수작업 스크린샷을 정본으로 삼지 않는다" 는 원칙에 걸린다.
// 그래서 필요한 만큼만 직접 그린다 — 고정 orthographic 카메라, Lambert 음영, z-buffer.
//
// 목적은 사진이 아니라 **판별**이다: 팔레트에서 무엇인지 알아볼 수 있고, 원본과 런타임을
// 같은 조건으로 나란히 놓을 수 있으면 된다.
import sharp from 'sharp';

const normalize = (v) => {
  const l = Math.hypot(v[0], v[1], v[2]) || 1;
  return [v[0] / l, v[1] / l, v[2] / l];
};
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];

/**
 * 고정 아이소메트릭 orthographic 카메라. 캔버스와 같은 방향을 쓴다 —
 * 썸네일만 다른 각도로 찍으면 팔레트와 캔버스가 다른 물건처럼 보인다.
 */
export function makeCamera({ direction = [1, 1, 1], target = [0, 0, 0], radius = 1, size = 256, yaw = 0 }) {
  const cos = Math.cos(yaw);
  const sin = Math.sin(yaw);
  const rotated = [direction[0] * cos - direction[2] * sin, direction[1], direction[0] * sin + direction[2] * cos];
  const forward = normalize(rotated);
  const worldUp = [0, 1, 0];
  const right = normalize(cross(worldUp, forward));
  const up = cross(forward, right);
  return { forward, right, up, target, radius, size };
}

/** 월드 좌표 → 픽셀 + 깊이 */
function project(camera, p) {
  const rel = sub(p, camera.target);
  const x = dot(rel, camera.right);
  const y = dot(rel, camera.up);
  const z = dot(rel, camera.forward);
  const half = camera.radius;
  return {
    px: ((x / half) * 0.5 + 0.5) * camera.size,
    py: (0.5 - (y / half) * 0.5) * camera.size,
    // `forward` 는 물체에서 **카메라 쪽**을 가리킨다. 그래서 `dot` 이 클수록 가깝다 —
    // z-buffer 규약("작을수록 가깝다")에 맞추려면 부호를 뒤집어야 한다. 안 뒤집으면
    // 가장 먼 면이 살아남아 모델이 속을 뒤집은 파편 덩어리로 그려진다
    depth: -z,
  };
}

/**
 * 삼각형 목록을 그린다.
 * @param {{positions: Float32Array, normals: Float32Array}} geometry
 * @param {[number,number,number]} baseColor 0~1
 */
export function renderToRaw(geometry, camera, { baseColor, ambient = 0.45, keyDirection = [1, 1.4, 0.8] } = {}) {
  const size = camera.size;
  const rgba = Buffer.alloc(size * size * 4, 0);
  const depthBuffer = new Float32Array(size * size).fill(Infinity);
  const key = normalize(keyDirection);
  const { positions, normals } = geometry;

  for (let i = 0; i < positions.length; i += 9) {
    const verts = [0, 1, 2].map((k) => project(camera, [positions[i + k * 3], positions[i + k * 3 + 1], positions[i + k * 3 + 2]]));
    const normal = normalize([
      (normals[i] + normals[i + 3] + normals[i + 6]) / 3,
      (normals[i + 1] + normals[i + 4] + normals[i + 7]) / 3,
      (normals[i + 2] + normals[i + 5] + normals[i + 8]) / 3,
    ]);
    const lambert = Math.max(0, dot(normal, key));
    const shade = Math.min(1, ambient + lambert * (1 - ambient));

    const minX = Math.max(0, Math.floor(Math.min(verts[0].px, verts[1].px, verts[2].px)));
    const maxX = Math.min(size - 1, Math.ceil(Math.max(verts[0].px, verts[1].px, verts[2].px)));
    const minY = Math.max(0, Math.floor(Math.min(verts[0].py, verts[1].py, verts[2].py)));
    const maxY = Math.min(size - 1, Math.ceil(Math.max(verts[0].py, verts[1].py, verts[2].py)));
    const area = (verts[1].px - verts[0].px) * (verts[2].py - verts[0].py) - (verts[2].px - verts[0].px) * (verts[1].py - verts[0].py);
    if (Math.abs(area) < 1e-9) continue;

    for (let y = minY; y <= maxY; y += 1) {
      for (let x = minX; x <= maxX; x += 1) {
        const cx = x + 0.5;
        const cy = y + 0.5;
        const w0 = ((verts[1].px - verts[0].px) * (cy - verts[0].py) - (cx - verts[0].px) * (verts[1].py - verts[0].py)) / area;
        const w1 = ((cx - verts[2].px) * (verts[0].py - verts[2].py) - (verts[0].px - verts[2].px) * (cy - verts[2].py)) / area;
        const w2 = 1 - w0 - w1;
        if (w0 < 0 || w1 < 0 || w2 < 0) continue;
        const depth = verts[0].depth * w2 + verts[1].depth * w1 + verts[2].depth * w0;
        const idx = y * size + x;
        if (depth >= depthBuffer[idx]) continue;
        depthBuffer[idx] = depth;
        const o = idx * 4;
        rgba[o] = Math.round(Math.min(255, baseColor[0] * 255 * shade));
        rgba[o + 1] = Math.round(Math.min(255, baseColor[1] * 255 * shade));
        rgba[o + 2] = Math.round(Math.min(255, baseColor[2] * 255 * shade));
        rgba[o + 3] = 255;
      }
    }
  }
  return { rgba, size };
}

export async function encodePng({ rgba, size }) {
  return sharp(rgba, { raw: { width: size, height: size, channels: 4 } }).png().toBuffer();
}

export async function encodeWebp({ rgba, size }, quality = 88) {
  return sharp(rgba, { raw: { width: size, height: size, channels: 4 } }).webp({ quality }).toBuffer();
}

/** 여러 렌더를 가로로 이어 붙인다 — parity sheet 용 */
export async function composeSheet(images, size) {
  const width = size * images.length;
  const base = sharp({ create: { width, height: size, channels: 4, background: { r: 18, g: 20, b: 30, alpha: 1 } } });
  return base
    .composite(images.map((buffer, i) => ({ input: buffer, left: i * size, top: 0 })))
    .png()
    .toBuffer();
}
