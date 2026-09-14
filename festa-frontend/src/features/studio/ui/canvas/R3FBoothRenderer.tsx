// R3F 부스 렌더러 — D-05 Spike (S15P21A604-470).
//
// 지위: 채택 확정이 아니라 검증용 프로토타입이다. 실제 Unity 에셋(GLB)은 쓰지 않고
// 계약의 OBJECT_LOCAL_BOUNDS 에서 파라메트릭 박스를 만든다. 실루엣 말고는 시안이 요구하는
// 것 — 실광원·그림자·PBR 재질·3축 기즈모 — 이 전부 여기서 성립하는지를 본다.
//
// 편집 계약은 SVG 렌더러와 같다: X/Z 이동, Y 고정, Y축 회전, snap, bounds clamp.
// 좌표 변환은 coords.ts 를 그대로 쓴다 — 부호 반전이 두 곳에 생기면 안 된다.
//
// default export 인 이유: BoothCanvasViewport 가 React.lazy 로 부른다. three 를 static import
// 하면 Studio 를 열지 않는 사용자도 받게 된다.
import { Suspense, useEffect, useMemo, useRef, useState } from 'react';
import { Canvas, useThree } from '@react-three/fiber';
import type { ThreeEvent } from '@react-three/fiber';
import * as THREE from 'three';
import { OBJECT_LOCAL_BOUNDS } from '../../../../entities/layout/objectTypes';
import { isAreaOutOfBounds, worldAABB } from '../../../../entities/layout/geometry';
import { overlappingObjectIds } from '../../lib/overlap';
import type { LayoutObject } from '../../../../entities/layout/types';
import { clampToBooth, normalizeRotation, snap } from '../../lib/coords';
import type { BoothRendererProps } from './canvasTypes';
import { boxPlacement, canRotateFrom, fitZoom, isoCameraPosition, isoTarget, rotationFromDrag } from './isoCamera';
import { IS_VISUAL_ACCEPTANCE, VISUAL_ACCEPTANCE_FRAME_MS } from './canvasRenderer';
import { AssetMesh } from './AssetMesh';
import { dragKind, exceedsDragThreshold } from '../../model/studioMode';
import { pickAsset } from '../../model/boothAssetManifest';
import type { BoothAssetEntry } from '../../model/boothAssetManifest';
import { useBoothAssets } from '../../model/useBoothAssets';
import { FALLBACK_BOX, OBJECT_FILL, OBJECT_SURFACE, shade } from './objectAppearance';

const ROTATE_SNAP_DEG = 15;
/** 바닥 평면 y=0. 드래그는 전부 이 평면 위의 교점으로 환산한다 */
const GROUND = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0);

interface DragState {
  kind: 'move' | 'rotate';
  objectId: string;
  origin: { x: number; z: number };
  grab: { x: number; z: number };
  startRotation: number;
  /** pointerdown 이 일어난 화면 좌표 — 손떨림과 진짜 드래그를 가르는 기준점 (S15P21A604-689) */
  startClient: { x: number; y: number };
  /** 임계값을 한 번 넘었는가. 넘기 전에는 선택만이고 좌표는 커밋되지 않는다 */
  armed: boolean;
}

/**
 * 검증 모드에서만 프레임을 타이머로 민다. rAF 가 멈춘 환경(숨겨진 pane)에서도 3D 가 갱신된다.
 * 제품 경로에서는 이 컴포넌트가 아예 렌더되지 않는다.
 */
function TimerDrivenFrames() {
  const gl = useThree((s) => s.gl);
  const scene = useThree((s) => s.scene);
  const camera = useThree((s) => s.camera);
  useEffect(() => {
    // R3F 의 advance() 를 먼저 써 봤는데 이 환경에서 프레임이 나오지 않았다.
    // 렌더러를 직접 부르면 루프 구현과 무관하게 그려진다 — 검증 모드에서만 쓰는 길이다.
    const id = setInterval(() => {
      scene.updateMatrixWorld();
      gl.render(scene, camera);
    }, VISUAL_ACCEPTANCE_FRAME_MS);
    return () => clearInterval(id);
  }, [gl, scene, camera]);
  return null;
}

// ── 카메라 ─────────────────────────────────────────────────────────
// 고정 iso 시점 + 캔버스 크기에 맞춘 zoom. 자유 orbit 은 D-04 가 기본 UX 에서 뺐다.
function IsoCamera({ bounds, zoom }: { bounds: BoothRendererProps['bounds']; zoom: number }) {
  const camera = useThree((s) => s.camera);
  const size = useThree((s) => s.size);
  const target = isoTarget(bounds);

  const fitted = fitZoom(size, bounds, zoom);
  if (camera instanceof THREE.OrthographicCamera && camera.zoom !== fitted) {
    camera.zoom = fitted;
    camera.updateProjectionMatrix();
  }
  camera.lookAt(target[0], target[1], target[2]);
  return null;
}

/** 화면 좌표 → 바닥 평면(y=0) 월드 좌표. SVG 렌더러의 unproj 에 해당한다 */
function useGroundPicker() {
  const camera = useThree((s) => s.camera);
  const gl = useThree((s) => s.gl);
  return useMemo(() => {
    const raycaster = new THREE.Raycaster();
    const ndc = new THREE.Vector2();
    const hit = new THREE.Vector3();
    return (clientX: number, clientY: number): { x: number; z: number } | null => {
      const rect = gl.domElement.getBoundingClientRect();
      if (rect.width === 0 || rect.height === 0) return null;
      ndc.x = ((clientX - rect.left) / rect.width) * 2 - 1;
      ndc.y = -((clientY - rect.top) / rect.height) * 2 + 1;
      raycaster.setFromCamera(ndc, camera);
      if (raycaster.ray.intersectPlane(GROUND, hit) === null) return null;
      return { x: hit.x, z: hit.z };
    };
  }, [camera, gl]);
}

// ── 정적 지오메트리 ────────────────────────────────────────────────
function BoothStage({ bounds, decor }: { bounds: BoothRendererProps['bounds']; decor: BoothRendererProps['decor'] }) {
  const halfW = bounds.width / 2;
  const halfD = bounds.depth / 2;
  const h = bounds.height;
  const wallT = 0.08;
  const bar = 0.09;

  // 트러스 기둥 4개 + 상단 링. 시안의 프레임을 최소 요소로 흉내 낸다
  const posts: Array<[number, number]> = [
    [-halfW, -halfD],
    [halfW, -halfD],
    [halfW, halfD],
    [-halfW, halfD],
  ];
  const lamps = [0.25, 0.5, 0.75].map((t) => -halfW + bounds.width * t);

  return (
    <group>
      {/* 바닥 — 그림자를 받는 유일한 면 */}
      <mesh rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
        <planeGeometry args={[bounds.width, bounds.depth]} />
        <meshStandardMaterial color={decor.floorHex} roughness={0.42} metalness={0.05} />
      </mesh>
      <gridHelper
        args={[Math.max(bounds.width, bounds.depth), Math.max(bounds.width, bounds.depth), '#8ea2c8', '#8ea2c8']}
        position={[0, 0.002, 0]}
      />

      {/* 뒤쪽(z=-D/2) + 왼쪽(x=-W/2) 벽 — 코너형 ㄱ자 */}
      <mesh position={[0, h / 2, -halfD - wallT / 2]} receiveShadow castShadow>
        <boxGeometry args={[bounds.width + wallT * 2, h, wallT]} />
        <meshStandardMaterial color={decor.wallHex} roughness={0.8} />
      </mesh>
      <mesh position={[-halfW - wallT / 2, h / 2, 0]} receiveShadow castShadow>
        <boxGeometry args={[wallT, h, bounds.depth]} />
        <meshStandardMaterial color={shade(decor.wallHex, 0.9)} roughness={0.8} />
      </mesh>

      {/* 외관 모드의 그래픽 띠 — 벽면 위에 살짝 띄워 z-fighting 을 피한다 */}
      {decor.graphic && (
        <>
          <mesh position={[0, h * 0.3, -halfD - wallT * 0.02]}>
            <planeGeometry args={[bounds.width, h * 0.6]} />
            <meshStandardMaterial color={decor.primaryHex} roughness={0.5} />
          </mesh>
          <mesh position={[-halfW - wallT * 0.02, h * 0.3, 0]} rotation={[0, Math.PI / 2, 0]}>
            <planeGeometry args={[bounds.depth, h * 0.6]} />
            <meshStandardMaterial color={shade(decor.primaryHex, 0.8)} roughness={0.5} />
          </mesh>
        </>
      )}

      {/* 트러스 */}
      {posts.map(([x, z], i) => (
        <mesh key={'post' + i} position={[x, h / 2, z]} castShadow>
          <boxGeometry args={[bar, h, bar]} />
          <meshStandardMaterial color="#8c93a4" roughness={0.4} metalness={0.65} />
        </mesh>
      ))}
      {posts.map(([x, z], i) => {
        const [nx, nz] = posts[(i + 1) % posts.length];
        const len = Math.hypot(nx - x, nz - z);
        return (
          <mesh
            key={'beam' + i}
            position={[(x + nx) / 2, h, (z + nz) / 2]}
            rotation={[0, Math.atan2(nx - x, nz - z), 0]}
            castShadow
          >
            <boxGeometry args={[bar, bar, len]} />
            <meshStandardMaterial color="#8c93a4" roughness={0.4} metalness={0.65} />
          </mesh>
        );
      })}

      {/* 트러스 조명 — 실제 SpotLight 다. SVG 는 여기서 그라디언트 원을 그렸다 */}
      {lamps.map((x, i) => (
        <group key={'lamp' + i} position={[x, h - 0.08, -halfD + 0.6]}>
          <mesh>
            <cylinderGeometry args={[0.07, 0.1, 0.14, 10]} />
            <meshStandardMaterial color="#ffe9a8" emissive="#ffd371" emissiveIntensity={1.4} />
          </mesh>
          {/* 타깃은 월드 원점(three 기본) — light.target 은 씬에 넣지 않으면 matrixWorld 가 갱신되지 않는다.
              그림자는 directionalLight 하나만 굽는다. 스포트마다 맵을 구우면 4장이 되고, 그 비용은
              이번 Spike 가 재려는 "Unity 와 공존 가능한가" 를 그대로 망친다 */}
          <spotLight position={[0, -0.1, 0]} angle={0.7} penumbra={0.7} intensity={9} distance={14} color="#ffeccd" />
        </group>
      ))}
    </group>
  );
}

// ── 오브젝트 ───────────────────────────────────────────────────────
// manifest 에 그 오브젝트의 실제 모델이 있으면 GLB 를, 없으면 파라메트릭 박스를 쓴다.
// 둘 중 무엇을 그리든 **도메인은 바뀌지 않는다** — 선택 윤곽·이탈 판정은 계약 AABB 그대로다.
// 모델 치수가 계약과 조금 달라도 저장값·검증이 흔들리면 안 되기 때문이다.
function ObjectMesh({
  obj,
  selected,
  invalid,
  asset,
  onDown,
}: {
  obj: LayoutObject;
  selected: boolean;
  /** 경계 이탈이거나 다른 오브젝트와 겹친다 — 사용자에게는 둘 다 "이 자리는 안 된다" 다 */
  invalid: boolean;
  asset: BoothAssetEntry | undefined;
  onDown: (e: ThreeEvent<PointerEvent>) => void;
}) {
  const box = OBJECT_LOCAL_BOUNDS[obj.type] ?? FALLBACK_BOX;
  const { center, size } = boxPlacement(box);
  const surface = OBJECT_SURFACE[obj.type] ?? { roughness: 0.7, metalness: 0, opacity: 1 };
  const color = invalid ? '#ff8a8a' : (OBJECT_FILL[obj.type] ?? '#e5e9f2');

  const boxBody = (
    <mesh position={center} castShadow receiveShadow>
      <boxGeometry args={size} />
      <meshStandardMaterial
        color={color}
        roughness={surface.roughness}
        metalness={surface.metalness}
        transparent={surface.opacity < 1}
        opacity={surface.opacity}
      />
    </mesh>
  );

  return (
    <group position={[obj.position.x, 0, obj.position.z]} rotation={[0, (obj.rotationY * Math.PI) / 180, 0]}>
      {/* 픽킹은 group 이 받는다 — 모델이 여러 mesh 로 쪼개져 있어도 한 덩어리로 잡힌다 */}
      <group onPointerDown={onDown}>
        {asset === undefined ? (
          boxBody
        ) : (
          <AssetMesh
            entry={asset}
            color={color}
            roughness={surface.roughness}
            metalness={surface.metalness}
            fallback={boxBody}
          />
        )}
      </group>
      {selected && (
        // 선택 윤곽 — 계약 AABB 를 살짝 키운 wireframe. 모델이 아니라 도메인을 보여 준다
        <mesh position={center}>
          <boxGeometry args={[size[0] * 1.04, size[1] * 1.04, size[2] * 1.04]} />
          <meshBasicMaterial color={invalid ? '#ff5d5d' : '#5ee08a'} wireframe />
        </mesh>
      )}
    </group>
  );
}

// ── 3축 기즈모 ─────────────────────────────────────────────────────
// 시안의 빨강(X)/초록(Y)/파랑(Z) 화살표. Y 는 계약상 고정이라 표시만 하고 잡을 수 없다.
function AxisArrow({
  dir,
  color,
  length,
  grabbable,
  onDown,
}: {
  dir: [number, number, number];
  color: string;
  length: number;
  grabbable: boolean;
  onDown?: (e: ThreeEvent<PointerEvent>) => void;
}) {
  const rot: [number, number, number] =
    dir[1] === 1 ? [0, 0, 0] : dir[0] === 1 ? [0, 0, -Math.PI / 2] : [Math.PI / 2, 0, 0];
  return (
    <group rotation={rot} onPointerDown={grabbable ? onDown : undefined}>
      <mesh position={[0, length / 2, 0]}>
        <cylinderGeometry args={[0.022, 0.022, length, 8]} />
        <meshBasicMaterial color={color} opacity={grabbable ? 1 : 0.45} transparent={!grabbable} />
      </mesh>
      <mesh position={[0, length, 0]}>
        <coneGeometry args={[0.07, 0.18, 10]} />
        <meshBasicMaterial color={color} opacity={grabbable ? 1 : 0.45} transparent={!grabbable} />
      </mesh>
    </group>
  );
}

function Gizmo({
  obj,
  tool,
  onMoveDown,
  onRotateDown,
}: {
  obj: LayoutObject;
  tool: BoothRendererProps['tool'];
  onMoveDown: (e: ThreeEvent<PointerEvent>) => void;
  onRotateDown: (e: ThreeEvent<PointerEvent>) => void;
}) {
  const box = OBJECT_LOCAL_BOUNDS[obj.type] ?? FALLBACK_BOX;
  const radius = Math.max(box.max.x - box.min.x, box.max.z - box.min.z) * 0.75 + 0.45;
  const rad = (obj.rotationY * Math.PI) / 180;

  return (
    <group position={[obj.position.x, 0.01, obj.position.z]}>
      <AxisArrow dir={[1, 0, 0]} color="#ff5d5d" length={radius + 0.4} grabbable onDown={onMoveDown} />
      <AxisArrow dir={[0, 0, 1]} color="#4d8dff" length={radius + 0.4} grabbable onDown={onMoveDown} />
      {/* Y 는 편집기에서 항상 0 이다(계약). 축이 있다는 것만 보이고 잡히지 않는다 */}
      <AxisArrow dir={[0, 1, 0]} color="#5ee08a" length={radius * 0.7} grabbable={false} />

      {/* 회전 링 */}
      <mesh rotation={[-Math.PI / 2, 0, 0]}>
        <ringGeometry args={[radius, radius + 0.035, 48]} />
        <meshBasicMaterial color="#5ee08a" side={THREE.DoubleSide} />
      </mesh>
      {/* 넓은 히트 밴드는 회전 도구일 때만 — 항상 켜 두면 아래 오브젝트 클릭을 가로챈다 */}
      {tool === 'rotate' && (
        <mesh rotation={[-Math.PI / 2, 0, 0]} onPointerDown={onRotateDown}>
          <ringGeometry args={[radius - 0.22, radius + 0.22, 32]} />
          <meshBasicMaterial transparent opacity={0} side={THREE.DoubleSide} depthWrite={false} />
        </mesh>
      )}
      {/* 현재 정면(+Z 기준 rotationY)을 가리키는 손잡이 */}
      <mesh position={[Math.sin(rad) * radius, 0.02, Math.cos(rad) * radius]} onPointerDown={onRotateDown}>
        <sphereGeometry args={[0.1, 12, 12]} />
        <meshBasicMaterial color="#2fbf6a" />
      </mesh>
    </group>
  );
}

// ── 씬 ────────────────────────────────────────────────────────────
function Scene(p: BoothRendererProps & { assets: BoothAssetEntry[] }) {
  const pick = useGroundPicker();
  const drag = useRef<DragState | null>(null);
  const [dragging, setDragging] = useState(false);

  // 겹침은 배치가 바뀔 때만 다시 센다 — 드래그 중 매 프레임 O(n²) 를 돌 이유가 없다
  const overlapping = useMemo(() => overlappingObjectIds(p.objects), [p.objects]);

  const selected = p.objects.find((o) => o.objectId === p.selectedObjectId) ?? null;
  const halfDiag = Math.hypot(p.bounds.width, p.bounds.depth);

  function begin(e: ThreeEvent<PointerEvent>, obj: LayoutObject, kind: 'move' | 'rotate') {
    e.stopPropagation();
    p.onSelect(obj.objectId);
    const w = pick(e.clientX, e.clientY);
    if (w === null) return;
    // 피벗 위를 잡은 회전은 시작하지 않는다 — 각도가 정의되지 않아 1 px 흔들림이 수십 도로 커밋된다.
    // 회전은 오브젝트 가장자리나 기즈모 링에서 시작한다 (S15P21A604-689).
    if (kind === 'rotate' && !canRotateFrom({ x: obj.position.x, z: obj.position.z }, w)) return;
    drag.current = {
      kind,
      objectId: obj.objectId,
      origin: { x: obj.position.x, z: obj.position.z },
      grab: w,
      startRotation: obj.rotationY,
      startClient: { x: e.clientX, y: e.clientY },
      armed: false,
    };
    setDragging(true);
  }

  function move(e: ThreeEvent<PointerEvent>) {
    const d = drag.current;
    if (d === null) return;
    // 임계값을 넘기 전에는 선택만 한 것으로 본다. 사람의 클릭은 1~3 px 흔들리고, 그 한 번의 pointermove 가
    // 곧바로 커밋되면 스냅이 켜진 상태에서 격자에 맞지 않던 좌표가 통째로 끌려간다 (S15P21A604-689).
    if (!d.armed) {
      if (!exceedsDragThreshold(d.startClient, { x: e.clientX, y: e.clientY })) return;
      d.armed = true;
    }
    const w = pick(e.clientX, e.clientY);
    if (w === null) return;
    if (d.kind === 'move') {
      let x = d.origin.x + (w.x - d.grab.x);
      let z = d.origin.z + (w.z - d.grab.z);
      if (p.snapOn) {
        x = snap(x);
        z = snap(z);
      }
      const c = clampToBooth(x, z, p.bounds);
      p.onMove(d.objectId, Number(c.x.toFixed(3)), Number(c.z.toFixed(3)));
    } else {
      let deg = rotationFromDrag(d.origin, d.grab, w, d.startRotation);
      if (p.snapOn) deg = Math.round(deg / ROTATE_SNAP_DEG) * ROTATE_SNAP_DEG;
      p.onRotate(d.objectId, Math.round(normalizeRotation(deg)));
    }
  }

  const end = () => {
    drag.current = null;
    setDragging(false);
  };

  // 드래그 종료를 window 에서도 받는다 (S15P21A604-689).
  //
  // 바닥 평면의 onPointerUp 은 커서가 캔버스 안에 있을 때만 온다. 속성 패널·팔레트 위에서 버튼을 떼면
  // 그 신호가 없어 drag 상태가 그대로 남고, 다음에 커서가 캔버스로 돌아오는 순간 버튼을 누르지 않았는데도
  // pointermove 가 좌표를 커밋한다 — 실측에서 오브젝트가 6 m 를 건너뛰었다. pointercancel 도 같이 받는다
  // (브라우저가 제스처를 가로채면 up 이 아니라 cancel 로 끝난다).
  useEffect(() => {
    const onUp = () => end();
    window.addEventListener('pointerup', onUp);
    window.addEventListener('pointercancel', onUp);
    return () => {
      window.removeEventListener('pointerup', onUp);
      window.removeEventListener('pointercancel', onUp);
    };
  }, []);

  return (
    <group>
      <IsoCamera bounds={p.bounds} zoom={p.zoom} />

      {/* 조명 — 시안의 "그림자 생성/받기" 를 실제로 만드는 부분 */}
      <ambientLight intensity={0.55} />
      <hemisphereLight args={['#dce8ff', '#2b3350', 0.5]} />
      <directionalLight
        position={[halfDiag * 1.2, halfDiag * 1.6, halfDiag * 0.9]}
        intensity={2.1}
        castShadow
        shadow-mapSize={[1024, 1024]}
        shadow-camera-left={-halfDiag}
        shadow-camera-right={halfDiag}
        shadow-camera-top={halfDiag}
        shadow-camera-bottom={-halfDiag}
        shadow-camera-near={0.1}
        shadow-camera-far={halfDiag * 4}
      />

      {/* 빈 곳을 누르면 선택 해제. 드래그 좌표도 이 평면에서 나온다 */}
      <mesh
        rotation={[-Math.PI / 2, 0, 0]}
        position={[0, -0.001, 0]}
        onPointerDown={() => {
          if (!dragging) p.onSelect(null);
        }}
        onPointerMove={move}
        onPointerUp={end}
      >
        <planeGeometry args={[halfDiag * 6, halfDiag * 6]} />
        <meshBasicMaterial transparent opacity={0} depthWrite={false} />
      </mesh>

      <BoothStage bounds={p.bounds} decor={p.decor} />

      {p.objects.map((obj) => {
        const known = OBJECT_LOCAL_BOUNDS[obj.type];
        const oob = known !== undefined && isAreaOutOfBounds(worldAABB(known, obj.rotationY, obj.position), p.bounds);
        return (
          <ObjectMesh
            key={obj.objectId}
            obj={obj}
            selected={obj.objectId === p.selectedObjectId}
            invalid={oob || overlapping.has(obj.objectId)}
            asset={pickAsset(p.assets, obj)}
            onDown={(e) => begin(e, obj, dragKind(e, p.tool))}
          />
        );
      })}

      {selected !== null && (
        <Gizmo
          obj={selected}
          tool={p.tool}
          onMoveDown={(e) => begin(e, selected, 'move')}
          onRotateDown={(e) => begin(e, selected, 'rotate')}
        />
      )}
    </group>
  );
}

export default function R3FBoothRenderer(p: BoothRendererProps) {
  // manifest 는 Canvas **밖**에서 읽는다. R3F 는 별도 reconciler 라 안쪽에서 앱 context 를
  // 기대하지 않는 편이 안전하고, 여기서 읽으면 값은 그냥 prop 으로 내려간다.
  const assets = useBoothAssets();
  return (
    <Canvas
      className="r3f-canvas"
      shadows
      orthographic
      dpr={[1, 2]}
      camera={{ position: isoCameraPosition(), zoom: 60, near: 0.1, far: 200 }}
      // preserveDrawingBuffer: 합성 뒤에도 드로잉 버퍼를 남긴다. 없으면 canvas.toDataURL() 이
      // 빈 이미지를 돌려줘 QA 캡처가 조용히 백지가 된다(S15P21A604-480 에서 실제로 그랬다).
      gl={{ antialias: true, powerPreference: 'low-power', preserveDrawingBuffer: true }}
      // Unity WebGL 과 컨텍스트를 나눠 쓴다 — Studio 를 벗어나면 R3F 쪽은 dispose 되어야 한다.
      // frameloop='demand' 는 편집 조작이 없을 때 GPU 를 놀린다(공존 부담을 줄이는 값싼 수단).
      frameloop={IS_VISUAL_ACCEPTANCE ? 'never' : 'demand'}
    >
      <Suspense fallback={null}>
        {IS_VISUAL_ACCEPTANCE && <TimerDrivenFrames />}
        <Scene {...p} assets={assets} />
      </Suspense>
    </Canvas>
  );
}
