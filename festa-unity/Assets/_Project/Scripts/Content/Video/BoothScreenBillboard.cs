// 월드 전광판 시제품 — 부스 스크린 자리에 유튜브 iframe 을 겹쳐 본다 (사용자 지시 2026-09-18).
// 이 파일이 있는 이유: "iframe 은 깊이가 없어 앞에 선 아바타를 덮는다" 를 말로만 주고받는 대신
// 한 부스에서 직접 보고 판단하려고 만든 것이다. 판단이 서면 지우거나 정식화한다.
using System.Runtime.InteropServices;
using Festa.Booth;
using UnityEngine;

namespace Festa.Content
{
    /// <summary>
    /// 부스 스크린의 네 꼭짓점을 매 프레임 화면좌표로 투영해 호스트에 넘긴다. 호스트(jslib)는
    /// 그 사각형에 맞춰 유튜브 iframe 을 <c>matrix3d</c> 로 눕힌다.
    ///
    /// <para><b>WebGL 빌드에서만 보인다.</b> 에디터에는 DOM 이 없어 아무 일도 일어나지 않는다 —
    /// 조용히 넘어가지 않고 한 번 로그를 남긴다.</para>
    ///
    /// <para><b>한계를 그대로 드러내는 것이 목적이다.</b> iframe 은 캔버스 위에 얹히는 DOM 이라
    /// 깊이 버퍼에 참여하지 않는다. 각도는 맞지만 <b>앞에 선 아바타를 덮는다.</b> 화면 중심이 가려지면
    /// 통째로 숨기는 것까지만 해 뒀다 — 실루엣 단위로 오려내는 것은 만들 수 있는 종류가 아니다.</para>
    /// </summary>
    [RequireComponent(typeof(BoothRuntimeObject))]
    public sealed class BoothScreenBillboard : MonoBehaviour
    {
        /// <summary>
        /// 특정 부스에만 붙이려면 그 <b>boothId</b>. <b>0 이면 전체</b>이고 기본값이 0 이다.
        ///
        /// <para>"3번 부스" 는 화면에 보이는 <b>칸 번호</b>인데 이 값은 <c>boothId</c> 라 둘이 다르다
        /// (칸 3 에 임차인 부스 17 이 들어올 수 있다 — S15P21A604-658 에서 한 번 밟은 혼동이다).
        /// 게다가 스크린은 그 부스 게시본에 영상 스크린을 놓았을 때만 생긴다. 한 부스로 좁혔다가
        /// 아무것도 안 보이면 빌드를 한 번 더 태워야 하므로, 시제품은 전체에 붙이고
        /// <b>어느 부스에서 떴는지 로그로 남긴다.</b> 한 번에 하나만 뜬다.</para>
        /// </summary>
        public static int OnlyBoothId = 0;

        /// <summary>
        /// 이 스크린이 틀 영상. <b>임차인이 프로젝트에 등록한 <c>videoUrl</c></b> 에서 온다
        /// (<see cref="VideoScreenPlaceholder"/> 가 전시 조회를 한 번만 하고 넘겨준다).
        /// 비어 있으면 전광판은 뜨지 않고 아래 정지 이미지만 남는다.
        /// </summary>
        string _videoId;

        /// <summary>등록된 영상 주소를 받는다. 유튜브가 아니거나 비었으면 스스로 꺼진다.</summary>
        public void SetVideoUrl(string url)
        {
            _videoId = ParseYouTubeId(url);
            if (string.IsNullOrEmpty(_videoId))
            {
                if (!string.IsNullOrWhiteSpace(url))
                    Debug.Log($"[BoothScreen] 유튜브 주소가 아니라 전광판을 켜지 않는다 — '{url}'. 정지 이미지로 둔다.");
                enabled = false;
                Release();
                return;
            }
            Debug.Log($"[BoothScreen] 전광판 영상 연결 — videoId={_videoId}");
        }

        /// <summary>
        /// 유튜브 주소에서 영상 id 를 뽑는다. FE 의 <c>videoEmbed.ts</c> 와 같은 표기를 받는다 —
        /// <c>watch?v=</c> · <c>youtu.be/</c> · <c>/embed/</c> · <c>/shorts/</c>.
        /// 유튜브가 아니면 null 이다(임의 URL 을 iframe 에 넣지 않는다).
        /// </summary>
        public static string ParseYouTubeId(string url)
        {
            if (string.IsNullOrWhiteSpace(url)) return null;
            if (!System.Uri.TryCreate(url.Trim(), System.UriKind.Absolute, out var uri)) return null;
            if (uri.Scheme != "https" && uri.Scheme != "http") return null;

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
                    foreach (var part in uri.Query.TrimStart('?').Split('&'))
                        if (part.StartsWith("v=")) { id = part.Substring(2); break; }
                }
            }
            if (string.IsNullOrEmpty(id)) return null;

            int slash = id.IndexOf('/');
            if (slash >= 0) id = id.Substring(0, slash);
            // 유튜브 영상 id 는 11자 [A-Za-z0-9_-] 다. 다른 것이 오면 iframe 에 넣지 않는다.
            if (id.Length != 11) return null;
            foreach (var c in id)
                if (!char.IsLetterOrDigit(c) && c != '_' && c != '-') return null;
            return id;
        }

        /// <summary>이 거리 밖에서는 숨긴다(월드 유닛). 부스 안에서만 보이면 된다.</summary>
        const float VisibleDistance = 260f;

        /// <summary>가림 판정 간격(프레임) — 이름표와 같은 주기다.</summary>
        const int OcclusionInterval = 3;

        static BoothScreenBillboard s_active;   // 한 번에 하나만 — iframe 도 하나다
        static bool s_warnedEditor;

        Vector3[] _corners;      // TL, TR, BR, BL (월드)
        int _occlusionFrame = -100;
        bool _occluded;

        void Start()
        {
            var runtime = GetComponent<BoothRuntimeObject>();
            if (OnlyBoothId != 0 && runtime.BoothId != OnlyBoothId) { enabled = false; return; }
            if (!TryMeasureCorners()) { enabled = false; return; }

#if !UNITY_WEBGL || UNITY_EDITOR
            if (!s_warnedEditor)
            {
                s_warnedEditor = true;
                Debug.Log("[BoothScreen] 전광판 시제품은 WebGL 빌드에서만 보인다 — 에디터에는 DOM 이 없다.");
            }
            enabled = false;
#endif
        }

        /// <summary>스크린 면의 네 꼭짓점을 잰다. 가장 얇은 축이 화면이 바라보는 방향이다.</summary>
        bool TryMeasureCorners()
        {
            var renderer = GetComponentInChildren<MeshRenderer>();
            if (renderer == null) return false;

            var b = renderer.bounds;
            var size = b.size;
            int thin = size.x <= size.y && size.x <= size.z ? 0 : size.y <= size.z ? 1 : 2;
            var normal = thin == 0 ? Vector3.right : thin == 1 ? Vector3.up : Vector3.forward;
            var right = thin == 0 ? Vector3.forward : Vector3.right;
            var up = thin == 1 ? Vector3.forward : Vector3.up;
            float hw = (thin == 0 ? size.z : size.x) * 0.5f;
            float hh = (thin == 1 ? size.z : size.y) * 0.5f;
            var center = b.center + normal * (size[thin] * 0.5f + 0.02f);

            _corners = new[]
            {
                center - right * hw + up * hh,   // TL
                center + right * hw + up * hh,   // TR
                center + right * hw - up * hh,   // BR
                center - right * hw - up * hh,   // BL
            };
            return true;
        }

        void OnDisable()
        {
            if (s_active != this) return;
            s_active = null;
            Hide();
        }

        void LateUpdate()
        {
            var cam = Camera.main;
            if (cam == null || _corners == null) { Release(); return; }

            var center = (_corners[0] + _corners[2]) * 0.5f;
            var toCam = cam.transform.position - center;
            if (toCam.magnitude > VisibleDistance) { Release(); return; }

            // 화면 뒤에 있는 꼭짓점이 하나라도 있으면 투영이 뒤집힌다 — 통째로 숨긴다.
            var n = new Vector2[4];
            for (int i = 0; i < 4; i++)
            {
                var sp = cam.WorldToScreenPoint(_corners[i]);
                if (sp.z <= 0.01f) { Release(); return; }
                n[i] = new Vector2(sp.x / Screen.width, sp.y / Screen.height);
            }

            if (Time.frameCount - _occlusionFrame >= OcclusionInterval)
            {
                _occlusionFrame = Time.frameCount;
                _occluded = IsBlocked(cam, center);
            }
            if (_occluded) { Release(); return; }
            if (string.IsNullOrEmpty(_videoId)) { Release(); return; }

            s_active = this;
            Show(_videoId, n[0].x, n[0].y, n[1].x, n[1].y, n[2].x, n[2].y, n[3].x, n[3].y);
        }

        /// <summary>중심이 가려졌는가. 실루엣 단위가 아니라 점 하나라 아바타는 못 거른다 — 그게 이 방식의 한계다.</summary>
        bool IsBlocked(Camera cam, Vector3 center)
        {
            var origin = cam.transform.position;
            var dir = center - origin;
            float distance = dir.magnitude;
            if (distance < 0.01f) return false;
            if (!Physics.Raycast(origin, dir / distance, out var hit, distance - 0.05f)) return false;
            return hit.transform != transform && !hit.transform.IsChildOf(transform);
        }

        void Release()
        {
            if (s_active != this) return;
            s_active = null;
            Hide();
        }

#if UNITY_WEBGL && !UNITY_EDITOR
        [DllImport("__Internal")]
        static extern void FestaScreenShow(string videoId, float x0, float y0, float x1, float y1,
                                           float x2, float y2, float x3, float y3);
        [DllImport("__Internal")] static extern void FestaScreenHide();

        static void Show(string id, float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3)
            => FestaScreenShow(id, x0, y0, x1, y1, x2, y2, x3, y3);
        static void Hide() => FestaScreenHide();
#else
        static void Show(string id, float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) { }
        static void Hide() { }
#endif
    }
}
