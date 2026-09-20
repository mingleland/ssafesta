// 일일 미션 real API 2종 — 계약은 GitLab #233 이 정본이다.
// 진행도 조회 endpoint 는 하나뿐이다: 미션별 개별 조회도, 진행도 보고도 없다(판정은 전부 서버가
// 기존 기록을 세서 한다).
import { api } from '../../shared/api/client';
import type { DailyMissionBoard, DailyMissionClaim } from './types';

export async function getDailyMissions(): Promise<DailyMissionBoard> {
  return api<DailyMissionBoard>('/api/v1/missions/daily');
}

export async function claimDailyMission(missionId: string): Promise<DailyMissionClaim> {
  return api<DailyMissionClaim>(`/api/v1/missions/daily/${missionId}/claims`, { method: 'POST' });
}
