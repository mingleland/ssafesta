// Booth Template — 빈 부스에서 시작하지 않게 해 주는 초기 배치 recipe (S15P21A604-551).
//
// **새 3D 자산이 아니다.** 기존 assetCode 여러 개 + 각자의 위치·회전을 미리 적어 둔 것뿐이라,
// 적용 결과는 사용자가 손으로 놓은 것과 **완전히 같은 LayoutObject** 다. 전용 renderer 도
// 전용 저장 포맷도 만들지 않는다 — 만들면 "템플릿으로 만든 부스" 와 "직접 만든 부스" 가
// 갈라지고, 편집·검증·저장을 두 벌 유지하게 된다.
//
// 그래서 Template 은 편집을 제한하지 않는다. 적용 뒤에는 일반 편집기와 구분이 없다.
import type { LayoutObject, ObjectType } from '../../../entities/layout/types';

export interface TemplateObject {
  assetCode: string;
  objectType: ObjectType;
  /** 부스 로컬 좌표(m). y 는 계약상 항상 0 이라 두지 않는다 */
  x: number;
  z: number;
  rotationY: number;
}

export interface BoothTemplate {
  templateCode: string;
  name: string;
  description: string;
  /** 대표 자산의 assetCode — 썸네일을 manifest 에서 끌어오는 키다. 별도 이미지 자산을 만들지 않는다 */
  thumbnailAssetCode: string | null;
  objects: TemplateObject[];
}

/**
 * 실제 inventory 로만 구성한다 — **없는 자산으로 템플릿을 채우지 않는다.**
 *
 * 코드는 정본 §8 의 canonical 값이다. manifest 에 아직 그 코드가 없으면 `pickAsset` 이
 * 타입 기본으로 떨어뜨리므로 배치 자체는 성립하고, 자산이 들어오는 대로 그림이 채워진다.
 *
 * 좌표는 6×6 m 부스 기준이다. 정면은 +Z 이고 원점은 바닥 중앙이다(헌법 21조).
 */
export const BOOTH_TEMPLATES: BoothTemplate[] = [
  {
    templateCode: 'EMPTY',
    name: '빈 부스',
    description: '아무것도 놓지 않고 직접 꾸민다',
    thumbnailAssetCode: null,
    objects: [],
  },
  {
    templateCode: 'EXHIBIT_BASIC',
    name: '기본 전시형',
    description: '패널로 보여 주고 카운터에서 맞이한다',
    thumbnailAssetCode: 'BOOTH_PANEL_PROJECT',
    objects: [
      { assetCode: 'BOOTH_PANEL_PROJECT', objectType: 'PROJECT_PANEL', x: 0, z: -2.2, rotationY: 0 },
      { assetCode: 'FURN_COUNTER_01', objectType: 'FURNITURE', x: 0, z: 1.2, rotationY: 180 },
      { assetCode: 'FURN_CHAIR_01_WHITE', objectType: 'FURNITURE', x: 1.2, z: 1.2, rotationY: 180 },
      { assetCode: 'DISP_SET_BOX_01', objectType: 'DECORATION', x: -2, z: -0.5, rotationY: 0 },
    ],
  },
  {
    templateCode: 'CONSULT',
    name: '상담형',
    description: '마주 앉아 이야기하는 배치',
    thumbnailAssetCode: 'BOOTH_DESK_CONSULT',
    objects: [
      { assetCode: 'BOOTH_DESK_CONSULT', objectType: 'CONSULTATION_DESK', x: 0, z: 0, rotationY: 0 },
      { assetCode: 'FURN_CHAIR_02_WHITE', objectType: 'FURNITURE', x: 0, z: 1.4, rotationY: 180 },
      { assetCode: 'STRUCT_PANEL_02', objectType: 'DECORATION', x: 0, z: -2.4, rotationY: 0 },
    ],
  },
  {
    templateCode: 'PROMO_VIDEO',
    name: '영상 홍보형',
    description: '큰 화면을 중심에 두고 사람을 모은다',
    thumbnailAssetCode: 'BOOTH_SCREEN_VIDEO',
    objects: [
      { assetCode: 'BOOTH_SCREEN_VIDEO', objectType: 'VIDEO_SCREEN', x: 0, z: -2.3, rotationY: 0 },
      { assetCode: 'FURN_COUNTER_02', objectType: 'FURNITURE', x: -1.6, z: 0.8, rotationY: 135 },
      { assetCode: 'DISP_SET_BOX_01', objectType: 'DECORATION', x: 2, z: 0.5, rotationY: 0 },
    ],
  },
];

export function findTemplate(templateCode: string): BoothTemplate | undefined {
  return BOOTH_TEMPLATES.find((t) => t.templateCode === templateCode);
}

/**
 * Template → LayoutObject 들. **여기서 나온 값은 일반 편집기 산출물과 구분이 없다.**
 *
 * `objectId` 는 편집기 관행대로 `crypto.randomUUID()` 다(계약 R-02). 호출부가 주입할 수
 * 있게 열어 둔 것은 테스트가 값을 고정하기 위해서다 — 무작위가 섞이면 배치를 단언할 수 없다.
 */
export function instantiateTemplate(
  template: BoothTemplate,
  newId: () => string = () => crypto.randomUUID(),
): LayoutObject[] {
  return template.objects.map((o) => ({
    objectId: newId(),
    type: o.objectType,
    position: { x: o.x, y: 0, z: o.z },
    rotationY: o.rotationY,
    assetCode: o.assetCode,
  }));
}
