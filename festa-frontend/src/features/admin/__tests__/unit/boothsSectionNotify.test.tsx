// @vitest-environment jsdom
// 관리자 강제 비공개가 임대까지 회수하므로(S15P21A604-927), 상주 월드에 슬롯 변경을 알리는지 검증한다
// (GitLab #254). notify 가 누기면 월드의 그 칸은 새로고침 전까지 옛 모습으로 남는다.
import { render, screen, waitFor, within } from '@testing-library/react';
import { act, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';

// vi.mock 은 파일 맨 위로 끌어올려진다 — spy 를 그냥 const 로 두면 factory 가 먼저 돌아
// 초기화 전 접근(TDZ)으로 죽는다. vi.hoisted 가 같은 자리로 같이 올라간다.
const { notifyBoothSlotChanged, showToast } = vi.hoisted(() => ({
  notifyBoothSlotChanged: vi.fn(),
  showToast: vi.fn(),
}));

// BoothsSection 은 entities/admin/api.select 를 통해 실 mock adapter 를 쓴다 — 응답 모양 검증이
// 아니라 "notify 가 불리는가" 만 보므로 기존 mock 을 그대로 쓰는 게 진실에 가깝다.
vi.mock('../../../../entities/admin/api.select', async () => {
  const mock = await import('../../../../entities/admin/api.mock');
  return { adminApi: mock.adminApi };
});
// 경로는 **테스트 파일 기준**이다 — 한 단계 얕으면 src/features/unity 를 가리켜 존재하지 않는
// 모듈을 모의하게 되고, 실물 bridge 가 그대로 돌아 spy 는 끝내 불리지 않는다.
vi.mock('../../../../unity/host/boothLayoutBridge', () => ({ notifyBoothSlotChanged }));
vi.mock('../../../../shared/ui/toast/toastStore', () => ({ showToast }));
const { __resetAdminMockForTests } = await import('../../../../entities/admin/api.mock');

import { BoothsSection } from '../../ui/BoothsSection';

describe('BoothsSection 강제 비공개', () => {
  beforeEach(() => {
    notifyBoothSlotChanged.mockClear();
    showToast.mockClear();
    __resetAdminMockForTests();
  });

  it('비공개 성공 시 슬롯 변경을 Unity 에 알린다 — slotId 로', async () => {
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
        <BoothsSection />
      </QueryClientProvider>,
    );

    await screen.findByText('AI 상담 부스');
    // **싸피 프로젝트관(slotId 3)** 행을 쓰는다 — slotId 1(AI 상담 부스)은 mock 의
    // MASTER 보호 부스(boothId 11)라 서버가 403(MASTER_PROTECTED)으로 거부한다.
    await waitFor(() => {
      const btns = screen.getAllByRole('button', { name: '강제 비공개' }).filter(
        (b) => (b as HTMLButtonElement).disabled === false,
      );
      expect(btns.length).toBeGreaterThan(0);
    });
    act(() => {
      const row = screen.getByText('싸피 프로젝트관').closest('tr')!;
      // 행 첫 버튼이 아니라 이름으로 집는다 — 운영 관리 4버튼이 앞에 서면서 위치 기반 선택이
      // '프로젝트' 패널을 열어 버렸다 (S15P21A604-951).
      fireEvent.click(within(row).getByRole('button', { name: '강제 비공개' }));
    });

    // 사유 필수(reasonProblem) — 비우면 onConfirm 이 무시된다. jsdom 에서 dialog 내부는
    // 접근성 이름이 붙지 않아 id 로 직접 잡는다.
    const reasonEl = await waitFor(() => {
      const el = document.getElementById('unpublish-reason') as HTMLTextAreaElement | null;
      expect(el).not.toBeNull();
      return el!;
    });
    act(() => {
      fireEvent.change(reasonEl, { target: { value: '부적절 이미지' } });
    });
    // React 19 controlled textarea — change 이벤트 뒤 값이 반영되는지 확인하고, 안 되었으면
    // native setter로 강제 쓰고 input 이벤트를 낸는다(testing-library 권장 수정 경로).
    await waitFor(() => {
      expect((document.getElementById('unpublish-reason') as HTMLTextAreaElement).value).toBe('부적절 이미지');
    });

    const confirmBtn = await waitFor(() => {
      const btn = Array.from(document.querySelectorAll('dialog.ad-dialog button')).find(
        (b) => b.textContent === '비공개',
      );
      expect(btn).toBeDefined();
      return btn!;
    });
    act(() => {
      fireEvent.click(confirmBtn);
    });

    // onSuccess 에 진입했다면 toast 와 notify 가 함께 불린다. 403 같은 실패면 notify 가 안
    // 불리는 것이 정상이므로, 무엇이 갈렸는지 알려 주는 표식으로 toast 를 먼저 본다.
    await waitFor(() => {
      expect(showToast).toHaveBeenCalled();
      expect(notifyBoothSlotChanged).toHaveBeenCalledWith(3);
    });
  });
});
