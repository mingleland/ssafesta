// AI Chat 오류 코드 중 **FE 가 만드는 것** — 서버 계약 코드가 아니라 FE 판정의 산물이다.
//
// api.ts(던지는 쪽)와 errorMessages.ts(읽는 쪽)가 함께 쓰므로 별도 모듈에 둔다. api.ts 에 두면
// 그 모듈을 통째로 vi.mock 한 테스트에서 상수까지 사라져 읽는 쪽이 터진다(실측: AiChatOverlay.test).
// 상수만 따로 두면 mock 대상이 아니게 되고 import 방향도 한쪽으로만 흐른다.
//
// 출처: S15P21A604-567

/**
 * AI 서버가 보낸 응답이 아니라는 뜻 (S15P21A604-567).
 *
 * `/ai/v1` 이 어디에도 붙어 있지 않으면 FE 오리진이 그 경로를 그대로 받는다 — dev 서버는 404,
 * 게이트웨이 뒤에 upstream 이 없으면 502·HTML 오류 페이지다. 그 응답은 계약 봉투가 아니므로
 * status 만 보고 안내하면 **거짓말이 된다**: 404 는 "대화가 만료되었습니다" 로 나가지만 실제로는
 * 만료된 대화가 없고 AI 서버가 없는 것이다.
 */
export const AI_ENDPOINT_UNREACHABLE = 'AI_ENDPOINT_UNREACHABLE';
