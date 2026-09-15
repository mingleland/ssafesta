// 에셋 팔레트 — 모드별 내용만 바뀐다(구조: 오브젝트 / 외관: 스와치 / 템플릿: 프리셋). Shell 골격은 동일.
//
// 구조 모드의 **장식형** 목록은 runtime manifest 가 정본이다(S15P21A604-551). 자산 표를 손으로
// 들지 않는다 — 예전 하드코딩 15항은 코드 7종이 저장소 어디에도 없어 실물과 교집합이 0 이었다.
//
// manifest 가 비면(=산출물을 아직 안 구웠으면) 기존 하드코딩 목록으로 떨어진다. manifest 없음이
// 기본 상태라서다 — 여기서 빈 화면을 내면 "에셋을 아직 안 구웠다" 가 "편집기가 고장났다" 로 보인다.
//
// **기능형은 그 분기에 걸리지 않는다** (GitLab #201, S15P21A604-783). manifest 는 장식형만 담아
// 기능 오브젝트가 실자산 라이브러리에 들어올 길이 없다. 배타 분기로 두면 의자 하나가 구워진
// 순간 AI 직원부터 채용 보드까지 8종이 화면에서 통째로 사라진다. 그래서 기능형 섹션은 항상 그린다.
import { useMemo, useState } from 'react';
import type { CatalogItemVM } from '../../model/catalog';
import { findCatalogItem } from '../../model/catalog';
import type { StudioMode } from '../../model/studioMode';
import { DECOR_PALETTE, FUNCTIONAL_PALETTE, TEMPLATE_PRESETS } from '../../model/visualAssets';
import type { PaletteItem, TemplatePreset } from '../../model/visualAssets';
import type { BoothAssetEntry } from '../../model/boothAssetManifest';
import { boothAssetBaseUrl } from '../../model/boothAssetManifest';
import { resolveAssetUrl } from '../../../../shared/assets/resolveAssetUrl';
import { buildLibrary, filterLibrary } from '../../model/assetLibrary';
import type { LibraryItem } from '../../model/assetLibrary';
import { BOOTH_TEMPLATES } from '../../model/boothTemplates';
import type { BoothTemplate } from '../../model/boothTemplates';
import { IcChevron, IcCube, IcLock } from './icons';

/** 하드코딩 팔레트의 검색 — 라벨과 assetCode 둘 다 본다(`filterLibrary` 와 같은 규칙) */
function filterPaletteItems(items: PaletteItem[], query: string): PaletteItem[] {
  const q = query.trim().toLowerCase();
  if (q === '') return items;
  return items.filter(
    (item) => item.label.toLowerCase().includes(q) || (item.assetCode ?? '').toLowerCase().includes(q),
  );
}

/**
 * 하드코딩 팔레트 항목 하나. 기능형 섹션과 장식형 폴백이 같은 마크업을 쓴다 —
 * 두 자리에 복제해 두면 한쪽만 고쳐져 갈라진다.
 */
function PaletteThumb({
  item,
  catalog,
  full,
  onAdd,
}: {
  item: PaletteItem;
  catalog: CatalogItemVM[];
  full: boolean;
  onAdd: (item: PaletteItem) => void;
}) {
  // 카탈로그 매핑은 assetCode 기준 조회만 — 매핑 확정 전엔 레지스트리의 locked 표시로 대체
  const cat = item.assetCode ? findCatalogItem(catalog, { code: item.assetCode }) : undefined;
  const locked = cat ? cat.locked : Boolean(item.locked);
  return (
    <button
      type="button"
      className="studio-thumb"
      disabled={full || locked}
      title={locked ? '잠금 — 보유 후 사용' : `${item.label} 추가`}
      onClick={() => onAdd(item)}
    >
      <span className="studio-thumb-art" data-kind={item.thumb} />
      {locked && <span className="studio-thumb-lock"><IcLock size={10} /></span>}
      <span className="studio-thumb-label">{item.label}</span>
      {cat?.purchasable && <span className="studio-thumb-price">{cat.price.toLocaleString()} C</span>}
    </button>
  );
}

interface Props {
  mode: StudioMode;
  currentCount: number;
  maxObjects: number;
  catalog: CatalogItemVM[];
  /** runtime manifest. 비어 있으면 하드코딩 목록으로 떨어진다 */
  assets: BoothAssetEntry[];
  activePresetId: string | null;
  onAddObject: (item: PaletteItem) => void;
  onAddAsset: (item: LibraryItem) => void;
  onApplyTemplate: (template: BoothTemplate) => void;
  onPickPreset: (preset: TemplatePreset) => void;
}

function Section({ title, defaultOpen = true, children }: { title: string; defaultOpen?: boolean; children: React.ReactNode }) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <section className="studio-section">
      <button type="button" className="studio-section-head" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        {title}
        <IcChevron size={14} />
      </button>
      {open && children}
    </section>
  );
}

export function AssetPalette({
  mode,
  currentCount,
  maxObjects,
  catalog,
  assets,
  activePresetId,
  onAddObject,
  onAddAsset,
  onApplyTemplate,
  onPickPreset,
}: Props) {
  const full = currentCount >= maxObjects;
  const [query, setQuery] = useState('');

  const library = useMemo(() => buildLibrary(assets, catalog), [assets, catalog]);
  const shown = useMemo(() => filterLibrary(library, query), [library, query]);
  // manifest 가 실물을 주면 **장식형만** 그것이 정본이다. 기능형은 manifest 에 들어올 수 없어
  // 이 값과 무관하게 항상 노출한다 (GitLab #201, S15P21A604-783).
  const hasRealAssets = library.length > 0;
  // 검색은 기능형·실물형 둘 다를 대상으로 한다. 한쪽만 걸러지면 "검색하면 사라지는 항목" 이 생긴다
  const functionalItems = useMemo(() => filterPaletteItems(FUNCTIONAL_PALETTE.items, query), [query]);
  const decorSections = useMemo(
    () => DECOR_PALETTE.map((s) => ({ ...s, items: filterPaletteItems(s.items, query) })).filter((s) => s.items.length > 0),
    [query],
  );

  return (
    <aside className="studio-panel studio-palette" aria-label="에셋 팔레트">
      <div className="studio-palette-scroll">
        {mode === 'layout' && (
          <Section title="빠른 시작">
            <div className="studio-preset-grid">
              {BOOTH_TEMPLATES.map((t) => (
                <button
                  key={t.templateCode}
                  type="button"
                  className="studio-preset"
                  title={t.description}
                  onClick={() => onApplyTemplate(t)}
                >
                  <span className="studio-preset-name">{t.name}</span>
                  <span className="studio-preset-desc">{t.description}</span>
                </button>
              ))}
            </div>
          </Section>
        )}

        {mode === 'layout' && (
          <>
            <div className="studio-palette-search">
              <input
                type="search"
                value={query}
                aria-label="에셋 검색"
                placeholder="에셋 검색"
                onChange={(e) => setQuery(e.target.value)}
              />
            </div>

            {/* 기능 오브젝트 — 실자산 유무와 무관하게 항상 (GitLab #201) */}
            {functionalItems.length > 0 && (
              <Section title={FUNCTIONAL_PALETTE.title}>
                <div className="studio-thumb-grid">
                  {functionalItems.map((item) => (
                    <PaletteThumb key={item.id} item={item} catalog={catalog} full={full} onAdd={onAddObject} />
                  ))}
                </div>
              </Section>
            )}
          </>
        )}

        {mode === 'layout' && hasRealAssets && (
          <>
            {shown.map((section) => (
              <Section key={section.category.id} title={section.category.label}>
                <div className="studio-thumb-grid">
                  {section.items.map((item) => (
                    <button
                      key={item.assetCode}
                      type="button"
                      className="studio-thumb"
                      disabled={full || item.locked}
                      title={item.locked ? '잠금 — 보유 후 사용' : `${item.displayName} 추가`}
                      onClick={() => onAddAsset(item)}
                    >
                      {item.thumbnail === null ? (
                        <span className="studio-thumb-art" data-kind="panel" />
                      ) : (
                        <img
                          className="studio-thumb-img"
                          src={resolveAssetUrl(item.thumbnail, boothAssetBaseUrl())}
                          alt=""
                          loading="lazy"
                        />
                      )}
                      {item.locked && <span className="studio-thumb-lock"><IcLock size={10} /></span>}
                      <span className="studio-thumb-label">{item.displayName}</span>
                      {item.price !== null && <span className="studio-thumb-price">{item.price.toLocaleString()} C</span>}
                    </button>
                  ))}
                </div>
              </Section>
            ))}
          </>
        )}

        {/* 장식형 폴백 — manifest 실물이 없을 때만. 기능형은 위에서 이미 그렸다 */}
        {mode === 'layout' &&
          !hasRealAssets &&
          decorSections.map((section) => (
            <Section key={section.id} title={section.title}>
              <div className="studio-thumb-grid">
                {section.items.map((item) => (
                  <PaletteThumb key={item.id} item={item} catalog={catalog} full={full} onAdd={onAddObject} />
                ))}
              </div>
            </Section>
          ))}

        {/* 외관은 오른쪽 패널이 저장한다 — 여기서 고를 것이 없다 (S15P21A604-617) */}
        {mode === 'facade' && (
          <Section title="외관">
            <p className="studio-note">테마·대표색·간판 문구·로고를 오른쪽 <strong>부스 외관</strong> 패널에서 저장합니다.</p>
          </Section>
        )}

        {mode === 'template' && (
          <Section title="프리셋">
            <div className="studio-preset-grid">
              {TEMPLATE_PRESETS.map((preset) => (
                <button key={preset.id} type="button" className="studio-preset" aria-pressed={activePresetId === preset.id} onClick={() => onPickPreset(preset)}>
                  <span className="studio-preset-art" style={{ background: `linear-gradient(135deg, #f5f7fb 0 40%, ${preset.primaryHex} 40% 70%, ${preset.floorHex} 70%)` }} />
                  <span className="studio-preset-name">{preset.label}</span>
                  <span className="studio-preset-desc">{preset.description}</span>
                </button>
              ))}
            </div>
          </Section>
        )}
      </div>

      {mode !== 'layout' && (
        <div className="studio-palette-foot">
          <span className="studio-note" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <IcCube size={14} /> 프리셋은 외관 모드에서 저장
          </span>
        </div>
      )}
    </aside>
  );
}
