// 음악 설정 (S15P21A604-618) — ESC 설정 화면이 쓰는 유일한 항목.
//
// **설정 UI 가 사용자 선호의 정본이고 재생 쪽은 그 값을 소비한다.** 그래서 화면 우상단
// 컨트롤(ScreenControls)과 같은 store 를 읽고 쓴다 — 한쪽에서 끄면 다른 쪽도 꺼져 보인다.
//
// 여기서 조절하는 것은 **FE 가 소유한 화면 오디오**(Landing·Login)뿐이다. World BGM 은 Unity
// 소관이고 AudioBridge 가 아직 SetMuted 만 받는다. volume 계약이 오면 이 UI 를 그대로 두고
// 연결 지점만 는다 — 그래서 Master/Screen/World 로 미리 쪼개지 않는다.
import { setMusicVolume, setMuted, useScreenAudio } from '../model/screenAudio';
import './musicSettings.css';

export function MusicSettings() {
  const { muted, volume } = useScreenAudio();
  const percent = Math.round(volume * 100);

  return (
    <div className="ms-root">
      <div className="ms-row">
        <span className="ms-label">음악</span>
        <button
          type="button"
          className="ms-toggle"
          role="switch"
          aria-checked={!muted}
          aria-label="음악"
          onClick={() => setMuted(!muted)}
        >
          <span className="ms-toggle-knob" />
        </button>
      </div>

      <div className="ms-row">
        <label className="ms-label" htmlFor="ms-volume">
          크기
        </label>
        <input
          id="ms-volume"
          className="ms-slider"
          type="range"
          min={0}
          max={100}
          step={5}
          value={percent}
          // 음소거 중에는 끌 수 없게 막지 않는다 — 미리 맞춰 두고 켜는 순서가 자연스럽다
          onChange={(e) => setMusicVolume(Number(e.target.value) / 100)}
        />
        <span className="ms-value">{percent}%</span>
      </div>

      <p className="ms-note">지금은 로그인·시작 화면의 음악에 적용됩니다.</p>
    </div>
  );
}
