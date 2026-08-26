// 공개 요청 전에 서버와 같은 경고를 미리 보여주는 사전 검증 — 순수 함수, UX 보조일 뿐이다
// 서버 errors/warnings와 답이 갈리면 서버가 이긴다(헌법 16조) — 여기는 판정 원본이 아니라 미리보기다
// 출처: specs/005-booth-studio-layout/FE/tasks.md T015, data-model.md ObjectType 판정표

import type { LayoutObject, ValidationDetail } from '../../../entities/layout/types';
import { OBJECT_LOCAL_BOUNDS, OBJECT_TYPE_INFO } from '../../../entities/layout/objectTypes';
import { isAreaOutOfBounds, worldAABB } from '../../../entities/layout/geometry';
import { AREA_OUT_OF_BOUNDS_MESSAGE, CONFIG_NOT_LINKED_MESSAGE, objectLimitMessage } from '../../../entities/layout/messages';

// rule 이름은 서버 계약(contracts/layout-api.md)과 맞춰 사전 경고와 서버 응답을 같은 문구로 보이게 한다.
export function precheckWarnings(objects: LayoutObject[]): ValidationDetail[] {
  const details: ValidationDetail[] = [];

  for (const o of objects) {
    // 서버가 새 타입을 추가하면(#56 GAME_PORTAL 등) 이 맵에 없는 type이 올 수 있다 — 판정은 서버 몫이라
    // 여기서는 조용히 건너뛴다(SC-005: 미지 타입이 있어도 나머지는 정상 동작해야 한다, T026)
    const info = OBJECT_TYPE_INFO[o.type];
    if (info?.warnOnMissingConfig && o.configId === undefined) {
      details.push({
        rule: 'CONFIG_NOT_LINKED',
        objectId: o.objectId,
        message: CONFIG_NOT_LINKED_MESSAGE,
      });
    }
  }

  return details;
}

// objectId는 crypto.randomUUID()로 생성돼 실질적으로 충돌하지 않지만, 계약상 유일성 요건이라 방어적으로 둔다.
// boothBounds는 §10-2 실물(회전 반영 AABB) 이탈 판정에 쓴다 — 앵커 점은 안인데 회전한 몸체가
// 옆 슬롯에 걸치는 배치를 드래그 중에 미리 잡아준다(T022).
export function precheckErrors(
  objects: LayoutObject[],
  maxObjects: number,
  boothBounds: { width: number; depth: number; height: number },
): ValidationDetail[] {
  const details: ValidationDetail[] = [];

  if (objects.length > maxObjects) {
    details.push({
      rule: 'OBJECT_LIMIT',
      message: objectLimitMessage(maxObjects, objects.length),
    });
  }

  const seen = new Set<string>();
  for (const o of objects) {
    if (seen.has(o.objectId)) {
      details.push({ rule: 'DUPLICATE_OBJECT_ID', objectId: o.objectId, message: '오브젝트 식별자가 중복됐습니다.' });
    }
    seen.add(o.objectId);

    // 미지 타입은 실물 크기를 모르니 판정하지 않고 건너뛴다 — 서버 몫(SC-005, T026과 같은 원칙).
    const local = OBJECT_LOCAL_BOUNDS[o.type];
    if (local && isAreaOutOfBounds(worldAABB(local, o.rotationY, o.position), boothBounds)) {
      details.push({
        rule: 'AREA_OUT_OF_BOUNDS',
        objectId: o.objectId,
        message: AREA_OUT_OF_BOUNDS_MESSAGE,
      });
    }
  }

  return details;
}
