// 관리창의 게시 계층 (S15P21A604-898). 조회(useBoothPublicationState)와 실행(useBoothPublish)을
// 갈라 둔다 — 상태는 캐시에서 파생되고, mutation 은 상태를 읽지 않는다.
//
// BE 는 그대로다. Studio 가 쓰던 draft → publish → publishedLayoutVersion 계약을 관리창이 "게시"
// 한 번으로 감싼다. 사용자는 draft·revision·version 을 보지 않는다.
//
// 실패해도 운영본은 남는다 — publish 는 서버 트랜잭션이라 검증 실패·409 에서 published 포인터가
// 움직이지 않는다. FE 도 실패 경로에서 캐시를 건드리지 않는다.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { AiAgent } from '../../../entities/aiAgent/api';
import { LAYOUT_NOT_PUBLISHED, LAYOUT_REVISION_CONFLICT } from '../../../entities/booth/layoutApi';
import type { PublishedLayout } from '../../../entities/booth/layoutApi';
import { layoutApi } from '../../../entities/booth/layoutApi.select';
import type { BoothDetail } from '../../../entities/booth/types';
import { isApiError } from '../../../shared/api/client';
import { notifyBoothSlotChanged } from '../../../unity/host/boothLayoutBridge';
import { compose, fromLayout, isUpToDate, toLayout } from './presentationComposer';
import type { Presentation } from './presentationComposer';

export type PublicationPhase =
  /** 프로젝트가 없어 게시할 수 없다 — 다음 행동은 프로젝트 등록 */
  | 'no-project'
  /** 프로젝트는 있고 아직 한 번도 공개되지 않았다 — 다음 행동은 게시 */
  | 'ready'
  /** 공개 중이고 게시본이 현재 presentation 과 같다 — 할 일 없음 */
  | 'live'
  /** 공개 중인데 presentation 이 달라졌다 — 다음 행동은 변경사항 적용 */
  | 'live-outdated';

export interface PublicationState {
  phase: PublicationPhase;
  /** 판정에 필요한 조회가 아직 끝나지 않았다. 버튼을 그리지 않는 근거 */
  pending: boolean;
  current: Presentation;
}

export const publishedLayoutQueryKey = (boothId: number) => ['booth-published-layout', boothId] as const;

/** 404 LAYOUT_NOT_PUBLISHED 는 오류가 아니라 "미게시" 라는 사실이다 — null 로 정규화 */
async function fetchPublishedOrNull(boothId: number): Promise<PublishedLayout | null> {
  try {
    return await layoutApi.getPublishedLayout(boothId);
  } catch (error) {
    if (isApiError(error) && error.code === LAYOUT_NOT_PUBLISHED) return null;
    throw error;
  }
}

interface StateInputs {
  boothId: number | null;
  detail: BoothDetail | null | undefined;
  hasProject: boolean | undefined;
  aiAgent: AiAgent | null | undefined;
}

export function useBoothPublicationState({ boothId, detail, hasProject, aiAgent }: StateInputs): PublicationState {
  const published = detail?.publishedLayoutVersion != null;
  const publishedQuery = useQuery({
    queryKey: publishedLayoutQueryKey(boothId ?? -1),
    queryFn: () => fetchPublishedOrNull(boothId as number),
    // 미게시면 404 가 확정이라 묻지 않는다. detail 이 오기 전에도 묻지 않는다
    enabled: boothId !== null && published,
  });

  const publishedPresentation = publishedQuery.data ? fromLayout(publishedQuery.data) : null;
  const current = compose({ aiAgent: aiAgent ?? null, published: publishedPresentation });
  const pending =
    detail === undefined || hasProject === undefined || aiAgent === undefined || (published && publishedQuery.data === undefined);

  let phase: PublicationPhase;
  if (!hasProject) phase = 'no-project';
  else if (!published) phase = 'ready';
  else if (publishedPresentation === null) phase = 'live'; // 조회 실패·미도착 — 낙관적으로 두고 pending 이 버튼을 막는다
  else phase = isUpToDate(publishedPresentation, current) ? 'live' : 'live-outdated';

  return { phase, pending, current };
}

export interface PublishArgs {
  boothId: number;
  slotId: number;
  presentation: Presentation;
}

export function isRevisionConflict(error: unknown): boolean {
  return isApiError(error) && error.code === LAYOUT_REVISION_CONFLICT;
}

export function useBoothPublish(onDone?: () => void) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ boothId, presentation }: PublishArgs) => {
      // 최초 게시는 0, 이후는 서버가 가진 revision. 매번 다시 읽는다 — 409 재시도가 여기서 자동 복구된다
      const draft = await layoutApi.getDraft(boothId);
      const saved = await layoutApi.putDraft(boothId, {
        expectedRevision: draft?.revision ?? 0,
        ...toLayout(presentation),
      });
      const result = await layoutApi.publishLayout(boothId);
      return { saved, result };
    },
    onSuccess: ({ saved, result }, { boothId, slotId }) => {
      // 응답값으로 먼저 채우고 재조회로 확정한다 — 화면이 "게시 → 운영 중" 으로 바로 넘어간다
      queryClient.setQueryData<BoothDetail>(['booth-detail', boothId], (prev) =>
        prev ? { ...prev, publishedLayoutVersion: result.publishedVersion } : prev,
      );
      const next: PublishedLayout = {
        boothId,
        version: result.publishedVersion,
        schemaVersion: saved.schemaVersion,
        template: saved.template,
        objects: saved.objects,
      };
      queryClient.setQueryData<PublishedLayout | null>(publishedLayoutQueryKey(boothId), next);
      void queryClient.invalidateQueries({ queryKey: ['booth-detail', boothId] });
      void queryClient.invalidateQueries({ queryKey: publishedLayoutQueryKey(boothId) });
      // 상주 월드가 이 슬롯 게시본을 다시 읽게 한다 (-644 seam). 월드 밖이면 다음 진입이 읽는다
      notifyBoothSlotChanged(slotId);
      onDone?.();
    },
    onError: (error: unknown, { boothId }) => {
      // 409 는 누군가 먼저 저장했다는 뜻 — 다음 시도가 최신 revision 을 읽도록 draft 캐시만 비운다.
      // 여기서 재시도하지 않는다. 사용자가 다시 누르면 mutationFn 첫 줄이 다시 읽는다
      if (isRevisionConflict(error)) {
        void queryClient.invalidateQueries({ queryKey: publishedLayoutQueryKey(boothId) });
      }
    },
  });
}
