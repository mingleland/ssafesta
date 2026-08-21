// 공개 요청 전에 서버와 같은 경고를 미리 보여주는 사전 검증 — 순수 함수, UX 보조일 뿐이다
// 서버 errors/warnings와 답이 갈리면 서버가 이긴다(헌법 16조) — 여기는 판정 원본이 아니라 미리보기다
// 출처: specs/005-booth-studio-layout/FE/tasks.md T015, data-model.md ObjectType 판정표

import type { LayoutObject, ValidationDetail } from '../../../entities/layout/types';
import { OBJECT_TYPE_INFO } from '../../../entities/layout/objectTypes';

// rule 이름은 서버 계약(contracts/layout-api.md)과 맞춰 사전 경고와 서버 응답을 같은 문구로 보이게 한다.
export function precheckWarnings(objects: LayoutObject[]): ValidationDetail[] {
  const details: ValidationDetail[] = [];

  for (const o of objects) {
    const info = OBJECT_TYPE_INFO[o.type];
    if (info.warnOnMissingConfig && o.configId === undefined) {
      details.push({
        rule: 'CONFIG_NOT_LINKED',
        objectId: o.objectId,
        message: '연결된 콘텐츠가 없습니다.',
      });
    }
  }

  return details;
}

// objectId는 crypto.randomUUID()로 생성돼 실질적으로 충돌하지 않지만, 계약상 유일성 요건이라 방어적으로 둔다.
export function precheckErrors(objects: LayoutObject[], maxObjects: number): ValidationDetail[] {
  const details: ValidationDetail[] = [];

  if (objects.length > maxObjects) {
    details.push({
      rule: 'OBJECT_LIMIT',
      message: `오브젝트는 ${maxObjects}개까지입니다. (현재 ${objects.length}개)`,
    });
  }

  const seen = new Set<string>();
  for (const o of objects) {
    if (seen.has(o.objectId)) {
      details.push({ rule: 'DUPLICATE_OBJECT_ID', objectId: o.objectId, message: '오브젝트 식별자가 중복됐습니다.' });
    }
    seen.add(o.objectId);
  }

  return details;
}
