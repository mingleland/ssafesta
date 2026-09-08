// BE AiAgentController/AiAgentService 응답 전사 (backend/src/main/java/com/example/ssafesta/ai).
// AgentView 에 status 필드는 없다 — 서버가 아예 안 돌려준다. AiAgent.java 주석: "ACTIVE on every
// row this class writes. There is no lifecycle here" — 활성/비활성 전환 API가 아직 없다는 뜻이라
// 여기서 있는 척 필드를 만들지 않는다(헌법 30조).
export type AgentRole = 'PROJECT_DOCENT' | 'GUIDE';
export type AgentTone = 'FRIENDLY' | 'PROFESSIONAL' | 'ENTHUSIASTIC';
export type AgentResponseLength = 'SHORT' | 'MEDIUM' | 'LONG';

export interface AgentView {
  agentId: number;
  boothId: number;
  name: string;
  role: AgentRole;
  tone: AgentTone;
  systemPrompt: string;
  responseLength: AgentResponseLength;
  servicePrice: number;
  handoffEnabled: boolean;
  forbiddenTopics: string[];
}

export interface AgentListView {
  agents: AgentView[];
}

/** POST/PATCH body. PresenceField(BE) — 보낸 키만 반영, 생략은 유지. dirty 키만 담아 보낸다. */
export interface AgentPatch {
  name?: string;
  role?: AgentRole;
  tone?: AgentTone;
  systemPrompt?: string;
  responseLength?: AgentResponseLength;
  servicePrice?: number;
  handoffEnabled?: boolean;
  forbiddenTopics?: string[];
}

// BE AiDocument.java 의 processing_status 4종 — QUEUED/PROCESSING/READY/EXPIRED. FAILED 는 아직
// 없다(internal ai_document_jobs 테이블에서 실패가 확정돼도 이 상태로 반영되는 코드가 없다,
// S15P21A604-174가 그 연결을 맡을 예정 — 실측 확인함, 2026-09-08).
export type DocumentProcessingStatus = 'QUEUED' | 'PROCESSING' | 'READY' | 'EXPIRED';

export interface UploadGrantView {
  documentId: number;
  uploadUrl: string | null;
  duplicate: boolean;
}

export interface CompleteView {
  documentId: number;
  processingStatus: DocumentProcessingStatus;
}
