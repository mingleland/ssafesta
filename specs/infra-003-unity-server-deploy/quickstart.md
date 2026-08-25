# Quickstart: infra-003 validation

## Prerequisites

- immutable Unity server image and compatible WebGL release
- `ROOT_DOMAIN`, `CONNECTION_TOKEN_SECRET` and origin TLS certificate references
- Docker Compose v2 and an internal `festa-demo` network

## Local checks

1. Run `backend/mvnw.cmd test`.
2. Run Unity EditMode tests for the Network Security assembly.
3. Validate `infra/unity-server/compose.yaml` with the required environment values.
4. Confirm no host mapping exposes 7777 and the game container runs as a non-root user.

## External P0

1. Resolve `world.${ROOT_DOMAIN}` and validate certificate chain/hostname.
2. Issue separate member and guest world sessions after WebGL loading.
3. Connect two external browsers, confirm mutual spawn/movement, then leave both without keyboard/mouse input for 10 minutes.
4. Disconnect one browser, confirm removal, request a new grant and manually reconnect within 30 seconds.
5. Reuse the old grant before and after game container restart; both attempts must be rejected.
6. Record evidence with `infra/unity-server/evidence/template.md` and no raw token or Secret.

## Capacity P1

Run external Unity clients at 1, 10, 20, 30 and 40 clients for 10 minutes per stage. Record accepted clients, unexpected disconnects, EC2 CPU/memory/network and restart counts of Backend/AI/web. Repeat the highest successful stage twice before declaring it safe capacity.
