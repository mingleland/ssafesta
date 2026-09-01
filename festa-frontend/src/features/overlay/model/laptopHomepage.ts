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

// 서버 값이라도 iframe·새 탭에 넣기 전 정규화한다 — http/https 외 scheme 은 그 자체가 구멍이다(기존 방어 유지)
function parse(raw: string): { href: string; hostname: string } | null {
  try {
    const parsed = new URL(raw);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null;
    return { href: parsed.href, hostname: parsed.hostname };
  } catch {
    return null;
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
    const parsed = parse(booth.homepageUrl);
    setState(parsed ? { kind: 'valid', boothId, ...parsed } : { kind: 'invalid', boothId });
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
