# Verification Evidence

검증 근거는 실제 실행 결과를 재현할 수 있게 하되 Secret 원문을 포함하지 않는다.

## 디렉터리 이름

```text
YYYYMMDDTHHMMSSZ-<environment>-<release-or-local>-<scenario>/
```

예: `20260830T021500Z-dev-local-contracts/`

## 필수 메타데이터

- UTC 실행 시각
- `environmentId`와 release ID 또는 `local`
- scenario와 실행 주체
- 민감정보가 제거된 명령 요약
- PASS/FAIL/SKIP 결과와 failure code
- 입력 manifest·schema·image digest 참조
- 생성된 증거 파일의 상대 경로와 SHA-256
- 실제 EC2/외부 검증 여부

## 금지 데이터

- 실제 credential·token·cookie·presigned URL
- TLS private key 또는 원본 환경 덤프
- 사용자 개인정보와 업로드 문서 본문
- Secret이 포함된 Compose 렌더링 전체

검증 스크립트는 저장 전에 `tests/lib/assert.sh`의 `redact_stream`을 사용하고, Secret scanner를 통과한 결과만 보존한다.
