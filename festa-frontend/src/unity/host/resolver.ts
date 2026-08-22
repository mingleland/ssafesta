// 프로덕션 빌드 URL 해석 — manifest.json 스키마는 Unity 측 미확정 계약이다(013a Host 계획 B-1).
// Host는 이 파일이 UnityBuildDescriptor 4종만 내주면 나머지는 모른다 — manifest 구조가
// 바뀌어도 이 함수 내부만 고치면 된다. resolver 계약 확정은 Unity 담당 승인 후.

import type { UnityBuildDescriptor } from './types';

const BUILD_BASE = import.meta.env.VITE_UNITY_BUILD_BASE ?? '';

export async function resolveBuildDescriptor(): Promise<UnityBuildDescriptor> {
  const response = await fetch(`${BUILD_BASE}/manifest.json`);
  if (!response.ok) {
    throw new Error(`Unity manifest.json을 불러오지 못했습니다 (${response.status})`);
  }

  const manifest = (await response.json()) as Partial<UnityBuildDescriptor>;
  const { loaderUrl, dataUrl, frameworkUrl, codeUrl } = manifest;
  if (!loaderUrl || !dataUrl || !frameworkUrl || !codeUrl) {
    throw new Error('Unity manifest.json에 필요한 빌드 URL이 없습니다');
  }

  return { loaderUrl, dataUrl, frameworkUrl, codeUrl };
}
