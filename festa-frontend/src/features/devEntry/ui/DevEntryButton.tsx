// 개발자 전용 입장구 (S15P21A604-467).
//
// 제품 로그인 버튼(Google·SSAFY·Kakao·게스트)을 **빌려 쓰지 않는다.** 그 버튼들은 제품 동선이고,
// 개발 편의를 겸하기 시작하면 어느 쪽 경로를 검증한 것인지 알 수 없어진다.
//
// 자리는 화면 **좌상단**이다. 우상단은 사용자 액션 영역으로 남긴다 — 지금은 사운드가 있고
// 앞으로 그래픽 효과 토글이 그 줄에 들어온다.
//   우상단 → 사용자 사운드·그래픽 액션
//   좌상단 → 개발 환경
// 치수는 그 사운드 컨트롤과 같은 토큰을 써 두 모서리가 한 벌로 보이게 한다.
//
// `.login-panel` **밖**에 fixed 로 둔다 — 패널 안에 넣으면 높이가 늘어 버튼 좌표가 밀리고,
// 그 좌표는 reference fidelity 의 acceptance 다(-379).
//
// 월드 안에는 표시하지 않는다. 개발 진입은 로그인 화면에서 한 번 정해지는 일이고,
// 게임 화면에 상시 표식을 두면 HUD 예산(총 점유 15% 미만)만 잠식한다.
import { useNavigate } from 'react-router-dom';
import { isApiError } from '../../../shared/api/client';
import { showToast } from '../../../shared/ui/toast/toastStore';
import { IS_DEV_ENTRY, enterAsDeveloper } from '../model/devEntry';
import { Tooltip } from '../../../shared/ui/tooltip/Tooltip';
import './devEntry.css';

/**
 * 오류를 사람이 읽을 한 줄로.
 *
 * `api()` 는 `Error` 가 아니라 **오류 봉투 객체**를 던진다(`isApiError`). `String(error)` 로
 * 떨어뜨리면 `[object Object]` 가 뜬다 — 실제로 그렇게 나왔다. 봉투면 `code` 와 `message` 를
 * 함께 보여 준다.
 */
function describe(error: unknown): string {
  if (isApiError(error)) return `${error.code} — ${error.message}`;
  if (error instanceof Error) return error.message;
  return String(error);
}

export function DevEntryButton() {
  const navigate = useNavigate();
  // 조건 하나로 컴포넌트 전체가 사라진다 — 프로덕션 빌드에는 이 갈래가 남지 않는다
  if (!IS_DEV_ENTRY) return null;

  return (
    <Tooltip content="개발자로 입장 — 회원 세션으로 바로 들어갑니다" placement="right">
    <button
      type="button"
      className="dev-entry-btn"
      aria-label="개발자로 입장"
      onClick={() => {
        // 실패는 공통 Toast 로 낸다 — 이 화면(LoginPage)이 이미 쓰는 층이고,
        // `kind: 'error'` 는 자동 소멸이 없다("읽고 닫는 것", toastStore.ts).
        // 직접 만든 fixed 배너는 버튼을 가렸고, 알림을 레이아웃에서 떼어 두라는
        // toastStore 의 설계 이유를 그대로 위반한 것이었다.
        enterAsDeveloper().then(
          () => navigate('/app/world', { replace: true }),
          (error: unknown) => showToast(describe(error), 'error'),
        );
      }}
    >
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M14.7 6.3a4 4 0 0 0 5 5l-9.4 9.4a2.1 2.1 0 0 1-3-3z" />
        <path d="M18.5 2.5 21.5 5.5" />
      </svg>
    </button>
    </Tooltip>
  );
}
