import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../../../../entities/project/api.select', async () => {
  const mockApi = await import('../../../../../entities/project/api.mock');
  return { projectApi: mockApi };
});

import {
  __resetProjectEditForTests,
  getProjectEditSnapshot,
  loadProjectEdit,
  saveProject,
  updateField,
} from '../../edit';
import { __lastUpdatePatchForTests, __resetProjectMockForTests } from '../../../../../entities/project/api.mock';

beforeEach(() => {
  __resetProjectEditForTests();
  __resetProjectMockForTests();
});

describe('project edit', () => {
  it('기존 프로젝트가 draft 로 로드된다', async () => {
    await loadProjectEdit(1);
    const s = getProjectEditSnapshot();
    expect(s.status).toBe('ready');
    expect(s.projectId).toBe(10);
    expect(s.draft.name).toBe('SSAFY FESTA 메타버스');
    expect(s.dirty.size).toBe(0);
  });

  it('PATCH 는 dirty 키만 직렬화한다 — PresenceField 계약', async () => {
    await loadProjectEdit(1);
    updateField('description', '수정된 설명');
    updateField('gitUrl', null); // 명시적 null = 비우기
    await saveProject();
    expect(__lastUpdatePatchForTests()).toEqual({ description: '수정된 설명', gitUrl: null });
    const s = getProjectEditSnapshot();
    expect(s.save.phase).toBe('success');
    expect(s.dirty.size).toBe(0);
    expect(s.draft.description).toBe('수정된 설명');
  });

  it('저장 중 편집은 무시된다 — 이중 제출 가드 해제·입력 유실 방지 (-377)', async () => {
    await loadProjectEdit(1);
    updateField('description', '첫 수정');
    const saving = saveProject();
    updateField('description', '저장 중 입력'); // submitting 중 — 무시돼야 한다
    expect(getProjectEditSnapshot().save.phase).toBe('submitting');
    await saving;
    const s = getProjectEditSnapshot();
    expect(s.save.phase).toBe('success');
    expect(s.draft.description).toBe('첫 수정');
    expect(__lastUpdatePatchForTests()).toEqual({ description: '첫 수정' });
  });

  it('저장 실패 시 error — draft·dirty 유지로 재시도 가능', async () => {
    await loadProjectEdit(1);
    updateField('name', 'FAIL');
    await saveProject();
    const s = getProjectEditSnapshot();
    expect(s.save.phase).toBe('error');
    expect(s.draft.name).toBe('FAIL');
    expect(s.dirty.has('name')).toBe(true);
  });
});
