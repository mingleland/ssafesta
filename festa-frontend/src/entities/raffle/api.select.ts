// 응모권 real API가 아직 없다(-836 스펙에 추첨 개념 없음, S15P21A604-842 후속에서 추가 예정) —
// 그래서 다른 entities와 달리 VITE_USE_MOCK으로 갈리지 않는다. 항상 mock이다.
//
// BE가 GET/POST 엔드포인트를 내놓으면: api.ts를 실 계약에 맞춰 고치고, 아래 export를
// `realApi`로 바꾸면 전환 끝 — 화면(EventRewardShopOverlay)은 이 파일 너머를 모른다.
import * as mockApi from './api.mock';

export const raffleApi = mockApi;
