// 부스 외부 표현(facade) 4필드 편집 폼 — Draft/Publish도 낙관적 잠금도 없이 저장 즉시 반영된다(R-11).
// editorReducer의 dirty·saveStatus·revision과 의미가 다르므로 상태를 섞지 않고 이 폼 안에서만 관리한다.
// 출처: specs/005-booth-studio-layout/FE/tasks.md T021, contracts/layout-api.md §6·§7

import { useEffect, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { isApiError } from '../../../shared/api/client';
import { facadeApi } from '../../../entities/booth/facadeApi.select';
import { FACADE_PALETTE, THEME_CODES, isPaletteColor } from '../../../entities/booth/types';
import type { BoothFacade } from '../../../entities/booth/types';

const HTTPS_URL = /^https:\/\//;

function defaultFacade(): BoothFacade {
  return { themeCode: 'DEFAULT', primaryColor: null, signText: null, logoUrl: null };
}

function isValidLogoUrl(text: string): boolean {
  return text === '' || (HTTPS_URL.test(text) && text.length <= 2048);
}

interface Props {
  boothId: number;
}

export function FacadePanel({ boothId }: Props) {
  const boothQuery = useQuery({
    queryKey: ['booth', boothId],
    queryFn: () => facadeApi.getBooth(boothId),
    enabled: Number.isFinite(boothId),
  });

  const [form, setForm] = useState<BoothFacade>(defaultFacade());
  const [hydrated, setHydrated] = useState(false);

  // 최초 로드 1회만 서버 값으로 채운다 — 매번 동기화하면 사용자가 입력 중인 값을 refetch가
  // 덮어쓸 수 있다(T026과 같은 함정). hydrated는 일부러 deps에서 뺀다 — 넣으면 하이드레이션
  // 직후 자기 자신의 변화로 effect가 재실행돼 같은 문제가 재현된다.
  useEffect(() => {
    if (boothQuery.data?.facade && !hydrated) {
      setForm(boothQuery.data.facade);
      setHydrated(true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [boothQuery.data]);

  const facadeMutation = useMutation({
    mutationFn: (body: BoothFacade) => facadeApi.putFacade(boothId, body),
    onSuccess: (result) => setForm(result),
  });

  if (boothQuery.isLoading) return null;

  const leaseExpiredOnEntry = boothQuery.data?.leaseStatus !== 'ACTIVE';
  const saveError = facadeMutation.error;
  const leaseExpiredOnSave = isApiError(saveError) && saveError.code === 'BOOTH_LEASE_EXPIRED';

  if (leaseExpiredOnEntry) {
    return <p>임대가 만료되어 이 부스의 외부 표현을 편집할 수 없습니다.</p>;
  }

  const logoText = form.logoUrl ?? '';
  // 팔레트 밖 기저장 값(팔레트 확정 전 저장분 등) — 이 상태로는 어느 필드를 고쳐 저장해도 서버가
  // primaryColor 400으로 거부한다(#17 함정: "간판만 고쳐도 400"). 안내를 띄우고 저장을 막아
  // 사용자가 12색 중 하나를 다시 고르게 한다.
  const colorOutsidePalette = form.primaryColor !== null && !isPaletteColor(form.primaryColor);
  const colorValid = !colorOutsidePalette;
  const logoValid = isValidLogoUrl(logoText);
  const signTextValid = (form.signText ?? '').length <= 60;
  const canSave = colorValid && logoValid && signTextValid && !facadeMutation.isPending;

  return (
    <div>
      <h3>외부 표현</h3>

      <label>
        테마
        <select value={form.themeCode} onChange={(e) => setForm({ ...form, themeCode: e.target.value as BoothFacade['themeCode'] })}>
          {THEME_CODES.map((code) => (
            <option key={code} value={code}>
              {code}
            </option>
          ))}
        </select>
      </label>

      {/* 자유 입력을 두지 않는다 — 팔레트 밖 hex는 서버가 400으로 거부하므로(계약 §6, PR #71)
          스와치가 12색만 내면 그 경로가 애초에 닫힌다. 색 없음은 별도 라디오. */}
      <fieldset>
        <legend>대표색</legend>
        <label>
          <input
            type="radio"
            name="primaryColor"
            checked={form.primaryColor === null}
            onChange={() => setForm({ ...form, primaryColor: null })}
          />
          없음
        </label>
        {FACADE_PALETTE.map((c) => (
          <label key={c.code}>
            <input
              type="radio"
              name="primaryColor"
              value={c.hex}
              checked={form.primaryColor?.toUpperCase() === c.hex}
              onChange={() => setForm({ ...form, primaryColor: c.hex })}
            />
            {c.label}
          </label>
        ))}
      </fieldset>
      {colorOutsidePalette && (
        <p>
          저장된 대표색({form.primaryColor})이 확정 팔레트에 없습니다. 위 12색 중 하나를 선택해야
          저장할 수 있습니다.
        </p>
      )}

      <label>
        간판 문구
        <input
          type="text"
          maxLength={60}
          value={form.signText ?? ''}
          onChange={(e) => setForm({ ...form, signText: e.target.value.trim() === '' ? null : e.target.value })}
        />
      </label>

      <label>
        로고 URL
        <input
          type="text"
          placeholder="https://"
          value={logoText}
          onChange={(e) => setForm({ ...form, logoUrl: e.target.value.trim() === '' ? null : e.target.value })}
        />
      </label>
      {!logoValid && <p>로고 URL은 https:// 형식 2048자 이하여야 합니다.</p>}

      {leaseExpiredOnSave && <p>임대가 만료되어 이 부스의 외부 표현을 편집할 수 없습니다.</p>}
      {isApiError(saveError) && !leaseExpiredOnSave && <p>{saveError.message}</p>}

      <button type="button" onClick={() => facadeMutation.mutate(form)} disabled={!canSave}>
        저장
      </button>
    </div>
  );
}
