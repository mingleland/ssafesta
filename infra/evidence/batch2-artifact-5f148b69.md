# Batch 2 외부 오프라인 fixture — `5f148b69` 실측 기록

Jira: `S15P21A604-939` / 실측 일시: 2026-09-20 (UTC) / 대상: `/var/tmp/ssafesta-batch2/5f148b69/` (repository 밖, EC2 임시 경로)

## 1. 판정

```text
sourceCommit 5f148b690998ad7534bb272823db1e1d645c22d6
→ 로컬 object DB: 없음 (git cat-file -e 실패)
→ GitLab 프로젝트 1443023 API commits/<sha>: 404
→ remote develop = 699cc162, main = 95e034ff

판정: UNVERIFIABLE_SOURCE → EXTERNAL_OFFLINE_FIXTURE (완전 read-only)
```

허용: ZIP/TAR 구조·manifest·label·contentId·상호 sourceCommit 검증, publisher/validator/registry immutability 테스트의 실물 기준점.
금지: `festa-webgl/5f148b69`·`festa-world/5f148b69` canonical publish, Demo/Production 적용, receipt 입력, `docker load`, repository/LFS 보존.

## 2. 파일 identity (sha256)

| 파일 | 크기 | sha256 |
|---|---:|---|
| `festa-webgl-release-5f148b69.zip` | 97,570,231 | `621d248a2078010e71770352d50377bcb95cd51acc8f05e72795dda6a9740f58` |
| `festa-game-5f148b69.tar` | 98,746,368 | `046cc43812b41a9e9d881cc20c268990ae4ff05164a62e3072d1016b2624b35b` |
| `webgl-manifest.json` | 625 | `141112d447e5bf3c44dfa4893d55948aed512d028f01607886a9ba8a65f63704` |
| `image-metadata.json` | 309 | `358f26b426f9a9f78c5d4e21c5612e9e2560c866a56c075c59ed165b34391141` |

## 3. Offline 검증 결과 (read-only)

- `validate-webgl-archive.sh`: **WEBGL_ARCHIVE_OK** — zip integrity, `index.html`/`manifest.json`/`Build/`/`TemplateData/` 존재, loader/data/framework/wasm 엔트리 실재, 내부 manifest == 외부 `webgl-manifest.json`, `sourceBranch=develop`, `dirty=false`, `buildProfile=release`, `unityVersion=6000.0.78f1`, `apiEnvironment=Prod`, `builtAt=2026-09-19T14:08:36Z`.
- `validate-game-image-archive.sh`: **GAME_ARCHIVE_OK** — OCI layout(`index.json` + `blobs/sha256/*`), `RepoTags=[festa-game:5f148b69…]`, label `component=game`/`managed=true`/`source-commit=5f148b69…`, `contentId sha256:2a335f60…` = OCI index manifest digest(containerd image store 도메인; legacy config digest 는 `sha256:595d1a85…` — 두 도메인은 서로 다르며 validator 는 둘 중 하나의 정확한 일치를 요구한다).
- 상호 lineage: WebGL `sourceCommit` == Game label `source-commit` — **일치**.
- prefab-tree 호환성: commit 이 repository 에 없어 `git rev-parse <sha>:festa-unity/Assets/_Project/Prefabs` 불가 — **UNRESOLVABLE**.

## 4. 이후 처리

첫 canonical WebGL/World artifact 는 Jenkins producer 가 GitLab 에 존재하는 develop commit 에서 생성한 것만 쓴다(§source-identity gate). 이 fixture 는 Batch 2 종료 후 EC2 임시 복사본을 제거해도 된다. Unity 담당자가 `5f148b69` 의 실제 ref 를 제공하면 historical provenance 자료로만 추가한다.
