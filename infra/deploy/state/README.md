# Deployment Runtime State Contract

이 디렉터리에는 배포 상태의 구조만 커밋한다. 실제 상태는 `runtime/<target-id>/` 아래에 생성하며 `.gitignore`로 제외한다. Jenkins artifact의 release manifest가 변경 불가능한 이력이고, 이 디렉터리의 pointer는 현재 운영 선택을 나타낸다.

## 대상별 구조

```text
runtime/<target-id>/
├── releases/<release-id>/release-manifest.json
├── verifications/<verification-id>.json
├── candidate.json
├── current.json
└── known-good.json
```

- `candidate.json`: 검증 중인 release. deploy agent만 생성·교체한다.
- `current.json`: 실제 트래픽/컨테이너가 사용 중인 release. 필수 non-AI 검증과 정책 판정이 끝난 뒤에만 deploy agent가 승격한다.
- `known-good.json`: 성공 검증을 통과해 rollback 가능한 마지막 release. rollback 로직은 이 pointer를 읽기만 하며 임의 후보를 선택하지 않는다.
- `releases/`와 `verifications/`: 해당 target에 적용된 manifest와 검증 근거의 로컬 사본. Secret을 포함하지 않는다.

## 원자적 갱신 규칙

1. 같은 디렉터리에 `<pointer>.tmp.<run-id>`를 쓰고 JSON Schema 및 content ID를 검증한다.
2. 파일을 flush한 후 동일 파일시스템의 rename으로 최종 pointer를 한 번에 교체한다.
3. deploy target의 Jenkins lock을 소유한 프로세스만 pointer를 변경한다.
4. release sequence와 commit freshness를 lock 획득 후 다시 확인한다. 오래된 실행은 `SUPERSEDED`로 끝내고 pointer를 쓰지 않는다.
5. develop 통합 배포는 모든 필수 non-AI 검증이 성공한 뒤 `current`를 한 번만 갱신한다. 컴포넌트별 중간 승격은 금지한다.
6. 실패 시 candidate와 evidence는 보존한다. 안전 분류가 `SAFE`인 허용 실패만 known-good으로 자동 rollback하며 반복 rollback은 하지 않는다.

운영 백업/복구 절차와 보존 기간은 EC2 디스크 크기 측정 후 확정한다.
