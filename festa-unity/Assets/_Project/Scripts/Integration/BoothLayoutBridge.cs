// FE(호스트) → Unity: "이 슬롯의 게시본이 바뀌었다" 를 받는 통로.
// 왜 있는가: Published Layout 조회는 씬 로드 때 한 번만 돌아, 스튜디오에서 배치를 고쳐 게시해도 탭을 새로고침해야
// 월드에 반영됐다(QA 2026-09-08 #11). 그래서 게시·임대에 성공한 쪽(FE)이 여기로 알린다.
//   SendMessage('BoothLayoutBridge', 'ReloadBoothSlot', '7')
//
// 2026-09-14 정정 — 여기 오래 적혀 있던 "부스 오브젝트는 NetworkObject 가 아니라 클라이언트별 Local Spawn 이라
// 월드 서버가 알려 줄 길도 없다" 는 **틀렸다.** 복제해야 하는 것은 오브젝트가 아니라 슬롯 번호 하나다.
// FE 의 SendMessage 는 자기 브라우저 탭에만 닿아서, 임대한 본인만 즉시 보이고 다른 접속자는 새로고침 전까지
// 빈 자리를 봤다(사용자 지적: "이게 우리 프로젝트 기본 전제 조건이야"). 이제 받은 번호를
// BoothLiveSyncNetwork 로 월드 전체에 중계한다.
// 포털로 방에 들어갈 때도 같은 재조회가 돈다(PortalInteractor) — 이 브리지는 "지금 그 방 안에 있는 사람" 까지 갱신하는 보강이다.
using UnityEngine;

namespace Festa.Integration
{
    public class BoothLayoutBridge : MonoBehaviour
    {
        public const string ObjectName = "BoothLayoutBridge";

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        static void AutoRegister()
        {
#if UNITY_SERVER
            return;
#else
            if (GameObject.Find(ObjectName) != null) return;
            var go = new GameObject(ObjectName);
            go.AddComponent<BoothLayoutBridge>();
            DontDestroyOnLoad(go);
#endif
        }

        /// <summary>슬롯 번호(1~12) 문자열. 그 슬롯만 다시 조회해 바뀌었으면 다시 짓는다. 잘못된 값은 경고로 드러낸다.</summary>
        public void ReloadBoothSlot(string slotId)
        {
            if (!int.TryParse(slotId, out var id) || id <= 0)
            {
                Debug.LogWarning($"[BoothLayoutBridge] 슬롯 번호를 읽지 못했다: '{slotId}'");
                return;
            }
            Debug.Log($"[BoothLayoutBridge] 슬롯 {id} 게시 알림 수신 → 재조회");
            Festa.Booth.WorldBoothPublishedBootstrap.RequestReload(id);

            // 그리고 같은 월드에 있는 다른 사람들에게도. 내 화면만 고치는 것으로는 끝이 아니다.
            Festa.Booth.BoothLiveSyncNetwork.Announce(id);
        }
    }
}
