// 에셋 팔레트 — 모드별 내용만 바뀐다(구조: 오브젝트 / 외관: 스와치 / 템플릿: 프리셋). Shell 골격은 동일.
import { useState } from 'react';
import type { CatalogItemVM } from '../../model/catalog';
import { findCatalogItem } from '../../model/catalog';
import type { StudioMode } from '../../model/studioMode';
import { FACADE_PALETTE_SECTIONS, LAYOUT_PALETTE, TEMPLATE_PRESETS } from '../../model/visualAssets';
import type { PaletteItem, TemplatePreset } from '../../model/visualAssets';
import { IcChevron, IcCube, IcLock, IcPlus } from './icons';

interface Props {
  mode: StudioMode;
  currentCount: number;
  maxObjects: number;
  catalog: CatalogItemVM[];
  activePresetId: string | null;
  onAddObject: (item: PaletteItem) => void;
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

export function AssetPalette({ mode, currentCount, maxObjects, catalog, activePresetId, onAddObject, onPickPreset }: Props) {
  const full = currentCount >= maxObjects;

  return (
    <aside className="studio-panel studio-palette" aria-label="에셋 팔레트">
      <div className="studio-palette-scroll">
        {mode === 'layout' &&
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

        {mode === 'facade' &&
          FACADE_PALETTE_SECTIONS.map((section) => (
            <Section key={section.id} title={section.title}>
              <div className="studio-thumb-grid">
                {section.items.map((sw) => (
                  <button key={sw.id} type="button" className="studio-thumb" disabled title="목업 표현 — 저장 계약에 없는 항목">
                    <span className="studio-swatch-art" data-kind={sw.id} />
                    <span className="studio-thumb-label">{sw.label}</span>
                  </button>
                ))}
              </div>
            </Section>
          ))}

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

      <div className="studio-palette-foot">
        {mode === 'layout' ? (
          <button type="button" className="studio-btn studio-btn-outline" disabled title="에셋 업로드는 후속 계약">
            <IcPlus size={16} /> 에셋 추가
          </button>
        ) : (
          <span className="studio-note" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <IcCube size={14} /> {mode === 'facade' ? '벽면·바닥·그래픽은 목업 표현' : '프리셋은 외관 모드에서 저장'}
          </span>
        )}
      </div>
    </aside>
  );
}
