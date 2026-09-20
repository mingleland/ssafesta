using System;
using System.Collections;
using System.Globalization;
using System.IO;
using System.Threading.Tasks;
using Festa.Integration;
using Festa.Network;
using Unity.Netcode;
using UnityEngine;
using UnityEngine.Networking;

namespace Festa.Diagnostics
{
    /// <summary>
    /// Linux headless 승인 접속 smoke runner (S15P21A604-675, GitLab #185).
    ///
    /// <para><b>무엇을 판정하나.</b> infra-003 의 승격 게이트 네 조건 중 <c>approvedAdmission</c> 이다.
    /// 앞의 셋(프로세스·내부 리스너·WebSocket 101)은 인프라가 밖에서 볼 수 있지만, 이것만은
    /// <b>실제 사용자처럼 들어가 봐야</b> 안다 — 새 grant 를 받아 공개 wss 로 접속하고,
    /// Unity 의 Connection Approval 을 통과해 플레이어가 스폰되는 데까지다.</para>
    ///
    /// <para><b>왜 기존 실행기로는 안 되나.</b> <c>Tools/loadtest/run-bots.ps1</c> 은 Windows 전용이고
    /// <c>127.0.0.1:7777</c> 로 직접 붙는다 — 에디터 Host 를 먼저 띄워야 하고, world-session API 를
    /// 호출하지 않으므로 승인 경로를 전혀 지나지 않는다. 승격 판정에 쓰면 <b>아무것도 검증하지 않는
    /// 초록불</b>이 된다.</para>
    ///
    /// <para><b>비밀은 결과 파일에도 로그에도 남기지 않는다.</b> 토큰·grant·JTI·Authorization 헤더·
    /// 닉네임은 기록 대상이 아니다. 남기는 것은 판정과 어디에 붙었는지(host·channelId)뿐이다.</para>
    ///
    /// <para>실행 예:
    /// <code>
    /// ./festa-world-smoke.x86_64 -batchmode -nographics -smoke-admission \
    ///   -api-base-url https://api.ssafesta.world \
    ///   -world-host world.ssafesta.world -world-port 443 -world-scheme wss \
    ///   -output /workspace/artifacts/approved-admission.json
    /// </code></para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class AdmissionSmokeRunner : MonoBehaviour
    {
        const string Flag = "-smoke-admission";

        /// <summary>Jenkins 가 단계 성패를 가르는 값. 이유마다 다른 코드를 준다 — 로그를 뒤지지 않아도 되게.</summary>
        const int ExitPass = 0;
        const int ExitAdmissionFailed = 1;   // 승인 거부 · 플레이어 미생성
        const int ExitSessionFailed = 2;     // 게스트 토큰 또는 world-session 발급 실패
        const int ExitTransportFailed = 3;   // endpoint · TLS · WebSocket
        const int ExitTimeout = 4;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Boot()
        {
            if (!HasFlag(Flag)) return;   // 평소 실행에는 아무 영향이 없다
            var go = new GameObject("@AdmissionSmoke");
            DontDestroyOnLoad(go);
            go.AddComponent<AdmissionSmokeRunner>();
        }

        IEnumerator Start()
        {
            string apiBase = Arg("-api-base-url");
            string outPath = Arg("-output");
            string wantHost = Arg("-world-host");
            string wantScheme = Arg("-world-scheme");
            string wantPortRaw = Arg("-world-port");
            float timeout = ParseFloat(Arg("-timeout-seconds"), 90f);

            Log($"시작 — api={Redact(apiBase)} 기대 endpoint={wantScheme}://{wantHost}:{wantPortRaw} 제한시간={timeout:F0}초");

            if (string.IsNullOrEmpty(apiBase) || string.IsNullOrEmpty(outPath))
            {
                Finish(outPath, ExitSessionFailed, "SESSION_ISSUE_FAILED", null, null,
                       "-api-base-url 과 -output 은 필수다");
                yield break;
            }

            // ── 1. 게스트 토큰 ───────────────────────────────────────────────
            // 실행할 때마다 새로 받는다. 이전 실행의 grant 를 재사용하면 만료·회수 경로를 검증하지 못한다.
            string token = null;
            yield return GuestToken(apiBase, t => token = t);
            if (string.IsNullOrEmpty(token))
            {
                Finish(outPath, ExitSessionFailed, "SESSION_ISSUE_FAILED", null, null, "게스트 토큰 발급 실패");
                yield break;
            }

            // ── 2. world-session grant ──────────────────────────────────────
            var api = new HttpUserApiClient(apiBase, new FixedToken(token));
            var sessionTask = api.CreateWorldSessionAsync();
            yield return AwaitTask(sessionTask, timeout);
            var session = sessionTask.Status == TaskStatus.RanToCompletion ? sessionTask.Result : null;
            if (session == null)
            {
                Finish(outPath, ExitSessionFailed, "SESSION_ISSUE_FAILED", null, null, "world-session 발급 실패");
                yield break;
            }

            // ── 3. endpoint 검사 ────────────────────────────────────────────
            if (!WorldSessionEndpoint.TryGetConnectionData(session, out string host, out ushort port, out bool secure))
            {
                Finish(outPath, ExitTransportFailed, "INVALID_ENDPOINT", null, session.channelId,
                       "world-session endpoint 가 유효하지 않다");
                yield break;
            }
            if (!EndpointMatches(host, port, secure, wantHost, wantPortRaw, wantScheme, out string mismatch))
            {
                // 기대와 다른 곳에 붙어 통과하면 승격 판정이 거짓말이 된다 — loopback·mock 우회를 여기서 막는다.
                Finish(outPath, ExitTransportFailed, "INVALID_ENDPOINT", host, session.channelId, mismatch);
                yield break;
            }

            // ── 4. 실제 접속 ────────────────────────────────────────────────
            // @Network 는 부트스트랩이 세우므로 첫 씬 로드 직후에는 아직 없을 수 있다 — 잠깐 기다린다.
            ConnectionManager cm = null;
            NetworkManager nm = null;
            float netDeadline = Time.realtimeSinceStartup + 30f;
            while (Time.realtimeSinceStartup < netDeadline)
            {
                cm = FindFirstObjectByType<ConnectionManager>();
                nm = NetworkManager.Singleton;
                if (cm != null && nm != null) break;
                yield return null;
            }

            if (cm == null || nm == null)
            {
                Finish(outPath, ExitTransportFailed, "WEBSOCKET_FAILED", host, session.channelId,
                       "ConnectionManager / NetworkManager 가 씬에 없다");
                yield break;
            }

            bool disconnected = false;
            ulong myId = ulong.MaxValue;
            nm.OnClientDisconnectCallback += id => { if (id == nm.LocalClientId) disconnected = true; };
            nm.OnClientConnectedCallback += id => { if (id == nm.LocalClientId) myId = id; };

            var payload = new ConnectionPayload { userId = 0, nickname = "smoke", avatarCode = "sk_01" };
            if (!cm.StartClient(session, payload))
            {
                Finish(outPath, ExitTransportFailed, "WEBSOCKET_FAILED", host, session.channelId, "StartClient 실패");
                yield break;
            }

            // ── 5. connected → player_ready ─────────────────────────────────
            float deadline = Time.realtimeSinceStartup + timeout;
            bool connected = false;
            while (Time.realtimeSinceStartup < deadline)
            {
                if (disconnected)
                {
                    // 승인 거부는 서버가 연결을 끊는 형태로 온다 — 붙기 전에 끊기면 그것이다.
                    Finish(outPath, ExitAdmissionFailed, "CONNECTION_APPROVAL_REJECTED", host, session.channelId,
                           connected ? "접속 후 끊김" : "승인 단계에서 끊김");
                    yield break;
                }
                if (!connected && nm.IsConnectedClient && myId != ulong.MaxValue)
                {
                    connected = true;
                    Log("connected 확인");
                }
                if (connected && nm.LocalClient != null && nm.LocalClient.PlayerObject != null)
                {
                    Log("player_ready 확인");
                    Finish(outPath, ExitPass, null, host, session.channelId, null);
                    yield break;
                }
                yield return null;
            }

            Finish(outPath,
                   connected ? ExitAdmissionFailed : ExitTimeout,
                   connected ? "PLAYER_NOT_READY" : "TIMEOUT",
                   host, session.channelId,
                   connected ? "접속은 됐지만 플레이어가 생성되지 않았다" : "제한시간 안에 접속되지 않았다");
        }

        // ── 게스트 토큰 ─────────────────────────────────────────────────────

        /// <summary>POST /api/v1/auth/guest — 응답의 accessToken 만 꺼내 쓰고 <b>로그에 남기지 않는다.</b></summary>
        IEnumerator GuestToken(string apiBase, Action<string> onDone)
        {
            using var req = new UnityWebRequest($"{apiBase.TrimEnd('/')}/api/v1/auth/guest", UnityWebRequest.kHttpVerbPOST)
            {
                downloadHandler = new DownloadHandlerBuffer(),
                timeout = 20,
            };
            req.SetRequestHeader("Content-Type", "application/json");
            yield return req.SendWebRequest();

            if (req.result != UnityWebRequest.Result.Success)
            {
                LogError($"게스트 토큰 실패 — HTTP {req.responseCode} {req.result}");
                onDone(null);
                yield break;
            }
            var parsed = JsonUtility.FromJson<GuestTokenResponse>(req.downloadHandler.text);
            onDone(parsed != null ? parsed.accessToken : null);
        }

        [Serializable]
        class GuestTokenResponse
        {
            public string accessToken;
            public string expiresAt;
        }

        /// <summary>받은 토큰을 그대로 물고 있는 제공자. 이 실행기 안에서만 산다.</summary>
        sealed class FixedToken : IAccessTokenProvider
        {
            readonly string _token;
            public FixedToken(string token) => _token = token;
            public string GetAccessToken() => _token;
        }

        // ── 판정 보조 ───────────────────────────────────────────────────────

        /// <summary>기대한 공개 경로에 붙었는지. 인자를 안 주면 그 항목은 검사하지 않는다.</summary>
        static bool EndpointMatches(string host, ushort port, bool secure,
                                    string wantHost, string wantPortRaw, string wantScheme, out string why)
        {
            why = null;
            if (!string.IsNullOrEmpty(wantHost) && !string.Equals(host, wantHost, StringComparison.OrdinalIgnoreCase))
            { why = $"host 가 기대와 다르다 (받은 값 {host})"; return false; }

            if (!string.IsNullOrEmpty(wantScheme))
            {
                bool wantSecure = string.Equals(wantScheme, "wss", StringComparison.OrdinalIgnoreCase);
                if (wantSecure != secure) { why = $"scheme 이 기대와 다르다 (받은 값 {(secure ? "wss" : "ws")})"; return false; }
            }
            if (!string.IsNullOrEmpty(wantPortRaw) && int.TryParse(wantPortRaw, out int wantPort) && wantPort != port)
            { why = $"port 가 기대와 다르다 (받은 값 {port})"; return false; }

            return true;
        }

        /// <summary>결과 파일을 쓰고 종료한다. <b>여기에 비밀을 넣지 않는다.</b></summary>
        void Finish(string outPath, int exitCode, string reasonCode, string endpointHost, string channelId, string detail)
        {
            string verdict = exitCode == ExitPass ? "PASS" : "FAIL";
            var sb = new System.Text.StringBuilder();
            sb.Append("{\n");
            sb.Append("  \"result\": \"").Append(verdict).Append("\",\n");
            sb.Append("  \"approvedAdmission\": \"").Append(verdict).Append("\",\n");
            if (exitCode == ExitPass)
            {
                sb.Append("  \"connected\": \"PASS\",\n");
                sb.Append("  \"playerReady\": \"PASS\",\n");
            }
            else if (!string.IsNullOrEmpty(reasonCode))
            {
                sb.Append("  \"reasonCode\": \"").Append(reasonCode).Append("\",\n");
            }
            if (!string.IsNullOrEmpty(endpointHost)) sb.Append("  \"endpoint\": \"").Append(endpointHost).Append("\",\n");
            if (!string.IsNullOrEmpty(channelId)) sb.Append("  \"channelId\": \"").Append(channelId).Append("\",\n");
            sb.Append("  \"checkedAt\": \"").Append(DateTime.UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ", CultureInfo.InvariantCulture)).Append("\"\n");
            sb.Append("}\n");

            if (!string.IsNullOrEmpty(outPath))
            {
                try
                {
                    var dir = Path.GetDirectoryName(outPath);
                    if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
                    File.WriteAllText(outPath, sb.ToString());
                }
                catch (Exception e) { LogError($"결과 파일을 쓰지 못했다: {e.Message}"); }
            }

            if (string.IsNullOrEmpty(detail)) Log($"{verdict} (exit {exitCode})");
            else LogError($"{verdict} (exit {exitCode}) — {reasonCode}: {detail}");

            Application.Quit(exitCode);
        }

        // ── 자잘한 것들 ─────────────────────────────────────────────────────

        static IEnumerator AwaitTask(Task task, float timeoutSeconds)
        {
            float deadline = Time.realtimeSinceStartup + timeoutSeconds;
            while (!task.IsCompleted && Time.realtimeSinceStartup < deadline) yield return null;
        }

        static bool HasFlag(string flag)
        {
            var args = Environment.GetCommandLineArgs();
            for (int i = 0; i < args.Length; i++)
                if (string.Equals(args[i], flag, StringComparison.OrdinalIgnoreCase)) return true;
            return false;
        }

        static string Arg(string name)
        {
            var args = Environment.GetCommandLineArgs();
            for (int i = 0; i < args.Length - 1; i++)
                if (string.Equals(args[i], name, StringComparison.OrdinalIgnoreCase)) return args[i + 1];
            return null;
        }

        static float ParseFloat(string raw, float fallback)
            => float.TryParse(raw, NumberStyles.Float, CultureInfo.InvariantCulture, out float v) ? v : fallback;

        /// <summary>주소에서 자격증명이 섞여 들어올 여지를 지운다 — 로그에 그대로 찍지 않는다.</summary>
        static string Redact(string url)
        {
            if (string.IsNullOrEmpty(url)) return "(없음)";
            int at = url.IndexOf('@');
            return at < 0 ? url : "(자격증명 포함 주소 — 가림)";
        }

        static void Log(string m) => Debug.Log($"[AdmissionSmoke] {m}");
        static void LogError(string m) => Debug.LogError($"[AdmissionSmoke] {m}");
    }
}
