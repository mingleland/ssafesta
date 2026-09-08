# Changed Component Contract v1

GitLab CI는 MR의 Front·Back gate를 `rules:changes`로 정하고, Jenkins는 develop Git diff로 CI와 dev 배포 범위를 정하는 계약이다.

## Input

| Field | Rule |
|---|---|
| `eventKind` | `gitlab_merge_request` or `jenkins_develop_push` |
| `baseSha` / `headSha` | Jenkins only: full Git SHA; both must resolve locally |
| `sourceBranch` | GitLab MR only: `feature/*` |
| `targetBranch` | GitLab MR only: `develop` |

GitLab MR selection uses `rules:changes`. Jenkins develop push range is GitLab `before..after`; fallback `HEAD^..HEAD` only for one-parent Squash merge.

## Mapping

| Path | GitLab MR gate | Jenkins CI / dev deploy component |
|---|---|---|
| `festa-ai/**` | none (initial scope) | `ai` on `jenkins_develop_push` |
| `backend/**` | `back` | `back` on `jenkins_develop_push` |
| `festa-frontend/**` | `front` | `front` on `jenkins_develop_push` |
| `festa-unity/**` | none (initial scope) | `game` on `jenkins_develop_push` |
| Back/Front shared contract or CI paths | `back` + `front` | all CI, none deploy |
| Jenkins/deploy shared paths | none | all CI, none deploy |
| documentation/spec-only paths | none | none |

GitLab Front·Back shared paths are `.gitlab-ci.yml`, `ci/**`, and shared API/contract paths. Jenkins shared CI/deploy paths are `Jenkinsfile`, `infra/jenkins/**`, `infra/deploy/scripts/**`, `infra/environments/scripts/**`, `infra/environments/compose/dev/base.yaml`, `infra/environments/config/manifests/dev.json`, and `infra/versions.env`.

## Output

```json
{
  "version": 1,
  "eventKind": "jenkins_develop_push",
  "baseSha": "full-sha",
  "headSha": "full-sha",
  "components": ["back", "front"],
  "deployComponents": ["back", "front"],
  "reasons": ["component-source"],
  "paths": ["backend/...", "festa-frontend/..."]
}
```

Components are unique and ordered `ai`, `back`, `front`, `game`. Empty `components` is a successful no-op. Unknown path under CI, environment, compose, or deployment ownership is an error; no build/deploy may start.

## Event rules

- GitLab MR: Front·Back `rules:changes` gate only; deploy does not exist in this pipeline.
- `jenkins_develop_push`: selected runtime components run CI, then all succeed before any deploy begins.
- Jenkins shared CI: all component CI; never dev deploy.
- docs-only: no CI/CD execution.
