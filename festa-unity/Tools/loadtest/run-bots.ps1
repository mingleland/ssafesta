# 부하 테스트 봇 실행기 (GitLab #89 2단계)
#
# 사용:
#   .\run-bots.ps1 -Count 10                 # 봇 10기 띄우기
#   .\run-bots.ps1 -Count 10 -Addr 127.0.0.1 -Port 7777
#   .\run-bots.ps1 -Stop                     # 전부 종료
#
# 전제: 에디터를 Host 로 띄워 둔다 (DevConnectionHud > Start Host).
#       봇은 클라이언트로 접속하고, 측정은 에디터에서 읽는다
#       (PerfHud = 프레임/메모리, RuntimeNetStatsMonitor = 수신 대역폭).
#
# 주의: 봇 1기 = 프로세스 1개다. 메모리가 곧 한계이므로 5기씩 늘리며
#       작업 관리자로 여유를 확인하고 올려라.

param(
    [int]$Count = 10,
    [string]$Addr = "127.0.0.1",
    [int]$Port = 7777,
    [switch]$Stop
)

$ErrorActionPreference = "Stop"
$exe = Join-Path $PSScriptRoot "..\..\Builds\bot\festa-bot.exe"

if ($Stop) {
    $procs = Get-Process -Name "festa-bot" -ErrorAction SilentlyContinue
    if ($procs) { $procs | Stop-Process -Force; Write-Host "봇 $($procs.Count)기 종료" }
    else { Write-Host "실행 중인 봇 없음" }
    return
}

if (-not (Test-Path $exe)) {
    Write-Error "봇 빌드가 없다: $exe`nUnity 메뉴 [Festa > 부하테스트 > 봇 클라이언트 빌드] 를 먼저 실행해라."
    return
}

Write-Host "봇 $Count 기 → ws://${Addr}:${Port}"
for ($i = 1; $i -le $Count; $i++) {
    $name = "bot{0:D2}" -f $i
    Start-Process -FilePath $exe -ArgumentList @(
        "-batchmode", "-nographics", "-bot",
        "-addr", $Addr, "-port", $Port, "-botName", $name
    ) -WindowStyle Hidden
    Start-Sleep -Milliseconds 400   # 동시 접속 폭주로 승인 큐가 막히지 않게 간격을 둔다
}

$running = (Get-Process -Name "festa-bot" -ErrorAction SilentlyContinue).Count
$mem = (Get-Process -Name "festa-bot" -ErrorAction SilentlyContinue |
        Measure-Object WorkingSet64 -Sum).Sum / 1MB
Write-Host ("실행 중 {0}기 · 합계 메모리 {1:N0} MB (1기당 {2:N0} MB)" -f $running, $mem, ($mem / [Math]::Max($running,1)))
Write-Host "종료: .\run-bots.ps1 -Stop"
