using System;
using System.Text;
using System.Threading.Tasks;
using UnityEngine;
using UnityEngine.Networking;

namespace Festa.Integration
{
    /// <summary>
    /// Spring User/Session API 실 HTTP 구현 (spec 013 BE plan · spec 002 BE plan 계약 확정분).
    ///
    /// **인증 실패를 조용히 삼키지 않는다.** 401/403 은 Mock 으로 폴백하지 않고 로그·상태로 드러낸다 —
    /// 조용한 기본값 복귀가 T-24 의 원인이었다. 저장 실패를 성공으로 보고하면
    /// 사용자는 외형이 저장된 줄 알고 재접속에서 잃는다.
    ///
    /// WebGL: 다른 오리진이면 서버 CORS 헤더가 필요하고, HTTPS 페이지에서는 HTTPS API 만 호출된다.
    /// </summary>
    public class HttpUserApiClient : IUserApiClient
    {
        const int TimeoutSeconds = 10;

        readonly string _baseUrl;
        readonly IAccessTokenProvider _tokenProvider;

        /// <summary>마지막 인증 실패(401/403) 사유. UI 가 오류 상태를 표시할 때 읽는다.</summary>
        public string LastAuthError { get; private set; }

        public HttpUserApiClient(string baseUrl, IAccessTokenProvider tokenProvider = null)
        {
            _baseUrl = (baseUrl ?? string.Empty).TrimEnd('/');
            _tokenProvider = tokenProvider ?? new EmptyAccessTokenProvider();
        }

        /// <summary>
        /// GET /api/v1/users/me — 응답에 avatarCode 가 실려 온다 (신규 사용자는 null).
        /// 서버는 기본값을 만들지 않으므로 null 은 정상이며, 호출자가 카탈로그 기본값을 고른다.
        /// </summary>
        public async Task<UserProfileDto> GetMyProfileAsync()
        {
            using var request = UnityWebRequest.Get($"{_baseUrl}/api/v1/users/me");
            var body = await SendAsync(request, "GET /users/me");
            if (body == null) return null;

            var profile = ParseOrNull<UserProfileDto>(body, "users/me");
            // status·providers 등 Unity 가 안 쓰는 필드는 JsonUtility 가 무시한다.
            return profile;
        }

        /// <summary>
        /// PUT /api/v1/users/me/avatar — 200 응답은 저장한 값을 그대로 echo 한다.
        /// 왕복 무손실이 계약(data-model §2)이라, echo 가 보낸 값과 다르면 저장을 신뢰하지 않는다.
        /// </summary>
        public async Task<bool> UpdateMyAvatarAsync(string encodedAppearance)
        {
            if (string.IsNullOrEmpty(encodedAppearance))
            {
                Debug.LogError("[HttpUserApi] 아바타 저장 거부 — 빈 문자열 (서버도 400 으로 거부한다)");
                return false;
            }

            var payload = JsonUtility.ToJson(new AvatarUpdateRequest { avatarCode = encodedAppearance });

            using var request = new UnityWebRequest($"{_baseUrl}/api/v1/users/me/avatar", UnityWebRequest.kHttpVerbPUT)
            {
                uploadHandler = new UploadHandlerRaw(Encoding.UTF8.GetBytes(payload)),
                downloadHandler = new DownloadHandlerBuffer(),
            };
            request.SetRequestHeader("Content-Type", "application/json");

            var body = await SendAsync(request, "PUT /users/me/avatar");
            if (body == null) return false;

            var echo = ParseOrNull<AvatarUpdateRequest>(body, "users/me/avatar");
            if (echo == null) return false;

            if (echo.avatarCode != encodedAppearance)
            {
                // 서버가 정규화·트림을 했다는 뜻 — 계약 위반이라 성공으로 처리하면 안 된다.
               Debug.LogError("[HttpUserApi] 저장 echo 불일치 — 왕복 무손실 계약 위반. " +
                              $"보낸 길이={encodedAppearance.Length} 받은 길이={echo.avatarCode?.Length ?? -1}");
               return false;
           }

           return true;
       }

        /// <summary>
        /// GET /api/v1/users/me/avatar/presets — 저장된 칸만 온다 (GitLab#237).
        ///
        /// <para>실패는 <c>null</c> 이다. <b>빈 배열로 위장하지 않는다</b> — 빈 배열은 "세 칸 다 비었다"
        /// 는 정상 응답으로 읽히는데, 그 상태로 화면을 그리면 저장해 둔 외형이 사라진 것처럼 보이고
        /// 거기에 덮어쓰면 실제로 사라진다.</para>
        /// </summary>
        public async Task<AvatarPresetDto[]> GetAvatarPresetsAsync()
        {
            using var request = UnityWebRequest.Get($"{_baseUrl}/api/v1/users/me/avatar/presets");
            var body = await SendAsync(request, "GET /users/me/avatar/presets");
            if (body == null) return null;

            // 최상위 배열을 JsonUtility 가 못 읽으므로 감싼다.
            var envelope = ParseOrNull<AvatarPresetListEnvelope>("{\"items\":" + body + "}", "users/me/avatar/presets");
            if (envelope?.items == null) return null;

            // 서버가 범위를 지키는 것이 계약이지만, 화면이 세 칸 고정이라 벗어난 값은 버린다.
            var kept = new System.Collections.Generic.List<AvatarPresetDto>(3);
            foreach (var p in envelope.items)
            {
                if (p == null || p.slot < 1 || p.slot > 3 || string.IsNullOrEmpty(p.avatarCode))
                {
                    Debug.LogWarning($"[HttpUserApi] 프리셋 항목을 버린다 — slot={p?.slot} 길이={p?.avatarCode?.Length ?? -1}");
                    continue;
                }
                kept.Add(p);
            }
            return kept.ToArray();
        }

        /// <summary>PUT /api/v1/users/me/avatar/presets/{slot} — 덮어쓰기도 같은 호출이다.</summary>
        public async Task<bool> SaveAvatarPresetAsync(int slot, string encodedAppearance)
        {
            if (slot < 1 || slot > 3 || string.IsNullOrEmpty(encodedAppearance))
            {
                Debug.LogError($"[HttpUserApi] 프리셋 저장 거부 — slot={slot} 길이={encodedAppearance?.Length ?? -1}");
                return false;
            }

            var payload = JsonUtility.ToJson(new AvatarUpdateRequest { avatarCode = encodedAppearance });
            using var request = new UnityWebRequest($"{_baseUrl}/api/v1/users/me/avatar/presets/{slot}", UnityWebRequest.kHttpVerbPUT)
            {
                uploadHandler = new UploadHandlerRaw(Encoding.UTF8.GetBytes(payload)),
                downloadHandler = new DownloadHandlerBuffer(),
            };
            request.SetRequestHeader("Content-Type", "application/json");
            return await SendAsync(request, $"PUT /users/me/avatar/presets/{slot}") != null;
        }

        /// <summary>DELETE /api/v1/users/me/avatar/presets/{slot} — 204 라 본문이 없다.</summary>
        public async Task<bool> DeleteAvatarPresetAsync(int slot)
        {
            if (slot < 1 || slot > 3) return false;
            using var request = UnityWebRequest.Delete($"{_baseUrl}/api/v1/users/me/avatar/presets/{slot}");
            request.downloadHandler = new DownloadHandlerBuffer();
            return await SendAsync(request, $"DELETE /users/me/avatar/presets/{slot}") != null;
        }

        /// <summary>
        /// GET /api/v1/catalog/items?type=AVATAR_PART — 파츠 목록과 보유 여부 (GitLab #120 §2).
        ///
        /// <para>실패는 <c>null</c> 로 돌려주고 <b>빈 목록으로 위장하지 않는다.</b> 빈 목록은
        /// "카탈로그가 비었다" 는 정상 응답으로 읽히는데, 그러면 호출자가 잠금을 전부 풀거나
        /// 전부 잠그는 판단을 오류인지 모르고 하게 된다 (T-24).</para>
        /// </summary>
        public async Task<CatalogItemsDto> GetAvatarPartCatalogAsync()
        {
            using var request = UnityWebRequest.Get($"{_baseUrl}/api/v1/catalog/items?type=AVATAR_PART");
            var body = await SendAsync(request, "GET /catalog/items");
            if (body == null) return null;

            var catalog = ParseOrNull<CatalogItemsDto>(body, "catalog/items");
            if (catalog?.items == null)
            {
                Debug.LogError("[HttpUserApi] 카탈로그 응답에 items 배열이 없다 — 계약 위반이라 실패로 처리한다.");
                return null;
            }

            return catalog;
        }

        /// <summary>
        /// POST /api/v1/catalog/items/{itemId}/purchases — 파츠 구매 (GitLab #120 §2·§7).
        ///
        /// <para><b>공통 <see cref="SendAsync"/> 를 쓰지 않는다.</b> 그쪽은 실패하면 본문을 버리고 null 만
        /// 돌려주는데, 구매는 <b>실패 사유가 화면 문구를 가른다</b> — 코인 부족·이미 보유·판매 중지가
        /// 전부 409 라 상태 코드만으로는 구분이 안 되고, 서버 봉투의 <c>code</c> 를 읽어야 한다.</para>
        /// </summary>
        public async Task<PurchaseResult> PurchaseAvatarPartAsync(long itemId)
        {
            if (itemId <= 0) return PurchaseResult.Fail(null, $"itemId 가 유효하지 않다 ({itemId})");

            using var request = new UnityWebRequest($"{_baseUrl}/api/v1/catalog/items/{itemId}/purchases", UnityWebRequest.kHttpVerbPOST)
            {
                downloadHandler = new DownloadHandlerBuffer(),
            };
            request.timeout = TimeoutSeconds;
            request.SetRequestHeader("Content-Type", "application/json");
            var token = _tokenProvider.GetAccessToken();
            if (!string.IsNullOrEmpty(token)) request.SetRequestHeader("Authorization", $"Bearer {token}");

            try { await request.SendWebRequest(); }
            catch { /* WebGL 에서 실패 요청의 await 가 던질 수 있다 — 아래에서 결과로 판정한다 */ }

            string body = request.downloadHandler != null ? request.downloadHandler.text : null;

            if (request.result == UnityWebRequest.Result.Success)
            {
                LastAuthError = null;
                var item = ParseOrNull<CatalogItemDto>(body, "purchases");
                Debug.Log($"[HttpUserApi] 파츠 구매 성공 — itemId={itemId} code={item?.code}");
                return new PurchaseResult { ok = true, item = item };
            }

            // 상태 코드가 아니라 봉투의 code 로 가른다. 봉투가 없으면(프록시 오류 등) code 는 null 로 남기고
            // 호출자가 "알 수 없는 오류" 로 다룬다 — 임의로 코인 부족이라고 지어내지 않는다.
            var error = ParseOrNull<ApiErrorDto>(body, "purchases 오류 봉투");
            string code = string.IsNullOrEmpty(error?.code) ? null : error.code;
            if (code == null && request.responseCode == 401) code = "UNAUTHORIZED";
            if (code == null && request.responseCode == 403) code = "MEMBER_ONLY";
            if (request.responseCode == 401 || request.responseCode == 403)
                LastAuthError = request.responseCode == 401 ? "Access Token 이 만료·무효다" : "게스트 계정은 구매할 수 없다 (MEMBER_ONLY)";

            string reason = $"HTTP {request.responseCode} code={code ?? "(없음)"} body={body}";
            Debug.LogError($"[HttpUserApi] 파츠 구매 실패 — itemId={itemId} {reason}");
            return PurchaseResult.Fail(code, reason);
        }

        /// <summary>
        /// POST /api/v1/world-sessions — 구조화 endpoint + 120초 1회용 connection token.
        /// Unity 는 endpoint 를 하드코딩하지 않고 항상 이 응답만 쓴다 (헌법 8조).
        /// 본문은 전체 optional 이지만 worldId 를 명시해 서버 기본값에 의존하지 않는다.
        /// </summary>
        public async Task<WorldSessionDto> CreateWorldSessionAsync()
        {
            var payload = JsonUtility.ToJson(new WorldSessionRequest { worldId = "11F" });

            using var request = new UnityWebRequest($"{_baseUrl}/api/v1/world-sessions", UnityWebRequest.kHttpVerbPOST)
            {
                uploadHandler = new UploadHandlerRaw(Encoding.UTF8.GetBytes(payload)),
                downloadHandler = new DownloadHandlerBuffer(),
            };
            request.SetRequestHeader("Content-Type", "application/json");

            var body = await SendAsync(request, "POST /world-sessions");
            if (body == null) return null;

            var session = ParseOrNull<WorldSessionDto>(body, "world-sessions");
            if (session == null) return null;

            if (!WorldSessionEndpoint.TryGetConnectionData(session, out _, out _, out _))
            {
                Debug.LogError("[HttpUserApi] world-session 응답 endpoint 가 유효하지 않다 — 접속 불가");
                return null;
            }

            return session;
        }

        // ── 공통부 ────────────────────────────────────────────────

        /// <summary>
        /// 전송 공통부. 실패는 null 을 돌려주되 **원인을 반드시 로그로 남긴다.**
        /// 401/403 은 LastAuthError 에도 적어 UI 가 "로그인이 필요하다"를 표시할 수 있게 한다.
        /// </summary>
        async Task<string> SendAsync(UnityWebRequest request, string what)
        {
            request.timeout = TimeoutSeconds;

            var token = _tokenProvider.GetAccessToken();
            if (!string.IsNullOrEmpty(token))
                request.SetRequestHeader("Authorization", $"Bearer {token}");

            try
            {
                await request.SendWebRequest();
            }
            catch
            {
                // WebGL 에서 실패한 요청의 await 가 예외를 던질 수 있다 — 아래 분기에서 처리한다.
            }

            switch (request.result)
            {
                case UnityWebRequest.Result.Success:
                    LastAuthError = null;
                    return request.downloadHandler.text;

                case UnityWebRequest.Result.ProtocolError when request.responseCode == 401:
                    if (string.IsNullOrEmpty(token))
                    {
                        // 토큰이 **아직** 없을 뿐, 호스트 배선이 끊긴 게 아닐 수 있다. 로비가 첫 조회를
                        // 토큰 주입보다 먼저 보내는 경우가 실제로 있고(TokenChanged 로 다시 부른다),
                        // 여기서 "wiring 확인" 이라고 단정한 문구가 demo 실측에서 원인을 FE 로 잘못
                        // 몰고 갔다 (GitLab #175 → 실제 원인은 world 호스트 nginx 403, #142).
                        // 배선 결함인지는 이 한 번으로 알 수 없으니 판정하지 않고 사실만 적는다.
                        LastAuthError = "Access Token 이 아직 없다 — 주입 전 호출이면 토큰 도착 후 자동 재시도된다";
                        Debug.LogWarning($"[HttpUserApi] {what} → 401 UNAUTHORIZED. {LastAuthError}");
                        return null;
                    }
                    LastAuthError = "Access Token 이 만료·무효다";
                    Debug.LogError($"[HttpUserApi] {what} → 401 UNAUTHORIZED. {LastAuthError}");
                    return null;

                case UnityWebRequest.Result.ProtocolError when request.responseCode == 403:
                    // 게스트에게 회원 전용 API 가 403 인 것은 **정상 응답**이다 — 서버가 계약대로
                    // 답한 것이라 에러가 아니다. 빨간 에러로 남기면 진짜 실패와 구분이 안 된다(QA #77).
                    LastAuthError = "게스트 계정은 이 기능을 쓸 수 없다 (MEMBER_ONLY)";
                    Debug.LogWarning($"[HttpUserApi] {what} → 403. {LastAuthError}");
                    return null;

                case UnityWebRequest.Result.ProtocolError:
                    // 400 VALIDATION_FAILED 등 — 서버 봉투에 사유가 들어 있어 본문째로 남긴다.
                    Debug.LogError($"[HttpUserApi] {what} → HTTP {request.responseCode}: {request.downloadHandler?.text}");
                    return null;

                default: // ConnectionError, DataProcessingError, timeout
                    Debug.LogError($"[HttpUserApi] {what} 실패: {request.error} (CORS·네트워크·타임아웃 확인)");
                    return null;
            }
        }

        static T ParseOrNull<T>(string body, string who) where T : class
        {
            try
            {
                var parsed = JsonUtility.FromJson<T>(body);
                if (parsed == null) Debug.LogError($"[HttpUserApi] {who}: 응답 JSON 이 비었다");
                return parsed;
            }
            catch (Exception e)
            {
                Debug.LogError($"[HttpUserApi] {who}: 응답 JSON 파싱 실패 — {e.Message}");
                return null;
            }
        }

        // JsonUtility 는 익명 타입을 못 쓴다 — 요청/echo 본문용 최소 타입.
        [Serializable]
        class AvatarUpdateRequest
        {
            public string avatarCode;
        }

       [Serializable]
       class WorldSessionRequest
       {
           public string worldId;
       }

        /// <summary>
        /// JsonUtility 는 최상위 배열을 못 읽는다 — 서버가 <c>[{...}]</c> 로 주므로
        /// <c>{"items":[...]}</c> 로 감싸서 파싱한다. 이 우회를 빼면 조용히 빈 배열이 된다.
        /// </summary>
        [Serializable]
        class AvatarPresetListEnvelope
        {
            public AvatarPresetDto[] items;
        }
    }
}
