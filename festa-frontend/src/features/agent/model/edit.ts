// AI 직원 소유자 편집 상태 기계 — draft + dirty 키 추적, 저장은 dirty 키만 PATCH 직렬화.
// features/project/model/edit.ts(S15P21A604-134)와 같은 패턴 복제 — BE PresenceField 규칙(키
// 생략 = 유지, 명시적 null = 비우기)이 같아서 전체 객체 전송을 여기서도 금지한다.
import { useSyncExternalStore } from 'react';
import { createAgent, getMyAgents, updateAgent } from '../../../entities/agent/api';
import type { AgentPatch, AgentRole, AgentTone, AgentView } from '../../../entities/agent/types';

export type AgentDraftField = 'name' | 'role' | 'tone' | 'systemPrompt';

export interface AgentDraft {
  name: string;
  role: AgentRole | '';
  tone: AgentTone;
  systemPrompt: string;
}

export interface AgentEditState {
  status: 'idle' | 'loading' | 'ready' | 'error';
  boothId: number | null;
  /** null = 아직 만든 AI 직원이 없음(0개 배열) — save 는 create 로 간다 */
  agentId: number | null;
  draft: AgentDraft;
  dirty: ReadonlySet<AgentDraftField>;
  save: { phase: 'idle' | 'submitting' | 'success' | 'error'; message?: string };
}

const EMPTY_DRAFT: AgentDraft = { name: '', role: '', tone: 'FRIENDLY', systemPrompt: '' };

const initialState: AgentEditState = {
  status: 'idle',
  boothId: null,
  agentId: null,
  draft: EMPTY_DRAFT,
  dirty: new Set(),
  save: { phase: 'idle' },
};

let state: AgentEditState = initialState;
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

function setState(patch: Partial<AgentEditState>): void {
  state = { ...state, ...patch };
  emit();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getAgentEditSnapshot(): AgentEditState {
  return state;
}

export function useAgentEdit(): AgentEditState {
  return useSyncExternalStore(subscribe, getAgentEditSnapshot);
}

function draftOf(view: AgentView): AgentDraft {
  return { name: view.name, role: view.role, tone: view.tone, systemPrompt: view.systemPrompt };
}

export async function loadAgentEdit(boothId: number): Promise<void> {
  setState({ ...initialState, status: 'loading', boothId });
  try {
    const view = await getMyAgents(boothId);
    if (state.boothId !== boothId) return; // 늦은 응답이 다른 부스 편집 상태를 덮지 않는다
    const agent = view.agents[0] ?? null;
    setState({
      status: 'ready',
      agentId: agent?.agentId ?? null,
      draft: agent ? draftOf(agent) : EMPTY_DRAFT,
      dirty: new Set(),
    });
  } catch {
    if (state.boothId !== boothId) return;
    setState({ status: 'error' });
  }
}

export function updateAgentField<K extends AgentDraftField>(key: K, value: AgentDraft[K]): void {
  if (state.save.phase === 'submitting') return;
  setState({
    draft: { ...state.draft, [key]: value },
    dirty: new Set(state.dirty).add(key),
    save: { phase: 'idle' },
  });
}

export async function saveAgent(): Promise<void> {
  if (state.save.phase === 'submitting' || state.boothId === null) return;

  if (state.agentId === null) {
    // 생성은 name·role·systemPrompt 셋 다 필수(BE) — dirty 여부와 무관하게 draft 전체를 보낸다.
    if (state.draft.name.trim() === '' || state.draft.role === '' || state.draft.systemPrompt.trim() === '') {
      setState({ save: { phase: 'error', message: '이름·역할·프롬프트를 모두 입력해 주세요.' } });
      return;
    }
  } else if (state.dirty.size === 0) {
    return;
  }

  setState({ save: { phase: 'submitting' } });
  try {
    let saved: AgentView;
    if (state.agentId === null) {
      const patch: AgentPatch = {
        name: state.draft.name,
        role: state.draft.role as AgentRole,
        tone: state.draft.tone,
        systemPrompt: state.draft.systemPrompt,
      };
      saved = await createAgent(state.boothId, patch);
    } else {
      const patch: AgentPatch = {};
      for (const key of state.dirty) {
        if (key === 'role' && state.draft.role === '') continue;
        (patch as Record<string, unknown>)[key] = state.draft[key];
      }
      saved = await updateAgent(state.agentId, patch);
    }
    setState({
      agentId: saved.agentId,
      draft: draftOf(saved),
      dirty: new Set(),
      save: { phase: 'success' },
    });
  } catch {
    setState({ save: { phase: 'error', message: '저장하지 못했습니다. 잠시 후 다시 시도해 주세요.' } });
  }
}

export function __resetAgentEditForTests(): void {
  state = initialState;
  listeners.clear();
}
