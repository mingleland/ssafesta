// BE MyAccountController 응답 전사 — 구현이 정본 (계약 문서 부재, origin/develop backend user/MyAccountController.java)
// avatarCode: null = 저장된 적 없음. 키는 항상 존재한다(빈 값일 뿐) — 기본 아바타 선택은 클라이언트 몫(spec 013a FR-010).

export interface MyAccountResponse {
  userId: number;
  nickname: string;
  /** AccountStatus enum 이름 (ACTIVE 등) — FE 는 표시 외 분기하지 않는다 */
  status: string;
  /** OAuthProvider enum 이름 대문자 (GOOGLE·KAKAO, 향후 SSAFY) — 게스트는 빈 배열 */
  providers: string[];
  avatarCode: string | null;
}

export interface AvatarResponse {
  avatarCode: string;
}
