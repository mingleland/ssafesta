// 부스 화면(프로젝트 전시 패널·영상 스크린)에 무엇을 띄울지 정하는 한 곳 (2026-09-18).
// 이 파일이 있는 이유: 부스 안 큰 화면이 검은 상자로 남으면 사용자 눈에 고장난 것으로 보인다.
// 띄울 것을 찾는 순서가 화면마다 달라지지 않게 여기서만 정한다.
using System.Collections.Generic;
using System.Threading.Tasks;
using Festa.Booth;
using UnityEngine;
using UnityEngine.Networking;

namespace Festa.Content
{
    /// <summary>
    /// 화면을 채우는 순서 — <b>영상 → 부스 로고 → 프로젝트 썸네일 → 검은 화면</b>.
    ///
    /// <para>영상은 <see cref="BoothScreenBillboard"/> 가 그 위에 띄운다(유튜브 iframe). 정지 이미지는
    /// 영상이 있어도 <b>함께 깐다</b> — 전광판이 가려지거나 멀어져 숨을 때 검은 상자로 돌아가지 않게 하는
    /// 바닥판이다.</para>
    ///
    /// <para><b>새 계약이 없다.</b> 부스 간판이 이미 읽는 <c>facade.logoUrl</c> 과 전시 조회의
    /// <c>thumbnailUrl</c>·<c>videoUrl</c> 을 그대로 쓴다.</para>
    ///
    /// <para><see cref="AllowVideo"/> 는 붙이는 쪽이 정한다. 한 부스에 화면이 둘이면 둘 다 영상을 틀려 해
    /// 서로 깜빡인다 — 영상은 <b>프로젝트 전시 패널에만</b> 준다.</para>
    /// </summary>
    [RequireComponent(typeof(BoothRuntimeObject))]
    public sealed class BoothScreenSurface : MonoBehaviour
    {
        /// <summary>이 화면이 영상을 틀 수 있는가. 붙이는 쪽이 <see cref="Attach"/> 로 정한다.</summary>
        public bool AllowVideo;

        const float SurfaceOffset = 0.02f;   // 같은 평면이면 z-fighting 으로 지직거린다
        const float Inset = 0.94f;           // 테두리를 남겨 프레임을 덮지 않는다

        /// <summary>같은 로고를 부스마다 다시 받지 않는다. 키는 URL 이다.</summary>
        static readonly Dictionary<string, Texture> TextureCache = new();

        bool _painted;

        /// <summary>이 오브젝트에 화면 내용을 붙인다. 이미 있으면 설정만 갱신한다.</summary>
        public static void Attach(GameObject target, bool allowVideo)
        {
            if (target == null) return;
            var surface = target.GetComponent<BoothScreenSurface>();
            if (surface == null) surface = target.AddComponent<BoothScreenSurface>();
            surface.AllowVideo = allowVideo;
            if (allowVideo && target.GetComponent<BoothScreenBillboard>() == null)
                target.AddComponent<BoothScreenBillboard>();
        }

        void Start() => FillAsync(GetComponent<BoothRuntimeObject>().BoothId);

        async void FillAsync(int boothId)
        {
            if (boothId <= 0) return;

            BoothProjectDto first = null;
            try
            {
                Festa.Integration.ApiServices.EnsureInitialized();
                var projects = await Festa.Integration.ApiServices.Booth.GetPublishedProjectsAsync(boothId);
                if (projects?.projects != null && projects.projects.Length > 0) first = projects.projects[0];
            }
            catch (System.Exception e)
            {
                Debug.LogWarning($"[BoothScreen] booth={boothId} 전시 조회 실패: {e.Message}");
            }
            if (this == null) return;

            if (AllowVideo)
            {
                var billboard = GetComponent<BoothScreenBillboard>();
                if (billboard != null) billboard.SetVideoUrl(first?.videoUrl);
            }

            Texture texture = null;
            try { texture = await ResolveImageAsync(boothId, first); }
            catch (System.Exception e)
            {
                // 조용히 검은 화면으로 두지 않는다 — 로고를 등록했는데 안 뜨면 원인을 찾을 수 없다 (T-24).
                Debug.LogWarning($"[BoothScreen] booth={boothId} 이미지 조회 실패: {e.Message}");
            }
            if (texture == null || this == null || _painted) return;   // 셋 다 없으면 검은 화면 그대로
            Paint(texture);
        }

        /// <summary>부스 로고 → 프로젝트 썸네일 순. 둘 다 없으면 null.</summary>
        static async Task<Texture> ResolveImageAsync(int boothId, BoothProjectDto project)
        {
            var slots = await BoothSlotDirectory.GetAsync();
            if (slots != null)
                foreach (var slot in slots)
                    if (slot != null && slot.boothId == boothId && slot.facade != null)
                    {
                        var logo = await LoadAsync(slot.facade.logoUrl, boothId, "부스 로고");
                        if (logo != null) return logo;
                        break;
                    }
            return await LoadAsync(project?.thumbnailUrl, boothId, "프로젝트 썸네일");
        }

        static async Task<Texture> LoadAsync(string url, int boothId, string label)
        {
            if (string.IsNullOrWhiteSpace(url)) return null;
            if (TextureCache.TryGetValue(url, out var cached)) return cached;

            // https 만 계약이다. http 는 WebGL 에서 mixed content 로 브라우저가 막아 요청 자체가 안 나간다.
            if (!url.StartsWith("https://"))
            {
                Debug.LogWarning($"[BoothScreen] booth={boothId} {label} 이 https 가 아니다 ('{url}') — 검은 화면으로 둔다.");
                return null;
            }

            using var request = UnityWebRequestTexture.GetTexture(url);
            request.timeout = 10;
            await request.SendWebRequest();
            if (request.result != UnityWebRequest.Result.Success)
            {
                Debug.LogWarning($"[BoothScreen] booth={boothId} {label} 로드 실패 ({request.result}) — {url}\n" +
                                 "WebGL 이면 CORS 헤더(Access-Control-Allow-Origin)를 먼저 의심하라 (#171).");
                return null;
            }

            var texture = DownloadHandlerTexture.GetContent(request);
            if (texture != null) TextureCache[url] = texture;
            return texture;
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

            var material = new Material(Shader.Find("Universal Render Pipeline/Unlit"));
            material.SetTexture("_BaseMap", texture);
            material.mainTexture = texture;

            // 화면이 아니라 **그 부모**에 붙인다. 화면은 두께 축이 얇게 눌려 있어
            // 자식으로 두면 판이 그 배율을 그대로 먹어 찌그러진다.
            var anchor = transform.parent != null ? transform.parent : transform;
            float unit = Mathf.Max(Mathf.Abs(anchor.lossyScale.x), 1e-4f);

            for (int side = 0; side < 2; side++)
            {
                var dir = side == 0 ? normal : -normal;
                var quad = GameObject.CreatePrimitive(PrimitiveType.Quad);
                quad.name = $"ScreenImage_{side}";
                Destroy(quad.GetComponent<Collider>());   // 상호작용 판정에 끼어들면 안 된다
                quad.transform.SetParent(anchor, true);
                quad.transform.position = bounds.center + dir * half;
                quad.transform.rotation = Quaternion.LookRotation(dir, thin == 1 ? Vector3.forward : Vector3.up);
                quad.transform.localScale = new Vector3(w * Inset / unit, h * Inset / unit, 1f);

                var renderer = quad.GetComponent<MeshRenderer>();
                renderer.sharedMaterial = material;
                renderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
                renderer.receiveShadows = false;
            }
        }
    }
}
