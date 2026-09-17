// 로컬 개발용 일일 미션 mock. BE 구현(S15P21A604-857)이 오기 전까지 화면을 돌려 보는 용도다.
// 세션 안에서만 상태를 들고, 수령하면 CLAIMED 로 넘기고 획득량을 더한다 — 실제 판정은 서버가
// 원장 멱등키로 하므로 여기서 흉내 내는 것은 화면 확인에 필요한 최소한이다.
import type { ApiError } from '../../shared/api/client';
import type { DailyMission, DailyMissionBoard, DailyMissionClaim } from './types';

const REWARD = 15;

// progress/goal 은 세 가지 상태를 한 화면에서 보려고 섞어 둔 값이다
const missions: DailyMission[] = [
  { missionId: 'WORLD_ENTER', reward: REWARD, progress: 1, goal: 1, status: 'CLAIMABLE' },
  { missionId: 'AI_CONSULT', reward: REWARD, progress: 0, goal: 1, status: 'LOCKED' },
  { missionId: 'SURVEY_ANSWER', reward: REWARD, progress: 1, goal: 1, status: 'CLAIMED' },
  { missionId: 'STRIKER_PLAY_3', reward: REWARD, progress: 2, goal: 3, status: 'LOCKED' },
  { missionId: 'STRIKER_SCORE', reward: REWARD, progress: 0, goal: 1, status: 'LOCKED' },
  { missionId: 'SLOT_PLAY_3', reward: REWARD, progress: 3, goal: 3, status: 'CLAIMABLE' },
  { missionId: 'SLOT_WIN', reward: REWARD, progress: 0, goal: 1, status: 'LOCKED' },
  { missionId: 'BOOTH_VISIT_3', reward: REWARD, progress: 2, goal: 3, status: 'LOCKED' },
  { missionId: 'BOOTH_VISIT_6', reward: REWARD, progress: 2, goal: 6, status: 'LOCKED' },
];

function apiError(code: string, message: string, status: number): ApiError {
  return { code, message, status, requestId: `mock_${Date.now()}`, errors: [], warnings: [] };
}

export async function getDailyMissions(): Promise<DailyMissionBoard> {
  const earnedToday = missions.filter((m) => m.status === 'CLAIMED').length * REWARD;
  return {
    date: new Date().toISOString().slice(0, 10),
    resetAt: new Date().toISOString(),
    dailyCap: missions.length * REWARD,
    earnedToday,
    missions: missions.map((m) => ({ ...m })),
  };
}

export async function claimDailyMission(missionId: string): Promise<DailyMissionClaim> {
  const mission = missions.find((m) => m.missionId === missionId);
  if (mission === undefined) throw apiError('NOT_COMPLETED', '아직 완료하지 않은 미션입니다.', 400);
  if (mission.status === 'CLAIMED') throw apiError('ALREADY_CLAIMED', '이미 받은 보상입니다.', 409);
  if (mission.status === 'LOCKED') throw apiError('NOT_COMPLETED', '아직 완료하지 않은 미션입니다.', 400);
  mission.status = 'CLAIMED';
  return { missionId, reward: mission.reward, balanceAfter: 0, claimedAt: new Date().toISOString() };
}
