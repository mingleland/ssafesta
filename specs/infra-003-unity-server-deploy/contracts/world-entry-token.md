# World Entry Token Contract v1

- Serialization: compact JWS/JWT
- Algorithm: `HS256` only; `none` and every other algorithm rejected
- Secret: Base64 `CONNECTION_TOKEN_SECRET`, decoded length at least 32 bytes
- TTL: 120 seconds, issued after Unity loading completes
- Issuer/Audience: `ssafesta-backend` / `ssafesta-world`
- Target: `worldId=11F`, `channelId=11F-01`
- Required claims: `jti`, `sub`, `role`, `playerId`, `nickname`, `sessionId`, `worldId`, `channelId`, `iat`, `exp`
- `avatarCode` was removed (S15P21A604-468, GitLab #138). A member's saved appearance ran 411 characters and pushed the grant past the ~1,114 byte cap of Unity's unfragmented Netcode connection request, so members who had saved one could not connect. The game server reads the appearance from the RPC that follows spawn — the path guests and unsaved members always used. The grant's size is therefore independent of appearance data.

The game server verifies signature and claims locally. Client-supplied identity is ignored. Before player creation it atomically consumes `jti`; reused, malformed, altered, expired or wrong-target grants receive `INVALID_TOKEN`. Raw tokens and secrets must never be logged.
