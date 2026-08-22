// Draft 저장·Publish 요청을 react-query mutation으로 감싸는 훅 — 409/충돌 처리를 한 곳에 둔다
// 출처: specs/005-booth-studio-layout/FE/plan.md, contracts/layout-api.md §3·§4

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { isApiError } from '../../../shared/api/client';
import { layoutApi } from '../../../entities/layout/api.select';
import type { LayoutObject } from '../../../entities/layout/types';
import type { EditorAction } from './editorReducer';

interface SaveArgs {
  boothId: number;
  expectedRevision: number;
  template: string;
  objects: LayoutObject[];
}

export function useSaveDraft(dispatch: (action: EditorAction) => void) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ boothId, expectedRevision, template, objects }: SaveArgs) =>
      layoutApi.putDraft(boothId, { expectedRevision, schemaVersion: 1, template, objects }),
    onMutate: () => dispatch({ type: 'SAVE_START' }),
    onSuccess: (result, variables) => {
      dispatch({ type: 'SAVE_SUCCESS', revision: result.revision });
      // 성공했을 때만 캐시를 무효화한다 — 실패(특히 409 conflict) 시에도 무효화하면
      // 자동 refetch가 LOAD_DRAFT를 다시 태워 conflict 상태와 사용자의 미저장 변경분을
      // 아무 경고 없이 서버본으로 덮어써 버린다. 재로드는 사용자가 명시적으로 눌러야 한다(StudioPage).
      queryClient.invalidateQueries({ queryKey: ['layout-draft', variables.boothId] });
      return result;
    },
    onError: (error) => {
      // 409 REVISION_CONFLICT는 revision을 파싱하지 않는다 — 숫자가 한글 메시지 안에만 있다(계약서 §3 인용 블록).
      // GET /draft 재호출이 유일한 안전 경로다. 여기서는 conflict 상태만 세팅하고, 재로드는 호출부(StudioPage)가 한다.
      if (isApiError(error) && error.code === 'LAYOUT_REVISION_CONFLICT') {
        dispatch({ type: 'SAVE_CONFLICT' });
      } else {
        dispatch({ type: 'SAVE_ERROR' });
      }
    },
  });
}

export function usePublish(dispatch: (action: EditorAction) => void) {
  return useMutation({
    mutationFn: (boothId: number) => layoutApi.publish(boothId),
    onSuccess: (result) => {
      dispatch({ type: 'PUBLISH_SUCCESS', publishedVersion: result.publishedVersion });
      return result;
    },
  });
}
