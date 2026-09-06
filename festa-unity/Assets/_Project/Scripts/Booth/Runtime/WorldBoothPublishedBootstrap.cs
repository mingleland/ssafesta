using System.Collections.Generic;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace Festa.Booth
{
    /// <summary>
    /// 월드 내부 Booth 12실에 Published Layout 을 채우는 오케스트레이터 (S15P21A604-172).
    ///
    /// 씬에 아무것도 심지 않는다 — RuntimeInitializeOnLoadMethod 로 설치되고, 씬이 로드될
    /// 때마다 BoothRuntime 앵커가 있으면 슬롯 병렬 조회(-103)를 돌려 채운다. 씬 파일을
    /// 수정하지 않으므로 다른 브랜치의 씬 변경과 충돌하지 않는다.
    ///
    /// 왜 BoothRuntime._loadOnStart 를 켜지 않았나 (Jira -172 원문과의 이탈):
    /// 앵커의 _boothId 는 방 번호(1~12) = **slotId** 다. _loadOnStart 경로는 이 번호를
    /// boothId 로 삼아 owner 엔드포인트(/booths/{id})를 부른다 — 슬롯 3 을 부스 7 이
    /// 임차 중이면 엉뚱한 부스를 그린다. 올바른 신원 해석(slot → 임차 부스)은 서버가
    /// visitor 경로(/booth-slots/{slotId})에서 해 주므로 이쪽으로 우회한다.
    ///
    /// 미게시 슬롯은 조회가 null 로 끝나 Rebuild 를 부르지 않는다 = 기본 프레임 유지
    /// (완료 조건 ②). 서버(-batchmode)는 부스 비주얼이 필요 없어 설치하지 않는다.
    /// </summary>
    public static class WorldBoothPublishedBootstrap
    {
        /// <summary>비주얼 검증·부하 측정에서 끄고 비교할 수 있게 (PerfHud 패턴).</summary>
        public static bool Enabled = true;

        /// <summary>
        /// 첫 조회가 끝났는가(성공·실패 무관). 포털이 "미게시 부스" 를 판정할 때 쓴다 — 조회 전에는 모든 방이
        /// <see cref="BoothRuntime.IsLoaded"/> false 라 게시된 부스까지 막게 되므로, 끝나기 전에는 판정하지 않는다 (S15P21A604-453).
        /// </summary>
        public static bool Completed { get; private set; }

        static bool s_Loading;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Install()
        {
            if (Application.isBatchMode) return;   // 데디케이티드 서버 — 로컬 비주얼 없음
            SceneManager.sceneLoaded += (_, _) => TryLoad();
            TryLoad();   // 첫 씬이 이미 월드인 경우 (에디터에서 main 직접 실행)
        }

        static async void TryLoad()
        {
            if (!Enabled || s_Loading) return;

            var runtimes = Object.FindObjectsByType<BoothRuntime>(FindObjectsSortMode.None);
            if (runtimes.Length == 0) return;   // 로비 등 부스 없는 씬

            s_Loading = true;
            try
            {
                // 방 번호(BoothId 직렬화 값) = slotId. 이미 채워진 방은 건너뛴다 —
                // additive 로드·재입장에서 중복 Rebuild 를 막는다.
                var bySlot = new Dictionary<int, BoothRuntime>(runtimes.Length);
                var slotIds = new List<int>(runtimes.Length);
                foreach (var r in runtimes)
                {
                    if (r.IsLoaded || bySlot.ContainsKey(r.BoothId)) continue;
                    bySlot.Add(r.BoothId, r);
                    slotIds.Add(r.BoothId);
                }
                if (slotIds.Count == 0) return;

                var layouts = await PublishedLayoutLoader.LoadAllAsync(slotIds);

                int built = 0;
                foreach (var slotId in slotIds)
                {
                    var layout = layouts[slotId];
                    if (layout == null) continue;              // 미게시 — 기본 프레임 유지
                    var runtime = bySlot[slotId];
                    if (runtime == null) continue;             // 씬 전환으로 파괴된 앵커
                    runtime.Rebuild(layout);
                    built++;
                }
                Debug.Log($"[WorldBoothPublishedBootstrap] {slotIds.Count}실 중 {built}실 게시 렌더, 나머지는 기본 프레임");
            }
            catch (System.Exception e)
            {
                // 부스 채우기 실패가 월드 진입을 막으면 안 된다 — 드러내되 계속 간다.
                Debug.LogError($"[WorldBoothPublishedBootstrap] 로드 실패 — 부스는 기본 프레임으로 남는다: {e.Message}");
            }
            finally
            {
                s_Loading = false;
                Completed = true;
            }
        }
    }
}
