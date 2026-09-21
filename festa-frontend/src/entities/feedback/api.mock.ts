import type { ApiError } from '../../shared/api/client';
import type { FeedbackRepository, FeedbackSubmission } from './types';

function apiError(code: string, status: number, message: string): ApiError {
  return { code, message, status, requestId: `mock_${Date.now()}`, errors: [], warnings: [] };
}

let nextId = 1;

export const feedbackApi: FeedbackRepository = {
  async submit(content: string): Promise<FeedbackSubmission> {
    const trimmed = content.trim();
    if (trimmed === '') throw apiError('VALIDATION_FAILED', 400, '필수입니다.');
    if (trimmed.length > 2000) throw apiError('VALIDATION_FAILED', 400, '2000자 이하여야 합니다.');
    return { feedbackId: nextId++, createdAt: new Date().toISOString() };
  },
};

export function __resetFeedbackMockForTests(): void {
  nextId = 1;
}
