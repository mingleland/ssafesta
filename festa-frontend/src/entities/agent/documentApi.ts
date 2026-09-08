// AI 직원 문서 업로드 real API — spec 007 US2 (AiDocumentController). 2단계 계약이다:
// 1) issueUploadUrl 로 presigned URL 발급  2) 그 URL 로 브라우저가 저장소에 직접 PUT
// (Spring 을 거치지 않는다 — Authorization 헤더를 붙이지 않는다)  3) completeUpload 로 확정.
//
// 상태 조회·목록 API는 아직 없다(S15P21A604-174, 실측 확인 2026-09-08) — complete() 가 돌려주는
// processingStatus(항상 QUEUED)가 이 화면이 알 수 있는 마지막 상태다. 그 뒤 PROCESSING→READY로
// 바뀌어도 새로고침하면 다시 조회할 방법이 없다 — 이 파일이 없는 게 아니라 서버에 없다.
import { api } from '../../shared/api/client';
import type { CompleteView, UploadGrantView } from './types';

async function sha256Hex(file: File): Promise<string> {
  const buffer = await file.arrayBuffer();
  const digest = await crypto.subtle.digest('SHA-256', buffer);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

export async function issueUploadUrl(agentId: number, file: File): Promise<UploadGrantView> {
  const contentSha256 = await sha256Hex(file);
  return api<UploadGrantView>(`/api/v1/agents/${agentId}/documents/upload-url`, {
    method: 'POST',
    body: JSON.stringify({
      fileName: file.name,
      contentType: file.type || 'application/octet-stream',
      size: file.size,
      contentSha256,
    }),
  });
}

/** presigned PUT — Spring API 클라이언트(api())를 쓰지 않는다. Authorization을 붙이면 안 된다. */
export async function putToStorage(uploadUrl: string, file: File): Promise<void> {
  const response = await fetch(uploadUrl, { method: 'PUT', body: file });
  if (!response.ok) {
    throw { code: 'STORAGE_PUT_FAILED', message: '파일 업로드에 실패했습니다.', status: response.status, errors: [], warnings: [] };
  }
}

export function completeUpload(documentId: number): Promise<CompleteView> {
  return api<CompleteView>(`/api/v1/documents/${documentId}/complete`, { method: 'POST' });
}
