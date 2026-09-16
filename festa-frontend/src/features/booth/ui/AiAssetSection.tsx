// AI 전시 에셋 관리 — 잠금 상태의 자리표. 핸들러도 조회도 없다 (S15P21A604-817).
// 노출 여부는 model/boothManagementFlags.ts 의 SHOW_AI_ASSET_SECTION 한 줄이 정한다.
// 기능이 도입되지 않으면 이 파일·플래그·오버레이의 한 줄을 지우면 된다.

const IcLock = (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="5" y="11" width="14" height="10" rx="2" />
    <path d="M8 11V7a4 4 0 0 1 8 0v4" />
  </svg>
);

const IcCube = (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M12 3l8 4.5v9L12 21l-8-4.5v-9L12 3zM4 7.5l8 4.5 8-4.5M12 12v9" />
  </svg>
);

const CASES = ['비어 있음', '비어 있음', '준비 중', '비어 있음'];

export function AiAssetSection() {
  return (
    <section className="bm-ai" aria-labelledby="bm-ai-title">
      <div className="bm-ai-head">
        <h3 id="bm-ai-title" className="bm-card-title">
          AI 전시 에셋 관리 <span className="bm-ai-badge">추후 제공 예정</span>
        </h3>
        <span className="bm-ai-lock">{IcLock}</span>
      </div>
      <p className="ov-note">AI로 생성한 전시 에셋을 부스에 배치하여 더욱 풍성한 부스를 만들어보세요.</p>
      <div className="bm-ai-cases">
        {CASES.map((state, i) => (
          <div key={i} className="bm-ai-case">
            {IcCube}
            <span>케이스 {i + 1}</span>
            <em>{state}</em>
          </div>
        ))}
        <p className="bm-ai-locked">AI 에셋 기능은 추후 제공 예정입니다.</p>
      </div>
      <div className="bm-ai-actions">
        <button type="button" className="ov-btn" disabled aria-disabled="true">AI 에셋 생성</button>
        <button type="button" className="ov-btn" disabled aria-disabled="true">케이스에 배치</button>
      </div>
    </section>
  );
}
