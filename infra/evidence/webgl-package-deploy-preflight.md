# WebGL Package Registry 자동 배포 preflight

Jira: `S15P21A604-581`

저장소 구현과 로컬 fixture는 완료했다. 아래 항목은 실제 Jenkins/EC2 값을 만들거나 확인하기 전에는 완료로 표시하지 않는다.

- [X] GitLab project 1443023에 `read_package_registry`만 가진 Deploy Token 발급
- [X] Jenkins Username/Password credential `gitlab-package-read` 등록 및 console masking 확인
- [X] `festa-webgl-package-deploy` Job DSL 적용, 전용 publisher 계정의 해당 job Read/Build 권한 확인
- [X] Unity 담당자 PC에서 `ci.ssafesta.world`의 `buildWithParameters` HTTPS 접근 확인
- [X] deploy-agent 재빌드 후 `/srv/festa/webgl` host bind, `curl`, `flock`, Python 확인
- [X] demo Nginx template 반영 후 `nginx -t`·reload, `.br`·`.unityweb` Brotli 응답 확인
- [X] 정상 package 실배포 후 current/previous, 공개 MIME·Brotli·Cache-Control 확인
- [X] 강제 공개 검증 실패 후 직전 current 복원 및 Dedicated Server 무재시작 확인
- [X] Jenkins console, archived evidence, workspace에서 token 원문 미검출 확인

실측 시 build URL, release ID, SHA-256, 배포 전후 symlink와 검증 결과만 기록한다. Secret 원문은 기록하지 않는다.


## 실측 기록

- **실행 경로**: Unity 담당자 PC (publish-webgl-release.sh) → Generic Package Registry 업로드 → Jenkins (festa-webgl-package-deploy) API 트리거
- **최근 릴리스 실측**: RELEASE_ID=1da79150 (SHA-256: cc8bd079...5a00e1), RELEASE_ID=e30d8d49
- **배포 검증**: /srv/festa/webgl/current symlink 원자적 전환, demo.ssafesta.world/unity/manifest.json 200 OK, Brotli (.br, .unityweb) 응답 정상 확인
- **롤백 검증**: Nginx 권한(0700) 오류 시 직전 정상 previous 릴리스 자동 복원 확인 (GitLab #165, T-257/T-258)
