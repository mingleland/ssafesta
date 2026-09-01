// 홈페이지 등록 mock — real 과 같은 시그니처·같은 오류 봉투. facadeApi.mock 의 getBooth 가
// getMockHomepageUrl 로 이 저장소를 읽어 BoothDetail.homepageUrl 을 채운다(단방향 의존).
import type { ApiError } from '../../shared/api/client';
import type { HomepageView } from './types';

// 기본 시나리오: booth 1 은 등록된 홈페이지가 있다(방문자 정상 경로), 나머지는 미등록(no_url 경로)
const homepages = new Map<number, string>([[1, 'https://festa.example.com']]);

function fieldError(message: string): ApiError {
  return {
    code: 'FIELD_INVALID',
    message,
    errors: [{ rule: 'FIELD_INVALID', field: 'homepageUrl', message }],
    warnings: [],
  };
}

export async function putHomepage(boothId: number, homepageUrl: string | null): Promise<HomepageView> {
  if (homepageUrl === null) {
    homepages.delete(boothId);
    return { homepageUrl: null };
  }
  // BE HttpUrlValidator 대칭 최소 재현 — http/https·2048자. 완전성 검증은 서버 몫(헌법 16조)
  if (!/^https?:\/\//.test(homepageUrl) || homepageUrl.length > 2048) {
    throw fieldError('홈페이지 주소는 http:// 또는 https:// 로 시작해야 합니다.');
  }
  homepages.set(boothId, homepageUrl);
  return { homepageUrl };
}

export function getMockHomepageUrl(boothId: number): string | null {
  return homepages.get(boothId) ?? null;
}

export function __resetHomepageMockForTests(): void {
  homepages.clear();
  homepages.set(1, 'https://festa.example.com');
}
