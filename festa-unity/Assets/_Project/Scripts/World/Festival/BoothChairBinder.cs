// 12부스 인테리어에 저작해 둔 좌석에 착석 기능을 붙인다 (2026-09-18).
// 이 파일이 있는 이유: 좌석은 씬에 이미 놓여 있고 게시본과 무관하다. 게시 여부에 얽매이면
// 임대 전 부스에서는 앉을 수 없게 되는데, 의자에 앉는 것은 부스 콘텐츠가 아니라 월드 가구다.
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// <c>@BoothInteriors/Interior_NN/BoothSlot_N/Studio</c> 아래의 좌석 4종에
    /// <see cref="BoothChairInteractable"/> 를 붙인다.
    ///
    /// <para><b>씬을 고치지 않는다.</b> 12부스 × 4좌석 = 48개를 저작 씬에 손으로 다는 것은 되돌리기 어렵고,
    /// 좌석 이름이 바뀌면 조용히 끊긴다. 런타임에 이름으로 찾아 붙이면 한 곳만 보면 된다.</para>
    ///
    /// <para>인테리어는 월드가 서는 동안 순차로 생길 수 있어 한 번만 훑지 않는다 — 다 찾거나 제한 시간이
    /// 지날 때까지 몇 초 간격으로 다시 본다. 이미 붙은 좌석은 건너뛴다.</para>
    /// </summary>
    public sealed class BoothChairBinder : MonoBehaviour
    {
        const string ObjectName = "BoothChairBinder";
        const int InteriorCount = 12;
        const int ScanInterval = 300;    // 프레임 — 안전망은 5초, 게시본 재구성은 이벤트로 즉시 받는다

        int _lastReported = -1;
        int _lastSeatCount = -1;
        Transform _root;
        static bool s_rescanRequested;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void AutoRegister()
        {
            if (Application.isBatchMode || GameObject.Find(ObjectName) != null) return;
            var go = new GameObject(ObjectName);
            go.AddComponent<BoothChairBinder>();
            DontDestroyOnLoad(go);
        }

        void Update()
        {
            if (!s_rescanRequested && Time.frameCount % ScanInterval != 0) return;
            s_rescanRequested = false;

            var root = ResolveRoot();
            if (root == null) return;
            int attached = Scan(root, out int seats);

            // **한 번 붙이고 끝내지 않는다.** 처음에는 12부스를 다 붙이면 스스로 꺼졌는데, 인테리어가
            // 그 뒤에 다시 만들어지면서 붙여 둔 컴포넌트가 통째로 사라졌다 — 실측하니 48개가 0개가 됐고
            // 사용자 화면에서는 "어떤 의자만 앉힌다" 로 보였다 (2026-09-18).
            // 다시 짓는 쪽을 쫓아다니되, 게시본 재구성 알림이면 즉시, 그 외에는 5초 안전망으로만 확인한다.
            // 평상시 전수 탐색을 매 1.5초마다 반복해 WebGL GC를 자극하지 않으면서도 재생성은 놓치지 않는다.
            if ((attached > 0 && attached != _lastReported) || seats != _lastSeatCount)
            {
                _lastReported = attached;
                _lastSeatCount = seats;
                Debug.Log($"[BoothChairBinder] 착석 대상 {seats}개 중 새 연결 {attached}개");
                if (seats > InteriorCount * 4)
                    Debug.LogWarning($"[BoothChairBinder] 기대 좌석 수(48)를 넘는 {seats}개를 발견했다 — 저작 계층의 장식용 Chair/Stool 이름을 확인하라.");
            }
        }

        void OnEnable() => Festa.Booth.WorldBoothPublishedBootstrap.BoothsRebuilt += RequestRescan;
        void OnDisable() => Festa.Booth.WorldBoothPublishedBootstrap.BoothsRebuilt -= RequestRescan;
        static void RequestRescan() => s_rescanRequested = true;

        int Scan(Transform root, out int seats)
        {
            int attachedNow = 0;
            seats = 0;
            for (int slot = 1; slot <= InteriorCount; slot++)
            {
                var studio = root.Find($"Interior_{slot:00}/BoothSlot_{slot}/Studio");
                if (studio == null) continue;

                // **이름 목록을 박아 두지 않는다.** 처음에는 Chair_L1·Stool_R1 네 개를 적어 뒀는데,
                // 부스마다 좌석 이름이 Chair_1·Chair_2·Stool_1·Stool_2 로 제각각이라 12부스 중 한 곳만
                // 앉을 수 있었다 (사용자 지적 2026-09-18 "파란 의자만 안 된다").
                // Studio 바로 아래의 Chair*/Stool* 을 전부 잡는다.
                foreach (Transform seat in studio)
                {
                    if (!IsSeatName(seat.name)) continue;
                    seats++;
                    if (seat.GetComponent<BoothChairInteractable>() != null) continue;
                    var chair = seat.gameObject.AddComponent<BoothChairInteractable>();
                    chair.SetSeatHeightRatio(SeatRatioOf(seat));
                    attachedNow++;
                }

                // 파티션·전시 벽은 **콜라이더가 아예 없어 통과된다** (실측 2026-09-18: Panel_Back x3 ·
                // Panel_Side x6 · ProjectPanel_LED 전부 0개). 부스 안이 방으로 읽히려면 막혀야 한다.
                // 좌석과 같은 자리에서 처리하는 이유는 같은 이유로 사라지기 때문이다 — 인테리어가 다시
                // 만들어지면 붙인 것이 통째로 없어지므로, 여기서 함께 계속 지켜본다.
                foreach (Transform part in studio)
                {
                    if (!IsSolidName(part.name)) continue;
                    attachedNow += EnsureColliders(part);
                }
            }
            return attachedNow;
        }

        static bool IsSeatName(string name) =>
            name.StartsWith("Chair") || name.StartsWith("Stool") || name.StartsWith("Seat");

        /// <summary>
        /// 인테리어 루트. <b><c>GameObject.Find</c> 로는 안 된다</b> — 그 함수는 활성 오브젝트만 찾는데,
        /// 부스 밖에 있으면 <c>BoothInteriorCulling</c> 이 인테리어를 통째로 꺼 둔다. 그래서 축제장을
        /// 걸어 다니는 동안에는 루트를 못 찾아 좌석·콜라이더가 하나도 붙지 않았다 (실측 2026-09-18:
        /// 좌석 48개 중 0개). 씬 루트 목록은 꺼진 것도 포함한다.
        /// </summary>
        Transform ResolveRoot()
        {
            if (_root != null) return _root;
            var scene = UnityEngine.SceneManagement.SceneManager.GetActiveScene();
            if (!scene.isLoaded) return null;
            foreach (var go in scene.GetRootGameObjects())
                if (go.name == "@BoothInteriors") { _root = go.transform; return _root; }
            return null;
        }

        /// <summary>막혀 있어야 하는 것 — 칸막이 패널과 전시 벽.</summary>
        static bool IsSolidName(string name) =>
            name.StartsWith("Panel_") || name.StartsWith("ProjectPanel");

        /// <summary>
        /// 메시가 있는 자식마다 <see cref="BoxCollider"/> 를 보장한다. 붙인 수를 돌려준다.
        ///
        /// <para><b>루트가 아니라 메시가 있는 자식에 붙인다.</b> 루트에 붙이면 Unity 가 크기를 재 주지 못해
        /// 1×1×1 짜리가 생긴다 — 얇은 패널에는 전혀 맞지 않는다. 메시와 같은 오브젝트에 붙이면
        /// 그 메시에 정확히 맞춰진다.</para>
        /// </summary>
        static int EnsureColliders(Transform part)
        {
            if (part.GetComponentsInChildren<Collider>(true).Length > 0) return 0;
            int added = 0;
            foreach (var filter in part.GetComponentsInChildren<MeshFilter>(true))
            {
                if (filter == null || filter.sharedMesh == null) continue;
                if (filter.GetComponent<Collider>() != null) continue;
                filter.gameObject.AddComponent<BoxCollider>();
                added++;
            }
            return added;
        }

        /// <summary>
        /// 좌면이 전체 높이의 어디쯤인가. 쓰이는 가구 프리팹이 둘뿐이라 그것으로 가른다 —
        /// <c>FURN_CHAIR_01_*</c> 은 등받이 없는 스툴(전체 0.45 m)이라 꼭대기가 곧 좌면이고,
        /// <c>FURN_CHAIR_02_*</c> 은 등받이 의자(전체 0.79 m)라 좌면이 0.45 m — 비율 0.57 이다.
        /// 색만 다른 변형(WHITE·BLUE·ORANGE)은 같은 형태라 번호만 본다.
        /// </summary>
        static float SeatRatioOf(Transform seat)
        {
            foreach (var t in seat.GetComponentsInChildren<Transform>(true))
                if (t.name.StartsWith("FURN_CHAIR_02")) return 0.57f;
            return 1f;
        }
    }
}
