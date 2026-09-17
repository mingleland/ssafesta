// 일일 미션 계약 타입 — GitLab #233 이 확정한 응답 모양 그대로다.
//
// 보상액·상한을 여기 상수로 두지 않는다. 서버가 `reward`·`dailyCap` 을 내려보내므로 숫자를 FE 에
// 박으면 배점이 바뀔 때 화면만 옛 값을 말한다 — 실제로 계약이 10 코인에서 15 코인으로 한 번 바뀌었다.

/** LOCKED 미달 · CLAIMABLE 수령 가능 · CLAIMED 수령 완료 */
export type MissionStatus = 'LOCKED' | 'CLAIMABLE' | 'CLAIMED';

export interface DailyMission {
  /** 서버가 아는 미션 식별자. 문구는 내려오지 않고 FE 가 매핑한다(#234) */
  missionId: string;
  reward: number;
  progress: number;
  goal: number;
  status: MissionStatus;
}

export interface DailyMissionBoard {
  /** KST 기준 오늘 날짜 (yyyy-MM-dd) */
  date: string;
  resetAt: string;
  dailyCap: number;
  earnedToday: number;
  /** 서버가 켠 미션만 담긴다 — 배열에 없는 미션은 그리지 않는다 */
  missions: DailyMission[];
}

export interface DailyMissionClaim {
  missionId: string;
  reward: number;
  balanceAfter: number;
  claimedAt: string;
}
