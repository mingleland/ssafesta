# World Entry Token Contract v1

- Serialization: compact JWS/JWT
- Algorithm: `HS256` only; `none` and every other algorithm rejected
- Secret: Base64 `CONNECTION_TOKEN_SECRET`, decoded length at least 32 bytes
- Runtime binding: Backend receives `CONNECTION_TOKEN_SECRET`; game reads the same Secret reference from the read-only path named by `CONNECTION_TOKEN_SECRET_FILE`
- TTL: 120 seconds, issued after Unity loading completes
- Issuer/Audience: `ssafesta-backend` / `ssafesta-world`
- Target: `worldId=11F`, `channelId=11F-01`
- Required claims: `jti`, `sub`, `role`, `playerId`, `nickname`, `avatarCode`, `sessionId`, `worldId`, `channelId`, `iat`, `exp`

The game server verifies signature and claims locally. Client-supplied identity is ignored. Before player creation it atomically consumes `jti`; reused, malformed, altered, expired or wrong-target grants receive `INVALID_TOKEN`. Raw tokens and secrets must never be logged.
