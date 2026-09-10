// AI 직원 설정 API의 FE 경계 — 내 부스 관리 화면이 Spring CRUD 계약을 한곳에서 소비한다.
import { api } from '../../shared/api/client';

export type AiAgentRole = 'PROJECT_DOCENT' | 'GUIDE';
export type AiAgentTone = 'FRIENDLY' | 'PROFESSIONAL' | 'ENTHUSIASTIC';
export type AiAgentResponseLength = 'SHORT' | 'MEDIUM' | 'LONG';

export interface AiAgent {
  agentId: number;
  boothId: number;
  name: string;
  role: AiAgentRole;
  tone: AiAgentTone;
  systemPrompt: string;
  responseLength: AiAgentResponseLength;
  servicePrice: number;
  handoffEnabled: boolean;
  forbiddenTopics: string[];
}

export interface AiAgentCommand {
  name: string;
  role: AiAgentRole;
  tone: AiAgentTone;
  systemPrompt: string;
  responseLength: AiAgentResponseLength;
  servicePrice: number;
  handoffEnabled: boolean;
  forbiddenTopics: string[];
}

export async function getAiAgent(boothId: number): Promise<AiAgent | null> {
  const result = await api<{ agents: AiAgent[] }>(`/api/v1/booths/${boothId}/agents`);
  return result.agents[0] ?? null;
}

export function createAiAgent(boothId: number, command: AiAgentCommand): Promise<AiAgent> {
  return api<AiAgent>(`/api/v1/booths/${boothId}/agents`, {
    method: 'POST',
    body: JSON.stringify(command),
  });
}

export function updateAiAgent(agentId: number, command: AiAgentCommand): Promise<AiAgent> {
  return api<AiAgent>(`/api/v1/agents/${agentId}`, {
    method: 'PATCH',
    body: JSON.stringify(command),
  });
}

type UploadGrant = { duplicate: boolean; documentId: number; uploadUrl?: string };
export type DocumentUploadResult = { duplicate: boolean; documentId: number; processingStatus?: string };

export type AiDocumentStatus = 'QUEUED' | 'PROCESSING' | 'READY' | 'FAILED' | 'EXPIRED' | 'DISABLED';

export interface AiDocumentView {
  documentId: number;
  fileName: string;
  sizeBytes: number;
  status: AiDocumentStatus;
  createdAt: string;
  uploadedAt: string | null;
}

export interface AiDocumentListView {
  documents: AiDocumentView[];
  quota: { countLimit: number; bytesLimit: number };
}

export function listAiDocuments(agentId: number): Promise<AiDocumentListView> {
  return api<AiDocumentListView>(`/api/v1/agents/${agentId}/documents`);
}

function sha256Hex(bytes: ArrayBuffer): string {
  return Array.from(new Uint8Array(bytes), (byte) => byte.toString(16).padStart(2, '0')).join('');
}

function contentTypeFor(file: File): string {
  const lowerName = file.name.toLowerCase();
  if (lowerName.endsWith('.pdf')) return 'application/pdf';
  if (lowerName.endsWith('.md')) return 'text/markdown';
  return 'text/plain';
}

/** Presigned PUT은 Spring을 거치지 않는다. 서버가 준 URL 외에는 인증 헤더도 덧붙이지 않는다. */
export async function uploadAiDocument(agentId: number, file: File): Promise<DocumentUploadResult> {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  const grant = await api<UploadGrant>(`/api/v1/agents/${agentId}/documents/upload-url`, {
    method: 'POST',
    body: JSON.stringify({
      fileName: file.name,
      contentType: contentTypeFor(file),
      size: file.size,
      contentSha256: sha256Hex(digest),
    }),
  });
  if (grant.duplicate) return { duplicate: true, documentId: grant.documentId };
  if (!grant.uploadUrl) throw new Error('문서 업로드 URL을 받지 못했습니다.');

  const response = await fetch(grant.uploadUrl, { method: 'PUT', body: file });
  if (!response.ok) throw new Error('저장소에 파일을 업로드하지 못했습니다.');

  const completed = await api<{ documentId: number; processingStatus: string }>(
    `/api/v1/documents/${grant.documentId}/complete`,
    { method: 'POST' },
  );
  return { duplicate: false, ...completed };
}
