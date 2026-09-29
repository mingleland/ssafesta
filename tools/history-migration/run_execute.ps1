# 계획 전체를 이어서 실행한다 (중단되면 이 스크립트를 다시 돌리면 state.json 에서 이어간다).
Set-Location C:\colosair\mingleland-migration
$env:PYTHONIOENCODING = 'utf-8'
python -u tools\execute_plan.py work mingleland/ssafesta *>> logs\execute-run.log
"exit $LASTEXITCODE $(Get-Date -Format s)" | Out-File -Append logs\execute-done.txt

