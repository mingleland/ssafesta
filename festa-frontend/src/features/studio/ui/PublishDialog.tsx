// 공개 결과 다이얼로그 — POST /publish는 단일 원자 요청이라(errors 있으면 이미 거부돼 공개가 일어나지 않고,
// warnings만 있으면 이미 공개됨) 재확인으로 다시 보낼 대상이 없다. 서버 응답을 그대로 보여줄 뿐이다(C-04, 헌법 16조).
import type { ApiErrorDetail } from '../../../shared/api/client';

interface Props {
  errors: ApiErrorDetail[];
  warnings: ApiErrorDetail[];
  published: boolean; // 이번 시도로 실제 공개됐는지 — errors가 있으면 false
  onClose: () => void;
}

// StudioPage의 공개 전 미리보기(precheckErrors/precheckWarnings)도 같은 렌더링을 쓴다.
export function DetailList({ items }: { items: ApiErrorDetail[] }) {
  if (items.length === 0) return null;
  // 이 목록은 재정렬·부분 삭제가 없어 인덱스 key로 충분하다(#58, T026)
  return (
    <ul>
      {items.map((d, i) => (
        <li key={i}>{d.message}</li>
      ))}
    </ul>
  );
}

export function PublishDialog({ errors, warnings, published, onClose }: Props) {
  return (
    <div className="studio-dialog-backdrop">
      <div role="dialog" className="studio-dialog" aria-modal="true">
        {!published && errors.length > 0 && (
          <div>
            <h2>공개하지 못했습니다</h2>
            <DetailList items={errors} />
          </div>
        )}
        {published && (
          <div>
            <h2>공개되었습니다</h2>
            <DetailList items={warnings} />
          </div>
        )}
        <div className="studio-dialog-actions">
          <button type="button" className="studio-btn studio-btn-primary" onClick={onClose}>
            확인
          </button>
        </div>
      </div>
    </div>
  );
}
