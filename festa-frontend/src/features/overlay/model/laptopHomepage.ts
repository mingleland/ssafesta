// LAPTOP 오버레이의 URL 확보 상태 기계 (S15P21A604-374).
// 016 확정 계약(C-01 #97): URL 정본은 GET /booths/{id} 의 homepageUrl 이다 — 이벤트 payload 의
// url 을 소비하지 않는다(Unity 는 url 을 보내지 않으며, 보내더라도 정본이 아니다).
import { useSyncExternalStore } from 'react';
import { facadeApi } from '../../../entities/booth/facadeApi.select';

export type LaptopHomepageState =
  | { kind: 'idle' }
  | { kind: 'loading'; boothId: number }
  | { kind: 'no_url'; boothId: number }
  | { kind: 'invalid'; boothId: number }
  | { kind: 'error'; boothId: number }
  /**
   * 주소는 멀쩡한데 **이 창 안에서는 열 수 없는** 것 (S15P21A604-946).
   *
   *  `insecure`    http 주소다. 계약상 허용이지만(016 FR-002 — 이동 대상이라 facade 로고와
   *                의도적 비대칭) https 페이지 안의 iframe 은 브라우저가 mixed content 로 막는다.
   *                그대로 넣으면 이유 없는 빈 프레임만 남는다.
   *  `self_origin` 축제장 자신을 가리킨다. same-origin 프레임에 `allow-scripts`+`allow-same-origin`
   *                이 걸리면 프레임이 제 sandbox 를 걷어낼 수 있어 그 조합 자체가 구멍이다.
   *
   * 둘 다 새 탭으로는 멀쩡히 열리므로 `invalid` 로 접지 않는다 — 016 SC-003 이 "새 창 포함
   * 성공률" 을 기준으로 잡은 그 자리다.
   */
  | { kind: 'external_only'; boothId: number; href: string; hostname: string; reason: 'insecure' | 'self_origin' }
  | { kind: 'valid'; boothId: number; href: string; hostname: string };

let state: LaptopHomepageState = { kind: 'idle' };
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

function setState(next: LaptopHomepageState): void {
  state = next;
  emit();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getLaptopHomepageSnapshot(): LaptopHomepageState {
  return state;
}

export function useLaptopHomepage(): LaptopHomepageState {
  return useSyncExternalStore(subscribe, getLaptopHomepageSnapshot);
}

/** `resolveLaptopHomepage` 가 내리는 판정 — 부스 번호가 붙기 전의 모양이다. */
export type LaptopHomepageVerdict =
  | { kind: 'invalid' }
  | { kind: 'external_only'; href: string; hostname: string; reason: 'insecure' | 'self_origin' }
  | { kind: 'valid'; href: string; hostname: string };

/**
 * 등록된 주소를 **어디에 띄울 수 있는가**로 가른다 (S15P21A604-946).
 *
 * <b>현재 오리진을 인자로 받는다.</b> 여기서 `window.location.origin` 을 읽으면 이 판정이
 * 브라우저 전역에 묶여 테스트가 `window` 를 스텁해야 하고, 그 스텁이 곧 계약이 된다. 주입하면
 * 판정은 순수 함수로 남고 호출부 한 곳만 전역을 안다.
 *
 * 비교는 `hostname` 이 아니라 <b>`origin`</b> 끼리 한다 — scheme·port 가 다르면 남의 출처다.
 *
 * http/https 외 scheme 은 기존대로 `invalid` 다. `javascript:` 를 새 탭으로 넘기는 것 자체가
 * 구멍이라 여기서 끊는다.
 */
export function resolveLaptopHomepage(
  raw: string,
  context: { currentOrigin: string },
): LaptopHomepageVerdict {
  let parsed: URL;
  try {
    parsed = new URL(raw);
  } catch {
    return { kind: 'invalid' };
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return { kind: 'invalid' };

  const seat = { href: parsed.href, hostname: parsed.hostname };
  if (parsed.protocol === 'http:') return { kind: 'external_only', ...seat, reason: 'insecure' };
  if (sameOrigin(parsed, context.currentOrigin)) {
    return { kind: 'external_only', ...seat, reason: 'self_origin' };
  }
  return { kind: 'valid', ...seat };
}

/**
 * 현재 오리진도 `URL` 로 한 번 통과시킨다 — 문자열 비교로 두면 후행 슬래시나 기본 포트 표기
 * (`https://a.example:443`) 하나에 판정이 갈린다. 파싱할 수 없는 값(테스트의 빈 문자열,
 * `origin` 이 `"null"` 인 opaque 문서)은 "같지 않다" 로 읽는다 — 자기 오리진 판정은 확신이
 * 설 때만 내린다.
 */
function sameOrigin(parsed: URL, currentOrigin: string): boolean {
  try {
    return parsed.origin === new URL(currentOrigin).origin;
  } catch {
    return false;
  }
}

export async function loadLaptopHomepage(boothId: number): Promise<void> {
  setState({ kind: 'loading', boothId });
  try {
    const booth = await facadeApi.getBooth(boothId);
    if (state.kind !== 'loading' || state.boothId !== boothId) return; // 늦은 응답 가드
    if (booth.homepageUrl === null) {
      setState({ kind: 'no_url', boothId });
      return;
    }
    // 전역을 읽는 곳은 여기 한 곳이다 — 판정 자체는 순수 함수로 두고 오리진만 주입한다.
    // `window` 유무를 여기서 본다(`runtime.ts` 의 `currentOrigin` 과 같은 관용구). 브라우저가
    // 아니면 빈 문자열이고, 그때 self_origin 판정은 성립하지 않는다 — 없는 오리진과 같다고 볼
    // 근거가 없다. 이 가드가 없으면 node 환경에서 조회 전체가 `error` 로 떨어진다.
    const verdict = resolveLaptopHomepage(booth.homepageUrl, {
      currentOrigin: typeof window === 'undefined' ? '' : window.location.origin,
    });
    setState({ ...verdict, boothId });
  } catch {
    if (state.kind === 'loading' && state.boothId === boothId) setState({ kind: 'error', boothId });
  }
}

export function resetLaptopHomepage(): void {
  setState({ kind: 'idle' });
}

export function __resetLaptopHomepageForTests(): void {
  state = { kind: 'idle' };
  listeners.clear();
}

/** 테스트 전용 — 서버 조회를 거치지 않고 화면이 읽을 상태를 직접 놓는다. */
export function __pushLaptopHomepageForTests(next: LaptopHomepageState): void {
  setState(next);
}
