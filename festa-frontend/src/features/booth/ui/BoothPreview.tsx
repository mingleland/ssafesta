// 부스 미리보기 — Unity 고정 카메라로 찍은 슬롯별 정적 캡처를 보여 준다 (S15P21A604-817).
//
// 부스 공간·기능형 에셋 배치는 Unity 의 부스별 고정 구성이 정본이라 FE 가 그릴 것이 없다.
// 2.5D 삽화(BoothMiniPreview)는 폴백에 끼우지 않는다 — "실제 부스 모습" 이라 적어 두고 삽화를
// 내면 의미가 섞인다. 폴백은 {slotCode}.webp → default.webp → 안내 문구, 셋으로 끝난다.
// 슬롯별 캡처는 게임 파트가 public/booth-preview/{slotCode}.webp 로 준다 — 파일만 추가하면 된다.
import { useState } from 'react';

const BASE = '/booth-preview/';

export function BoothPreview({ slotCode }: { slotCode: string | null }) {
  const sources = slotCode === null ? [BASE + 'default.webp'] : [BASE + slotCode + '.webp', BASE + 'default.webp'];
  const [failed, setFailed] = useState(0);
  const src = sources[failed];

  return (
    <section className="bm-preview-card">
      <h3 className="bm-card-title">부스 미리보기</h3>
      <p className="ov-note">방문자에게 보여지는 실제 부스 모습입니다.</p>
      <div className="bm-preview-frame">
        {src === undefined ? (
          <p className="bm-preview-missing">부스 미리보기를 불러올 수 없습니다.</p>
        ) : (
          <img className="bm-preview-img" src={src} alt="내 부스 미리보기" onError={() => setFailed((n) => n + 1)} />
        )}
        <span className="bm-preview-chip">이 부스는 SSAFESTA 기본 구성으로 제공되는 고정 부스입니다.</span>
      </div>
    </section>
  );
}
