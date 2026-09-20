# Unity Dedicated Server infrastructure

이 경로는 `infra-003-unity-server-deploy`의 서버 독립 설정과 검증 도구를 소유한다. 실제 EC2, DNS, TLS 인증서와 Secret이 없어도 계약·Compose·Nginx·보안 경계를 검토할 수 있다.

## 재사용 및 금지 경계

- 릴리스 식별자, target lock, current/known-good 및 rollback 결정은 `infra/deploy/`의 infra-001 계약을 재사용한다.
- demo 내부 network와 공개 80/443 경계는 infra-002 계약을 소비한다.
- `festa-unity/Docker/`, `NetworkPlayer.cs`, `ConnectionManager.cs`, `BoothRuntime.cs`를 이 경로에서 수정하거나 재구현하지 않는다.
- Backend world-session 발급기와 Unity 토큰 검증기는 각 파트가 구현한다. Infra는 환경 변수·Secret 파일·network·volume·ingress만 연결한다.
- 실제 Secret, 토큰, 인증서 개인 키와 운영 evidence는 커밋하지 않는다.
- Unity 6000.0.78f1 Linux Dedicated Server는 x86_64 host와 x86_64 image에서만 실행한다. OCI Ampere A1 ARM64, x86_64 에뮬레이션과 임시 엔진 업그레이드는 검증 경로로 사용하지 않는다.
- OCI에서 WebGL 정적 파일만 배포한 결과는 서버 기동·WSS·브라우저 동기화를 증명하지 못하므로 완료 근거로 사용하지 않는다.

## 서버 없이 실행

Git Bash에서 다음을 실행한다.

```bash
bash infra/unity-server/tests/run-static.sh
```

검사는 OpenAPI의 optional `worldId`, 토큰 고정 계약, Compose의 7777 비공개·비관리자·Secret mount, Nginx Upgrade/timeout/no-cache 및 Secret 유출 패턴을 확인한다. Docker가 없으면 Compose rendering만 `SKIP`되고 나머지는 계속 검사한다.

## 서버 수령 후 실행

1. `.env.example`의 참조 이름을 서버 Secret 저장소와 배포 변수에 매핑한다.
2. `CONNECTION_TOKEN_SECRET`은 한 번 생성해 Backend issuer와 game verifier가 같은 값을 소비하게 한다. 저장소나 Compose 환경 렌더링 결과에 원문을 남기지 않는다.
3. `scripts/preflight.sh`로 image ref, Secret 파일, network, volume과 7777 비공개를 확인한다.
4. Nginx template을 실제 domain/upstream/certificate reference로 렌더링하고 `nginx -t`를 통과시킨다.
5. 외부 DNS/TLS/WSS, 브라우저 2개 10분 idle, 재접속과 수용량 시험은 서버와 도메인이 제공된 뒤에만 완료 처리한다.

현재 준비 상태는 `specs/infra-003-unity-server-deploy/checklists/implementation.md`에서 확인한다.
