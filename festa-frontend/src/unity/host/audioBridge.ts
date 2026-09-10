// React → Unity 월드 오디오 음소거 (S15P21A604-557, GitLab #151).
// 계약(#151, 2026-09-08 게임 파트 통보). 수신부는 Unity `AudioBridge`
// (festa-unity, 커밋 f7b6258f — AuthBridge·InputBridge 와 같은 자동 등록 오브젝트):
//   SendMessage('AudioBridge', 'SetMuted', '1')   // 음소거
//   SendMessage('AudioBridge', 'SetMuted', '0')   // 해제
//
// 왜 FE 가 보내야 하는가: 화면 BGM 의 mute 선호(`festa.settings.music.muted`)는 screenAudio 가
// localStorage 에 들고 있는데 그 값을 읽는 소비자가 화면 안에만 있었다. 그래서 로그인 화면에서
// 끄고 월드에 들어가면 Unity BGM 이 다시 났고, 월드 안에는 끄는 수단이 없어 탭을 닫는 것 말고
// 방법이 없었다. 오디오 소유권을 넘길 때(-463) 소리의 on/off 선호는 같이 넘어가지 않은 것이다.
//
// **볼륨은 이 seam 이 다루지 않는다.** `SetVolume` 은 화면↔11F 실효 출력 차(-463 · #140) 축이고,
// 그 판단은 화면 전환 전후 체감을 쥔 쪽이 따로 내린다. 여기서 섞으면 두 축이 한 자리에서 다툰다.
//
// 멱등이라 중복 호출·순서 어긋남에 안전하다. 재시도 boot 로 새 인스턴스가 서면 그 인스턴스는
// 음소거 상태를 모르므로(상태는 인스턴스마다 새로 시작) 다시 밀어 넣어야 한다.
import type { UnityInstance } from './types';

export const AUDIO_BRIDGE_OBJECT = 'AudioBridge';

/** 화면 mute 선호를 Unity 월드 오디오에 반영한다. muted=true 면 월드 BGM 무음. */
export function syncAudioMute(instance: UnityInstance, muted: boolean): void {
  instance.SendMessage(AUDIO_BRIDGE_OBJECT, 'SetMuted', muted ? '1' : '0');
}
