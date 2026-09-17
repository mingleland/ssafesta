// 내 부스 관리창의 "AI 전시 에셋 관리" 섹션을 그릴지만 정한다. 기능 자체는 비활성(잠금)이라
// ENABLED 가 아니라 SHOW 다. 기능이 아예 도입되지 않으면 이 파일과 AiAssetSection.tsx 를 지운다.
export const SHOW_AI_ASSET_SECTION = true;

// 게시본에 authored AI 직원 바인딩(AI_AGENT + configId)을 싣는가 (S15P21A604-898, GitLab #244).
// 기본 OFF — 지금 Unity 는 게시본의 AI_AGENT 를 씬의 authored NPC 에 바인드하는 동시에 Factory 로
// 한 번 더 스폰해 NPC 가 둘이 된다. 게임 파트가 authored 4종 스폰을 걷어낸 것이 develop 에 닿으면
// 이 값을 true 로 바꾼다. 그 순간 기존 게시본과 presentation 이 어긋나 관리창에 "변경사항 적용" 이 뜬다.
export const PUBLISH_AI_AGENT_BINDING = false;
