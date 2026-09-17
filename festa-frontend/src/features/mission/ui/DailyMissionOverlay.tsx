// 일일 미션 패널 (S15P21A604-859, GitLab #234 · 계약 #233).
//
// ESC 메뉴의 자식 화면이다 — 닫으면 월드가 아니라 메뉴로 돌아간다. ESC·배타·입력 잠금·focus 반환은
// 여기 없다: WorldPage 가 유일한 중재자이고 MenuPanelHost 가 프레임만 씌운다.
//
// **진행도를 FE 가 들고 있지 않는다.** 서버가 기존 기록을 오늘 날짜로 세서 판정하므로 조회가 항상
// 정답이다(#233). 그래서 Unity 상호작용마다 invalidate 를 걸 필요가 없다 — 이 패널은 열려 있는
// 동안만 존재하고, 열릴 때마다 다시 묻는다. 새 Unity 계약도 만들지 않는다.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { missionApi } from '../../../entities/mission/api.select';
import type { DailyMission } from '../../../entities/mission/types';
import { isApiError } from '../../../shared/api/client';
import { OverlayError, OverlayFrame, OverlayLoading } from '../../overlay/ui/OverlayFrame';
import { missionLabel } from '../model/labels';
import './dailyMission.css';

const IcMission = (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M9 11l3 3 8-8" />
    <path d="M20 12v7a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h8" />
  </svg>
);

// 화면이 낡아서 나는 오류 둘. 배너를 띄우지 않고 재조회로 맞춘다 — 다른 탭에서 이미 받았거나
// 진행도가 방금 바뀐 경우이고, 사용자가 잘못한 것이 없다.
const STALE_CODES = new Set(['NOT_COMPLETED', 'ALREADY_CLAIMED']);

const MESSAGE: Record<string, string> = {
  DAILY_CAP_REACHED: '오늘 받을 수 있는 코인을 모두 받았습니다.',
  MEMBER_ONLY: '회원만 미션 보상을 받을 수 있습니다.',
};

function actionLabel(mission: DailyMission): string {
  if (mission.status === 'CLAIMABLE') return '받기';
  if (mission.status === 'CLAIMED') return '완료됨';
  return `${mission.reward}`;
}

export function DailyMissionOverlay({ onClose }: { onClose: () => void }) {
  const queryClient = useQueryClient();
  const board = useQuery({ queryKey: ['daily-missions'], queryFn: missionApi.getDailyMissions });

  const claim = useMutation({
    mutationFn: missionApi.claimDailyMission,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['daily-missions'] });
      // ESC 메뉴 상단과 WalletBadge 가 쓰는 키다. balanceAfter 로 캐시를 직접 쓰지 않는 이유는
      // 그 키의 값이 Wallet 전체이고, 일부만 갈아 끼우면 나머지 필드가 옛 값으로 남는다.
      void queryClient.invalidateQueries({ queryKey: ['wallet-balance'] });
    },
    onError: (error) => {
      if (isApiError(error) && STALE_CODES.has(error.code)) {
        void queryClient.invalidateQueries({ queryKey: ['daily-missions'] });
      }
    },
  });

  const failure = claim.error;
  const code = failure !== null && isApiError(failure) ? failure.code : null;
  const banner =
    failure === null || (code !== null && STALE_CODES.has(code))
      ? null
      : (code !== null ? MESSAGE[code] : undefined) ?? '보상을 받지 못했습니다. 잠시 후 다시 시도해 주세요.';

  return (
    <OverlayFrame title="일일 미션" subtitle="달성한 미션의 코인을 받아 가세요" size="m" icon={IcMission} onClose={onClose}>
      {board.isPending && <OverlayLoading />}
      {board.isError && <OverlayError title="미션을 불러오지 못했습니다" onRetry={() => void board.refetch()} />}
      {board.data && (
        <>
          <p className="dm-total">{`오늘 ${board.data.earnedToday} / ${board.data.dailyCap}`}</p>
          {banner !== null && <p className="ov-alert" role="alert">{banner}</p>}
          <ul className="dm-list">
            {board.data.missions.map((mission) => (
              <li key={mission.missionId} className="dm-row" data-status={mission.status}>
                <span className="dm-label">{missionLabel(mission.missionId)}</span>
                <span className="ov-chip dm-progress">
                  {mission.progress}/{mission.goal}
                </span>
                <button
                  type="button"
                  className={mission.status === 'CLAIMABLE' ? 'ov-btn ov-btn-primary dm-action' : 'ov-btn dm-action'}
                  disabled={mission.status !== 'CLAIMABLE' || claim.isPending}
                  onClick={() => claim.mutate(mission.missionId)}
                >
                  {claim.isPending && claim.variables === mission.missionId ? '받는 중' : actionLabel(mission)}
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
    </OverlayFrame>
  );
}
