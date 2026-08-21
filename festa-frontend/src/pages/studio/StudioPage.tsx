// spec 005 Booth Studio 편집기의 조립 지점 — /app/studio/:boothId가 마운트하는 화면
import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import * as realApi from '../../entities/layout/api';
import * as mockApi from '../../entities/layout/api.mock';

// VITE_USE_MOCK=true면 실 BE 없이 메모리 mock으로 개발한다 (FE/research.md R-09)
const layoutApi = import.meta.env.VITE_USE_MOCK === 'true' ? mockApi : realApi;

export function StudioPage() {
  const { boothId } = useParams<{ boothId: string }>();
  const boothIdNum = Number(boothId);

  const draftQuery = useQuery({
    queryKey: ['layout-draft', boothIdNum],
    queryFn: () => layoutApi.getDraft(boothIdNum),
    enabled: Number.isFinite(boothIdNum),
  });

  if (draftQuery.isLoading) return <div>불러오는 중...</div>;
  if (draftQuery.isError) return <div>작업본을 불러오지 못했습니다.</div>;

  // 편집기 본체(EditorCanvas·ObjectPalette·PublishDialog 등)는 US1 구현(T008~T013)에서 조립한다
  return <div>Booth Studio — boothId {boothIdNum}</div>;
}
