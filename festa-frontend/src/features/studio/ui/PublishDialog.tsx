// 공개 결과 다이얼로그 — POST /publish는 단일 원자 요청이라(errors 있으면 이미 거부돼 공개가 일어나지 않고,
// warnings만 있으면 이미 공개됨) 재확인으로 다시 보낼 대상이 없다. 서버 응답을 그대로 보여줄 뿐이다(C-04, 헌법 16조).
import type { ApiErrorDetail } from '../../../shared/api/client';

interface Props {
  errors: ApiErrorDetail[];
  warnings: ApiErrorDetail[];
  published: boolean; // 이번 시도로 실제 공개됐는지 — errors가 있으면 false
  onClose: () => void;
}

// StudioPage의 공개 전 미리보기(precheckErrors/precheckWarnings)도 같은 렌더링을 쓴다 —
// 요청 전 미리보기와 요청 후 서버 결과가 다른 컴포넌트로 보이면 사용자가 다른 것으로 오해한다.
export function DetailList({ items }: { items: ApiErrorDetail[] }) {
  if (items.length === 0) return null;
  // rule+objectId 조합 key는 구분자가 모호해 충돌할 수 있었다(예: rule="a-b",objectId="c"와
  // rule="a",objectId="b-c"가 같은 문자열) — 이 목록은 재정렬·부분 삭제가 없어 인덱스로 충분하다(#58, T026)
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
