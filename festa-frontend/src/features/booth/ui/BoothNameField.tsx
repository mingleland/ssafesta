// 부스 이름 인라인 편집 — 스튜디오 외관 패널에 있던 이름 편집만 관리창으로 옮겼다 (S15P21A604-817).
// 정책은 -786 그대로: trim, 비었거나 안 바뀌면 보내지 않는다, 1~100자.
//
// PUT /booths/{id}/facade 는 facade 4필드 전체를 받으므로 facade 를 확보하기 전에는 편집을 열지
// 않고, 본문은 mutation 직전에 캐시에서 다시 읽는다 — 렌더 시점 클로저의 낡은 facade 로 대표색·
// 간판·로고를 옛 값으로 덮어쓰는 길을 막는다.
import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { isApiError } from '../../../shared/api/client';
import { facadeApi } from '../../../entities/booth/facadeApi.select';
import type { BoothDetail } from '../../../entities/booth/types';
import { notifyCurrentBoothSlotChanged } from '../../../unity/host/boothLayoutBridge';

const NAME_MAX = 100;

const IcPencil = (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z" />
  </svg>
);

interface Props {
  boothId: number;
  initialName: string;
  /** facade 가 캐시에 들어온 뒤에만 true — 그 전에는 입력이 잠긴다 */
  ready: boolean;
}

export function BoothNameField({ boothId, initialName, ready }: Props) {
  const queryClient = useQueryClient();
  const [name, setName] = useState(initialName);
  const [saved, setSaved] = useState(initialName);

  const mutation = useMutation({
    mutationFn: (trimmed: string) => {
      const facade = queryClient.getQueryData<BoothDetail>(['booth-detail', boothId])?.facade;
      if (!facade) return Promise.reject(new Error('facade not loaded'));
      return facadeApi.putFacade(boothId, { ...facade, name: trimmed });
    },
    onSuccess: (_result, trimmed) => {
      setSaved(trimmed);
      void queryClient.invalidateQueries({ queryKey: ['booth-detail', boothId] });
      void queryClient.invalidateQueries({ queryKey: ['my-booth'] });
      // 간판 라벨은 facade.signText → boothName 순이라 이름 변경도 RequestReload 로 반영된다(bridge 주석)
      notifyCurrentBoothSlotChanged(queryClient);
    },
  });

  function save() {
    const trimmed = name.trim();
    if (trimmed === '' || trimmed === saved) return;
    mutation.mutate(trimmed);
  }

  const error = mutation.error;
  const message = isApiError(error)
    ? (error.errors.find((d) => d.field === 'name')?.message ?? error.message)
    : error
      ? '이름을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.'
      : null;

  return (
    <form
      className="bm-name-field"
      onSubmit={(e) => {
        e.preventDefault();
        save();
      }}
    >
      <label htmlFor="bm-name-input">부스 이름</label>
      <span className="bm-name-input">
        <input
          id="bm-name-input"
          type="text"
          maxLength={NAME_MAX}
          value={name}
          disabled={!ready || mutation.isPending}
          onChange={(e) => setName(e.target.value)}
        />
        <button type="submit" aria-label="부스 이름 저장" disabled={!ready || mutation.isPending}>
          {IcPencil}
        </button>
      </span>
      {message !== null && (
        <span className="bm-name-error" role="alert">
          {message}
        </span>
      )}
    </form>
  );
}
