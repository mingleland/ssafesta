// FE(호스트) → Unity: "이 슬롯의 게시본이 바뀌었다" 를 받는 통로.
// 왜 있는가: Published Layout 조회는 씬 로드 때 한 번만 돌아, 스튜디오에서 배치를 고쳐 게시해도 탭을 새로고침해야
// 월드에 반영됐다(QA 2026-09-08 #11). 부스 오브젝트는 NetworkObject 가 아니라 클라이언트별 Local Spawn 이라
// 월드 서버가 알려 줄 길도 없다. 그래서 게시에 성공한 쪽(FE)이 여기로 알린다.
//   SendMessage('BoothLayoutBridge', 'ReloadBoothSlot', '7')
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
        }
    }
}
