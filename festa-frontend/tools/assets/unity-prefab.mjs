// Unity prefab 계층을 three 씬으로 재현한다 (S15P21A604-476).
//
// 목적은 하나다 — SurveyKiosk 처럼 여러 prefab 이 겹쳐 만들어진 오브젝트를 런타임이
// prefab 을 몰라도 되게 GLB 하나로 굽는 것. 그래서 여기서 계층을 다 풀어 헤친다.
//
// 지원 범위는 **정적 구조뿐**이다: GameObject/Transform 트리, MeshFilter 의 FBX 참조,
// child prefab 인스턴스의 local position/rotation/scale. MonoBehaviour·Animator·Particle·
// Variant override 는 지원하지 않는다. 만나면 조용히 넘기지 않고 경고한다 — 조용한 누락은
// "모델이 왜 이렇게 생겼지" 로만 남고 원인을 못 찾는다.
//
// Unity YAML 은 일반 YAML 파서로 읽기 어렵다(`!u!` 태그, 중복 키, 문서마다 다른 스키마).
// 필요한 필드가 열 개 남짓이라 문서 단위로 잘라 정규식으로 꺼낸다.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import * as THREE from 'three';

const DOC_RE = /^--- !u!(\d+) &(\d+)(?: stripped)?$/gm;

/** classId — Unity 가 정한 숫자다 */
const CLASS = { GAME_OBJECT: 1, TRANSFORM: 4, MESH_FILTER: 33, PREFAB: 1001 };

/** 지원하지 않는 컴포넌트 — 만나면 경고만 남기고 지나간다 */
const UNSUPPORTED = new Map([
  [114, 'MonoBehaviour'],
  [95, 'Animator'],
  [111, 'Animation'],
  [198, 'ParticleSystem'],
  [199, 'ParticleSystemRenderer'],
  [212, 'SpriteRenderer'],
]);

/** Assets 트리 전체를 훑어 guid → 파일 경로를 만든다. .meta 하나에 guid 하나다 */
export function buildGuidIndex(assetsRoot) {
  const index = new Map();
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name);
      const st = statSync(full);
      if (st.isDirectory()) {
        walk(full);
        continue;
      }
      if (!name.endsWith('.meta')) continue;
      const m = /^guid: ([0-9a-f]{32})$/m.exec(readFileSync(full, 'utf8'));
      if (m !== null) index.set(m[1], full.slice(0, -'.meta'.length));
    }
  };
  walk(assetsRoot);
  return index;
}

/** 문서 단위로 자른다 — { fileID: { classId, body } } */
function splitDocuments(text) {
  const docs = new Map();
  const marks = [];
  DOC_RE.lastIndex = 0;
  let m;
  while ((m = DOC_RE.exec(text)) !== null) {
    marks.push({ classId: Number(m[1]), fileID: m[2], start: m.index + m[0].length });
  }
  marks.forEach((mark, i) => {
    const end = i + 1 < marks.length ? text.lastIndexOf('\n--- ', marks[i + 1].start) : text.length;
    docs.set(mark.fileID, { classId: mark.classId, body: text.slice(mark.start, end) });
  });
  return docs;
}

const vec3 = (body, key, fallback) => {
  const m = new RegExp(`${key}: \\{x: ([-\\d.eE+]+), y: ([-\\d.eE+]+), z: ([-\\d.eE+]+)\\}`).exec(body);
  return m === null ? fallback : { x: Number(m[1]), y: Number(m[2]), z: Number(m[3]) };
};
const quat = (body, key) => {
  const m = new RegExp(`${key}: \\{x: ([-\\d.eE+]+), y: ([-\\d.eE+]+), z: ([-\\d.eE+]+), w: ([-\\d.eE+]+)\\}`).exec(body);
  return m === null ? { x: 0, y: 0, z: 0, w: 1 } : { x: Number(m[1]), y: Number(m[2]), z: Number(m[3]), w: Number(m[4]) };
};
const ref = (body, key) => {
  const m = new RegExp(`${key}: \\{fileID: (\\d+)(?:, guid: ([0-9a-f]{32}))?`).exec(body);
  return m === null ? null : { fileID: m[1], guid: m[2] ?? null };
};
const scalar = (body, key) => {
  const m = new RegExp(`^\\s*${key}: (.*)$`, 'm').exec(body);
  return m === null ? null : m[1].trim();
};
const fileIdList = (body, key) => {
  const m = new RegExp(`${key}:\\n((?:\\s*- \\{fileID: \\d+\\}\\n)*)`).exec(body);
  if (m === null) return [];
  return [...m[1].matchAll(/fileID: (\d+)/g)].map((x) => x[1]);
};

/** PrefabInstance 의 m_Modifications 에서 인스턴스 루트의 transform 을 꺼낸다 */
function instanceTransform(body) {
  const get = (path, fallback) => {
    const m = new RegExp(`propertyPath: ${path}\\n\\s*value: ([-\\d.eE+]+)`).exec(body);
    return m === null ? fallback : Number(m[1]);
  };
  return {
    position: { x: get('m_LocalPosition\\.x', 0), y: get('m_LocalPosition\\.y', 0), z: get('m_LocalPosition\\.z', 0) },
    rotation: {
      x: get('m_LocalRotation\\.x', 0),
      y: get('m_LocalRotation\\.y', 0),
      z: get('m_LocalRotation\\.z', 0),
      w: get('m_LocalRotation\\.w', 1),
    },
    scale: { x: get('m_LocalScale\\.x', 1), y: get('m_LocalScale\\.y', 1), z: get('m_LocalScale\\.z', 1) },
    name: (/propertyPath: m_Name\n\s*value: (.+)/.exec(body) ?? [undefined, null])[1],
  };
}

function applyTransform(object3d, t) {
  object3d.position.set(t.position.x, t.position.y, t.position.z);
  object3d.quaternion.set(t.rotation.x, t.rotation.y, t.rotation.z, t.rotation.w);
  object3d.scale.set(t.scale.x, t.scale.y, t.scale.z);
}

/**
 * prefab 하나를 three 그룹으로 만든다. 자식 PrefabInstance 가 있으면 그 원본까지 재귀로 따라간다.
 *
 * @param {string} prefabPath 절대 경로
 * @param {{guidIndex: Map<string,string>, unitScale: number, loadFbx: (p:string)=>THREE.Object3D, warn: (m:string)=>void, depth?: number}} ctx
 */
export function loadPrefab(prefabPath, ctx) {
  const depth = ctx.depth ?? 0;
  if (depth > 8) throw new Error(`prefab 중첩이 너무 깊다 — 순환일 수 있다: ${prefabPath}`);
  const text = readFileSync(prefabPath, 'utf8');
  const docs = splitDocuments(text);
  const label = prefabPath.split('/').pop();

  // GameObject fileID → Transform fileID (역방향이 필요하다 — Transform 이 GameObject 를 가리킨다)
  const transformOfGameObject = new Map();
  for (const [fileID, doc] of docs) {
    if (doc.classId !== CLASS.TRANSFORM) continue;
    const go = ref(doc.body, 'm_GameObject');
    if (go !== null) transformOfGameObject.set(go.fileID, fileID);
  }

  const buildFromTransform = (transformID) => {
    const doc = docs.get(transformID);
    if (doc === undefined) return null;
    const node = new THREE.Group();
    applyTransform(node, {
      position: vec3(doc.body, 'm_LocalPosition', { x: 0, y: 0, z: 0 }),
      rotation: quat(doc.body, 'm_LocalRotation'),
      scale: vec3(doc.body, 'm_LocalScale', { x: 1, y: 1, z: 1 }),
    });

    const goRef = ref(doc.body, 'm_GameObject');
    const go = goRef === null ? undefined : docs.get(goRef.fileID);
    if (go !== undefined) {
      node.name = scalar(go.body, 'm_Name') ?? '';
      for (const componentID of fileIdList(go.body, 'm_Component').concat(
        [...go.body.matchAll(/component: \{fileID: (\d+)\}/g)].map((x) => x[1]),
      )) {
        const component = docs.get(componentID);
        if (component === undefined) continue;
        if (UNSUPPORTED.has(component.classId)) {
          ctx.warn(`${label}: ${UNSUPPORTED.get(component.classId)} 는 재현하지 않는다 (${node.name})`);
          continue;
        }
        if (component.classId !== CLASS.MESH_FILTER) continue;
        const mesh = ref(component.body, 'm_Mesh');
        if (mesh === null || mesh.guid === null) continue;
        const fbxPath = ctx.guidIndex.get(mesh.guid);
        if (fbxPath === undefined) {
          ctx.warn(`${label}: guid ${mesh.guid} 를 못 찾았다 (${node.name})`);
          continue;
        }
        const model = ctx.loadFbx(fbxPath);
        // Unity 는 import 때 파일 단위 스케일을 메시에 굽는다. prefab transform 은 그 위에서 미터로 논다
        model.scale.multiplyScalar(ctx.unitScale);
        node.add(model);
      }
    }

    for (const childID of fileIdList(doc.body, 'm_Children')) {
      const child = buildFromTransform(childID);
      if (child !== null) node.add(child);
    }
    return node;
  };

  // 루트 찾기 — 구형(Prefab + m_RootGameObject)과 신형(m_Father 가 0 인 Transform) 둘 다 있다
  let rootTransformID = null;
  for (const [, doc] of docs) {
    if (doc.classId !== CLASS.PREFAB) continue;
    const rootGo = ref(doc.body, 'm_RootGameObject');
    if (rootGo !== null && rootGo.fileID !== '0') rootTransformID = transformOfGameObject.get(rootGo.fileID) ?? null;
  }
  if (rootTransformID === null) {
    for (const [fileID, doc] of docs) {
      if (doc.classId !== CLASS.TRANSFORM) continue;
      const father = ref(doc.body, 'm_Father');
      if (father === null || father.fileID === '0') {
        rootTransformID = fileID;
        break;
      }
    }
  }
  if (rootTransformID === null) throw new Error(`루트 Transform 을 찾지 못했다: ${prefabPath}`);

  const root = buildFromTransform(rootTransformID);
  if (root === null) throw new Error(`루트를 만들지 못했다: ${prefabPath}`);

  // 자식 PrefabInstance — 원본 prefab 을 따라가 통째로 붙인다
  for (const [, doc] of docs) {
    if (doc.classId !== CLASS.PREFAB) continue;
    const source = ref(doc.body, 'm_SourcePrefab');
    if (source === null || source.guid === null) continue; // 자기 자신을 가리키는 구형 Prefab 문서
    const sourcePath = ctx.guidIndex.get(source.guid);
    if (sourcePath === undefined) {
      ctx.warn(`${label}: child prefab guid ${source.guid} 를 못 찾았다`);
      continue;
    }
    const child = loadPrefab(resolve(dirname(prefabPath), sourcePath), { ...ctx, depth: depth + 1 });
    // m_Modifications 는 원본 루트의 Transform 을 **덮어쓴다**. 겹쳐 곱하는 것이 아니다 —
    // 감싸는 그룹에 넣고 원본 회전을 그대로 두면 -90°가 두 번 걸려 모델이 눕는다.
    // (처음에 그렇게 만들었다가 키오스크가 높이 1.21m·깊이 0.94m 로 나와 드러났다)
    const t = instanceTransform(doc.body);
    applyTransform(child, t);
    if (t.name !== null) child.name = t.name;
    root.add(child);
  }

  root.updateMatrixWorld(true);
  return root;
}
