// 피드백 제출 계약 — BE FeedbackController 정본 (S15P21A604-953).
export interface FeedbackSubmission {
  feedbackId: number;
  createdAt: string; // ISO-8601 UTC
}

export interface FeedbackRepository {
  submit(content: string): Promise<FeedbackSubmission>;
}
