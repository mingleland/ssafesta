// 부스 외부 표현(facade) 4필드 편집 폼 — Draft/Publish도 낙관적 잠금도 없이 저장 즉시 반영된다(R-11).
// editorReducer의 dirty·saveStatus·revision과 의미가 다르므로 상태를 섞지 않고 이 폼 안에서만 관리한다.
// 출처: specs/005-booth-studio-layout/FE/tasks.md T021, contracts/layout-api.md §6·§7
// 표현은 Booth Studio Inspector(외관 모드) — 구조(fieldset·label·role=alert 위치)는 -86 회귀 테스트가 고정한다.

import { useEffect, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { isApiError } from '../../../shared/api/client';
import { facadeApi } from '../../../entities/booth/facadeApi.select';
import { FACADE_PALETTE, THEME_CODES, isPaletteColor } from '../../../entities/booth/types';
import type { BoothFacade } from '../../../entities/booth/types';
import { IcSave } from './shell/icons';

const HTTPS_URL = /^https:\/\//;

const THEME_LABEL: Record<string, string> = { DEFAULT: '기본', SSAFY_BLUE: 'SSAFY 블루', WARM: '웜', MONO: '모노' };

function defaultFacade(): BoothFacade {
  return { themeCode: 'DEFAULT', primaryColor: null, signText: null, logoUrl: null };
}

function isValidLogoUrl(text: string): boolean {
  return text === '' || (HTTPS_URL.test(text) && text.length <= 2048);
}

// 봉투의 errors[]는 field로 위반 지점을 가리킨다(docs/08 §1.3-1, contracts/layout-api.md §6).
function fieldMessage(error: unknown, field: string): string | null {
  if (!isApiError(error)) return null;
  return error.errors.find((detail) => detail.field === field)?.message ?? null;
}

interface Props {
  boothId: number;
  /** 폼 값이 바뀔 때마다 알린다 — 캔버스 미리보기용(선택) */
  onChange?: (form: BoothFacade) => void;
  /** 템플릿 프리셋을 폼에 적용(선택). 값이 바뀔 때만 반영 */
  preset?: { themeCode: BoothFacade['themeCode']; primaryColor: string } | null;
}

export function FacadePanel({ boothId, onChange, preset }: Props) {
  const boothQuery = useQuery({
    queryKey: ['booth', boothId],
    queryFn: () => facadeApi.getBooth(boothId),
    enabled: Number.isFinite(boothId),
  });

  const [form, setForm] = useState<BoothFacade>(defaultFacade());
  const [hydrated, setHydrated] = useState(false);

  // 최초 로드 1회만 서버 값으로 채운다 — 매번 동기화하면 사용자가 입력 중인 값을 refetch가 덮어쓸 수 있다(T026과 같은 함정).
  useEffect(() => {
    if (boothQuery.data?.facade && !hydrated) {
      setForm(boothQuery.data.facade);
      setHydrated(true);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [boothQuery.data]);

  useEffect(() => {
    if (preset) setForm((f) => ({ ...f, themeCode: preset.themeCode, primaryColor: preset.primaryColor }));
  }, [preset]);

  useEffect(() => {
    onChange?.(form);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [form]);

  const facadeMutation = useMutation({
    mutationFn: (body: BoothFacade) => facadeApi.putFacade(boothId, body),
    onSuccess: (result) => setForm(result),
  });

  if (boothQuery.isLoading) return null;

  const leaseExpiredOnEntry = boothQuery.data?.leaseStatus !== 'ACTIVE';
  const saveError = facadeMutation.error;
  const leaseExpiredOnSave = isApiError(saveError) && saveError.code === 'BOOTH_LEASE_EXPIRED';

  if (leaseExpiredOnEntry) {
    return <p className="studio-alert">임대가 만료되어 이 부스의 외부 표현을 편집할 수 없습니다.</p>;
  }

  const logoText = form.logoUrl ?? '';
  const colorOutsidePalette = form.primaryColor !== null && !isPaletteColor(form.primaryColor);
  const colorValid = !colorOutsidePalette;
  const logoValid = isValidLogoUrl(logoText);
  const signTextValid = (form.signText ?? '').length <= 60;
  const canSave = colorValid && logoValid && signTextValid && !facadeMutation.isPending;

  const themeCodeError = fieldMessage(saveError, 'themeCode');
  const primaryColorError = fieldMessage(saveError, 'primaryColor');
  const signTextError = fieldMessage(saveError, 'signText');
  const logoUrlError = fieldMessage(saveError, 'logoUrl');
  const shownAsFieldError = themeCodeError !== null || primaryColorError !== null || signTextError !== null || logoUrlError !== null;

  return (
    <div>
      <div className="studio-group">
        <h3 className="studio-group-title">외부 표현</h3>
        <label className="studio-field-label" htmlFor="facade-theme">테마</label>
        <span className="studio-input">
          <select id="facade-theme" value={form.themeCode} onChange={(e) => setForm({ ...form, themeCode: e.target.value as BoothFacade['themeCode'] })}>
            {THEME_CODES.map((code) => (
              <option key={code} value={code}>
                {THEME_LABEL[code] ?? code}
              </option>
            ))}
          </select>
        </span>
        {themeCodeError !== null && <p className="studio-alert" role="alert">{themeCodeError}</p>}
      </div>

      <div className="studio-group">
        {/* 자유 입력을 두지 않는다 — 팔레트 밖 hex는 서버가 400으로 거부하므로(계약 §6, PR #71) 스와치가 12색만 낸다. */}
        <fieldset style={{ border: 'none', margin: 0, padding: 0 }}>
          <legend className="studio-group-title">대표색</legend>
          <div className="studio-swatches">
            <label className="studio-swatch studio-swatch-none" title="없음" aria-checked={form.primaryColor === null} role="radio">
              <input type="radio" name="primaryColor" checked={form.primaryColor === null} onChange={() => setForm({ ...form, primaryColor: null })} style={{ position: 'absolute', opacity: 0, width: 0, height: 0 }} />
              <span style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)' }}>없음</span>
            </label>
            {FACADE_PALETTE.map((c) => (
              <label key={c.code} className="studio-swatch" title={c.label} style={{ background: c.hex }} aria-checked={form.primaryColor?.toUpperCase() === c.hex} role="radio">
                <input type="radio" name="primaryColor" value={c.hex} checked={form.primaryColor?.toUpperCase() === c.hex} onChange={() => setForm({ ...form, primaryColor: c.hex })} style={{ position: 'absolute', opacity: 0, width: 0, height: 0 }} />
                <span style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)' }}>{c.label}</span>
              </label>
            ))}
          </div>
        </fieldset>
        {colorOutsidePalette && (
          <p className="studio-alert">
            저장된 대표색({form.primaryColor})이 확정 팔레트에 없습니다. 위 12색 중 하나를 선택해야 저장할 수 있습니다.
          </p>
        )}
        {primaryColorError !== null && <p className="studio-alert" role="alert">{primaryColorError}</p>}
      </div>

      <div className="studio-group">
        {/* label 이 입력을 감싼다 — field 오류 <p> 의 previousElementSibling 이 "간판 문구" 텍스트를 가져야 한다(-86 회귀 테스트) */}
        <label>
          <span className="studio-field-label">간판 문구</span>
          <span className="studio-input">
            <input type="text" maxLength={60} value={form.signText ?? ''} placeholder="부스 간판에 표시" onChange={(e) => setForm({ ...form, signText: e.target.value.trim() === '' ? null : e.target.value })} />
          </span>
        </label>
        {signTextError !== null && <p className="studio-alert" role="alert">{signTextError}</p>}

        <label>
          <span className="studio-field-label">로고 URL</span>
          <span className="studio-input">
            <input type="text" placeholder="https://" value={logoText} onChange={(e) => setForm({ ...form, logoUrl: e.target.value.trim() === '' ? null : e.target.value })} />
          </span>
        </label>
        {!logoValid && <p className="studio-note">로고 URL은 https:// 형식 2048자 이하여야 합니다.</p>}
        {logoUrlError !== null && <p className="studio-alert" role="alert">{logoUrlError}</p>}
      </div>

      {leaseExpiredOnSave && <p className="studio-alert">임대가 만료되어 이 부스의 외부 표현을 편집할 수 없습니다.</p>}
      {isApiError(saveError) && !leaseExpiredOnSave && !shownAsFieldError && <p className="studio-alert">{saveError.message}</p>}

      <div className="studio-inspector-foot">
        <button type="button" className="studio-btn studio-btn-primary" onClick={() => facadeMutation.mutate(form)} disabled={!canSave}>
          <IcSave size={15} /> 저장
        </button>
        <p className="studio-note">외관은 저장 즉시 반영됩니다(게시 불필요).</p>
      </div>
    </div>
  );
}
