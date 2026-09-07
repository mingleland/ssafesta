// 템플릿 모드 Inspector — 프리셋 구성 표시. 실제 저장 가능한 값(themeCode·primaryColor)과 목업 값을 구분 표기한다.
import type { TemplatePreset } from '../../model/visualAssets';
import { IcBrush } from './icons';

interface Props {
  preset: TemplatePreset | null;
  onApplyToFacade: () => void;
}

export function TemplateInspector({ preset, onApplyToFacade }: Props) {
  if (!preset) {
    return <p className="studio-inspector-empty">프리셋을 고르면 벽면·바닥·포인트 색이 함께 바뀝니다.<br />저장은 외관 모드에서 합니다.</p>;
  }
  return (
    <>
      <div className="studio-group">
        <p className="studio-group-title">프리셋 구성</p>
        <div className="studio-kv"><span className="studio-kv-label">테마 코드</span><span>{preset.themeCode}</span></div>
        <div className="studio-kv"><span className="studio-kv-label">대표색</span><span className="studio-color-row"><span className="studio-color-chip" style={{ background: preset.primaryHex }} />{preset.primaryHex}</span></div>
        <div className="studio-kv"><span className="studio-kv-label">포인트<span className="studio-provisional">목업</span></span><span className="studio-color-chip" style={{ background: preset.accentHex }} /></div>
        <div className="studio-kv"><span className="studio-kv-label">바닥<span className="studio-provisional">목업</span></span><span className="studio-color-chip" style={{ background: preset.floorHex }} /></div>
      </div>
      <div className="studio-group">
        <p className="studio-group-title" data-tone="plain">함께 바뀌는 것</p>
        <p className="studio-note">벽면 그래픽 · 바닥 · 트러스 포인트 · 카운터 그래픽 · 일부 가구 accent</p>
        <p className="studio-note">계약상 저장되는 값은 테마 코드와 대표색뿐이다(PUT /booths/{'{id}'}/facade). 나머지는 목업 표현.</p>
      </div>
      <div className="studio-inspector-foot">
        <button type="button" className="studio-btn studio-btn-primary" onClick={onApplyToFacade}><IcBrush size={15} /> 외관 모드로 가져가기</button>
      </div>
    </>
  );
}
