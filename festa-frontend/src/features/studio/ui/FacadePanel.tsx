// 부스 외부 표현(facade) 4필드 편집 폼 — Draft/Publish도 낙관적 잠금도 없이 저장 즉시 반영된다(R-11).
// editorReducer의 dirty·saveStatus·revision과 의미가 다르므로 상태를 섞지 않고 이 폼 안에서만 관리한다.
// 출처: specs/005-booth-studio-layout/FE/tasks.md T021, contracts/layout-api.md §6·§7
// 표현은 Booth Studio Inspector(외관 모드) — 구조(fieldset·label·role=alert 위치)는 -86 회귀 테스트가 고정한다.

import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { isApiError } from '../../../shared/api/client';
import { facadeApi } from '../../../entities/booth/facadeApi.select';
import { FACADE_PALETTE, THEME_CODES, isPaletteColor } from '../../../entities/booth/types';
import type { BoothFacade, FacadePutRequest } from '../../../entities/booth/types';
import { notifyCurrentBoothSlotChanged } from '../../../unity/host/boothLayoutBridge';
import { IcSave } from './shell/icons';
import { Tooltip } from '../../../shared/ui/tooltip/Tooltip';

const HTTPS_URL = /^https:\/\//;
const NAME_MAX = 100; // 계약 §6 — 1~100자

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
  const queryClient = useQueryClient();
  const boothQuery = useQuery({
    queryKey: ['booth', boothId],
    queryFn: () => facadeApi.getBooth(boothId),
    enabled: Number.isFinite(boothId),
  });

  const [form, setForm] = useState<BoothFacade>(defaultFacade());
  // 이름은 form(BoothFacade) 과 **분리해서** 든다 (S15P21A604-786).
  //
  // 응답은 facade 4필드뿐이라 `setForm(result)` 가 name 을 훑고 지나간다. 같은 객체에 얹으면
  // 저장 성공 직후 화면의 이름이 undefined 로 사라진다 — 상태를 가르면 그 사고가 구조적으로 불가능하다.
  // `initialName` 은 "안 바뀌었으면 보내지 않는다" 판정의 기준이다.
  const [name, setName] = useState('');
  const [initialName, setInitialName] = useState('');
  const [hydrated, setHydrated] = useState(false);

  // 최초 로드 1회만 서버 값으로 채운다 — 매번 동기화하면 사용자가 입력 중인 값을 refetch가 덮어쓸 수 있다(T026과 같은 함정).
  useEffect(() => {
    if (boothQuery.data?.facade && !hydrated) {
      setForm(boothQuery.data.facade);
      // 이름은 facade 가 아니라 BoothDetail 에 있다 — 같은 1회 수화에서 함께 읽는다.
      setName(boothQuery.data.name ?? '');
      setInitialName(boothQuery.data.name ?? '');
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
    mutationFn: (body: FacadePutRequest) => facadeApi.putFacade(boothId, body),
    onSuccess: (result, body) => {
      setForm(result);
      // 응답에 name 이 없으므로 보낸 값을 기준으로 옮긴다 — 다음 저장에서 또 보내지 않기 위해서다.
      if (body.name !== undefined) setInitialName(body.name);
      queryClient.invalidateQueries({ queryKey: ['booth', boothId] });
      // 이름만 특별 취급하지 않는다 — signText·대표색도 지금까지 저장 후 월드에 반영되지 않았다.
      // 간판·외벽은 RequestReload 가 레이아웃 서명과 무관하게 매번 다시 읽는다(bridge 주석).
      notifyCurrentBoothSlotChanged(queryClient);
    },
  });

  /**
   * 요청 본문 — **입력 정규화는 FE 가 담당한다** (S15P21A604-786, GitLab #171 통보).
   *
   * 계약상 `name` 생략은 "현재 이름 유지"라, 비었거나 안 바뀌었으면 키를 아예 넣지 않는다.
   * 앞뒤 공백을 FE 가 지우는 이유는 BE 검증이 1자 이상이라 **공백 한 칸짜리 이름이 통과**하기
   * 때문이다. BE 가 서버 정규화를 택하면 이쪽을 걷는다.
   */
  function buildBody(): FacadePutRequest {
    const trimmed = name.trim();
    if (trimmed === '' || trimmed === initialName) return form;
    return { ...form, name: trimmed };
  }

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
  const nameValid = name.trim().length <= NAME_MAX;
  const canSave = colorValid && logoValid && signTextValid && nameValid && !facadeMutation.isPending;

  const nameError = fieldMessage(saveError, 'name');
  const themeCodeError = fieldMessage(saveError, 'themeCode');
  const primaryColorError = fieldMessage(saveError, 'primaryColor');
  const signTextError = fieldMessage(saveError, 'signText');
  const logoUrlError = fieldMessage(saveError, 'logoUrl');
  const shownAsFieldError =
    nameError !== null || themeCodeError !== null || primaryColorError !== null || signTextError !== null || logoUrlError !== null;

  return (
    <div>
      <div className="studio-group">
        <h3 className="studio-group-title">부스 이름</h3>
        <label>
          <span className="studio-field-label">이름</span>
          <span className="studio-input">
            <input
              type="text"
              maxLength={NAME_MAX}
              value={name}
              placeholder="월드 간판과 목록에 표시"
              onChange={(e) => setName(e.target.value)}
            />
          </span>
        </label>
        {nameError !== null && <p className="studio-alert" role="alert">{nameError}</p>}
        <p className="studio-note">비워 두면 현재 이름을 그대로 둡니다.</p>
      </div>

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
            {/* 스와치는 색만 보여 준다 — 이름은 툴팁과 숨은 라벨 둘 다에 남는다 */}
            <Tooltip content="없음">
              <label className="studio-swatch studio-swatch-none" aria-checked={form.primaryColor === null} role="radio">
                <input type="radio" name="primaryColor" checked={form.primaryColor === null} onChange={() => setForm({ ...form, primaryColor: null })} style={{ position: 'absolute', opacity: 0, width: 0, height: 0 }} />
                <span style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)' }}>없음</span>
              </label>
            </Tooltip>
            {FACADE_PALETTE.map((c) => (
              <Tooltip key={c.code} content={c.label}>
                <label className="studio-swatch" style={{ background: c.hex }} aria-checked={form.primaryColor?.toUpperCase() === c.hex} role="radio">
                  <input type="radio" name="primaryColor" value={c.hex} checked={form.primaryColor?.toUpperCase() === c.hex} onChange={() => setForm({ ...form, primaryColor: c.hex })} style={{ position: 'absolute', opacity: 0, width: 0, height: 0 }} />
                  <span style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)' }}>{c.label}</span>
                </label>
              </Tooltip>
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
        <button type="button" className="studio-btn studio-btn-primary" onClick={() => facadeMutation.mutate(buildBody())} disabled={!canSave}>
          <IcSave size={15} /> 저장
        </button>
        <p className="studio-note">외관은 저장 즉시 반영됩니다(게시 불필요).</p>
      </div>
    </div>
  );
}
