// 프로덕션 빌드 URL 해석 — manifest.json 방향과 키 4종(loaderUrl/dataUrl/frameworkUrl/codeUrl)은
// Unity 담당 확인으로 확정됐다(#60, 2026-08-22). base 는 런타임 주입값(shared/config/runtime.ts unityBuildBase)
// 을 호출 시점에 읽는다 — 모듈 상수로 고정하면 runtime-config.js 가 먼저 로드돼도 빌드 타임 값이 남는다.
// manifest 의 URL 4종은 상대경로(Build/<hash>.loader.js)라 base 기준으로 절대화한다(#128 §4) — 그대로
// script.src 에 넣으면 FE 오리진 기준으로 풀려 base 가 다른 오리진·하위 경로면 404 다.
// Host는 이 파일이 UnityBuildDescriptor 4종만 내주면 나머지는 모른다.

import { unityBuildBase } from '../../shared/config/runtime';
import { normalizeAssetBase, resolveAssetUrl } from '../../shared/assets/resolveAssetUrl';
import type { UnityBuildDescriptor } from './types';

export async function resolveBuildDescriptor(): Promise<UnityBuildDescriptor> {
  const configured = unityBuildBase();
  // 미설정이면 `/manifest.json`(앱 자기 자신)을 읽다가 HTML 파싱 오류로 죽는다 — 원인을 이름으로 말한다(-341)
  if (configured === '') {
    throw new Error('Unity 빌드 서빙 경로가 설정되지 않았습니다 — PUBLIC_UNITY_BUILD_BASE(런타임) 또는 VITE_UNITY_BUILD_BASE(빌드)를 지정해주세요');
  }
  const base = normalizeAssetBase(configured);
  const response = await fetch(resolveAssetUrl('manifest.json', base));
  if (!response.ok) {
    throw new Error(`Unity manifest.json을 불러오지 못했습니다 (${response.status})`);
  }

  const manifest = (await response.json()) as Partial<UnityBuildDescriptor>;
  const { loaderUrl, dataUrl, frameworkUrl, codeUrl } = manifest;
  if (!loaderUrl || !dataUrl || !frameworkUrl || !codeUrl) {
    throw new Error('Unity manifest.json에 필요한 빌드 URL이 없습니다');
  }

  return {
    loaderUrl: resolveAssetUrl(loaderUrl, base),
    dataUrl: resolveAssetUrl(dataUrl, base),
    frameworkUrl: resolveAssetUrl(frameworkUrl, base),
    codeUrl: resolveAssetUrl(codeUrl, base),
  };
}
