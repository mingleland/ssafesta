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
    }

    public enum PlayerEmoteId : byte
    {
        None = 0,
        Greeting = 1,
        Salute = 2,
        King = 3,
        GangnamStyle = 4,
        Defeat = 5,
        Praying = 6,
        Twerk = 7,
        JoyfulJump = 8,
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
