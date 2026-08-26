using Unity.Collections;
using Unity.Netcode;
using UnityEngine;

namespace Festa.Network
{
    public enum PlayerAnimState : byte
    {
        Idle = 0,
        Walk = 1,
        Run = 2,
        // 값을 뒤에 붙인다 — byte 백업이라 기존 값의 의미가 바뀌지 않아 호환이 유지된다.
        Jump = 3,
        // 도약 **직전** 의 발 구르기. 몸은 아직 바닥에 붙어 있다 — 이게 화면에 먼저
        // 나와야 점프로 읽힌다 (T-184).
        JumpLaunch = 4,
        // 착지 흡수·복귀. 공중 클립이 루프(Fall01)로 바뀌면서 착지 동작이 클립에
        // 들어 있지 않게 됐다 — 별도 상태로 낸다 (2026-08-25 점프 3단 재구성).
        JumpLand = 5,
    }

    /// <summary>
    /// 감정표현 식별자. 네트워크로 byte 하나만 오가고, 각 값은 애니메이터의
    /// `Emote_{이름}` 상태와 **이름으로** 짝지어진다 (PlayerAvatarVisual.ApplyEmote).
    /// 값을 바꾸면 상태 이름도 함께 바꿔야 한다.
    ///
    /// 2026-08-25 개정 — 엑스포·축제 톤으로 교체했다. 이전 세트(강남스타일·트월킹·
    /// 왕의 자세·패배)는 기업 부스가 있는 행사장 표현으로 맞지 않았다.
    /// 클립은 Kevin Iglesias Human Animations (Humanoid 리타게팅).
    /// </summary>
    public enum PlayerEmoteId : byte
    {
        None = 0,
        Greeting = 1,     // HandWave01 — 인사
        Clap = 2,         // HandClap01 — 박수
        Cheer = 3,        // Cheer01 — 환호
        Nod = 4,          // HeadNod01 — 끄덕임
        Laugh = 5,        // Laugh01 — 웃음
        SitGround = 6,    // SitGround01 - Loop — 앉기 (루프)
        Drink = 7,        // Drink01_R - Loop — 건배 (루프)
        Thanks = 8,       // Reverence01 — 감사
    }

    /// <summary>
    /// 네트워크로 동기화되는 Player 상태 (doc 16 §4 NetworkVariables 후보 기준).
    /// nickname/avatarCode는 서버가 승인된 세션 데이터로만 기록한다 —
    /// 클라이언트가 임의로 다른 userId/닉네임을 주장할 수 없다 (doc 12 §12).
    /// </summary>
    public class NetworkPlayer : NetworkBehaviour
    {
        // NGO 제약: NetworkVariable은 필드로 선언 (프로퍼티는 ILPP 인식 불가)
        public readonly NetworkVariable<long> UserId =
            new(0, NetworkVariableReadPermission.Everyone, NetworkVariableWritePermission.Server);

        public readonly NetworkVariable<FixedString32Bytes> Nickname =
            new(default, NetworkVariableReadPermission.Everyone, NetworkVariableWritePermission.Server);

        public readonly NetworkVariable<FixedString32Bytes> AvatarCode =
            new(default, NetworkVariableReadPermission.Everyone, NetworkVariableWritePermission.Server);

        public readonly NetworkVariable<int> CurrentBoothId =
            new(-1, NetworkVariableReadPermission.Everyone, NetworkVariableWritePermission.Server);

        public readonly NetworkVariable<PlayerAnimState> AnimState =
            new(PlayerAnimState.Idle, NetworkVariableReadPermission.Everyone, NetworkVariableWritePermission.Owner);

        public readonly NetworkVariable<PlayerEmoteId> EmoteId =
            new(PlayerEmoteId.None, NetworkVariableReadPermission.Everyone, NetworkVariableWritePermission.Owner);

        public override void OnNetworkSpawn()
        {
            if (IsServer)
            {
                var session = SessionDataStore.Get(OwnerClientId);
                if (session != null)
                {
                    UserId.Value = session.userId;
                    Nickname.Value = session.nickname ?? "Unknown";
                    // AvatarCode is retained for legacy preset clients and is only
                    // 32 bytes long.  A full modular appearance is synchronized by
                    // PlayerAppearanceController.Encoded after the player object is
                    // spawned, so never truncate or assign the long `fa|...` payload
                    // here: FixedString overflow aborts the spawn initialization.
                    var legacyAvatarCode = session.avatarCode;
                    AvatarCode.Value = !string.IsNullOrEmpty(legacyAvatarCode) && legacyAvatarCode.Length <= 31
                        ? legacyAvatarCode
                        : Festa.World.AvatarAppearance.DefaultPreset;
                }
            }

            Debug.Log($"[NetworkPlayer] Spawned owner={OwnerClientId} isOwner={IsOwner}");
        }

        public override void OnNetworkDespawn()
        {
            Debug.Log($"[NetworkPlayer] Despawned owner={OwnerClientId}");
        }

        // ---------- Booth Entry (doc 16 §4 Server RPC 후보) ----------

        [Rpc(SendTo.Server)]
        public void EnterBoothServerRpc(int boothId)
        {
            // TODO(P1): booth 존재/거리 검증
            CurrentBoothId.Value = boothId;
        }

        [Rpc(SendTo.Server)]
        public void ExitBoothServerRpc()
        {
            CurrentBoothId.Value = -1;
        }

    }
}
