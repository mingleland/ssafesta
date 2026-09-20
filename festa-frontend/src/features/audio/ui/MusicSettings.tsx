// 음악 설정 (S15P21A604-618) — ESC 설정 화면이 쓰는 유일한 항목.
//
// **설정 UI 가 사용자 선호의 정본이고 재생 쪽은 그 값을 소비한다.** 그래서 화면 우상단
// 컨트롤(ScreenControls)과 같은 store 를 읽고 쓴다 — 한쪽에서 끄면 다른 쪽도 꺼져 보인다.
//
// **2026-09-14 (S15P21A604-733) — 이제 월드 BGM 에도 닿는다.** 여기 적혀 있던 "AudioBridge 가 아직
// SetMuted 만 받는다" 는 사실이 아니었다. `UnityHost` 가 mute 와 함께 volume 도 Unity 로 보낸다.
// 값 하나가 화면 오디오와 월드 BGM 둘 다를 움직이므로 Master/Screen/World 로 쪼개지 않는다.
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
          // 포인터 조절 뒤 range에 focus가 남으면 이후 Space/방향키가 월드 조작 대신 음량을 바꾼다.
          onPointerUp={(e) => e.currentTarget.blur()}
          onKeyDown={(e) => {
            if (e.key !== ' ' && e.key !== 'Spacebar' && !e.key.startsWith('Arrow')) return;
            // 의도치 않은 키 입력으로 음량을 바꾸지 않고, 이벤트는 상위 월드 단축키가 계속 처리하게 둔다.
            e.preventDefault();
            e.currentTarget.blur();
          }}
        />
        <span className="ms-value">{percent}%</span>
      </div>

      <p className="ms-note">로그인·시작 화면과 월드 음악에 함께 적용됩니다.</p>
    </div>
  );
}
