# GitLab Runner MR Gate Evidence

Date: 2026-09-09 (KST)

## Runner

- Runner: `festa-ec2-docker-runner`
- Executor: Docker
- Docker socket is available to job containers.
- The runner was re-registered and verified as valid before the MR test.

## MR !499 verification

`feature/S15P21A604-154-gitlab-mr-gate` to `develop` completed successfully.

| Job | Result |
| --- | --- |
| `front-test` | passed |
| `front-build` | passed |
| `back-test` | passed |
| `back-build` | passed |

The Back test uses Testcontainers. The runner uses host networking and the job
sets `TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1`, which resolved the Docker socket
network reachability failure observed during the first run.
