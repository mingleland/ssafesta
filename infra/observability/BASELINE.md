# 관측성 24~72시간 기준선 측정

관측 보존기간·자원 상한·알림 임계치는 EC2 사양과 실제 유입량을 보기 전 확정하지 않는다. 서버 수령 후 최소 24시간, 가능하면 평일 피크를 포함한 72시간 동안 아래 표를 채우고 Infra 승인 후 `.env`와 승인 규칙을 갱신한다.

| 항목 | 시작 | 평균 | 피크/P95 | 승인값 | 근거 링크 |
|---|---:|---:|---:|---:|---|
| 로그 유입 GB/day, lines/s, max line bytes | 미측정 | | | | |
| Prometheus active series / scrape duration | 미측정 | | | | |
| Alloy CPU/RAM/WAL·retry horizon | 미측정 | | | | |
| Loki CPU/RAM/disk/query latency | 미측정 | | | | |
| Prometheus CPU/RAM/TSDB bytes/day | 미측정 | | | | |
| Grafana CPU/RAM/query concurrency | 미측정 | | | | |
| EC2 전체 CPU/RAM/EBS 여유율 | 미측정 | | | | |

## 확정 순서

1. EC2 vCPU/RAM과 EBS 종류·용량을 기록한다.
   cAdvisor 기반 컨테이너 자원 수집은 Alloy의 privileged 접근이 필요하므로 `ALLOY_CADVISOR_PRIVILEGED` 허용 여부와 대체 격리를 별도 승인한다. 미승인 시 컨테이너 자원 패널은 비어 있어도 로그·호스트 지표 수집은 유지한다.
2. 24~72시간 동안 정상·피크 구간의 유입량과 자원 사용량을 수집한다.
3. 피크에 안전계수를 적용해 컨테이너 CPU/RAM/PID 상한과 disk budget을 계산한다.
4. disk budget 안에서 Loki retention, Prometheus retention time/size, Alloy WAL/queue를 정한다. Loki는 24시간 미만으로 두지 않는다.
5. dashboard range/refresh/query concurrency와 alert evaluation/pending/threshold를 실제 노이즈율로 조정한다.
6. load/soak 중 앱 SLO 또는 CI 시간이 악화되면 관측 수집량·query 동시성부터 낮춘다.

단일 EC2가 중단되면 같은 호스트의 Grafana도 알릴 수 없다. host-down 알림은 외부 heartbeat, CloudWatch 또는 별도 Grafana 실패영역을 팀 결정으로 추가해야 한다.
