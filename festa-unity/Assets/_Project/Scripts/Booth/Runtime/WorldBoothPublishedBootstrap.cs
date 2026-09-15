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

        /// <summary>
        /// 부스 내용물을 실제로 하나 이상 새로 세웠을 때. 거리 컬링처럼 **렌더러 목록을 캐시해 둔 쪽**이
        /// 다시 훑을 계기다 — 그러지 않으면 나중에 스폰된 집기가 목록에 없어 영영 안 꺼진다.
        /// </summary>
        public static event System.Action BoothsRebuilt;

        /// <summary>
        /// 아직 못 채운 방만 다시 시도하는 주기. 조회가 한 번 실패하면 그 방은 세션 내내
        /// <see cref="BoothRuntime.IsLoaded"/> false 로 남아, 실제로는 게시돼 있는 부스인데도 포털이
        /// "아직 준비 중" 으로 막았다 (2026-09-08 조사). 채워진 방은 다시 부르지 않으므로
        /// 전부 채워지면 요청이 0 이 된다.
        /// </summary>
        const float RetrySeconds = 20f;

        /// <summary>재시도 상한. 서버가 정말 미게시라면 영원히 두드릴 이유가 없다.</summary>
        const int MaxRetries = 6;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Install()
        {
            if (Application.isBatchMode) return;   // 데디케이티드 서버 — 로컬 비주얼 없음
            SceneManager.sceneLoaded += (_, _) => { s_retries = 0; PublishedSlotResolution.Clear(); TryLoad(); };
            TryLoad();   // 첫 씬이 이미 월드인 경우 (에디터에서 main 직접 실행)
            RetryLoopAsync();
        }

        static int s_retries;

        /// <summary>슬롯별로 마지막에 적용한 레이아웃의 서명. 같은 서명이면 다시 짓지 않는다.</summary>
        static readonly Dictionary<int, string> s_appliedSignature = new();

        /// <summary>
        /// 한 슬롯만 다시 조회해 **바뀌었으면** 다시 짓는다. 게시 뒤 새로고침 없이 반영되게 하는 경로(QA 2026-09-08 #11).
        /// 부르는 곳: ① 포털로 그 방에 들어갈 때(<c>PortalInteractor</c>), ② FE 가 게시 성공을 알릴 때(<c>BoothLayoutBridge</c>).
        /// 방 하나라 비용은 무시할 수 있고, 서명이 같으면 Rebuild 를 건너뛰어 들어갈 때마다 깜빡이지 않는다.
        /// 미게시(null) 응답이면 기존 모습을 그대로 둔다 — 잠깐의 조회 실패로 방을 비우지 않는다.
        /// </summary>
        public static async void RequestReload(int slotId)
        {
            if (!Enabled || !Application.isPlaying) return;

            // 바깥 표현(간판 문구·부스 대표색)도 같이 다시 읽는다 — 그 값들은 **레이아웃 버전을 바꾸지 않아**
            // 아래 서명 비교로는 걸러지지 않는다. 임대하고 돌아왔는데 간판이 그대로이던 자리다.
            // 목록을 버리는 것은 여기 한 곳에서만 한다 (S15P21A604-659).
            BoothSlotDirectory.Invalidate();
            Festa.World.BoothSignPresenter.Refresh(slotId);
            Festa.World.BoothFacadePresenter.Refresh(slotId);

            BoothRuntime target = null;
            foreach (var r in Object.FindObjectsByType<BoothRuntime>(FindObjectsSortMode.None))
                if (r.BoothId == slotId) { target = r; break; }
            if (target == null) return;

            try
            {
                var layout = await Festa.Integration.ApiServices.Booth.GetPublishedLayoutBySlotAsync(slotId);
                if (layout == null || target == null) return;
                string sig = Signature(layout);
                if (s_appliedSignature.TryGetValue(slotId, out var prev) && prev == sig) return;   // 변화 없음
                target.Rebuild(layout);
                s_appliedSignature[slotId] = sig;
                Debug.Log($"[WorldBoothPublishedBootstrap] 슬롯 {slotId} 게시본 변경 감지 → 다시 지음 (v{layout.version}, 오브젝트 {layout.objects?.Length ?? 0})");
                BoothsRebuilt?.Invoke();
            }
            catch (System.Exception e)
            {
                Debug.LogWarning($"[WorldBoothPublishedBootstrap] 슬롯 {slotId} 재조회 실패 — 기존 모습 유지: {e.Message}");
            }
        }

        /// <summary>버전 + 오브젝트 열(id·type·assetCode·위치·회전·configId). Mock 처럼 version 이 0 이어도 내용 변화를 잡는다.</summary>
        static string Signature(BoothLayoutDto layout)
        {
            var sb = new System.Text.StringBuilder();
            sb.Append('v').Append(layout.version).Append('|');
            if (layout.objects != null)
                foreach (var o in layout.objects)
                {
                    if (o == null) continue;
                    sb.Append(o.objectId ?? o.id).Append(':').Append(o.type).Append(':').Append(o.assetCode).Append(':');
                    if (o.position != null) sb.Append(o.position.x).Append(',').Append(o.position.y).Append(',').Append(o.position.z);
                    sb.Append(':').Append(o.rotationY).Append(':').Append(o.configId).Append(';');
                }
            return sb.ToString();
        }

        /// <summary>
        /// 못 채운 방을 주기적으로 다시 시도한다. <see cref="TryLoad"/> 는 이미
        /// <c>IsLoaded</c> 인 방을 건너뛰므로, 부르기만 하면 남은 방만 조회한다.
        /// </summary>
        static async void RetryLoopAsync()
        {
            while (Application.isPlaying)
            {
                await Awaitable.WaitForSecondsAsync(RetrySeconds);
                if (!Enabled || s_Loading || !Completed) continue;
                if (s_retries >= MaxRetries) continue;

                // 미게시(404)로 확정된 방은 채워지지 않았어도 다시 묻지 않는다 — 릴리스 37d9b4f4 에서 11실 × 6회 헛조회.
                // 일시 실패(네트워크·타임아웃)였거나 아직 답을 못 받은 방만 센다.
                int pending = 0;
                foreach (var r in Object.FindObjectsByType<BoothRuntime>(FindObjectsSortMode.None))
                    if (!r.IsLoaded && PublishedSlotResolution.NeedsRetry(r.BoothId)) pending++;
                if (pending == 0) continue;

                s_retries++;
                Debug.Log($"[WorldBoothPublishedBootstrap] 조회가 일시 실패한 방 {pending}실 — 재시도 {s_retries}/{MaxRetries}");
                TryLoad(onlyUnresolved: true);
            }
        }

        /// <param name="onlyUnresolved">true 면 확정 답(200/404/409)을 이미 받은 방은 건너뛴다 — 재시도 루프용.</param>
        static async void TryLoad(bool onlyUnresolved = false)
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
                    if (onlyUnresolved && !PublishedSlotResolution.NeedsRetry(r.BoothId)) continue;
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
                    s_appliedSignature[slotId] = Signature(layout);   // 첫 입장 때 같은 것을 다시 짓지 않게
                    built++;
                }
                Debug.Log($"[WorldBoothPublishedBootstrap] {slotIds.Count}실 중 {built}실 게시 렌더, 나머지는 기본 프레임");

                // 방금 스폰한 집기들을 아는 쪽에 알린다. 이 알림이 없으면 거리 컬링은
                // Awake 시점의 빈 방 렌더러만 알고 있어, 정작 무거운 것들이 안 꺼진다.
                // 이벤트로 뒤집은 이유: Festa.Booth 는 Festa.World 를 참조하지 않는다(방향이 반대다).
                if (built > 0) BoothsRebuilt?.Invoke();
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
