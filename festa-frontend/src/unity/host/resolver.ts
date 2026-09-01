// 프로덕션 빌드 URL 해석 — manifest.json 방향과 키 4종(loaderUrl/dataUrl/frameworkUrl/codeUrl)은
// Unity 담당 확인으로 확정됐다(#60, 2026-08-22). 남은 미결은 생성 위치(로컬 빌드 vs Jenkins)·
// 배포 경로·캐시 헤더뿐이며 이는 #30(인프라 재결정) 후속이다 — 이 함수의 반환 형태는 안 바뀐다.
// Host는 이 파일이 UnityBuildDescriptor 4종만 내주면 나머지는 모른다.

import type { UnityBuildDescriptor } from './types';

const BUILD_BASE = import.meta.env.VITE_UNITY_BUILD_BASE ?? '';

export async function resolveBuildDescriptor(): Promise<UnityBuildDescriptor> {
  // 미설정이면 `/manifest.json`(앱 자기 자신)을 읽다가 HTML 파싱 오류로 죽는다 — 원인을 이름으로 말한다(-341)
  if (BUILD_BASE === '') {
    throw new Error('VITE_UNITY_BUILD_BASE가 설정되지 않았습니다 — Unity 빌드 서빙 경로를 지정해주세요');
  }
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
