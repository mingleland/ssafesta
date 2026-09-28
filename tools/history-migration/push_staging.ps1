# 스테이징 저장소의 ref 와 LFS 객체를 새 GitHub 저장소로 올린다 (history 이전 단계).
$ErrorActionPreference = 'Continue'
$env:GIT_TERMINAL_PROMPT = '0'
$env:GCM_INTERACTIVE = 'never'
Set-Location C:\colosair\mingleland-migration\work\staging.git
$log = 'C:\colosair\mingleland-migration\logs'
# LFS 객체 3개는 git lfs push --object-id 로 먼저 올렸다 (--all 은 ref 3천여 개 스캔이 너무 느리다)
git push --progress github 'refs/heads/develop:refs/heads/develop' *> "$log\push-develop.log"
git push --progress github 'refs/heads/*:refs/heads/*' 'refs/tags/*:refs/tags/*' *> "$log\push-all.log"
"exit $LASTEXITCODE" | Out-File "$log\push-done.txt"

