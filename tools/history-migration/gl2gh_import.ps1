# 직접 구성한 GitLab export archive 를 GL2GH 로 가져온다.
$env:GH_PAT = (gh auth token)
Set-Location C:\colosair\mingleland-migration
gh gl2gh migrate-repo --archive-path C:\colosair\mingleland-migration\gei\S15P21A604-export.tar.gz --gitlab-group s15-metaverse-game-sub1 --gitlab-project S15P21A604 --github-org mingleland --github-repo ssafesta-gei-test --use-github-storage --keep-archive --target-repo-visibility private --verbose *> logs\gl2gh-try3.log
"exit $LASTEXITCODE" | Out-File -Append logs\gl2gh-try3.log

