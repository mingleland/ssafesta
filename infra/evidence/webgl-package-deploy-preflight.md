# WebGL Package Registry 자동 배포 preflight

Jira: `S15P21A604-581`

저장소 구현과 로컬 fixture는 완료했다. 아래 항목은 실제 Jenkins/EC2 값을 만들거나 확인하기 전에는 완료로 표시하지 않는다.

- [ ] GitLab project 1443023에 `read_package_registry`만 가진 Deploy Token 발급
- [ ] Jenkins Username/Password credential `gitlab-package-read` 등록 및 console masking 확인
- [ ] `festa-webgl-package-deploy` Job DSL 적용, 전용 publisher 계정의 해당 job Read/Build 권한 확인
- [ ] Unity 담당자 PC에서 `ci.ssafesta.world`의 `buildWithParameters` HTTPS 접근 확인
- [ ] deploy-agent 재빌드 후 `/srv/festa/webgl` host bind, `curl`, `flock`, Python 확인
- [ ] demo Nginx template 반영 후 `nginx -t`·reload, `.br`·`.unityweb` Brotli 응답 확인
- [ ] 정상 package 실배포 후 current/previous, 공개 MIME·Brotli·Cache-Control 확인
- [ ] 강제 공개 검증 실패 후 직전 current 복원 및 Dedicated Server 무재시작 확인
- [ ] Jenkins console, archived evidence, workspace에서 token 원문 미검출 확인

실측 시 build URL, release ID, SHA-256, 배포 전후 symlink와 검증 결과만 기록한다. Secret 원문은 기록하지 않는다.
