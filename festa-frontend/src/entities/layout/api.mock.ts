// 로컬 개발용 메모리 mock — 실 BE 시맨틱(revision 충돌·미지 필드 거부·12개 상한·publish 경고)을 재현한다.
// VITE_USE_MOCK=true일 때 api.ts 대신 이 모듈을 쓴다(엔트리 조립은 features/studio에서 분기).
// 출처: specs/005-booth-studio-layout/FE/research.md R-09, contracts/layout-api.md

import type { ApiError, ApiErrorDetail } from '../../shared/api/client';
import { OBJECT_TYPE_INFO, OBJECT_TYPES } from './objectTypes';
import type {
  DraftGetResponse,
  DraftPutRequest,
  DraftPutResponse,
  LayoutObject,
  PublishResponse,
  PublishedLayout,
  TemplateCatalog,
} from './types';

const KNOWN_DRAFT_FIELDS = new Set(['expectedRevision', 'schemaVersion', 'template', 'objects']);
const KNOWN_OBJECT_FIELDS = new Set(['objectId', 'type', 'position', 'rotationY', 'configId', 'assetCode']);
const MAX_OBJECTS = 12;

function apiError(code: string, message: string, errors: ApiErrorDetail[] = []): ApiError {
  return { code, message, requestId: `mock_${Date.now()}`, errors, warnings: [] };
}

interface StoredDraft {
  revision: number;
  schemaVersion: number;
  template: string;
  objects: LayoutObject[];
  updatedAt: string;
  updatedByUserId: number;
  publishedVersion: number | null;
}

// sessionStorage로 백업 — 순수 메모리면 브라우저 새로고침마다 초기화돼 "새로고침 후 복원"(SC-001)을
// mock으로 증명할 수 없다. 탭을 닫으면 사라지는 정도가 딱 맞는 휘발성이라 sessionStorage를 쓴다.
const STORAGE_KEY = 'festa-mock-layout-drafts';

function loadDrafts(): Map<number, StoredDraft> {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return new Map();
    return new Map(JSON.parse(raw) as Array<[number, StoredDraft]>);
  } catch {
    return new Map();
  }
}

function persistDrafts(): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(Array.from(drafts.entries())));
  } catch {
    // sessionStorage 미가용(예: 프라이빗 모드 제한) — mock은 이번 세션만 메모리로 동작
  }
}

const drafts = loadDrafts();

export async function getDraft(boothId: number): Promise<DraftGetResponse | null> {
  const stored = drafts.get(boothId);
  if (!stored) return null;
  const { revision, schemaVersion, template, objects, updatedAt, updatedByUserId, publishedVersion } = stored;
  return { boothId, revision, schemaVersion, template, objects, updatedAt, updatedByUserId, publishedVersion };
}

// 외부 시그니처는 api.ts와 동일(DraftPutRequest)해 소비부가 env로 real/mock을 그냥 바꿔 끼울 수 있다.
// "계약에 없는 필드" 검사는 JS 런타임에선 타입이 지워지므로, 원시 키 목록을 그대로 조회해
// quickstart §7(devtools로 계약 외 필드를 끼워 보내면 거부되는지 확인)을 재현한다.
export async function putDraft(boothId: number, body: DraftPutRequest): Promise<DraftPutResponse> {
  const rawTopKeys = Object.keys(body as unknown as Record<string, unknown>);
  const unknownTop = rawTopKeys.filter((k) => !KNOWN_DRAFT_FIELDS.has(k));
  if (unknownTop.length > 0) {
    throw apiError('LAYOUT_VALIDATION_FAILED', '계약에 없는 필드가 포함되어 있습니다.', [
      { rule: 'MALFORMED_LAYOUT', message: `알 수 없는 필드: ${unknownTop.join(', ')}` },
    ]);
  }

  const req = body;
  for (const obj of req.objects) {
    const unknownObjKeys = Object.keys(obj as unknown as Record<string, unknown>).filter(
      (k) => !KNOWN_OBJECT_FIELDS.has(k),
    );
    if (unknownObjKeys.length > 0) {
      throw apiError('LAYOUT_VALIDATION_FAILED', '오브젝트에 계약에 없는 필드가 있습니다.', [
        { rule: 'MALFORMED_LAYOUT', objectId: obj.objectId, message: `알 수 없는 필드: ${unknownObjKeys.join(', ')}` },
      ]);
    }
    if (!OBJECT_TYPES.includes(obj.type)) {
      throw apiError('LAYOUT_VALIDATION_FAILED', '알 수 없는 오브젝트 타입입니다.', [
        { rule: 'UNKNOWN_OBJECT_TYPE', objectId: obj.objectId, message: `알 수 없는 타입: ${obj.type}` },
      ]);
    }
  }

  if (req.objects.length > MAX_OBJECTS) {
    throw apiError('LAYOUT_VALIDATION_FAILED', `오브젝트는 ${MAX_OBJECTS}개까지입니다. (현재 ${req.objects.length}개)`, [
      { rule: 'OBJECT_LIMIT', message: `오브젝트는 ${MAX_OBJECTS}개까지입니다. (현재 ${req.objects.length}개)` },
    ]);
  }

  const existing = drafts.get(boothId);
  const currentRevision = existing?.revision ?? 0;
  if (req.expectedRevision !== currentRevision) {
    throw apiError(
      'LAYOUT_REVISION_CONFLICT',
      '다른 편집자가 먼저 저장했습니다.',
      [{ rule: 'CURRENT_REVISION', message: `서버의 현재 revision은 ${currentRevision}입니다. 다시 불러온 뒤 저장하세요.` }],
    );
  }

  const nextRevision = currentRevision + 1;
  const updatedAt = new Date().toISOString();
  const warnings: ApiErrorDetail[] = req.objects
    .filter((o) => OBJECT_TYPE_INFO[o.type].warnOnMissingConfig && o.configId == null)
    .map((o) => ({ rule: 'CONFIG_NOT_LINKED', objectId: o.objectId, message: '연결된 콘텐츠가 없습니다.' }));

  drafts.set(boothId, {
    revision: nextRevision,
    schemaVersion: req.schemaVersion,
    template: req.template,
    objects: req.objects,
    updatedAt,
    updatedByUserId: 1,
    publishedVersion: existing?.publishedVersion ?? null,
  });
  persistDrafts();

  return {
    boothId,
    revision: nextRevision,
    schemaVersion: req.schemaVersion,
    template: req.template,
    objects: req.objects,
    updatedAt,
    warnings,
  };
}

export async function publish(boothId: number): Promise<PublishResponse> {
  const stored = drafts.get(boothId);
  if (!stored) {
    throw apiError('BOOTH_NOT_FOUND', '작업본이 없습니다.');
  }
  const warnings: ApiErrorDetail[] = stored.objects
    .filter((o) => OBJECT_TYPE_INFO[o.type].warnOnMissingConfig && o.configId == null)
    .map((o) => ({ rule: 'CONFIG_NOT_LINKED', objectId: o.objectId, message: '연결된 콘텐츠가 없습니다.' }));

  const nextPublished = (stored.publishedVersion ?? 0) + 1;
  stored.publishedVersion = nextPublished;
  persistDrafts();
  return { boothId, publishedVersion: nextPublished, publishedAt: new Date().toISOString(), warnings };
}

export async function getPublished(boothId: number): Promise<PublishedLayout> {
  const stored = drafts.get(boothId);
  if (!stored || stored.publishedVersion == null) {
    throw apiError('LAYOUT_NOT_PUBLISHED', '공개된 배치가 없습니다.');
  }
  return {
    boothId,
    version: stored.publishedVersion,
    schemaVersion: stored.schemaVersion,
    template: stored.template,
    objects: stored.objects,
  };
}

export async function getTemplates(): Promise<TemplateCatalog> {
  return {
    templates: [
      { template: 'PROJECT_EXHIBITION', footprint: { width: 6, depth: 6, height: 2.72 }, maxObjects: MAX_OBJECTS },
    ],
  };
}
