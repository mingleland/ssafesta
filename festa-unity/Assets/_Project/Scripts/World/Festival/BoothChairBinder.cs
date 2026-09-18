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
        const int ScanInterval = 90;     // 프레임 — 1.5 초쯤

        int _lastReported = -1;

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
            if (Time.frameCount % ScanInterval != 0) return;

            var root = GameObject.Find("@BoothInteriors");
            if (root == null) return;
            int attached = Scan(root.transform);

            // **한 번 붙이고 끝내지 않는다.** 처음에는 12부스를 다 붙이면 스스로 꺼졌는데, 인테리어가
            // 그 뒤에 다시 만들어지면서 붙여 둔 컴포넌트가 통째로 사라졌다 — 실측하니 48개가 0개가 됐고
            // 사용자 화면에서는 "어떤 의자만 앉힌다" 로 보였다 (2026-09-18).
            // 다시 짓는 쪽을 쫓아다니는 대신 계속 지켜본다. 1.5 초에 한 번 Find 12회 + 컴포넌트 조회 48회라
            // 비용이 무시할 수준이고, 무엇이 언제 다시 짓든 빠지지 않는다.
            if (attached > 0 && attached != _lastReported)
            {
                _lastReported = attached;
                Debug.Log($"[BoothChairBinder] 좌석 {attached}개에 착석을 붙였다");
            }
        }

        int Scan(Transform root)
        {
            int attachedNow = 0;
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
                    if (seat.GetComponent<BoothChairInteractable>() != null) continue;
                    var chair = seat.gameObject.AddComponent<BoothChairInteractable>();
                    chair.SetSeatHeightRatio(SeatRatioOf(seat));
                    attachedNow++;
                }
            }
            return attachedNow;
        }

        static bool IsSeatName(string name) =>
            name.StartsWith("Chair") || name.StartsWith("Stool") || name.StartsWith("Seat");

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
