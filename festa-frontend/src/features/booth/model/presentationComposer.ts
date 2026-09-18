// 부스 presentation 의 정본과 layout 계약 사이의 순수 변환 (S15P21A604-898).
//
// 관리창은 layout JSON 을 모른다. 관리창이 아는 것은 "이 부스가 방문자에게 무엇을 보여 주는가" —
// 그것이 Presentation 이다. 이 파일이 Presentation ↔ LayoutDocument 를 오가고, "게시본이 지금
// 보여 줘야 할 것과 같은가" 를 layout 필드가 아니라 Presentation 으로 비교한다. objectId·좌표·회전은
// 비교 대상이 아니다 — Unity 부스는 슬롯별 고정 배치라 그 값들이 화면을 바꾸지 않는다.
//
// 지금 Presentation 은 Unity 기본 부스 + (플래그 ON 일 때) authored AI 직원 바인딩이 전부다.
// 향후 AI 생성 에셋은 여기 Presentation 필드와 compose/toLayout/fromLayout 세 곳에만 더한다 —
// 관리창·게시 mutation 은 손대지 않는다.
import type { AiAgent } from '../../../entities/aiAgent/api';
import type { LayoutDocument, LayoutObject } from '../../../entities/booth/layoutApi';
import { PUBLISH_AI_AGENT_BINDING } from './boothManagementFlags';

/** 서버 LayoutValidator.SUPPORTED_SCHEMA_VERSION · LayoutTemplate 유일값 */
export const LAYOUT_SCHEMA_VERSION = 1;
export const LAYOUT_TEMPLATE = 'PROJECT_EXHIBITION';

/** 씬에 미리 놓인 4종 — Unity AuthoredBoothContentBinder 가 게시본에서 configId 만 읽는 타입 */
const AUTHORED_TYPES = new Set(['AI_AGENT', 'LAPTOP', 'SURVEY_KIOSK', 'PROJECT_PANEL']);

/** objectId 는 안정적이어야 diff 가 결정적이다 — Unity binder 의 fallback 이름과 맞췄다 */
const AI_AGENT_OBJECT_ID = 'authored-ai-agent';

export interface Presentation {
  template: typeof LAYOUT_TEMPLATE;
  /** authored AI 직원 NPC 에 바인드할 agentId. null = 바인드 없음(직원 "준비 중") */
  aiAgentId: number | null;
}

export interface ComposeInputs {
  aiAgent: AiAgent | null;
  /** 지금 방문자에게 보이는 게시본. 미게시면 null */
  published: PublishedPresentation | null;
}

/**
 * 지금 이 부스가 방문자에게 보여 줘야 할 것.
 *
 * 플래그 OFF 는 "FE 가 AI 바인딩을 관리하지 않는다" 는 뜻이지 "없앤다" 가 아니다 — Studio 시절
 * 게시본에 이미 실린 AI_AGENT 는 그대로 이어받는다. 그래야 그것 하나 때문에 "변경사항 적용" 이
 * 뜨지 않고, 다른 이유로 적용해도 직원 바인딩이 지워지지 않는다. 플래그 ON 이면 현재 등록된
 * 직원이 정본이다.
 */
export function compose(inputs: ComposeInputs): Presentation {
  const aiAgentId = PUBLISH_AI_AGENT_BINDING
    ? (inputs.aiAgent?.agentId ?? null)
    : inputs.published
      ? publishedAiAgentId(inputs.published)
      : null;
  return {
    template: LAYOUT_TEMPLATE,
    aiAgentId,
  };
}

/** 게시할 문서. 빈 objects 는 validator 통과·authored 인테리어 유지가 확인된 기준선이다 */
export function toLayout(presentation: Presentation): LayoutDocument {
  const objects: LayoutObject[] = [];
  if (presentation.aiAgentId !== null) {
    objects.push({
      objectId: AI_AGENT_OBJECT_ID,
      type: 'AI_AGENT',
      position: { x: 0, y: 0, z: 0 },
      rotationY: 0,
      configId: presentation.aiAgentId,
    });
  }
  return { schemaVersion: LAYOUT_SCHEMA_VERSION, template: presentation.template, objects };
}

export type PublishedPresentation =
  | { kind: 'presentation'; presentation: Presentation }
  /** Studio 시절 가구 등 지금 Presentation 이 표현하지 못하는 오브젝트가 남아 있다 — 적용하면 기준선으로 덮인다.
   *  AI 바인딩은 legacy 에서도 읽어 둔다 — 덮을 때 함께 지워지면 안 된다 */
  | { kind: 'legacy'; aiAgentId: number | null };

/** 게시본을 Presentation 으로 읽는다. 위치·회전·objectId 는 버리고 의미만 남긴다 */
export function fromLayout(layout: LayoutDocument): PublishedPresentation {
  let aiAgentId: number | null = null;
  let legacy = layout.template !== LAYOUT_TEMPLATE;
  for (const object of layout.objects) {
    if (!AUTHORED_TYPES.has(object.type)) legacy = true;
    if (object.type === 'AI_AGENT') {
      // 둘 이상이면 어느 것이 바인드될지 Unity 가 첫 것을 집는다 — 그 모양은 이 composer 가 만들지 않으므로 legacy
      if (aiAgentId !== null) legacy = true;
      else aiAgentId = object.configId ?? null;
    }
  }
  if (legacy) return { kind: 'legacy', aiAgentId };
  return { kind: 'presentation', presentation: { template: LAYOUT_TEMPLATE, aiAgentId } };
}

export function equals(a: Presentation, b: Presentation): boolean {
  return a.template === b.template && a.aiAgentId === b.aiAgentId;
}

/** 게시본이 지금 compose 결과를 이미 담고 있는가. legacy 는 항상 "다르다" */
export function isUpToDate(published: PublishedPresentation, current: Presentation): boolean {
  return published.kind === 'presentation' && equals(published.presentation, current);
}

/** compose 입력용 — legacy 든 아니든 게시본이 들고 있는 AI 바인딩 */
export function publishedAiAgentId(published: PublishedPresentation): number | null {
  return published.kind === 'presentation' ? published.presentation.aiAgentId : published.aiAgentId;
}
