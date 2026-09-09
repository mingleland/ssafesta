// 화면 음소거 → Unity 전달 계약 (S15P21A604-557, GitLab #151).
// 여기서 잠그는 것은 **전달 인자가 계약과 정확히 같은가** 하나다 — 오브젝트명·메서드명, 그리고
// boolean 이 아니라 '1'/'0' 문자열이라는 점. 언제 보내는가는 배선 테스트(unityHostAudioMute)가 본다.
import { describe, expect, it, vi } from 'vitest';
import { AUDIO_BRIDGE_OBJECT, syncAudioMute } from '../../audioBridge';
import type { UnityInstance } from '../../types';

function instance() {
  return { SendMessage: vi.fn(), SetFullscreen: vi.fn(), Quit: vi.fn(async () => {}) } as unknown as UnityInstance & {
    SendMessage: ReturnType<typeof vi.fn>;
  };
}

describe('syncAudioMute (-557, #151)', () => {
  it('음소거면 AudioBridge.SetMuted 에 문자열 "1" 을 보낸다', () => {
    const unity = instance();
    syncAudioMute(unity, true);
    expect(unity.SendMessage).toHaveBeenCalledWith(AUDIO_BRIDGE_OBJECT, 'SetMuted', '1');
  });

  it('해제면 "0" 을 보낸다', () => {
    const unity = instance();
    syncAudioMute(unity, false);
    expect(unity.SendMessage).toHaveBeenCalledWith(AUDIO_BRIDGE_OBJECT, 'SetMuted', '0');
  });

  it('boolean 을 그대로 넘기지 않는다 — 수신부 규약이 문자열이다', () => {
    const unity = instance();
    syncAudioMute(unity, true);
    const [, , value] = unity.SendMessage.mock.calls[0] as [string, string, unknown];
    expect(typeof value).toBe('string');
  });

  it('볼륨은 이 seam 이 다루지 않는다 — SetVolume 을 보내지 않는다 (-463 · #140 축)', () => {
    const unity = instance();
    syncAudioMute(unity, true);
    syncAudioMute(unity, false);
    expect(unity.SendMessage).not.toHaveBeenCalledWith(AUDIO_BRIDGE_OBJECT, 'SetVolume', expect.anything());
  });

  it('멱등 — 같은 값을 다시 밀어도 그대로 한 번 더 전달된다 (재시도 boot 보험)', () => {
    const unity = instance();
    syncAudioMute(unity, true);
    syncAudioMute(unity, true);
    expect(unity.SendMessage.mock.calls).toEqual([
      [AUDIO_BRIDGE_OBJECT, 'SetMuted', '1'],
      [AUDIO_BRIDGE_OBJECT, 'SetMuted', '1'],
    ]);
  });
});
