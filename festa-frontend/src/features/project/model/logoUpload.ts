// 프로젝트 로고 파일 선택·업로드의 FE 경계 — BE ProjectLogoController(#241, S15P21A604-895)의
// 3단계 계약(시작 → presigned PUT → 완료)을 이 함수 하나가 대신한다.
import { api, isApiError } from '../../../shared/api/client';

export const PROJECT_LOGO_ACCEPTED_TYPES = ['image/png', 'image/jpeg', 'image/webp'] as const;
export const PROJECT_LOGO_ACCEPT = PROJECT_LOGO_ACCEPTED_TYPES.join(',');

export const PROJECT_LOGO_PREVIEW_MAX_BYTES = 5 * 1024 * 1024;
export const PROJECT_LOGO_UPLOAD_AVAILABLE: boolean = true;

export interface ProjectLogoUploadResult {
  thumbnailUrl: string;
}

export type ProjectLogoUploadAdapter = (file: File, boothId: number) => Promise<ProjectLogoUploadResult>;

export function validateProjectLogo(file: File): string | null {
  if (!PROJECT_LOGO_ACCEPTED_TYPES.includes(file.type as (typeof PROJECT_LOGO_ACCEPTED_TYPES)[number])) {
    return 'PNG, JPG, WebP 이미지만 선택할 수 있습니다.';
  }
  if (file.size > PROJECT_LOGO_PREVIEW_MAX_BYTES) {
    return '이미지는 5MB 이하만 선택할 수 있습니다.';
  }
  return null;
}

/** 시작 응답 — presigned PUT 자리. 10분 유효이다. */
interface LogoGrant {
  logoId: string;
  expiresAt: string;
  uploadUrl: string;
  requiredContentType: string;
}

/** 완료 응답 — 검증 실패도 200 이고 status 로 갈린다. 실패한 자리는 되살아나지 않는다. */
interface LogoView {
  logoId: string;
  status: 'READY' | 'FAILED' | string;
  url: string | null;
  failureRule: string | null;
}

const FAILURE_MESSAGES: Record<string, string> = {
  MIME_NOT_ALLOWED: '지원하지 않는 이미지 형식입니다.',
  SIZE_EXCEEDED: '이미지는 5MB 이하만 업로드할 수 있습니다.',
  DIMENSION_EXCEEDED: '이미지 한 변이 4096px 이하여야 합니다.',
  DECODE_FAILED: '이미지 파일을 읽을 수 없습니다. 손상되지 않은 파일로 다시 시도해 주세요.',
  UPLOAD_MISSING: '업로드가 저장소에 도달하지 않았습니다. 다시 시도해 주세요.',
  GRANT_EXPIRED: '업로드 유효 시간(10분)이 지났습니다. 다시 시도해 주세요.',
};

/** 업로드 실패를 사용자 문장으로 바꾼다 — status·code 는 화면에 내보내지 않는다 */
export function describeLogoUploadError(error: unknown): string {
  if (isApiError(error)) {
    if (error.status === 403) return '업로드 권한이 없습니다. 부스 소유자(편집자) 계정으로 시도해 주세요.';
    if (error.code === 'PROJECT_LOGO_QUOTA_EXCEEDED') return '저장되지 않은 이미지가 너무 많습니다. 잠시 후 다시 시도해 주세요.';
    if (error.code === 'BOOTH_LEASE_EXPIRED') return '부스 임대가 만료되어 업로드할 수 없습니다.';
    if (error.status === 404 || error.status === 405) return '서버에 업로드 기능이 아직 배포되지 않았습니다. 배포 후 다시 시도해 주세요.';
  }
  if (error instanceof Error && error.message !== '') return error.message;
  return '업로드에 실패했습니다. 잠시 후 다시 시도해 주세요.';
}

/**
 * presigned PUT 은 raw fetch 로 보낸다 — 저장소는 FE 오리진 밖이고 서명에 Authorization 헤더가
 * 포함되지 않았으므로, api() 를 타면 그 헤더가 끼어들어 저장소가 403 으로 거절한다.
 */
export const uploadProjectLogo: ProjectLogoUploadAdapter = async (file, boothId) => {
  const grant = await api<LogoGrant>(`/api/v1/booths/${boothId}/project-logos`, {
    method: 'POST',
    body: JSON.stringify({ contentType: file.type, byteSize: file.size }),
  });

  const put = await fetch(grant.uploadUrl, {
    method: 'PUT',
    headers: { 'Content-Type': grant.requiredContentType },
    body: file,
  });
  if (!put.ok) throw new Error('이미지를 저장소에 올리지 못했습니다. 다시 시도해 주세요.');

  const view = await api<LogoView>(
    `/api/v1/booths/${boothId}/project-logos/${encodeURIComponent(grant.logoId)}/complete`,
    { method: 'POST' },
  );
  if (view.status !== 'READY' || view.url === null) {
    throw new Error(
      FAILURE_MESSAGES[view.failureRule ?? ''] ?? '이미지 검증에 실패했습니다. 다른 이미지로 다시 시도해 주세요.',
    );
  }
  return { thumbnailUrl: view.url };
};

