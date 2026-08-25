# SSAFESTA 데일리 플래닝 — Windows 작업 스케줄러용 headless 실행기
# 등록: schtasks (SSAFESTA\Daily Plan) · 평일 08:56
# 로그: .claude\logs\daily-plan-YYYY-MM-DD.md
$ErrorActionPreference = 'Continue'

$repo   = 'C:\Users\SSAFY\Desktop\SSAFESTA'
$logDir = Join-Path $repo '.claude\logs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }

$stamp = Get-Date -Format 'yyyy-MM-dd'
$log   = Join-Path $logDir "daily-plan-$stamp.md"
$claude = 'C:\Users\SSAFY\.local\bin\claude.exe'

Set-Location $repo

"# 데일리 플래닝 $stamp $(Get-Date -Format 'HH:mm')" | Out-File -FilePath $log -Encoding utf8
"" | Out-File -FilePath $log -Encoding utf8 -Append

$out = & $claude -p "/daily-plan" `
    --allowedTools "Bash,Read,Write,Edit,Glob,Grep" `
    2>&1 | Out-String

$out | Out-File -FilePath $log -Encoding utf8 -Append

if ($LASTEXITCODE -ne 0 -or $out -match 'Failed to authenticate') {
    @"

---
⚠ 실행 실패 (exit=$LASTEXITCODE)

headless 인증이 만료됐을 가능성이 높다. 터미널에서 대화형으로 한 번 로그인하면 복구된다:
    claude          (실행 후 /login)
또는 API 키 사용 시:
    setx ANTHROPIC_API_KEY "<키>"
"@ | Out-File -FilePath $log -Encoding utf8 -Append
    exit 1
}
exit 0
