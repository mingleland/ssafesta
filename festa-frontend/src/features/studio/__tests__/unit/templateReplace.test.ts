// 템플릿 적용은 **교체**다 — 기존 배치 위에 쌓이지 않는다 (감사 항목 4).
//
// 예전 경로는 ADD_OBJECT 반복이라 같은 템플릿을 두 번 누르면 오브젝트가 두 벌이 됐고,
// 상한에 걸리면 조용히 중간에서 끊겼다. 여기서 고정하는 것은 그 두 성질이다:
//   ① 두 번 적용해도 결과는 한 번 적용한 것과 같다(멱등)
//   ② 빈 템플릿은 실제로 배치를 비운다 — 예전엔 objects: [] 라 아무 일도 일어나지 않았다
import { describe, expect, it } from 'vitest';
import { createInitialState, editorReducer } from '../../model/editorReducer';
import { BOOTH_TEMPLATES, findTemplate, instantiateTemplate } from '../../model/boothTemplates';

const BOOTH_ID = 7;

// objectId 는 crypto.randomUUID 라 값을 단언할 수 없다 — 배치의 뜻(무엇이 어디에)만 비교한다
function shape(objects: ReadonlyArray<{ type: string; position: { x: number; z: number }; assetCode?: string }>) {
  return objects.map((o) => `${o.type}@${o.position.x},${o.position.z}:${o.assetCode ?? '-'}`);
}

function applyTemplate(state: ReturnType<typeof createInitialState>, templateCode: string) {
  const template = findTemplate(templateCode);
  if (template === undefined) throw new Error(`템플릿 없음: ${templateCode}`);
  return editorReducer(state, { type: 'REPLACE_OBJECTS', objects: instantiateTemplate(template) });
}

describe('템플릿 적용 — 누적이 아니라 교체', () => {
  it('두 번 적용해도 한 번 적용한 것과 같은 배치다', () => {
    const once = applyTemplate(createInitialState(BOOTH_ID), 'EXHIBIT_BASIC');
    const twice = applyTemplate(once, 'EXHIBIT_BASIC');

    expect(shape(twice.objects)).toEqual(shape(once.objects));
    expect(twice.objects.length).toBe(findTemplate('EXHIBIT_BASIC')!.objects.length);
  });

  it('다른 템플릿으로 바꾸면 앞의 배치는 남지 않는다', () => {
    const basic = applyTemplate(createInitialState(BOOTH_ID), 'EXHIBIT_BASIC');
    const consult = applyTemplate(basic, 'CONSULT');

    expect(shape(consult.objects)).toEqual(shape(instantiateTemplate(findTemplate('CONSULT')!)));
  });

  it("'빈 부스'는 실제로 배치를 비운다", () => {
    const basic = applyTemplate(createInitialState(BOOTH_ID), 'EXHIBIT_BASIC');
    expect(basic.objects.length).toBeGreaterThan(0);

    const emptied = applyTemplate(basic, 'EMPTY');
    expect(emptied.objects).toEqual([]);
    expect(emptied.selectedObjectId).toBeNull();
    // 비우는 것도 편집이다 — 저장할 것이 생겼다고 알려야 한다
    expect(emptied.dirty).toBe(true);
    expect(emptied.saveStatus).toBe('dirty');
  });

  it('교체 뒤 선택은 새 배치 안에 있다 — Inspector 가 사라진 오브젝트를 가리키지 않는다', () => {
    const state = applyTemplate(createInitialState(BOOTH_ID), 'PROMO_VIDEO');
    expect(state.selectedObjectId).not.toBeNull();
    expect(state.objects.some((o) => o.objectId === state.selectedObjectId)).toBe(true);
  });

  it('저장소의 모든 템플릿이 교체 경로를 통과한다', () => {
    for (const template of BOOTH_TEMPLATES) {
      const state = applyTemplate(createInitialState(BOOTH_ID), template.templateCode);
      expect(state.objects.length).toBe(template.objects.length);
    }
  });
});
