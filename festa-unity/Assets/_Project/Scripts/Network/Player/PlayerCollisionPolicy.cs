// 플레이어끼리의 물리 충돌 정책 — 코드 한 곳에 못 박는다 (S15P21A604-761, 2026-09-16).
// 이 파일이 있는 이유: 이 값은 ProjectSettings 의 충돌 매트릭스 체크박스 하나인데, 그 체크 하나가
// "벽에서 서로 밀면 밖으로 빠진다" 를 만든다. 설정 파일 diff 에 묻히지 않게 코드로 두고 이유를 적는다.
using UnityEngine;

namespace Festa.Network
{
    /// <summary>
    /// Player 레이어끼리는 물리 충돌을 끈다. 사람끼리 겹치지 않게 하는 것은
    /// <see cref="PlayerMovement"/> 의 부드러운 밀어내기(속도)가 맡는다.
    ///
    /// <para><b>왜 끄는가.</b> 원격 캡슐이 내 CharacterController 에 단단한 벽으로 작용하면, 상대가 밀고
    /// 들어올 때 엔진 디페네트레이션이 나를 임의 거리로 튕겨낸다. 그 방향에 벽이 있으면 얇은 벽은 그대로
    /// 통과한다. 충돌을 끄고 겹침을 속도로 풀면 그 속도가 Move() 를 거치며 벽 판정을 받으므로 뚫을 수 없다.</para>
    ///
    /// <para>레이캐스트·오버랩 질의는 이 설정과 무관하다 — 조준·호버·이름표 차폐·밀어내기 판정은 그대로 동작한다.</para>
    /// </summary>
    public static class PlayerCollisionPolicy
    {
        public const string PlayerLayerName = "Player";

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        static void Apply()
        {
            int player = LayerMask.NameToLayer(PlayerLayerName);
            if (player < 0)
            {
                Debug.LogError($"[PlayerCollisionPolicy] '{PlayerLayerName}' 레이어가 없다 — 사람끼리 단단히 부딪혀 벽을 뚫을 수 있다");
                return;
            }
            Physics.IgnoreLayerCollision(player, player, true);
            Debug.Log("[PlayerCollisionPolicy] Player↔Player 물리 충돌 해제 — 겹침은 PlayerMovement 가 속도로 푼다");
        }
    }
}
