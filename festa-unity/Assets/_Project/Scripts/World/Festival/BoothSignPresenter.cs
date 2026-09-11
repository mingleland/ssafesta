using System.Collections.Generic;
using System.Threading.Tasks;
using Festa.Booth;
using Festa.Integration;
using UnityEngine;
using UnityEngine.Networking;

namespace Festa.World
{
    /// <summary>
    /// 축제장 부스 12칸의 <b>바깥 간판</b>에 이름과 전시 이미지를 채운다 (GitLab #171).
    ///
    /// <para><b>값의 출처는 이미 있는 계약 둘이다</b> — 새로 정한 것이 없다.
    /// <c>GET /booths/{id}</c> 의 <c>name</c>·<c>facade.signText</c> (docs/08 §3·§4) 와
    /// <c>GET /booths/{id}/projects/published</c> 의 <c>name</c>·<c>thumbnailUrl</c> (spec 009 §6).</para>
    ///
    /// <para><b>표시 우선순위</b> — 앞의 것이 비면 뒤로 내려간다:
    /// <c>facade.signText</c> → <c>booths.name</c> → <c>projects[0].name</c> → <c>"N번 부스"</c>.
    /// FE 가 사용자에게 어느 칸을 입력받는지 아직 확정 전이라(#171 ②) 셋 다 받는다 —
    /// 어느 쪽이 채워지든 간판에 뭔가는 뜬다.</para>
    ///
    /// <para><b>한 번만 조회한다.</b> <see cref="BoothVacancyPresenter"/> 처럼 주기적으로 쓸어보지 않는다 —
    /// 간판 문구는 임대 중에 바뀌는 값이 아니고, 12칸 × 2요청이라 반복하면 그대로 서버 부하다.
    /// 부스에 들어갔다 나오면 그 칸만 다시 읽는다(<see cref="Refresh"/>) — 스튜디오에서 고치고
    /// 돌아왔을 때 반영되게 하려는 것이다.</para>
    ///
    /// <para><b>이미지가 안 나올 수 있다.</b> WebGL 은 <c>UnityWebRequestTexture</c> 로 굽는 순간
    /// 브라우저 CORS 를 탄다 — <c>Access-Control-Allow-Origin</c> 이 없으면 조용히 실패한다.
    /// FE 의 <c>&lt;img&gt;</c> 로 보인다고 여기서도 보이는 게 아니다(#171 ③). 그래서 실패를
    /// 기본값으로 삼키지 않고 <b>프로젝트명 카드로 떨어뜨리고 경고를 남긴다</b> (T-24 원칙).</para>
    ///
    /// 로컬 표시 전용 — NetworkObject 없음.
    /// </summary>
    public class BoothSignPresenter : MonoBehaviour
    {
        /// <summary>부스 슬롯 수. BoothVacancyPresenter 와 같은 값이어야 한다.</summary>
        public const int SlotCount = 12;

        /// <summary>테스트·진단에서 끌 수 있게 열어 둔다.</summary>
        public static bool Enabled = true;

        static BoothSignPresenter _instance;

        readonly Dictionary<int, BoothSign> _signs = new();
        bool _started;

        /// <summary>
        /// 부스 한 칸에 대해 <b>바깥에서 보이는 것 전부</b>. 표지판과 축제장 지도가 같은 값을 쓴다.
        /// </summary>
        public readonly struct BoothInfo
        {
            public readonly string Name;
            public readonly Texture Thumbnail;   // null 가능 — 없거나 CORS 로 막혔거나
            public readonly bool HasProject;

            public BoothInfo(string name, Texture thumbnail, bool hasProject)
            {
                Name = name; Thumbnail = thumbnail; HasProject = hasProject;
            }
        }

        // 조회 결과를 여기 남긴다. **지도가 따로 부르지 않게** 하려는 것이다 —
        // 일괄 조회 endpoint 가 없어서 12칸 × 2요청이 이미 부담인데(#171 ④), 지도가 같은 것을
        // 다시 물으면 그대로 두 배가 된다.
        static readonly Dictionary<int, BoothInfo> Cache = new();

        /// <summary>그 부스에 대해 조회가 끝났으면 true. 아직 안 왔으면 false.</summary>
        public static bool TryGetInfo(int boothId, out BoothInfo info) => Cache.TryGetValue(boothId, out info);

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Install()
        {
            if (!Enabled || _instance != null) return;
            var go = new GameObject("@BoothSignPresenter");
            DontDestroyOnLoad(go);
            _instance = go.AddComponent<BoothSignPresenter>();
        }

        /// <summary>부스 한 칸을 다시 읽는다. 스튜디오에서 고치고 돌아온 경우를 위한 것이다.</summary>
        public static void Refresh(int boothId)
        {
            if (_instance == null) return;
            _ = _instance.FillOneAsync(boothId);
        }

        void Update()
        {
            // 씬이 서는 시점과 이 컴포넌트가 붙는 시점이 갈릴 수 있어(AfterSceneLoad 는 씬 전환마다 돌지 않는다)
            // 간판을 실제로 찾을 수 있게 될 때까지 기다렸다가 한 번만 채운다.
            if (_started) return;
            var found = FindObjectsByType<BoothSign>(FindObjectsInactive.Include, FindObjectsSortMode.None);
            if (found.Length == 0) return;

            _signs.Clear();
            foreach (var s in found)
                if (s.boothId >= 1 && s.boothId <= SlotCount) _signs[s.boothId] = s;

            _started = true;
            _ = FillAllAsync();
        }

        async Task FillAllAsync()
        {
            // 12칸을 **순차로** 읽는다. 동시에 24요청을 던지면 WebGL 에서 첫 진입이 그만큼 느려지고,
            // 일괄 조회 endpoint 가 없어서 어차피 요청 수는 같다(#171 ④ 에서 목록 endpoint 를 요청해 뒀다).
            // 간판은 서 있기만 해도 부스가 깨져 보이지 않으므로 늦게 채워져도 괜찮다.
            foreach (var kv in _signs)
                await FillOneAsync(kv.Key);
        }

        async Task FillOneAsync(int boothId)
        {
            if (!_signs.TryGetValue(boothId, out var sign) || sign == null) return;

            var api = ApiServices.Booth;
            if (api == null)
            {
                Debug.LogWarning($"[BoothSignPresenter] ApiServices.Booth 가 없다 — 부스 {boothId} 간판은 기본 문구로 둔다.");
                Apply(sign, null, null);
                return;
            }

            BoothDetailDto detail = null;
            BoothProjectsDto projects = null;
            try { detail = await api.GetBoothDetailAsync(boothId); }
            catch (System.Exception e) { Debug.LogWarning($"[BoothSignPresenter] 부스 {boothId} 상세 조회 실패: {e.Message}"); }
            try { projects = await api.GetPublishedProjectsAsync(boothId); }
            catch (System.Exception e) { Debug.LogWarning($"[BoothSignPresenter] 부스 {boothId} 전시 조회 실패: {e.Message}"); }

            var first = projects?.projects != null && projects.projects.Length > 0 ? projects.projects[0] : null;
            Apply(sign, detail, first);

            if (!string.IsNullOrWhiteSpace(first?.thumbnailUrl))
                await LoadThumbnailAsync(sign, boothId, first.thumbnailUrl);
        }

        static void Apply(BoothSign sign, BoothDetailDto detail, BoothProjectDto project)
        {
            string name = FirstNonBlank(
                detail?.facade?.signText,
                detail?.name,
                project?.name,
                $"{sign.boothId}번 부스");

            sign.SetLabel(name);
            // 카드는 여기서 켜지 않는다 — 썸네일이 **실제로 로드됐을 때만** BoothSign.ShowThumbnail 이 켠다.

            // 지도가 읽어 갈 수 있게 남긴다. 썸네일은 나중에 오므로 이 시점에는 아직 null 이다.
            Cache[sign.boothId] = new BoothInfo(name, null, project != null);
        }

        static async Task LoadThumbnailAsync(BoothSign sign, int boothId, string url)
        {
            // https 만 계약이다 (spec 009 §1 URL 규칙). http 는 mixed content 로 브라우저가 막아
            // 요청 자체가 안 나가므로 여기서 걸러 이유를 남긴다 — 조용한 실패로 두지 않는다.
            if (!url.StartsWith("https://"))
            {
                Debug.LogWarning($"[BoothSignPresenter] 부스 {boothId} 썸네일이 https 가 아니다 ('{url}') — " +
                                 "WebGL 에서 mixed content 로 차단된다. 프로젝트명 카드로 둔다.");
                return;
            }

            using var req = UnityWebRequestTexture.GetTexture(url);
            req.timeout = 10;
            await req.SendWebRequest();

            if (req.result != UnityWebRequest.Result.Success)
            {
                Debug.LogWarning($"[BoothSignPresenter] 부스 {boothId} 썸네일 로드 실패 ({req.result}: {req.error}) — {url}\n" +
                                 "WebGL 이면 CORS 헤더(Access-Control-Allow-Origin)를 먼저 의심하라 (#171 ③). 프로젝트명 카드로 둔다.");
                return;
            }

            var tex = DownloadHandlerTexture.GetContent(req);
            if (tex == null || sign == null) return;
            sign.ShowThumbnail(tex);

            if (Cache.TryGetValue(boothId, out var prev))
                Cache[boothId] = new BoothInfo(prev.Name, tex, prev.HasProject);
        }

        static string FirstNonBlank(params string[] values)
        {
            foreach (var v in values)
                if (!string.IsNullOrWhiteSpace(v)) return v.Trim();
            return string.Empty;
        }
    }
}
