# GitLab Merge Policy Evidence

Date: 2026-09-09 (KST)

## `develop` branch rule

| Setting | Applied value |
| --- | --- |
| Allowed to merge | Developers and Maintainers |
| Allowed to push and merge directly | No one |
| Allow force push | disabled |

## Merge request check

| Setting | Applied value |
| --- | --- |
| Pipelines must succeed | enabled |
| Skipped pipelines are considered successful | disabled |

The values were verified in GitLab project settings after saving. A merge
request targeting `develop` therefore requires its latest GitLab pipeline to
finish successfully; direct pushes to `develop` are blocked.
