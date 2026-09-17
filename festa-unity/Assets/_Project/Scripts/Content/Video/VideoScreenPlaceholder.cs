using System.Collections.Generic;
using System.Threading.Tasks;
using Festa.Booth;
using UnityEngine;
using UnityEngine.Networking;

namespace Festa.Content
{
    /// <summary>
    /// 부스 영상 스크린 — <b>장식이다</b> (GitLab #194 ② B안, 2026-09-17). 영상은 재생하지 않는다.
    ///
    /// <para><b>왜 영상이 아닌가.</b> 기능화하려면 영상 호스팅부터 정해야 한다. 프로젝트의
    /// <c>videoUrl</c> 은 YouTube 링크가 들어오는 자리인데 Unity <c>VideoPlayer</c> 는 그것을 재생하지
    /// 못한다. 유튜브 영상은 지금도 볼 수 있다 — 그래픽 패널에 F 를 누르면 React 오버레이가 임베드로 튼다.</para>
    ///
    /// <para><b>대신 이미지를 올린다.</b> 검은 상자로 두면 사용자 눈에 고장난 것으로 보인다.
    /// <c>facade.logoUrl</c> → 프로젝트 <c>thumbnailUrl</c> 순으로 찾고, <b>둘 다 없으면 검은 화면 그대로</b> 둔다
    /// (사용자 지시 2026-09-17). 새 계약이 필요 없다 — 부스 간판이 이미 같은 값을 읽는다.</para>
    ///
    /// <para>양면에 붙인다. 스크린이 어느 쪽을 보는지는 배치에 달렸는데, 뒷면이 검은 상자로 남으면
    /// 반대편에서 들어온 방문자에게는 아무것도 안 고친 것이 된다.</para>
    /// </summary>
    [RequireComponent(typeof(BoothRuntimeObject))]
    public class VideoScreenPlaceholder : MonoBehaviour
    {
        /// <summary>스크린 면에서 살짝 띄운다 — 같은 평면이면 z-fighting 으로 지직거린다.</summary>
        const float SurfaceOffset = 0.02f;

        /// <summary>테두리를 남겨 스크린 프레임을 덮지 않는다.</summary>
        const float Inset = 0.94f;

        /// <summary>같은 로고를 부스마다 다시 받지 않는다. 키는 URL 이다.</summary>
        static readonly Dictionary<string, Texture> TextureCache = new();

        void Start()
        {
            var runtimeObject = GetComponent<BoothRuntimeObject>();
            Debug.Log($"[VideoScreen] booth={runtimeObject.BoothId} configId={runtimeObject.ConfigId} ready (decorative)");
            ApplyBoothImageAsync(runtimeObject.BoothId);
        }

        /// <summary>
        /// 화면을 채우는 순서 — <b>영상 → 로고 → 썸네일 → 검은 화면</b>.
        ///
        /// <para>영상은 <see cref="BoothScreenBillboard"/> 가 그 위에 띄운다. 정지 이미지는 영상이 있어도
        /// 함께 깐다 — 전광판이 가려지거나 멀어져 숨을 때 검은 상자로 돌아가지 않게 하는 바닥판이다.</para>
        /// </summary>
        async void ApplyBoothImageAsync(int boothId)
        {
            BoothProjectDto first = null;
            try
            {
                Festa.Integration.ApiServices.EnsureInitialized();
                var projects = await Festa.Integration.ApiServices.Booth.GetPublishedProjectsAsync(boothId);
                if (projects?.projects != null && projects.projects.Length > 0) first = projects.projects[0];
            }
            catch (System.Exception e)
            {
                Debug.LogWarning($"[VideoScreen] booth={boothId} 전시 조회 실패: {e.Message}");
            }
            if (this == null) return;

            // 등록된 영상 주소를 전광판에 넘긴다. 없으면 전광판은 스스로 꺼지고 아래 이미지만 남는다.
            var billboard = GetComponent<BoothScreenBillboard>();
            if (billboard != null) billboard.SetVideoUrl(first?.videoUrl);

            Texture texture = null;
            try { texture = await ResolveImageAsync(boothId, first); }
            catch (System.Exception e)
            {
                // 조용히 검은 화면으로 두지 않는다 — 로고를 등록했는데 안 뜨면 원인을 찾을 수 없다 (T-24).
                Debug.LogWarning($"[VideoScreen] booth={boothId} 이미지 조회 실패: {e.Message}");
            }
            if (texture == null || this == null) return;   // 둘 다 없으면 검은 화면 그대로
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
                Debug.LogWarning($"[VideoScreen] booth={boothId} {label} 이 https 가 아니다 ('{url}') — 검은 화면으로 둔다.");
                return null;
            }

            using var request = UnityWebRequestTexture.GetTexture(url);
            request.timeout = 10;
            await request.SendWebRequest();
            if (request.result != UnityWebRequest.Result.Success)
            {
                Debug.LogWarning($"[VideoScreen] booth={boothId} {label} 로드 실패 ({request.result}) — {url}\n" +
                                 "WebGL 이면 CORS 헤더(Access-Control-Allow-Origin)를 먼저 의심하라 (#171).");
                return null;
            }

            var texture = DownloadHandlerTexture.GetContent(request);
            if (texture != null) TextureCache[url] = texture;
            return texture;
        }

        /// <summary>스크린 앞뒤 면에 이미지 판을 한 장씩 덮는다. 원본 재질은 건드리지 않는다.</summary>
        void Paint(Texture texture)
        {
            var source = GetComponentInChildren<MeshRenderer>();
            if (source == null) return;

            var bounds = source.bounds;
            var size = bounds.size;

            // 가장 얇은 축이 화면이 바라보는 방향이다 — 스크린은 납작한 판이다.
            int thin = size.x <= size.y && size.x <= size.z ? 0 : size.y <= size.z ? 1 : 2;
            var normal = thin == 0 ? Vector3.right : thin == 1 ? Vector3.up : Vector3.forward;
            float w = thin == 0 ? size.z : size.x;
            float h = thin == 1 ? size.z : size.y;
            float half = size[thin] * 0.5f + SurfaceOffset;

            var material = new Material(Shader.Find("Universal Render Pipeline/Unlit"));
            material.SetTexture("_BaseMap", texture);
            material.mainTexture = texture;

            // **스크린이 아니라 그 부모(앵커)에 붙인다.** 스크린은 두께 축이 얇게 눌려 있어
            // (예: 2.4 × 1.4 × 0.1) 거기 자식으로 두면 판이 그 배율을 그대로 먹어 찌그러진다.
            // 앵커는 배율 1 이라 월드 크기를 그대로 쓸 수 있고, 부스를 다시 지을 때 같이 사라진다.
            var anchor = transform.parent != null ? transform.parent : transform;
            float unit = Mathf.Max(Mathf.Abs(anchor.lossyScale.x), 1e-4f);
            if (Mathf.Abs(anchor.lossyScale.y - anchor.lossyScale.x) > 1e-3f ||
                Mathf.Abs(anchor.lossyScale.z - anchor.lossyScale.x) > 1e-3f)
                Debug.LogWarning($"[VideoScreen] 앵커 배율이 축마다 다르다 ({anchor.lossyScale}) — " +
                                 "이미지 비율이 어긋날 수 있다.");

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
