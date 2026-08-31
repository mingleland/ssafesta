# Environment Configuration

이 디렉터리에는 커밋 가능한 변수 이름·참조·안전한 예시만 둔다. 실제 값은 Jenkins Credentials 또는 승인된 서버 Secret 경계에서 런타임에 주입한다.

## 허용

- `*.env.example`: 변수 이름과 비민감 로컬 자리표시자
- `*_REF`: Secret 값이 아니라 Jenkins credential ID, 파일 경로 또는 런타임 참조 이름
- 이미지 digest, Docker network alias, 공급자 endpoint template

## 금지

- `.env`, Secret 원문, OAuth client secret, JWT 서명 키, DB·Redis 비밀번호
- presigned URL, TLS 개인 키·인증서 원문
- 실제 Secret이 포함된 `docker compose config` 출력이나 환경 덤프

## 주입 경로

1. Jenkins가 필요한 credential을 최소 stage 범위에서 바인딩한다.
2. `infra/jenkins/scripts/with-credentials.sh`가 필수 변수 존재를 검증한 child process만 실행한다.
3. Compose는 `environment` 또는 read-only Secret file reference로 컨테이너에 전달한다.
4. 배포 종료 후 credential 환경변수는 child process 범위와 Jenkins binding 범위에서 제거된다.

실제 값이 없는 로컬 검사는 fixture와 안전한 합성 값을 사용한다. 배포 가능한 기본 Secret은 제공하지 않는다.
