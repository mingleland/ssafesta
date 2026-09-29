# GL2GH 로 GitLab 프로젝트 archive 생성·이전을 시도한다 (토큰은 키링에서 읽어 환경 변수로만 넘긴다).
$out = & glab auth status -t --hostname lab.ssafy.com 2>&1 | Out-String
$env:GITLAB_PAT = ([regex]::Match($out, 'Token found[^:]*:\s*(\S+)')).Groups[1].Value
$env:GH_PAT = (gh auth token)
Set-Location C:\colosair\mingleland-migration
New-Item -ItemType Directory -Force gei | Out-Null
gh gl2gh migrate-repo --gitlab-server-url https://lab.ssafy.com --gitlab-group s15-metaverse-game-sub1 --gitlab-project S15P21A604 --github-org mingleland --github-repo ssafesta-gei-test --archive-path C:\colosair\mingleland-migration\gei\S15P21A604-gl2gh.tar.gz --keep-archive --use-github-storage --target-repo-visibility private --verbose *> logs\gl2gh-try1.log
"exit $LASTEXITCODE" | Out-File -Append logs\gl2gh-try1.log

