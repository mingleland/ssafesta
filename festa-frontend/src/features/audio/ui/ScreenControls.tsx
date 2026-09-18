// Landing·Login 공통 화면 컨트롤 (S15P21A604-463).
//
// Landing 루트는 화면 전체를 덮는 <button> 이라 그 **안에** 두면 중첩 버튼이 된다 — 형제로
// 배치한다. 형제이므로 클릭이 랜딩 진입으로 새지 않아 stopPropagation 이 필요 없다.
//
// 지금 담는 것은 음소거 하나지만 그릇은 여럿을 전제로 만든다 — 레이어 효과(구름·열기구·
// 불꽃놀이) 토글이 이 줄에 나란히 들어온다. 그 토글은 동작할 것이 아직 없어 렌더하지 않는다.
import { useEffect } from 'react';
import { enterScreen, setMuted, unlockAndPlay, useScreenAudio } from '../model/screenAudio';
import { Tooltip } from '../../../shared/ui/tooltip/Tooltip';
import './screenControls.css';

export function ScreenControls() {
  const { muted, pendingGesture } = useScreenAudio();

  // 표시 기준은 "음소거인가" 가 아니라 **지금 실제로 들리는가** 다. 주소창으로 곧장 들어오면
  // 제스처가 없어 자동재생이 거부되는데, muted 만 보면 버튼이 "켜짐" 으로 남아 사용자는
  // 켜져 있다고 믿은 채 켤 방법을 잃는다(주소창 직행 시 소리가 안 나던 결함).
  const silent = muted || pendingGesture;

  // 들리는 중이면 끄고, 들리지 않으면 켠다. 안 들리는 이유가 음소거인지 자동재생 거부인지에
  // 따라 켜는 방법이 다르다 — 후자는 이 클릭 자체가 브라우저가 요구하는 제스처다.
  function toggle() {
    if (!silent) {
      setMuted(true);
      return;
    }
    if (muted) setMuted(false);
    else unlockAndPlay();
  }

  // 이 컨트롤이 떠 있는 화면 = 화면 음악이 있어야 하는 화면. 진입과 동시에 켠다.
  useEffect(() => {
    enterScreen();
  }, []);

  return (
    <div className="screen-controls">
      <Tooltip content={silent ? '배경음악 켜기' : '배경음악 끄기'} placement="bottom">
        <button
          type="button"
          className="screen-control-btn"
          aria-pressed={silent}
          aria-label={silent ? '배경음악 켜기' : '배경음악 끄기'}
          onClick={toggle}
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M11 5 6 9H3v6h3l5 4z" />
            {silent ? (
              <>
                <path d="m17 9 4 6" />
                <path d="m21 9-4 6" />
              </>
            ) : (
              <>
                <path d="M15.6 8.4a5 5 0 0 1 0 7.2" />
                <path d="M18.4 5.6a9 9 0 0 1 0 12.8" />
              </>
            )}
          </svg>
        </button>
      </Tooltip>
    </div>
  );
}
