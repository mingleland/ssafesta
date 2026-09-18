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

        // 2026-09-10 추가 — 11층 라운지 글자 소파 눕기 (Sleep Anim Pack, Humanoid 리타게팅).
        // 소파 F(LoungeSofaInteractable) 가 무작위로 하나 고른다. 감정표현 휠에는 넣지 않는다.
        // 값만 덧붙였다(기존 값·이름 불변) — 기준선 동결(헌법 27조)은 재구현·리팩터링 금지이고 이건 추가다.
        LieSofa = 9,            // Sleep_Sofa_SleepLoop — 소파에 웅크려 눕기 (루프)
        LieLeft = 10,           // Sleep_Bed_LeftSide_SleepLoop — 왼쪽으로 눕기 (루프)
        LieRight = 11,          // Sleep_Bed_RightSide_SleepLoop — 오른쪽으로 눕기 (루프)
        LieLeftRestless = 12,   // Sleep_Bed_LeftSide_RestlessLoop — 왼쪽 뒤척임 (루프)
        LieRightRestless = 13,  // Sleep_Bed_RightSide_RestlessLoop — 오른쪽 뒤척임 (루프)

        // 2026-09-10 추가 — 축제장 하이 스트라이커(망치 게임). Mine Animations 의 MineStart 를 리타게팅한 원샷.
        // 감정표현 휠에는 넣지 않는다 — HighStrikerInteractable 만 재생한다. 재생 중에는 손에 망치가 생긴다
        // (AvatarStrikeProp). 여기서도 값만 덧붙였다 (기준선 동결은 재구현 금지이지 확장 금지가 아니다).
        Strike = 14,            // MineStart — 내리찍기 (원샷, 임팩트 1.16 s)

        // 2026-09-15 추가 — 좌클릭 주먹질(사용자 지시). Mixamo Boxing 3종을 리타게팅한 원샷이고
        // **효과는 없다. 애니메이션만 나간다.** 감정표현 휠에는 넣지 않는다 — 휠은 고르는 것이고
        // 이것은 누르면 바로 나가는 동작이다.
        //
        // 값을 셋으로 나눈 이유: 어느 것이 나올지는 **소유자가 뽑아 EmoteId 로 복제**해야
        // 모두가 같은 동작을 본다. 하나의 값으로 두고 각자 클립을 고르면 화면마다 다른 주먹이 나간다.
        Punch1 = 15,            // Boxing 01 — 주먹질 (원샷)
        Punch2 = 16,            // Boxing 02 — 주먹질 (원샷)
        Punch3 = 17,            // Boxing 03 — 주먹질 (원샷)

        // 2026-09-18 추가 — 부스 안 의자 착석(사용자 지시). Mixamo 앉기 2종을 리타게팅한 루프다.
        // 감정표현 휠에는 넣지 않는다 — 의자에 붙어 F 를 눌러야 나온다.
        //
        // 값을 둘로 나눈 이유는 주먹질과 같다: 어느 자세가 나올지는 **앉는 사람이 뽑아 EmoteId 로
        // 복제**해야 모두가 같은 모습을 본다. 하나로 두고 각자 고르면 화면마다 다른 자세가 보인다.
        SitChair1 = 18,         // SitChair1 — 의자에 앉아 이야기 (루프)
        SitChair2 = 19,         // SitChair2 — 의자에 앉아 있기 (루프)
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
                    // **바이트로 재서 넣는다.** FixedString32Bytes 는 UTF-8 29바이트까지다.
                    // 한글은 한 자가 3바이트라 10자면 30바이트 — 넘기면 대입에서 예외가 나고
                    // 스폰 초기화가 통째로 중단된다. 그러면 그 사람은 월드에 들어와도 아바타가
                    // 안 뜨고 움직이지도 않는다 (2026-09-08 조사).
                    Nickname.Value = FitFixedString32(session.nickname, "Unknown", "nickname");

                    // AvatarCode is retained for legacy preset clients and is only
                    // 32 bytes long.  A full modular appearance is synchronized by
                    // PlayerAppearanceController.Encoded after the player object is
                    // spawned, so never truncate or assign the long `fa|...` payload
                    // here: FixedString overflow aborts the spawn initialization.
                    //
                    // 예전 가드는 `Length <= 31` 로 **문자 수**를 셌다 — 한글 31자는 93바이트라
                    // 가드를 통과한 뒤 대입에서 던졌다. 같은 결함이 여기에도 있었다.
                    var legacyAvatarCode = session.avatarCode;
                    AvatarCode.Value = Utf8ByteLength(legacyAvatarCode) is > 0 and <= FixedString32Capacity
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

        // ---------- FixedString 안전 대입 ----------

        /// <summary><see cref="FixedString32Bytes"/> 가 담을 수 있는 UTF-8 바이트 수.</summary>
        const int FixedString32Capacity = 29;

        static int Utf8ByteLength(string s) =>
            string.IsNullOrEmpty(s) ? 0 : System.Text.Encoding.UTF8.GetByteCount(s);

        /// <summary>
        /// UTF-8 바이트 기준으로 잘라 넣는다. 넘치면 **자르되 조용히 넘어가지 않는다** —
        /// 스폰을 중단시켜 사람을 통째로 사라지게 하는 것보다 이름이 짧게 나오는 편이 낫고,
        /// 왜 짧아졌는지는 로그에 남아야 다음 사람이 원인을 찾는다 (T-24).
        ///
        /// <para>문자 경계에서 자른다. 바이트로 자르면 한글 한 글자가 반토막 나 깨진 문자가 된다.</para>
        /// </summary>
        static string FitFixedString32(string value, string fallback, string what)
        {
            if (string.IsNullOrEmpty(value)) return fallback;
            if (Utf8ByteLength(value) <= FixedString32Capacity) return value;

            var si = new System.Globalization.StringInfo(value);
            int keep = si.LengthInTextElements;
            string cut = value;
            while (keep > 0)
            {
                cut = System.Globalization.StringInfo.ParseCombiningCharacters(value).Length >= keep
                    ? si.SubstringByTextElements(0, keep)
                    : value;
                if (Utf8ByteLength(cut) <= FixedString32Capacity) break;
                keep--;
            }
            if (keep <= 0) return fallback;

            Debug.LogWarning($"[NetworkPlayer] {what} 가 FixedString32 용량({FixedString32Capacity}바이트)을 넘어 잘랐다 — " +
                             $"'{value}' ({Utf8ByteLength(value)}바이트) → '{cut}' ({Utf8ByteLength(cut)}바이트). " +
                             "한글은 한 자가 3바이트라 10자부터 넘는다.");
            return cut;
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
