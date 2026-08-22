// 부스 외부 표현(facade) 4필드 편집 폼 — Draft/Publish도 낙관적 잠금도 없이 저장 즉시 반영된다(R-11).
// editorReducer의 dirty·saveStatus·revision과 의미가 다르므로 상태를 섞지 않고 이 폼 안에서만 관리한다.
// 출처: specs/005-booth-studio-layout/FE/tasks.md T021, contracts/layout-api.md §6·§7

import { useEffect, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { isApiError } from '../../../shared/api/client';
import * as realApi from '../../../entities/booth/facadeApi';
import * as mockApi from '../../../entities/booth/facadeApi.mock';
import { THEME_CODES } from '../../../entities/booth/types';
import type { BoothFacade } from '../../../entities/booth/types';

const facadeApi = import.meta.env.VITE_USE_MOCK === 'true' ? mockApi : realApi;

const HEX_RRGGBB = /^#[0-9A-Fa-f]{6}$/;
const HTTPS_URL = /^https:\/\//;

function defaultFacade(): BoothFacade {
  return { themeCode: 'DEFAULT', primaryColor: null, signText: null, logoUrl: null };
}

function isValidColor(text: string): boolean {
  return text === '' || HEX_RRGGBB.test(text);
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

  const colorText = form.primaryColor ?? '';
  const logoText = form.logoUrl ?? '';
  const colorValid = isValidColor(colorText);
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

      <label>
        대표색
        <input
          type="text"
          placeholder="#RRGGBB"
          value={colorText}
          onChange={(e) => setForm({ ...form, primaryColor: e.target.value.trim() === '' ? null : e.target.value })}
        />
      </label>
      {!colorValid && <p>대표색은 #RRGGBB 형식이어야 합니다.</p>}

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
