// 문서 업로드 진행 상태 — 이 세션에서 올린 것만 추적한다. 서버에 문서 목록·상태 조회 API가
// 아직 없어(entities/agent/documentApi.ts 상단 주석, S15P21A604-174) 새로고침하면 이 목록도
// 사라진다 — 숨기지 않고 화면에 그대로 알린다.
import { useSyncExternalStore } from 'react';
import { completeUpload, issueUploadUrl, putToStorage } from '../../../entities/agent/documentApi';
import type { DocumentProcessingStatus } from '../../../entities/agent/types';
import { isApiError } from '../../../shared/api/client';

export interface DocumentRow {
  tempId: string;
  fileName: string;
  phase: 'uploading' | 'completing' | 'done' | 'error';
  documentId?: number;
  processingStatus?: DocumentProcessingStatus;
  duplicate?: boolean;
  errorMessage?: string;
}

let rows: DocumentRow[] = [];
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getDocumentRowsSnapshot(): DocumentRow[] {
  return rows;
}

export function useDocumentRows(): DocumentRow[] {
  return useSyncExternalStore(subscribe, getDocumentRowsSnapshot);
}

function patchRow(tempId: string, patch: Partial<DocumentRow>): void {
  rows = rows.map((r) => (r.tempId === tempId ? { ...r, ...patch } : r));
  emit();
}

export async function uploadDocument(agentId: number, file: File): Promise<void> {
  const tempId = `${Date.now()}-${file.name}`;
  rows = [...rows, { tempId, fileName: file.name, phase: 'uploading' }];
  emit();

  try {
    const grant = await issueUploadUrl(agentId, file);
    if (grant.duplicate) {
      patchRow(tempId, { phase: 'done', documentId: grant.documentId, duplicate: true, processingStatus: 'READY' });
      return;
    }
    if (grant.uploadUrl === null) {
      patchRow(tempId, { phase: 'error', errorMessage: '업로드 URL을 받지 못했습니다.' });
      return;
    }
    await putToStorage(grant.uploadUrl, file);
    patchRow(tempId, { phase: 'completing', documentId: grant.documentId });
    const completed = await completeUpload(grant.documentId);
    patchRow(tempId, { phase: 'done', processingStatus: completed.processingStatus });
  } catch (e) {
    const message = isApiError(e) ? e.message : '업로드에 실패했습니다.';
    patchRow(tempId, { phase: 'error', errorMessage: message });
  }
}

export function __resetDocumentRowsForTests(): void {
  rows = [];
  listeners.clear();
}
