// 겹침 판정의 단일 지점 (S15P21A604-607).
//
// 두 렌더러(R3F·SVG)와 사전 검증이 같은 답을 내야 한다 — 캔버스는 빨갛게 칠하는데 경고 목록에는
// 없거나, 그 반대가 되면 사용자는 무엇을 믿어야 할지 모른다.
//
// 미지 타입은 건너뛴다. 실물 크기를 모르면 겹침도 판정할 수 없고, 그 판정은 서버 몫이다
// (SC-005 — 미지 타입이 있어도 나머지는 정상 동작해야 한다).
import { resolveLocalBounds } from '../model/useBoothAssets';
import { overlappingIds, worldAABB } from '../../../entities/layout/geometry';
import type { LayoutObject } from '../../../entities/layout/types';

export function overlappingObjectIds(objects: ReadonlyArray<LayoutObject>): Set<string> {
  const items = objects.flatMap((o) => {
    const local = resolveLocalBounds(o);
    if (local === undefined) return [];
    return [{ objectId: o.objectId, area: worldAABB(local, o.rotationY, o.position) }];
  });
  return overlappingIds(items);
}
