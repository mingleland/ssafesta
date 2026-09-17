// missionId → 화면 문구. 서버는 문구를 내려보내지 않는다 — 카피 한 줄 고치는 데 서버 배포가
// 필요해지지 않게 한 계약이다(GitLab #234).
//
// 모르는 missionId 는 숨기지 않고 식별자를 그대로 보여 준다. 서버가 미션을 하나 늘렸을 때 FE 가
// 조용히 한 줄을 빼면 "받을 수 있는 보상이 화면에 없다" 가 되고, 그 원인을 찾기 어렵다.
const LABEL: Record<string, string> = {
  AI_CONSULT: 'AI 직원과 이야기 나누기',
  SURVEY_ANSWER: '설문 참여하기',
  STRIKER_PLAY_3: '하이스트라이커 3판 하기',
  STRIKER_SCORE: '하이스트라이커 목표 점수 넘기기',
  SLOT_PLAY_3: '슬롯머신 3판 돌리기',
  SLOT_WIN: '슬롯머신 당첨되기',
  BOOTH_VISIT_3: '부스 3곳 둘러보기',
  BOOTH_VISIT_6: '부스 6곳 둘러보기',
  WORLD_ENTER: '오늘 월드 접속하기',
};

export function missionLabel(missionId: string): string {
  return LABEL[missionId] ?? missionId;
}
