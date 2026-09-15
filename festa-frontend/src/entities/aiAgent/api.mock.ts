// AI 직원 설정 API mock — booth·wallet·profile과 같은 상용구가 없었던 것이 버그였다
// (S15P21A604-800, VITE_USE_MOCK=true에서도 실 8080을 탔다).
//
// 부스당 AI 직원 1명 제약(V15__agent_one_per_booth)과 일치시켜 agentId = boothId로 둔다 —
// 별도 채번·매핑 테이블이 필요 없어진다.
// ponytail: 메모리만 쓰고 sessionStorage에 안 남긴다. 새로고침하면 등록·문서가 초기화된다.
// leaseApi.mock처럼 영속화가 필요해지면 그때 추가한다.
import type {
  AiAgent,
  AiAgentCommand,
  AiDocumentListView,
  AiDocumentStatus,
  AiDocumentView,
  DocumentUploadResult,
} from './api';

const agents = new Map<number, AiAgent>(); // key: boothId(=agentId)
const documents = new Map<number, AiDocumentView[]>(); // key: agentId
let nextDocumentId = 1;

const QUOTA = { countLimit: 20, bytesLimit: 20 * 1024 * 1024 };

export async function getAiAgent(boothId: number): Promise<AiAgent | null> {
  return agents.get(boothId) ?? null;
}

export async function createAiAgent(boothId: number, command: AiAgentCommand): Promise<AiAgent> {
  const agent: AiAgent = { agentId: boothId, boothId, ...command };
  agents.set(boothId, agent);
  return agent;
}

export async function updateAiAgent(agentId: number, command: AiAgentCommand): Promise<AiAgent> {
  const existing = agents.get(agentId);
  const agent: AiAgent = { agentId, boothId: existing?.boothId ?? agentId, ...command };
  agents.set(agentId, agent);
  return agent;
}

export async function listAiDocuments(agentId: number): Promise<AiDocumentListView> {
  return { documents: documents.get(agentId) ?? [], quota: QUOTA };
}

function addDocument(agentId: number, file: File, status: AiDocumentStatus): AiDocumentView {
  const doc: AiDocumentView = {
    documentId: nextDocumentId++,
    fileName: file.name,
    sizeBytes: file.size,
    status,
    createdAt: new Date().toISOString(),
    uploadedAt: new Date().toISOString(),
  };
  documents.set(agentId, [doc, ...(documents.get(agentId) ?? [])]);
  return doc;
}

export async function uploadAiDocument(agentId: number, file: File): Promise<DocumentUploadResult> {
  const doc = addDocument(agentId, file, 'READY');
  return { duplicate: false, documentId: doc.documentId, processingStatus: doc.status };
}

// 실사용처 없음(-800 시점) — real api.ts와 표면을 맞춰 select 스위치가 타입 오류 없이 동작하게 한다.
export async function replaceAiDocument(documentId: number, file: File): Promise<DocumentUploadResult> {
  for (const [agentId, list] of documents) {
    const index = list.findIndex((d) => d.documentId === documentId);
    if (index === -1) continue;
    const next = addDocument(agentId, file, 'READY');
    return { duplicate: false, documentId: next.documentId, processingStatus: next.status };
  }
  throw new Error('문서를 찾을 수 없습니다.');
}

export async function deleteAiDocument(documentId: number): Promise<void> {
  for (const [agentId, list] of documents) {
    documents.set(agentId, list.filter((d) => d.documentId !== documentId));
  }
}

export function __resetAiAgentMockForTests(): void {
  agents.clear();
  documents.clear();
  nextDocumentId = 1;
}
