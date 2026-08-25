# Game Runtime Contract v1

| Item | Value |
|---|---|
| service | `demo-game` |
| internal listener | `0.0.0.0:7777`, WebSocket |
| external endpoint | `wss://world.${ROOT_DOMAIN}:443` |
| channel | `11F-01` |
| maximum clients | 40 |
| process user | non-root |
| replay ledger | `/var/lib/festa-world/used-grants.log` persistent volume |
| Nginx idle candidate | 180 seconds |

Only Nginx publishes host ports. Deployment uses the immutable image reference and updates game with `--no-deps`. A candidate is not known-good until internal listener and real external approved WSS connection succeed.
