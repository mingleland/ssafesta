// 프로젝트 로고를 부스 소유권으로 업로드하고 서버가 검증한 경로만 편집 폼에 돌려준다 (#241).
import { api, getAccessToken } from '../../../shared/api/client';
import { apiBaseUrl } from '../../../shared/config/runtime';

export const PROJECT_LOGO_ACCEPTED_TYPES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp'] as const;
export const PROJECT_LOGO_ACCEPT = PROJECT_LOGO_ACCEPTED_TYPES.join(',');

// 실제 바이트·4096px·애니메이션 여부의 최종 판정은 complete가 담당한다.
export const PROJECT_LOGO_PREVIEW_MAX_BYTES = 5 * 1024 * 1024;

export interface ProjectLogoUploadResult {
  thumbnailUrl: string;
}

export type ProjectLogoUploadAdapter = (boothId: number, file: File) => Promise<ProjectLogoUploadResult>;

export function validateProjectLogo(file: File): string | null {
  if (!PROJECT_LOGO_ACCEPTED_TYPES.includes(file.type as (typeof PROJECT_LOGO_ACCEPTED_TYPES)[number])) {
    return 'PNG, JPG, GIF, WebP 이미지만 선택할 수 있습니다.';
  }
  if (file.size > PROJECT_LOGO_PREVIEW_MAX_BYTES) {
    return '이미지는 5MB 이하만 선택할 수 있습니다.';
  }
  return null;
}

const FAILURE_MESSAGES: Record<string, string> = {
  MIME_NOT_ALLOWED: '지원하지 않는 이미지 형식입니다.',
  SIZE_EXCEEDED: '이미지는 5MB 이하만 선택할 수 있습니다.',
  DIMENSION_EXCEEDED: '이미지의 가로와 세로는 각각 4096px 이하여야 합니다.',
  DECODE_FAILED: '이미지를 읽을 수 없습니다. 다른 파일을 선택해 주세요.',
  UPLOAD_MISSING: '업로드된 파일을 찾지 못했습니다. 다시 선택해 주세요.',
  GRANT_EXPIRED: '업로드 시간이 만료됐습니다. 다시 선택해 주세요.',
};

export const uploadProjectLogo: ProjectLogoUploadAdapter = async (boothId, file) => {
  const invalid = validateProjectLogo(file);
  if (invalid) throw new Error(invalid);
  if (!Number.isSafeInteger(boothId) || boothId <= 0) throw new Error('부스 정보를 확인해 주세요.');
  const path = `/api/v1/booths/${boothId}/project-logos`;
  const grant = await api<{ logoId: string; uploadUrl: string; requiredContentType: string }>(path, {
    method: 'POST', body: JSON.stringify({ contentType: file.type, byteSize: file.size }),
  });
  if (typeof grant.logoId !== 'string' || !/^[A-Za-z0-9_-]+$/.test(grant.logoId)
    || typeof grant.uploadUrl !== 'string' || !/^https?:\/\//.test(grant.uploadUrl)
    || typeof grant.requiredContentType !== 'string') throw new Error('업로드 응답을 확인할 수 없습니다.');
  // 서명된 저장소 요청에는 앱의 인증 헤더나 쿠키를 보내지 않는다.
  const response = await fetch(grant.uploadUrl, {
    method: 'PUT', headers: { 'Content-Type': grant.requiredContentType }, body: file,
    credentials: 'omit', signal: AbortSignal.timeout(60_000),
  });
  if (!response.ok) throw new Error('이미지를 업로드하지 못했습니다. 다시 선택해 주세요.');
  const result = await api<{ status: string; url: string | null; failureRule: string | null }>(
    `${path}/${grant.logoId}/complete`, { method: 'POST' },
  );
  if (result.status !== 'READY') {
    throw new Error(FAILURE_MESSAGES[result.failureRule ?? ''] ?? '이미지 검증에 실패했습니다. 다른 파일을 선택해 주세요.');
  }
  if (result.url !== `${path}/${grant.logoId}/content`) throw new Error('업로드한 이미지 경로가 일치하지 않습니다.');
  return { thumbnailUrl: result.url };
};

export function isManagedProjectLogo(url: string): boolean {
  return /^\/api\/v1\/booths\/[1-9]\d*\/project-logos\/[A-Za-z0-9_-]+\/content$/.test(url);
}

// 미게시 로고는 편집자 인증이 필요하다. 외부 URL에는 Bearer를 보내지 않는다.
export async function fetchProjectLogo(url: string, signal: AbortSignal): Promise<Blob> {
  if (!isManagedProjectLogo(url)) throw new Error('프로젝트 로고 경로가 아닙니다.');
  const token = getAccessToken();
  const response = await fetch(`${apiBaseUrl()}${url}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined, signal,
  });
  if (!response.ok) throw new Error('현재 프로젝트 로고를 불러오지 못했습니다.');
  return response.blob();
}
