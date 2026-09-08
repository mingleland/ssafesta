// AI 직원 real API — spec 007 US1 (backend/src/main/java/com/example/ssafesta/ai/AiAgentController).
// mock/select 층을 두지 않는다 — entities/conversation/api.ts 와 같은 결정: 이 영역은 아직
// 안정화 중이라 두 구현을 나란히 유지하는 비용이 더 크다.
import { api } from '../../shared/api/client';
import type { AgentListView, AgentPatch, AgentView } from './types';

export function createAgent(boothId: number, patch: AgentPatch): Promise<AgentView> {
  return api<AgentView>(`/api/v1/booths/${boothId}/agents`, {
    method: 'POST',
    body: JSON.stringify(patch),
  });
}

/** 0개 또는 1개 배열 — 부스당 AI 직원 1명(C-13). 없는 것은 오류가 아니다(BE 주석) */
export function getMyAgents(boothId: number): Promise<AgentListView> {
  return api<AgentListView>(`/api/v1/booths/${boothId}/agents`);
}

/** patch 에는 dirty 키만 담는다 — BE PresenceField(키 생략 ≠ null 전송) */
export function updateAgent(agentId: number, patch: AgentPatch): Promise<AgentView> {
  return api<AgentView>(`/api/v1/agents/${agentId}`, {
    method: 'PATCH',
    body: JSON.stringify(patch),
  });
}
