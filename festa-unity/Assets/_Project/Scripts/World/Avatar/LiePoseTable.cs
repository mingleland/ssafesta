// 눕기 이모트(PlayerEmoteId.Lie*) 의 실측표 — 2026-09-10, F_FullBody(F_bodyAvatar) 에 클립을 PlayableGraph 로 샘플해
// (t = 0·25·50·75 %) 스킨 메시를 굽고 루트 기준으로 잰 값. 단위는 모델 원본(m). 런타임에는 외형 배율을 곱해 쓴다.
//
//   클립                              최하단 y   몸 중심 x   발자국(x×z)
//   Idle01 (기준)                     -0.005      0.009      0.55×0.32
//   Sleep_Sofa_SleepLoop               0.307      0.136      1.44×0.51   ← 팩의 소파 좌면 높이(0.31 m)만큼 떠 있다
//   Sleep_Bed_LeftSide_SleepLoop       0.499     -0.122      1.62×0.50   ← 침대 높이(0.50 m)
//   Sleep_Bed_RightSide_SleepLoop      0.516      0.130      1.64×0.54
//   Sleep_Bed_LeftSide_RestlessLoop    0.495     -0.121      1.62×0.52
//   Sleep_Bed_RightSide_RestlessLoop   0.513      0.130      1.64×0.54
//
// 왜 표가 필요한가: 클립의 루트는 팩의 침대·소파 **바닥**에 있고 몸은 그 위 0.3~0.5 m 에 떠 있다. 우리 소파 위에
// 그대로 재생하면 몸이 소파에서 4~7u 떠 보인다. PlayerAvatarVisual 이 크로스페이드 뒤 최하단을 다시 재서 붙이지만
// (SitGround 와 같은 경로) 그 전 0.4 초 동안 떠 있는 게 보이므로, 이 표로 **시작 순간부터** 내려 둔다.
// 몸 중심 x 는 눕는 방향(루트 오른쪽)으로 치우친 양 — LoungeSofaInteractable 이 몸을 글자 중앙에 놓을 때 뺀다.
using Festa.Network;
using UnityEngine;

namespace Festa.World
{
    public static class LiePoseTable
    {
        /// <summary>Idle01 의 최하단(m). 눕기 최하단과의 차가 내려야 할 양이다.</summary>
        public const float IdleMinY = -0.005f;

        public static bool IsLie(PlayerEmoteId e) => e >= PlayerEmoteId.LieSofa && e <= PlayerEmoteId.LieRightRestless;

        /// <summary>루트 기준 몸 최하단(m). 눕기가 아니면 Idle 값.</summary>
        public static float MinY(PlayerEmoteId e) => e switch
        {
            // 웅크림은 전체 최하단(0.307)이 **소파 밖으로 늘어진 발**이라 그 값으로 붙이면 몸통이 1.3u(9 cm) 뜬다
            // (사용자 사진 2026-09-10 11:40, 재현 narrow_g46.png). 본 가중치로 부위별로 재면 몸통 0.397·허벅지 0.396·
            // 정강이 0.351·발 0.307 — 소파에 닿는 면은 몸통·허벅지다. 발은 글자 끝 밖으로 내보낸다(LoungeSofaInteractable).
            PlayerEmoteId.LieSofa => 0.397f,
            PlayerEmoteId.LieLeft => 0.499f,
            PlayerEmoteId.LieRight => 0.516f,
            PlayerEmoteId.LieLeftRestless => 0.495f,
            PlayerEmoteId.LieRightRestless => 0.513f,
            _ => IdleMinY,
        };

        /// <summary>루트 기준 몸 중심의 x 치우침(m, 루트 오른쪽 +). 몸 길이 방향은 항상 루트의 오른쪽 축이다.</summary>
        public static float CenterX(PlayerEmoteId e) => e switch
        {
            PlayerEmoteId.LieSofa => 0.136f,
            PlayerEmoteId.LieLeft => -0.122f,
            PlayerEmoteId.LieRight => 0.130f,
            PlayerEmoteId.LieLeftRestless => -0.121f,
            PlayerEmoteId.LieRightRestless => 0.130f,
            _ => 0f,
        };

        /// <summary>소파 F 가 고르는 후보. 무작위 선택은 로컬에서 하고 EmoteId 로 동기화되므로 남에게도 같은 자세가 보인다.</summary>
        public static readonly PlayerEmoteId[] SofaPoses =
        {
            PlayerEmoteId.LieSofa, PlayerEmoteId.LieLeft, PlayerEmoteId.LieRight,
            PlayerEmoteId.LieLeftRestless, PlayerEmoteId.LieRightRestless,
        };

        /// <summary>
        /// 웅크림(LieSofa)에서 늘어진 발 끝의 x(m, 루트 오른쪽 +) 와 머리 끝의 x. 발은 몸통보다 1.3u 아래로 늘어지므로
        /// 글자 위에 있으면 윗면을 뚫는다 — 발 끝이 글자 끝을 1.5u 넘도록 루트를 밀되, 머리 끝은 반대쪽 끝 +3u 안에 둔다.
        /// 다른 자세는 발이 몸통보다 높아(침대 클립) 해당 없음(0).
        /// </summary>
        public static float DanglingFootEndX(PlayerEmoteId e) => e == PlayerEmoteId.LieSofa ? 0.856f : 0f;
        public static float HeadEndX(PlayerEmoteId e) => e == PlayerEmoteId.LieSofa ? -0.584f : 0f;

        /// <summary>좁은 글자(몸이 넘어가는 소파) 전용 — 웅크림만. 사용자 지시 2026-09-10 "좁은 글자는 웅크림 전용으로".</summary>
        public static readonly PlayerEmoteId[] NarrowSofaPoses = { PlayerEmoteId.LieSofa };

        /// <summary>
        /// 로컬 플레이어가 누워 있는가. 누운 동안은 상호작용 입력(F)·프롬프트·링을 전부 끈다 — 누운 채 F 를 다시 누르면
        /// 소파 위로 다시 텔레포트되며 자세가 바뀌고 몸이 떠 보였다(사용자 사진 2026-09-10). 일어나는 길은 WASD 하나다.
        /// </summary>
        public static bool IsLocalPlayerLying()
        {
            var nm = Unity.Netcode.NetworkManager.Singleton;
            var po = nm != null && nm.LocalClient != null ? nm.LocalClient.PlayerObject : null;
            var np = po != null ? po.GetComponent<NetworkPlayer>() : null;
            return np != null && IsLie(np.EmoteId.Value);
        }
    }
}
