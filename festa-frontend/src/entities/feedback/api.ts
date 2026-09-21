import { api } from '../../shared/api/client';
import type { FeedbackRepository, FeedbackSubmission } from './types';

export const feedbackApi: FeedbackRepository = {
  submit(content: string): Promise<FeedbackSubmission> {
    return api<FeedbackSubmission>('/api/v1/feedback', {
      method: 'POST',
      body: JSON.stringify({ content }),
    });
  },
};
