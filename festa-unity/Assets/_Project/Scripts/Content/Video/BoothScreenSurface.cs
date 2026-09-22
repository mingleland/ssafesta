// 부스 화면(프로젝트 전시 패널·영상 스크린)에 무엇을 띄울지 정하는 한 곳 (2026-09-18).
// 이 파일이 있는 이유: 부스 안 큰 화면이 검은 상자로 남으면 사용자 눈에 고장난 것으로 보인다.
// 띄울 것을 찾는 순서가 화면마다 달라지지 않게 여기서만 정한다.
using System.Collections.Generic;
using System.Threading.Tasks;
using System.Text.RegularExpressions;
using Festa.Booth;
using UnityEngine;
using UnityEngine.Networking;
using UnityEngine.SceneManagement;

namespace Festa.Content
{
    /// <summary>
    /// 화면을 채우는 순서 — <b>유튜브 썸네일 → 프로젝트 대표이미지 → 부스 로고 → 검은 화면</b>.
    ///
    /// <para><b>영상 재생(iframe)은 걷어냈다</b> (사용자 지시 2026-09-18). DOM 오버레이는 WebGL 캔버스
    /// <b>위에</b> 얹히므로 깊이 버퍼에 참여하지 않는다 — 화면과 나 사이에 사람이 서도 가려 주지 못하고,
    /// 에디터에는 DOM 이 아예 없어 확인도 빌드로만 가능했다. 실제로 링크와 대표이미지를 둘 다 걸어 둔
    /// 부스가 검은 화면으로 남았다. 정지 이미지 한 장은 이 셋 중 어느 것도 문제가 되지 않는다.</para>
    ///
    /// <para>영상 링크가 있으면 그 <b>유튜브 썸네일</b>을 쓴다 — 무엇에 대한 전시인지 대표이미지보다
    /// 잘 드러내는 경우가 많고, 링크를 건 사람의 의도에 더 가깝다.</para>
    ///
    /// <para><b>새 계약이 없다.</b> 전시 조회의 <c>videoUrl</c>·<c>thumbnailUrl</c> 과 부스 간판이 이미
    /// 읽는 <c>facade.logoUrl</c> 을 그대로 쓴다.</para>
    /// </summary>
    [RequireComponent(typeof(BoothRuntimeObject))]
    public sealed class BoothScreenSurface : MonoBehaviour
    {
        const float SurfaceOffset = 0.02f;   // 같은 평면이면 z-fighting 으로 지직거린다
        const float Inset = 0.94f;           // 테두리를 남겨 프레임을 덮지 않는다

        /// <summary>같은 로고를 부스마다 다시 받지 않는다. 키는 URL 이다.</summary>
        static readonly Dictionary<string, Texture> TextureCache = new();
        /// <summary>처음 진입 때 같은 URL을 여러 화면이 동시에 받는 경우도 한 요청으로 합친다.</summary>
        static readonly Dictionary<string, Task<Texture>> TextureLoads = new();
        static int s_cacheGeneration;
        static readonly Regex ManagedProjectLogoPath = new(
            @"^/api/v1/booths/[1-9]\d*/project-logos/[A-Za-z0-9_-]+/content$",
            RegexOptions.CultureInvariant);

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        static void InstallCacheCleanup()
        {
            SceneManager.sceneUnloaded -= OnSceneUnloaded;
            SceneManager.sceneUnloaded += OnSceneUnloaded;
        }

        static void OnSceneUnloaded(Scene _)
        {
            // 다운로드 텍스처는 Resources 소유가 아니다. 씬 전환 뒤 정적 캐시에 남겨 두면
            // 로고 URL이 바뀔수록 WebGL 메모리가 회수되지 않는다.
            s_cacheGeneration++;
            foreach (var texture in TextureCache.Values)
                if (texture != null) Destroy(texture);
            TextureCache.Clear();
            TextureLoads.Clear();
        }

        bool _painted;
        Material _screenMaterial;
        readonly List<GameObject> _screenImages = new();
        int _refreshVersion;

        /// <summary>이 오브젝트에 화면 내용을 붙인다. 이미 있으면 설정만 갱신한다.</summary>
        public static void Attach(GameObject target)
        {
            if (target == null) return;
            var surface = target.GetComponent<BoothScreenSurface>();
            if (surface == null) target.AddComponent<BoothScreenSurface>();
            else surface.Refresh();
        }

        void Start() => Refresh();

        /// <summary>
        /// 게시본 재적용 뒤에도 같은 저작 패널은 살아 있다. Start 한 번에만 읽으면 로고를
        /// 저장·게시한 뒤 월드를 새로 열기 전까지 검은 화면으로 남으므로 명시적으로 다시 채운다.
        /// </summary>
        void Refresh()
        {
            _refreshVersion++;
            ClearPaintedSurface();
            FillAsync(GetComponent<BoothRuntimeObject>().BoothId, _refreshVersion);
        }

        async void FillAsync(int boothId, int refreshVersion)
        {
            // 조용히 돌아가면 화면은 검은 상자로 남고 원인은 어디에도 남지 않는다 (T-24). 이 경로는
            // 붙인 오브젝트에 부스 번호가 들어오지 않았다는 뜻이라 데이터가 아니라 배선 문제다.
            if (boothId <= 0)
            {
                Debug.LogWarning($"[BoothScreen] {name}: 부스 번호가 없어(BoothId={boothId}) 화면을 채우지 않는다 — " +
                                 "BoothRuntimeObject.Init 이 먼저 불렸는지 확인하라.");
                return;
            }

            Texture texture = null;
            BoothProjectDto first = null;
            for (int attempt = 0; attempt < 3 && texture == null; attempt++)
            {
                try
                {
                    Festa.Integration.ApiServices.EnsureInitialized();
                    var projects = await Festa.Integration.ApiServices.Booth.GetPublishedProjectsAsync(boothId);
                    if (projects?.projects != null && projects.projects.Length > 0) first = projects.projects[0];
                    texture = await ResolveImageAsync(boothId, first);
                }
                catch (System.Exception e)
                {
                    Debug.LogWarning($"[BoothScreen] booth={boothId} 이미지 조회 실패: {e.Message}");
                }
                if (texture == null && attempt < 2 && this != null && refreshVersion == _refreshVersion)
                    await Awaitable.WaitForSecondsAsync(2f);
            }
            if (this == null || _painted || refreshVersion != _refreshVersion) return;
            if (texture == null)
            {
                // 사용자는 "로고를 넣었는데 안 뜬다" 로 겪는다. 무엇이 비어 있었는지 남겨야 데이터인지 배선인지 가른다.
                Debug.LogWarning($"[BoothScreen] booth={boothId} 화면을 검은 채로 둔다 — 후보가 모두 비었다. " +
                                 $"videoUrl='{first?.videoUrl}' thumbnailUrl='{first?.thumbnailUrl}' 전시={(first == null ? "없음" : "있음")}. " +
                                 "부스 로고는 슬롯 목록의 facade.logoUrl 을 본다.");
                return;
            }
            Paint(texture);
        }

        /// <summary>유튜브 썸네일 → 프로젝트 대표이미지 → 부스 로고 순. 셋 다 없으면 null.</summary>
        static async Task<Texture> ResolveImageAsync(int boothId, BoothProjectDto project)
        {
            var youtube = await LoadYouTubeThumbnailAsync(project?.videoUrl, boothId);
            if (youtube != null) return youtube;

            var cover = await LoadAsync(project?.thumbnailUrl, boothId, "프로젝트 대표이미지");
            if (cover != null) return cover;

            var slots = await BoothSlotDirectory.GetAsync();
            if (slots != null)
                foreach (var slot in slots)
                    if (slot != null && slot.boothId == boothId && slot.facade != null)
                    {
                        var slotLogo = await LoadAsync(slot.facade.logoUrl, boothId, "부스 로고");
                        if (slotLogo != null) return slotLogo;
                        break;
                    }

            // 슬롯 목록은 한 번에 12개를 읽는 최적화일 뿐 로고의 유일한 출처가 아니다.
            // 토큰 교체 직후 목록만 일시 실패하면 화면이 영구히 검게 남던 경로를 끊는다.
            var api = Festa.Integration.ApiServices.Booth;
            var detail = api != null ? await api.GetBoothDetailAsync(boothId) : null;
            var logo = await LoadAsync(detail?.facade?.logoUrl, boothId, "부스 로고(상세 폴백)");
            if (logo != null) return logo;
            return null;
        }

        /// <summary>
        /// 영상 링크의 유튜브 썸네일. 유튜브가 아니거나 못 받으면 null 이라 다음 후보로 넘어간다.
        ///
        /// <para>해상도를 높은 것부터 시도한다 — <c>maxresdefault</c> 는 올린 사람이 고화질 원본을 넣지
        /// 않았으면 <b>404</b> 다(실측 2026-09-18). <c>hqdefault</c> 는 항상 있지만 4:3 이라 16:9 영상이면
        /// 위아래에 검은 띠가 남는다 — 그래서 마지막 후보다.</para>
        ///
        /// <para>WebGL 에서 쓸 수 있는 근거는 실측이다 — <c>img.youtube.com</c> 은 썸네일에
        /// <c>Access-Control-Allow-Origin: *</c> 를 준다(2026-09-18 확인). CORS 가 막혔다면 에디터에서만
        /// 되고 빌드에서 깨졌을 것이다.</para>
        /// </summary>
        static async Task<Texture> LoadYouTubeThumbnailAsync(string videoUrl, int boothId)
        {
            var id = ParseYouTubeId(videoUrl);
            if (string.IsNullOrEmpty(id))
            {
                if (!string.IsNullOrWhiteSpace(videoUrl))
                    Debug.Log($"[BoothScreen] booth={boothId} 영상 링크가 유튜브 형식이 아니다 ('{videoUrl}') — 대표이미지로 넘어간다.");
                return null;
            }
            foreach (var quality in YouTubeThumbnailQualities)
            {
                // 마지막 후보만 시끄럽게 말한다. 앞의 404 는 "그 해상도를 안 올렸다" 는 정상 경로라
                // 경고로 남기면 진짜 실패와 구분이 안 된다.
                bool last = quality == YouTubeThumbnailQualities[YouTubeThumbnailQualities.Length - 1];
                var texture = await LoadAsync($"https://img.youtube.com/vi/{id}/{quality}.jpg", boothId,
                                              $"유튜브 썸네일({quality})", quiet: !last);
                if (texture != null) return texture;
            }
            return null;
        }

        static readonly string[] YouTubeThumbnailQualities = { "maxresdefault", "sddefault", "hqdefault" };

        /// <summary>
        /// 유튜브 주소에서 영상 id 를 뽑는다. FE 의 <c>videoEmbed.ts</c> 와 같은 표기를 받는다 —
        /// <c>watch?v=</c> · <c>youtu.be/</c> · <c>/embed/</c> · <c>/shorts/</c>. 유튜브가 아니면 null.
        /// </summary>
        public static string ParseYouTubeId(string url)
        {
            if (string.IsNullOrWhiteSpace(url)) return null;
            if (!System.Uri.TryCreate(url.Trim(), System.UriKind.Absolute, out var uri)) return null;

            var host = uri.Host.ToLowerInvariant();
            if (host.StartsWith("www.")) host = host.Substring(4);
            if (host.StartsWith("m.")) host = host.Substring(2);

            string id = null;
            if (host == "youtu.be")
            {
                id = uri.AbsolutePath.Trim('/');
            }
            else if (host == "youtube.com" || host == "music.youtube.com" || host == "youtube-nocookie.com")
            {
                var path = uri.AbsolutePath;
                if (path.StartsWith("/embed/")) id = path.Substring(7);
                else if (path.StartsWith("/shorts/")) id = path.Substring(8);
                else
                {
                    foreach (var pair in uri.Query.TrimStart('?').Split('&'))
                        if (pair.StartsWith("v=")) { id = pair.Substring(2); break; }
                }
            }
            if (string.IsNullOrEmpty(id)) return null;

            int slash = id.IndexOf('/');
            if (slash >= 0) id = id.Substring(0, slash);

            // 유튜브 영상 id 는 11자 [A-Za-z0-9_-] 다. 다른 것이 오면 썸네일 주소를 만들지 않는다.
            if (id.Length != 11) return null;
            foreach (var c in id)
                if (!char.IsLetterOrDigit(c) && c != '_' && c != '-') return null;
            return id;
        }

        static async Task<Texture> LoadAsync(string url, int boothId, string label, bool quiet = false)
        {
            if (string.IsNullOrWhiteSpace(url)) return null;
            if (!TryResolveImageUrl(url, boothId, label, out var resolvedUrl)) return null;
            if (TextureCache.TryGetValue(resolvedUrl, out var cached)) return cached;
            if (TextureLoads.TryGetValue(resolvedUrl, out var inFlight)) return await inFlight;

            int cacheGeneration = s_cacheGeneration;
            var load = DownloadAsync(resolvedUrl, boothId, label, quiet);
            TextureLoads[resolvedUrl] = load;
            try
            {
                var texture = await load;
                if (texture != null && cacheGeneration == s_cacheGeneration)
                    TextureCache[resolvedUrl] = texture;
                else if (texture != null)
                {
                    Destroy(texture);
                    texture = null;
                }
                return texture;
            }
            finally
            {
                if (TextureLoads.TryGetValue(resolvedUrl, out var current) && current == load)
                    TextureLoads.Remove(resolvedUrl);
            }
        }

        static bool TryResolveImageUrl(string url, int boothId, string label, out string resolvedUrl)
        {
            resolvedUrl = null;
            if (System.Uri.TryCreate(url, System.UriKind.Absolute, out var absolute))
            {
                if (absolute.Scheme != System.Uri.UriSchemeHttps)
                {
                    Debug.LogWarning($"[BoothScreen] booth={boothId} {label} 이 https 가 아니다 ('{url}') — 검은 화면으로 둔다.");
                    return false;
                }
                resolvedUrl = absolute.AbsoluteUri;
                return true;
            }

            // 프로젝트 로고 업로드 API만 상대 경로를 계약으로 허용한다. 임의 상대 URL까지 열면
            // 깨진 외부 주소를 같은 출처에 엉뚱하게 붙이므로 정확한 서버 발급 형태만 받는다.
            if (!ManagedProjectLogoPath.IsMatch(url) ||
                !System.Uri.TryCreate(Festa.Integration.ApiServices.SpringBaseUrl, System.UriKind.Absolute, out var apiBase) ||
                apiBase.Scheme != System.Uri.UriSchemeHttps ||
                !System.Uri.TryCreate(apiBase, url, out var managed))
            {
                Debug.LogWarning($"[BoothScreen] booth={boothId} {label} URL을 해석할 수 없다 ('{url}') — 검은 화면으로 둔다.");
                return false;
            }
            resolvedUrl = managed.AbsoluteUri;
            return true;
        }

        static async Task<Texture> DownloadAsync(string url, int boothId, string label, bool quiet)
        {
            using var request = UnityWebRequestTexture.GetTexture(url);
            request.timeout = 10;
            await request.SendWebRequest();
            if (request.result != UnityWebRequest.Result.Success)
            {
                if (!quiet)
                    Debug.LogWarning($"[BoothScreen] booth={boothId} {label} 로드 실패 ({request.result}) — {url}\n" +
                                     "WebGL 이면 CORS 헤더(Access-Control-Allow-Origin)를 먼저 의심하라 (#171).");
                return null;
            }

            return DownloadHandlerTexture.GetContent(request);
        }

        /// <summary>화면 앞뒤 면에 이미지 판을 한 장씩 덮는다. 원본 재질은 건드리지 않는다.</summary>
        void Paint(Texture texture)
        {
            var source = GetComponentInChildren<MeshRenderer>();
            if (source == null) return;
            _painted = true;

            var bounds = source.bounds;
            var size = bounds.size;

            // 가장 얇은 축이 화면이 바라보는 방향이다 — 전시 패널은 납작한 판이다.
            int thin = size.x <= size.y && size.x <= size.z ? 0 : size.y <= size.z ? 1 : 2;
            var normal = thin == 0 ? Vector3.right : thin == 1 ? Vector3.up : Vector3.forward;
            float w = thin == 0 ? size.z : size.x;
            float h = thin == 1 ? size.z : size.y;
            float half = size[thin] * 0.5f + SurfaceOffset;

            _screenMaterial = new Material(Shader.Find("Universal Render Pipeline/Unlit"));
            _screenMaterial.SetTexture("_BaseMap", texture);
            _screenMaterial.mainTexture = texture;

            // 화면이 아니라 **그 부모**에 붙인다. 화면은 두께 축이 얇게 눌려 있어
            // 자식으로 두면 판이 그 배율을 그대로 먹어 찌그러진다.
            var anchor = transform.parent != null ? transform.parent : transform;
            float unit = Mathf.Max(Mathf.Abs(anchor.lossyScale.x), 1e-4f);

            for (int side = 0; side < 2; side++)
            {
                var dir = side == 0 ? normal : -normal;
                var quad = GameObject.CreatePrimitive(PrimitiveType.Quad);
                quad.name = $"ScreenImage_{side}";
                _screenImages.Add(quad);
                Destroy(quad.GetComponent<Collider>());   // 상호작용 판정에 끼어들면 안 된다
                quad.transform.SetParent(anchor, true);
                quad.transform.position = bounds.center + dir * half;
                quad.transform.rotation = Quaternion.LookRotation(dir, thin == 1 ? Vector3.forward : Vector3.up);
                quad.transform.localScale = new Vector3(w * Inset / unit, h * Inset / unit, 1f);

                var renderer = quad.GetComponent<MeshRenderer>();
                renderer.sharedMaterial = _screenMaterial;
                renderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
                renderer.receiveShadows = false;
            }
        }

        void ClearPaintedSurface()
        {
            // 이미지는 BoothScreenSurface의 형제가 될 수 있어 컴포넌트 오브젝트를 없애도 자동으로
            // 같이 사라지지 않는다. 게시 레이아웃 재구성마다 quad와 동적 Material이 남지 않게
            // 만든 쪽에서 직접 거둔다 (S15P21A604-947).
            foreach (var image in _screenImages)
                if (image != null) Destroy(image);
            _screenImages.Clear();

            if (_screenMaterial != null) Destroy(_screenMaterial);
            _screenMaterial = null;
            _painted = false;
        }

        void OnDestroy() => ClearPaintedSurface();
    }
}
