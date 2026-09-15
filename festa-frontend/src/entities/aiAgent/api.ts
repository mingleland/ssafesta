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

function sha256Base64(bytes: ArrayBuffer): string {
  return btoa(String.fromCharCode(...new Uint8Array(bytes)));
}

function contentTypeFor(file: File): string {
  const lowerName = file.name.toLowerCase();
  if (lowerName.endsWith('.pdf')) return 'application/pdf';
  if (lowerName.endsWith('.md')) return 'text/markdown';
  return 'text/plain';
}

/**
 * 3단계 업로드의 공통 몸통 — 1단계 endpoint 만 신규 등록과 교체로 갈린다 (S15P21A604-691).
 *
 * 2·3단계(presigned PUT → complete)는 두 경로가 **완전히 같다**. 계약 문서가 "2단계는 일반 업로드와
 * 같다" 고 적은 그대로다. 그래서 갈라지는 1단계만 인자로 받는다 — 두 벌로 두면 한쪽만 고치는 날이 온다.
 *
 * Presigned PUT 은 Spring 을 거치지 않는다. 서버가 준 URL 외에는 인증 헤더도 덧붙이지 않는다.
 */
async function putThroughGrant(
  file: File,
  issueGrant: (body: string) => Promise<UploadGrant>,
): Promise<DocumentUploadResult> {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  const grant = await issueGrant(
    JSON.stringify({
      fileName: file.name,
      contentType: contentTypeFor(file),
      size: file.size,
      contentSha256: sha256Hex(digest),
    }),
  );
  if (grant.duplicate) return { duplicate: true, documentId: grant.documentId };
  if (!grant.uploadUrl) throw new Error('문서 업로드 URL을 받지 못했습니다.');

  const response = await fetch(grant.uploadUrl, {
    method: 'PUT',
    headers: {
      'Content-Type': contentTypeFor(file),
      'x-amz-checksum-sha256': sha256Base64(digest),
    },
    body: file,
  });
  if (!response.ok) throw new Error('저장소에 파일을 업로드하지 못했습니다.');

  // 완료는 **새 문서 id** 로 부른다. 교체에서 이 id 는 원본이 아니라 1단계가 만들어 준 교체본이다.
  const completed = await api<{ documentId: number; processingStatus: string }>(
    `/api/v1/documents/${grant.documentId}/complete`,
    { method: 'POST' },
  );
  return { duplicate: false, ...completed };
}

/** 신규 등록 — 1단계는 AI 직원에 새 문서를 붙인다 */
export function uploadAiDocument(agentId: number, file: File): Promise<DocumentUploadResult> {
  return putThroughGrant(file, (body) =>
    api<UploadGrant>(`/api/v1/agents/${agentId}/documents/upload-url`, { method: 'POST', body }),
  );
}

/**
 * 수정본 교체 (S15P21A604-691, GitLab #179).
 *
 * 원본은 새 문서가 준비 완료가 되는 순간에야 물러난다 — 그때까지 AI 직원은 원본으로 답한다.
 * 그래서 실패해도 답변 근거가 사라지지 않는다. 같은 파일을 다시 보내면 `duplicate: true` 이고,
 * 아직 업로드하지 않은 교체본에 같은 파일로 다시 부르면 새 `uploadUrl` 이 재발급된다.
 */
export function replaceAiDocument(documentId: number, file: File): Promise<DocumentUploadResult> {
  return putThroughGrant(file, (body) =>
    api<UploadGrant>(`/api/v1/documents/${documentId}/replacement`, { method: 'PUT', body }),
  );
}

export function deleteAiDocument(documentId: number): Promise<void> {
  return api<void>(`/api/v1/documents/${documentId}`, { method: 'DELETE' });
}
