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
            PlayerEmoteId.LieSofa => 0.312f,   // 표값 0.307 + 0.005 — 플레이 실측에서 위상 최저가 0.07u(5 mm) 떠서 내림
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
    }
}
