using Festa.Content;
using Festa.Integration;
using UnityEngine;

namespace Festa.Minigame
{
    /// <summary>
    /// 게임 부스 오브젝트를 클릭하면 미니게임을 연다 (spec 014 FR-001, T010).
    ///
    /// <see cref="LaptopInteractable"/>·<see cref="Festa.Content.AI.AiNpcInteractable"/> 와 같은 형태다 —
    /// 클릭 감지는 중앙 디스패처(<see cref="BoothInteractionInput"/>)가 하고 여기서는 열기만 한다.
    /// `OnMouseDown` 은 Unity 6 WebGL 에서 발생하지 않는다 (T-166).
    ///
    /// **월드를 건드리지 않는다** (FR-007). 게임은 화면 위에 얹힐 뿐이고,
    /// 실패하거나 중간에 나가도 월드 접속·플레이어 상태에 영향이 없다.
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class MinigameInteractable : MonoBehaviour
    {
        void Awake()
        {
            // 레이캐스트 대상 보장 — 부스 오브젝트에 콜라이더가 없을 수 있다.
            if (GetComponentsInChildren<Collider>(true).Length == 0)
                gameObject.AddComponent<BoxCollider>();

            BoothInteractionInput.Ensure();
        }

        public void Interact()
        {
            ApiServices.EnsureInitialized();
            TimerStopGameHud.Open(ApiServices.Game);
        }
    }
}
