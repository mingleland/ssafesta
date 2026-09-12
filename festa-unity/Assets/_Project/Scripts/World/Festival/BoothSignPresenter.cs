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
    /// <para><b>값의 출처는 슬롯 목록 하나와 전시 조회 하나다.</b>
    /// <c>GET /api/v1/booth-slots</c> 가 12칸의 <c>boothId</c>·<c>boothName</c>·<c>facade</c> 를 한 번에 주고
    /// (BE S15P21A604-622), 전시 이미지는 임대된 칸의 <b>진짜 boothId</b> 로
    /// <c>GET /api/v1/booths/{boothId}/projects/published</c> 를 부른다 (spec 009 §6).</para>
    ///
    /// <para><b>칸 번호를 부스 식별자로 쓰지 않는다.</b> 씬의 간판은 칸 번호(1~12)만 아는데 그 번호로
    /// 부스를 조회하면 남의 부스가 나온다 — 실제로 슬롯 1 간판에 슬롯 3 임차인의 문구가 떴다
    /// (S15P21A604-658). 둘을 잇는 정본은 슬롯 목록뿐이다.</para>
    ///
    /// <para><b>표시 우선순위</b> — 앞의 것이 비면 뒤로 내려간다:
    /// <c>facade.signText</c> → <c>boothName</c> → <c>projects[0].name</c> → <c>"N번 부스"</c>.
    /// FE 도 같은 순서를 쓴다(<c>BoothMiniPreview</c>, #171 ② 회신).</para>
    ///
    /// <para><b>한 번만 조회한다.</b> <see cref="BoothVacancyPresenter"/> 처럼 주기적으로 쓸어보지 않는다 —
    /// 간판 문구는 임대 중에 바뀌는 값이 아니다. 부스에 들어갔다 나오면 그 칸만 다시 읽는다
    /// (<see cref="Refresh"/>) — 스튜디오에서 고치고 돌아왔을 때 반영되게 하려는 것이다.</para>
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

        // 조회 결과를 여기 남긴다. **지도가 따로 부르지 않게** 하려는 것이다 — 지도가 같은 것을
        // 다시 물으면 요청이 그대로 두 배가 된다. 키는 **칸 번호**다(간판·포털과 같은 번호).
        static readonly Dictionary<int, BoothInfo> Cache = new();

        /// <summary>그 칸에 대해 조회가 끝났으면 true. 아직 안 왔으면 false. 키는 칸 번호(1~12)다.</summary>
        public static bool TryGetInfo(int slotId, out BoothInfo info) => Cache.TryGetValue(slotId, out info);

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Install()
        {
            if (!Enabled || _instance != null) return;
            var go = new GameObject("@BoothSignPresenter");
            DontDestroyOnLoad(go);
            _instance = go.AddComponent<BoothSignPresenter>();
        }

        /// <summary>부스 한 칸을 다시 읽는다. 스튜디오에서 고치고 돌아온 경우를 위한 것이다.</summary>
        public static void Refresh(int slotId)
        {
            if (_instance == null) return;
            _ = _instance.RefreshAsync(slotId);
        }

        async Task RefreshAsync(int slotId)
        {
            var api = ApiServices.Booth;
            if (api == null) return;
            // 임대가 바뀌었을 수도 있으니 목록부터 다시 읽는다 — 요청 한 번이고, 그래야 방금 임대한
            // 칸의 이름이 바로 붙는다.
            if (!await LoadSlotsAsync(api)) return;
            await FillOneAsync(slotId);
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

        /// <summary>슬롯 번호 → 그 칸을 쓰는 부스. 목록 조회 결과를 그대로 들고 있는다.</summary>
        readonly Dictionary<int, BoothSlotDto> _slots = new();

        async Task FillAllAsync()
        {
            var api = ApiServices.Booth;
            if (api == null)
            {
                Debug.LogWarning("[BoothSignPresenter] ApiServices.Booth 가 없다 — 간판은 기본 문구로 둔다.");
                return;
            }

            // **먼저 슬롯 목록을 한 번 읽는다.** 씬의 간판은 칸 번호(1~12)만 아는데 그 번호로 부스를
            // 조회하면 남의 부스가 나온다 — 간판이 옆 칸 이름을 띄우던 원인이다 (S15P21A604-658).
            // 이 목록이 slotId → boothId·boothName·facade 를 이어 주고, 덕분에 첫 진입 요청도
            // 최대 24회에서 1 + 임대된 칸 수로 줄어든다 (#171 ④).
            if (!await LoadSlotsAsync(api)) return;

            foreach (var kv in _signs)
                await FillOneAsync(kv.Key);
        }

        async Task<bool> LoadSlotsAsync(IBoothApiClient api)
        {
            BoothSlotDto[] slots = null;
            try { slots = await api.GetSlotsAsync(); }
            catch (System.Exception e) { Debug.LogWarning($"[BoothSignPresenter] 슬롯 목록 조회 실패: {e.Message}"); }

            if (slots == null)
            {
                // 여기서 칸 번호로 부스를 조회하는 옛 경로로 되돌아가지 않는다 — 그건 남의 부스를 띄운다.
                // 이름 없는 간판이 낫다.
                Debug.LogWarning("[BoothSignPresenter] 슬롯 목록을 받지 못했다 — 간판은 'N번 부스' 로 둔다.");
                return false;
            }

            _slots.Clear();
            foreach (var s in slots)
                if (s != null && s.slotId >= 1 && s.slotId <= SlotCount) _slots[s.slotId] = s;
            return true;
        }

        async Task FillOneAsync(int slotId)
        {
            if (!_signs.TryGetValue(slotId, out var sign) || sign == null) return;

            _slots.TryGetValue(slotId, out var slot);
            Apply(sign, slot, null);

            // 임대되지 않은 칸은 전시도 없다 — 요청을 아낀다.
            if (slot == null || !slot.HasBooth) return;

            var api = ApiServices.Booth;
            BoothProjectsDto projects = null;
            try { projects = await api.GetPublishedProjectsAsync(slot.boothId); }
            catch (System.Exception e) { Debug.LogWarning($"[BoothSignPresenter] 부스 {slot.boothId} 전시 조회 실패: {e.Message}"); }

            var first = projects?.projects != null && projects.projects.Length > 0 ? projects.projects[0] : null;
            if (first == null) return;

            Apply(sign, slot, first);

            if (!string.IsNullOrWhiteSpace(first.thumbnailUrl))
                await LoadThumbnailAsync(sign, slotId, first.thumbnailUrl);
        }

        static void Apply(BoothSign sign, BoothSlotDto slot, BoothProjectDto project)
        {
            string name = FirstNonBlank(
                slot?.facade?.signText,
                slot?.boothName,
                project?.name,
                $"{sign.boothId}번 부스");

            sign.SetLabel(name);
            // 카드는 여기서 켜지 않는다 — 썸네일이 **실제로 로드됐을 때만** BoothSign.ShowThumbnail 이 켠다.

            // 지도가 읽어 갈 수 있게 남긴다. 썸네일은 나중에 오므로 이 시점에는 아직 null 이다.
            Cache[sign.boothId] = new BoothInfo(name, null, project != null);
        }

        // 인자는 **칸 번호**다. 로그에 "부스 N" 으로 적으면 부스 식별자로 읽혀서, 이 파일이 방금 고친
        // 혼동(S15P21A604-658)을 로그가 다시 만든다 — 그래서 "슬롯 N" 으로 적는다.
        static async Task LoadThumbnailAsync(BoothSign sign, int slotId, string url)
        {
            // https 만 계약이다 (spec 009 §1 URL 규칙). http 는 mixed content 로 브라우저가 막아
            // 요청 자체가 안 나가므로 여기서 걸러 이유를 남긴다 — 조용한 실패로 두지 않는다.
            if (!url.StartsWith("https://"))
            {
                Debug.LogWarning($"[BoothSignPresenter] 슬롯 {slotId} 썸네일이 https 가 아니다 ('{url}') — " +
                                 "WebGL 에서 mixed content 로 차단된다. 프로젝트명 카드로 둔다.");
                return;
            }

            using var req = UnityWebRequestTexture.GetTexture(url);
            req.timeout = 10;
            await req.SendWebRequest();

            if (req.result != UnityWebRequest.Result.Success)
            {
                Debug.LogWarning($"[BoothSignPresenter] 슬롯 {slotId} 썸네일 로드 실패 ({req.result}: {req.error}) — {url}\n" +
                                 "WebGL 이면 CORS 헤더(Access-Control-Allow-Origin)를 먼저 의심하라 (#171 ③). 프로젝트명 카드로 둔다.");
                return;
            }

            var tex = DownloadHandlerTexture.GetContent(req);
            if (tex == null || sign == null) return;
            sign.ShowThumbnail(tex);

            if (Cache.TryGetValue(slotId, out var prev))
                Cache[slotId] = new BoothInfo(prev.Name, tex, prev.HasProject);
        }

        static string FirstNonBlank(params string[] values)
        {
            foreach (var v in values)
                if (!string.IsNullOrWhiteSpace(v)) return v.Trim();
            return string.Empty;
        }
    }
}
