// StudioPage의 파생 판정(오류 분류·lease 상태·공개 전 미리보기)을 한 곳에 모은 훅.
// StudioPage가 조립·렌더에 집중하고 "무엇이 막혔는가" 계산은 여기서 한다.
// 출처: 2026-08-22 구조 진단 Y-3(작업일지) — 확장(#20 Game Studio 배선) 전 게이트 로직 분리

import { useMemo } from 'react';
import { isApiError } from '../../../shared/api/client';
import type { ApiErrorDetail } from '../../../shared/api/client';
import { passageWarnings } from '../../../entities/layout/passage';
import type { EditorState } from './editorReducer';
import { precheckErrors, precheckWarnings } from '../lib/validate';

// draftQuery·save·publish 세 경로 어디서든 BOOTH_LEASE_EXPIRED가 뜰 수 있다(만료된 부스에 진입·저장·공개 시도).
// 편집을 전부 막는 게 목적이라 한 곳에서 판정한다(T018).
function isLeaseExpired(...errors: unknown[]): boolean {
  return errors.some((e) => isApiError(e) && e.code === 'BOOTH_LEASE_EXPIRED');
}

interface Params {
  state: EditorState;
  draftError: unknown;
  saveError: unknown;
  publishError: unknown;
  publishData: { warnings: ApiErrorDetail[] } | undefined;
  publishIsSuccess: boolean;
  publishIsError: boolean;
  maxObjects: number;
  bounds: { width: number; depth: number; height: number };
}

export function useStudioGates({
  state,
  draftError,
  saveError,
  publishError,
  publishData,
  publishIsSuccess,
  publishIsError,
  maxObjects,
  bounds,
}: Params) {
  // 통행 판정(§10-3)은 120×120 래스터라 다른 렌더(선택·입력 중 텍스트 변경)마다 다시 돌리지 않는다 —
  // 12개 규모라 배치가 실제로 바뀔 때만 재계산해도 충분하다(계약 명시, T023).
  const passage = useMemo(() => passageWarnings(state.objects), [state.objects]);

  const draftLeaseExpired = isLeaseExpired(draftError);
  const leaseExpired = isLeaseExpired(saveError, publishError);

  // LAYOUT_VALIDATION_FAILED 등 conflict가 아닌 저장 실패의 상세 — conflict는 StudioPage가 별도 안내로 처리(T018)
  const saveErrorDetails: ApiErrorDetail[] =
    isApiError(saveError) && saveError.code !== 'LAYOUT_REVISION_CONFLICT' ? saveError.errors : [];

  const publishErrors: ApiErrorDetail[] = isApiError(publishError) ? publishError.errors : [];
  const publishWarnings = publishData?.warnings ?? [];
  const showPublishResult = publishIsSuccess || publishIsError;

  // 공개 요청 전 미리보기 — 서버 응답이 오면(showPublishResult) 그 값으로 교체된다(T016)
  const preErrors = precheckErrors(state.objects, maxObjects, bounds);
  const preWarnings = [...precheckWarnings(state.objects), ...passage];

  return {
    draftLeaseExpired,
    leaseExpired,
    saveErrorDetails,
    publishErrors,
    publishWarnings,
    showPublishResult,
    preErrors,
    preWarnings,
  };
}
