# EC2 서버 수령 및 UFW/TLS 적용 절차

이 문서는 EC2를 실제로 받은 뒤 운영자가 수행한다. 현재 로컬 PC에서는 실행하지 않는다. 서버 제공자의 Security Group에서도 같은 공개 포트 정책을 적용해야 한다.

## 1. 접속을 잃지 않기 위한 준비

1. 같은 키로 SSH 터미널을 2~3개 연결한다.
2. 한 터미널에서 `sudo ufw status verbose`와 `sudo ufw show added`를 기록한다.
3. 현재 접속 원격 주소와 SSH 포트가 22인지 확인한다.
4. UFW를 reset/disable하지 않는다. 제공된 활성 상태를 기준으로 필요한 규칙만 추가한다.

## 2. UFW 공개 포트

아래 순서로 하나씩 적용하고 매번 `sudo ufw status numbered`로 확인한다.

```bash
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw status numbered
```

새 SSH 터미널에서 다시 접속되는지 확인한 뒤 다음 단계로 간다. UFW가 inactive인 신규 서버에만 `sudo ufw enable`을 사용하며, 활성화 직후 새 SSH 세션을 검증한다.

다음 포트는 인터넷에 허용하지 않는다.

- `8080/tcp`: Jenkins는 `127.0.0.1:8080`에서만 수신하고 Nginx를 경유한다.
- `3000/tcp`: Grafana는 추후 reverse proxy 또는 SSH 터널로만 접근한다.
- `50000/tcp`: inbound agent는 WebSocket을 사용하므로 공개하지 않는다.
- Docker daemon/socket 및 Loki/Prometheus/Alloy 내부 포트도 공개하지 않는다.
- Unity `7777`은 게임 접속 구조가 확정될 때 별도 검토하며 이 단계에서 열지 않는다.

## 3. DNS와 TLS 사전 확인

1. 팀 도메인의 A/AAAA 레코드가 제공된 EC2 공인 주소를 가리키는지 외부 DNS 조회로 확인한다.
2. 80/443 Security Group과 UFW 규칙을 모두 확인한다.
3. Certbot 등 승인된 방식으로 인증서를 발급한다.
4. 인증서와 개인 키를 각각 `/etc/nginx/tls/fullchain.pem`, `/etc/nginx/tls/privkey.pem`에 배치하고 root만 쓸 수 있게 한다. 원본 인증서는 저장소에 복사하지 않는다.
5. `nginx -t`가 성공한 뒤에만 reload한다.
6. 외부에서 HTTPS 인증서 체인과 HTTP→HTTPS redirect를 확인한다.

## 4. Jenkins 적용 및 검증

1. `infra/.env.example`을 복사한 비커밋 `infra/.env`에 실제 값을 넣는다.
2. `infra/versions.env`와 `infra/.env`를 함께 로드하여 controller Compose를 렌더링한다.
3. controller를 시작하고 `curl http://127.0.0.1:8080/login` 및 JCasC 로그를 확인한다.
4. Jenkins 웹에서 pre-created node의 agent secret을 확인해 `infra/.env`에 넣고 필요한 profile의 agent만 시작한다.
5. 외부에서 8080/3000/50000이 닫혀 있고 443의 Jenkins만 접근되는지 확인한다.

규칙 삭제가 필요하면 `sudo ufw status numbered`로 현재 번호를 다시 확인한 후 `sudo ufw delete <번호>`를 한 건씩 수행한다. 과거 출력의 번호를 재사용하지 않는다.
