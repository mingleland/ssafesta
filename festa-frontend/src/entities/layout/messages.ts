// rule별 한글 문구 정본 — FE 사전 검증(validate.ts)과 mock 서버(api.mock.ts)가 같은 rule에
// 각자 문구를 들고 있으면 §6b가 요구하는 "FE와 서버가 같은 답"의 마지막 비공유 지점이 된다.
// 출처: specs/005-booth-studio-layout/contracts/layout-api.md §10 rule 표

export const AREA_OUT_OF_BOUNDS_MESSAGE = '회전한 실물이 부스 영역을 벗어났습니다.';
export const CONFIG_NOT_LINKED_MESSAGE = '연결된 콘텐츠가 없습니다.';

export function objectLimitMessage(maxObjects: number, current: number): string {
  return `오브젝트는 ${maxObjects}개까지입니다. (현재 ${current}개)`;
}
