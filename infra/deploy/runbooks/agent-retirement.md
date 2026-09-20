# Unity agent 폐기 절차

Unity Personal 라이선스는 정상 Jenkins job의 `post`에서 반환하지 않는다. 영속 agent 폐기·교체 또는 라이선스 소유자 변경 때만 아래 순서를 수행한다.

1. Jenkins에서 Unity node를 임시 offline으로 전환한다.
2. 실행·queue에 Unity job이 없고 Editor 프로세스가 종료됐는지 확인한다.
3. 보존할 build log와 provenance를 archive하고 Secret/세션 데이터가 없는지 검사한다.
4. 운영자가 Unity Hub GUI에서 로그인 상태를 확인하고 로그아웃/Return license를 수행한다.
5. Hub 또는 라이선스 관리 화면에서 반환 완료를 눈으로 확인하고 담당자·시간·대상 머신만 기록한다.
6. `Library`는 재사용 가치가 없으면 Editor 종료 후 target workspace 단위로만 정리한다. 캐시 내부 파일을 부분 삭제하지 않는다.
7. Jenkins node와 agent 연결 credential을 제거한 뒤 disk를 승인된 방식으로 폐기한다.

반환 확인 전 disk를 폐기하지 않는다. Unity ID, 비밀번호, 세션, 라이선스 파일은 Jenkins credential·SCM·로그·artifact로 옮기지 않는다.
