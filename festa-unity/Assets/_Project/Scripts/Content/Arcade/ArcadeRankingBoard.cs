// 오락기별 표시 전용 TOP 5를 백엔드와 동기화하고 결과 화면의 점수를 제출한다.
using System;
using System.Collections.Generic;
using System.Text;
using System.Threading.Tasks;
using Festa.Integration;
using TMPro;
using UnityEngine;
using UnityEngine.Networking;
using UnityEngine.SceneManagement;

namespace Festa.Content.Arcade
{
    /// <summary>GitLab #264 / S15P21A604-963의 machineId 귀속 랭킹 계약을 소비한다.</summary>
    public static class ArcadeRankingBoard
    {
        const int TimeoutSeconds = 8;
        const float CacheSeconds = 30f;
        static readonly Dictionary<string, CacheEntry> Cache = new();
        static readonly HashSet<string> Fetching = new();
        static readonly Dictionary<int, int> ViewVersions = new();

        /// <summary>
        /// 씬이 내려가면 표시용 캐시를 버린다 (S15P21A604-970). <see cref="ViewVersions"/> 는 텍스트
        /// 인스턴스 ID 를 키로 넣기만 해서, 정리하지 않으면 월드를 드나들 때마다 항목이 늘어난다.
        /// 랭킹 본문도 어차피 30초 캐시라 버려도 잃는 것이 없다.
        /// </summary>
        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
        static void Install()
        {
            SceneManager.sceneUnloaded -= OnSceneUnloaded;
            SceneManager.sceneUnloaded += OnSceneUnloaded;
        }

        static void OnSceneUnloaded(Scene _)
        {
            Cache.Clear();
            Fetching.Clear();
            ViewVersions.Clear();
        }

        [Serializable] sealed class RankEntry { public int rank; public string nickname; public int bestScore; }
        [Serializable] sealed class RankEntries { public RankEntry[] items; }
        [Serializable] sealed class ScoreRequest { public int score; }
        sealed class CacheEntry { public string Board; public float LoadedAt; }

        public static void ShowPreview(MonoBehaviour owner, TextMeshPro target, Camera camera, string title)
        {
            Show(owner, target, camera, title, "[F] PLAY", false, 0);
        }

        public static void ShowResult(MonoBehaviour owner, TextMeshPro target, Camera camera, string title, string summary, int score)
        {
            Show(owner, target, camera, title + "\n" + summary, "SPACE TO RETRY", true, score);
        }

        static void Show(MonoBehaviour owner, TextMeshPro target, Camera camera, string header, string footer, bool submit, int score)
        {
            if (owner == null || target == null) return;
            var machineId = owner.GetComponent<ArcadeMachineInteractable>()?.MachineId;
            if (string.IsNullOrWhiteSpace(machineId))
            {
                target.text = header + "\nTOP 5\nRANKING UNAVAILABLE\n" + footer;
                return;
            }

            int targetId = target.GetInstanceID();
            int version = ViewVersions.TryGetValue(targetId, out var current) ? current + 1 : 1;
            ViewVersions[targetId] = version;
            target.text = Compose(header, CachedBoard(machineId), footer);
            RenderIdle(camera);

            if (submit) SubmitThenFetch(owner, machineId, score, target, camera, header, footer, targetId, version);
            else Fetch(owner, machineId, target, camera, header, footer, targetId, version, false);
        }

        static string CachedBoard(string machineId)
        {
            return Cache.TryGetValue(machineId, out var entry) ? entry.Board : "TOP 5\nLOADING...";
        }

        static async void SubmitThenFetch(MonoBehaviour owner, string machineId, int score, TextMeshPro target,
            Camera camera, string header, string footer, int targetId, int version)
        {
            if (score < 0 || score > 100000)
            {
                Debug.LogError($"[ArcadeRanking] 계약 범위를 벗어난 점수라 제출하지 않는다 — machineId={machineId} score={score}");
            }
            else
            {
                await Submit(machineId, score);
                Cache.Remove(machineId);
            }
            Fetch(owner, machineId, target, camera, header, footer, targetId, version, true);
        }

        static async Task Submit(string machineId, int score)
        {
            if (ApiServices.TokenProvider == null || ApiServices.IsMock || string.IsNullOrWhiteSpace(ApiServices.SpringBaseUrl)) return;
            var token = ApiServices.TokenProvider?.GetAccessToken();
            if (string.IsNullOrWhiteSpace(token)) return; // 게스트는 조회만 가능하다.

            var url = $"{ApiServices.SpringBaseUrl}/api/arcade/rankings/{UnityWebRequest.EscapeURL(machineId)}/scores";
            var json = JsonUtility.ToJson(new ScoreRequest { score = score });
            using var request = new UnityWebRequest(url, UnityWebRequest.kHttpVerbPOST)
            {
                uploadHandler = new UploadHandlerRaw(Encoding.UTF8.GetBytes(json)),
                downloadHandler = new DownloadHandlerBuffer(), timeout = TimeoutSeconds,
            };
            request.SetRequestHeader("Content-Type", "application/json");
            request.SetRequestHeader("Authorization", $"Bearer {token}");
            try { await request.SendWebRequest(); } catch { }
            if (request.result != UnityWebRequest.Result.Success)
                Debug.LogWarning($"[ArcadeRanking] 점수 제출 실패 — machineId={machineId} score={score} http={request.responseCode} error={request.error}");
        }

        static async void Fetch(MonoBehaviour owner, string machineId, TextMeshPro target, Camera camera,
            string header, string footer, int targetId, int version, bool force)
        {
            if (!force && Cache.TryGetValue(machineId, out var cached) && Time.realtimeSinceStartup - cached.LoadedAt < CacheSeconds)
            {
                Apply(target, camera, header, footer, targetId, version, cached.Board);
                return;
            }
            if (!Fetching.Add(machineId)) return;
            try
            {
                // main 씬 단독 실행 중에는 Bootstrap을 억지로 Mock 초기화하지 않는다. 실제 앱은 첫 씬에서 이미 Init한다.
                if (ApiServices.TokenProvider == null || ApiServices.IsMock || string.IsNullOrWhiteSpace(ApiServices.SpringBaseUrl))
                {
                    Store(machineId, "TOP 5\nRANKING UNAVAILABLE");
                    Apply(target, camera, header, footer, targetId, version, Cache[machineId].Board);
                    return;
                }

                var url = $"{ApiServices.SpringBaseUrl}/api/arcade/rankings/{UnityWebRequest.EscapeURL(machineId)}?limit=5";
                using var request = UnityWebRequest.Get(url);
                request.timeout = TimeoutSeconds;
                try { await request.SendWebRequest(); } catch { }
                if (request.result != UnityWebRequest.Result.Success)
                {
                    Debug.LogWarning($"[ArcadeRanking] TOP 5 조회 실패 — machineId={machineId} http={request.responseCode} error={request.error}");
                    Store(machineId, "TOP 5\nRANKING UNAVAILABLE");
                }
                else Store(machineId, ParseBoard(request.downloadHandler?.text));
                Apply(target, camera, header, footer, targetId, version, Cache[machineId].Board);
            }
            finally { Fetching.Remove(machineId); }
        }

        static string ParseBoard(string json)
        {
            try
            {
                var wrapped = JsonUtility.FromJson<RankEntries>("{\"items\":" + (string.IsNullOrWhiteSpace(json) ? "[]" : json) + "}");
                var rows = wrapped?.items ?? Array.Empty<RankEntry>();
                if (rows.Length == 0) return "TOP 5\nNO RECORDS YET";
                var builder = new StringBuilder("TOP 5");
                for (int i = 0; i < rows.Length && i < 5; i++)
                {
                    var row = rows[i];
                    var nickname = string.IsNullOrWhiteSpace(row.nickname) ? "PLAYER" : row.nickname;
                    if (nickname.Length > 10) nickname = nickname.Substring(0, 10);
                    builder.Append('\n').Append(row.rank).Append("  ").Append(nickname).Append("  ").Append(row.bestScore.ToString("N0"));
                }
                return builder.ToString();
            }
            catch (Exception ex)
            {
                Debug.LogWarning($"[ArcadeRanking] TOP 5 응답 파싱 실패 — {ex.Message}");
                return "TOP 5\nRANKING UNAVAILABLE";
            }
        }

        static void Store(string machineId, string board) => Cache[machineId] = new CacheEntry { Board = board, LoadedAt = Time.realtimeSinceStartup };
        static string Compose(string header, string board, string footer) => header + "\n" + board + "\n" + footer;

        static void Apply(TextMeshPro target, Camera camera, string header, string footer, int targetId, int version, string board)
        {
            if (target == null || !ViewVersions.TryGetValue(targetId, out var current) || current != version) return;
            target.text = Compose(header, board, footer);
            RenderIdle(camera);
        }

        static void RenderIdle(Camera camera)
        {
            // 대기 중인 게임은 본체가 재워져 있다 — 그대로 굽으면 빈 화면이 캐비닛을 덮는다.
            if (camera == null || camera.enabled) return;
            if (!ArcadeRuntimeSuspension.TryRenderIdle(camera)) camera.Render();
        }
    }
}
