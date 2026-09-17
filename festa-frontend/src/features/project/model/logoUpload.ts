// 프로젝트 로고 파일 선택의 FE 경계. 실제 업로드 구현은 BE 계약(#241) 도착 후 이 함수만 교체한다.

export const PROJECT_LOGO_ACCEPTED_TYPES = ['image/png', 'image/jpeg', 'image/webp'] as const;
export const PROJECT_LOGO_ACCEPT = PROJECT_LOGO_ACCEPTED_TYPES.join(',');

// 서버 계약값이 아니다. 계약 전 로컬 미리보기에서 큰 파일을 읽지 않기 위한 임시 안전 상한이다.
export const PROJECT_LOGO_PREVIEW_MAX_BYTES = 5 * 1024 * 1024;
export const PROJECT_LOGO_UPLOAD_AVAILABLE: boolean = false;

export interface ProjectLogoUploadResult {
  thumbnailUrl: string;
}

export type ProjectLogoUploadAdapter = (file: File) => Promise<ProjectLogoUploadResult>;

export function validateProjectLogo(file: File): string | null {
  if (!PROJECT_LOGO_ACCEPTED_TYPES.includes(file.type as (typeof PROJECT_LOGO_ACCEPTED_TYPES)[number])) {
    return 'PNG, JPG, WebP 이미지만 선택할 수 있습니다.';
  }
  if (file.size > PROJECT_LOGO_PREVIEW_MAX_BYTES) {
    return '이미지는 5MB 이하만 선택할 수 있습니다.';
  }
  return null;
}

export const uploadProjectLogo: ProjectLogoUploadAdapter = async () => {
  throw new Error('프로젝트 로고 업로드 API가 아직 연결되지 않았습니다.');
};
