// 내 부스 관리창의 "AI 전시 에셋 관리" 섹션을 그릴지만 정한다. 기능 자체는 비활성(잠금)이라
// ENABLED 가 아니라 SHOW 다. 기능이 아예 도입되지 않으면 이 파일과 AiAssetSection.tsx 를 지운다.
export const SHOW_AI_ASSET_SECTION = true;

// 게시본에 authored AI 직원 바인딩(AI_AGENT + configId)을 싣는가 (S15P21A604-898, GitLab #244).
// ON — Unity 가 저작 인테리어(@BoothInteriors)에서는 authored 4종을 스폰하지 않고 binder 에만 넘기도록
// 고쳐졌다(S15P21A604-900, develop 6394658b). 이 값이 true 가 되는 순간 AI 직원이 있는 부스는 기존
// 게시본과 presentation 이 어긋나 관리창에 "변경사항 적용" 이 뜬다 — 그것이 의도한 전이다.
// OFF 로 되돌리면 composer 가 게시본의 AI_AGENT 를 그대로 이어받아 지우지 않는다(presentationComposer).
export const PUBLISH_AI_AGENT_BINDING = true;
