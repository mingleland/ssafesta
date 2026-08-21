// 공개 결과 다이얼로그 — POST /publish는 단일 원자 요청이라(errors 있으면 이미 거부돼 공개가 일어나지 않고,
// warnings만 있으면 이미 공개됨) 재확인으로 다시 보낼 대상이 없다. 서버 응답을 그대로 보여줄 뿐이다(C-04, 헌법 16조).
import type { ApiErrorDetail } from '../../../shared/api/client';

interface Props {
  errors: ApiErrorDetail[];
  warnings: ApiErrorDetail[];
  published: boolean; // 이번 시도로 실제 공개됐는지 — errors가 있으면 false
  onClose: () => void;
}

function DetailList({ items }: { items: ApiErrorDetail[] }) {
  if (items.length === 0) return null;
  return (
    <ul>
      {items.map((d, i) => (
        <li key={`${d.rule}-${d.objectId ?? i}`}>{d.message}</li>
      ))}
    </ul>
  );
}

export function PublishDialog({ errors, warnings, published, onClose }: Props) {
  return (
    <div role="dialog">
      {!published && errors.length > 0 && (
        <div>
          <strong>공개하지 못했습니다</strong>
          <DetailList items={errors} />
        </div>
      )}
      {published && (
        <div>
          <strong>공개되었습니다</strong>
          <DetailList items={warnings} />
        </div>
      )}
      <button type="button" onClick={onClose}>
        확인
      </button>
    </div>
  );
}
