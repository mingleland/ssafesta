// 에셋 팔레트 — 모드별 내용만 바뀐다(구조: 오브젝트 / 외관: 스와치 / 템플릿: 프리셋). Shell 골격은 동일.
//
// 구조 모드는 **두 갈래를 겹쳐서** 낸다 (S15P21A604-783).
//   ① 기능 오브젝트(FUNCTIONAL_PALETTE) — assetCode 가 없다. manifest 와 무관하게 **항상** 선다
//   ② 장식 자산 — **runtime manifest 가 정본**이다(S15P21A604-551). 자산 표를 손으로 들지
//      않는다 — 예전 하드코딩 15항은 코드 7종이 저장소 어디에도 없어 실물과 교집합이 0 이었다
//
// manifest 가 비면(=산출물을 아직 안 구웠으면) ②만 LAYOUT_PALETTE 로 떨어진다. manifest 없음이
// 기본 상태라서다 — 여기서 빈 화면을 내면 "에셋을 아직 안 구웠다" 가 "편집기가 고장났다" 로 보인다.
//
// **①을 ②의 배타 분기로 두지 마라.** manifest 는 assetCode 가 붙은 자산만 담아 기능형을 절대 싣지
// 못하는데, -741 이 자산을 자동 생성하게 되면서 기능형 8종이 통째로 사라졌다(GitLab #201).
import { useMemo, useState } from 'react';
import type { CatalogItemVM } from '../../model/catalog';
import { findCatalogItem } from '../../model/catalog';
import type { StudioMode } from '../../model/studioMode';
import { FUNCTIONAL_PALETTE, LAYOUT_PALETTE, TEMPLATE_PRESETS } from '../../model/visualAssets';
import type { PaletteItem, TemplatePreset } from '../../model/visualAssets';
import type { BoothAssetEntry } from '../../model/boothAssetManifest';
import { boothAssetBaseUrl } from '../../model/boothAssetManifest';
import { resolveAssetUrl } from '../../../../shared/assets/resolveAssetUrl';
import { buildLibrary, filterLibrary } from '../../model/assetLibrary';
import type { LibraryItem } from '../../model/assetLibrary';
import { BOOTH_TEMPLATES } from '../../model/boothTemplates';
import type { BoothTemplate } from '../../model/boothTemplates';
import { IcChevron, IcCube, IcLock } from './icons';

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
  // manifest 가 실물을 주면 **장식 자산의** 정본이다. 없으면 옛 목록으로 떨어진다(파일 상단 주석).
  // 기능형은 이 값을 보지 않는다 — 그것이 #201 의 원인이었다.
  const hasRealAssets = library.length > 0;
  // 검색은 기능형에도 걸린다. 여기서 빼면 "검색하면 AI 직원이 사라진다" 가 된다.
  const functionalItems = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (q === '') return FUNCTIONAL_PALETTE.items;
    return FUNCTIONAL_PALETTE.items.filter((item) => item.label.toLowerCase().includes(q));
  }, [query]);

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

        {mode === 'layout' && hasRealAssets && (
          <div className="studio-palette-search">
            <input
              type="search"
              value={query}
              aria-label="에셋 검색"
              placeholder="에셋 검색"
              onChange={(e) => setQuery(e.target.value)}
            />
          </div>
        )}

        {/* 기능 오브젝트 — manifest 유무와 무관하게 항상. 카탈로그 잠금은 assetCode 기준이라 대상이 아니다 */}
        {mode === 'layout' && functionalItems.length > 0 && (
          <Section title={FUNCTIONAL_PALETTE.title}>
            <div className="studio-thumb-grid">
              {functionalItems.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  className="studio-thumb"
                  disabled={full}
                  title={`${item.label} 추가`}
                  onClick={() => onAddObject(item)}
                >
                  <span className="studio-thumb-art" data-kind={item.thumb} />
                  <span className="studio-thumb-label">{item.label}</span>
                </button>
              ))}
            </div>
          </Section>
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

        {mode === 'layout' &&
          !hasRealAssets &&
          LAYOUT_PALETTE.map((section) => (
            <Section key={section.id} title={section.title}>
              <div className="studio-thumb-grid">
                {section.items.map((item) => {
                  // 카탈로그 매핑은 assetCode 기준 조회만 — 매핑 확정 전엔 레지스트리의 locked 표시로 대체
                  const cat = item.assetCode ? findCatalogItem(catalog, { code: item.assetCode }) : undefined;
                  const locked = cat ? cat.locked : Boolean(item.locked);
                  return (
                    <button
                      key={item.id}
                      type="button"
                      className="studio-thumb"
                      disabled={full || locked}
                      title={locked ? '잠금 — 보유 후 사용' : `${item.label} 추가`}
                      onClick={() => onAddObject(item)}
                    >
                      <span className="studio-thumb-art" data-kind={item.thumb} />
                      {locked && <span className="studio-thumb-lock"><IcLock size={10} /></span>}
                      <span className="studio-thumb-label">{item.label}</span>
                      {cat?.purchasable && <span className="studio-thumb-price">{cat.price.toLocaleString()} C</span>}
                    </button>
                  );
                })}
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
