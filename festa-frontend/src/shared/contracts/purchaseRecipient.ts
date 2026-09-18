// 실물 경품 구매·응모의 받는 자 정보 (S15P21A604-842 후속). entities/eventShop와
// entities/raffle 둘 다 이 모양을 쓴다 — 화폐만 다르지 "누구한테 줄지"는 같은 개념이라
// shared/contracts에 둔다(survey.ts·overlay.ts와 같은 자리).
export const CAMPUS_OPTIONS = ['서울', '대전', '광주', '구미', '부울경'] as const;
export type Campus = (typeof CAMPUS_OPTIONS)[number];

export interface PurchaseRecipient {
  campus: Campus;
  teamName: string;
  recipientName: string;
}

export function isRecipientComplete(recipient: Partial<PurchaseRecipient>): recipient is PurchaseRecipient {
  return (
    recipient.campus !== undefined &&
    recipient.teamName !== undefined &&
    recipient.teamName.trim() !== '' &&
    recipient.recipientName !== undefined &&
    recipient.recipientName.trim() !== ''
  );
}
