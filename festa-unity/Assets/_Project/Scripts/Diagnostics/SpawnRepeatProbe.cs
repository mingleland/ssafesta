using System.Collections;
using System.Collections.Generic;
using System.Text;
using Festa.Integration;
using Festa.Network;
using Unity.Netcode;
using UnityEngine;

namespace Festa.Diagnostics
{
    /// <summary>
    /// 접속을 반복하며 **스폰 좌표를 모은다** (S15P21A604-78 완료 조건 "10회 접속 중 허공 스폰 0회").
    ///
    /// <para><b>왜 자동화하나.</b> 손으로 10번 붙였다 끊으면 매 회차의 대기 시간과 타이밍이 달라진다 —
    /// 스폰 경합은 <b>타이밍에 좌우되는 결함</b>이라 그 흔들림이 곧 측정의 흔들림이 된다. 게다가
    /// "허공 스폰 0회" 는 <b>안 일어난 것을 보이는 주장</b>이라 회차 수와 판정 기준을 코드로 고정해야
    /// 나중에 같은 조건으로 다시 잴 수 있다.</para>
    ///
    /// <para><b>판정 기준.</b> T-177 의 증상은 "원점(방 밖 허공)에서 떨어진다" 였다. 그래서
    /// <b>원점 근처</b>를 허공 스폰으로 본다. 서버가 배정하는 실제 스폰은 원점에서 멀리 있다.
    /// 아울러 <c>PlayerMovement</c> 가 남기는 <b>"스폰 경합 감지"</b> 경고도 함께 센다 —
    /// 강제 이동이 실제로 동작했는지(=경합이 있었는지) 가 그 로그에만 남기 때문이다.</para>
    ///
    /// <para>실행: main 씬에서 Play 후 <b>F11</b>. 서버가 따로 떠 있어야 한다.</para>
    /// </summary>
    public sealed class SpawnRepeatProbe : MonoBehaviour
    {
        const int Attempts = 10;
        const float ConnectTimeout = 15f;    // 실시간. 저프레임에서도 흔들리지 않게 realtime 을 쓴다
        const float SettleAfterSpawn = 1.0f; // 스폰 직후 강제 이동이 끝나기를 기다린다
        const float GapBetween = 0.5f;

        /// <summary>원점에서 이 안쪽이면 "방 밖 허공" 으로 본다 (T-177 의 증상 지점).</summary>
        const float OriginRadius = 5f;

        bool _running;
        int _raceWarnings;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void Install()
        {
            if (!Debug.isDebugBuild && !Application.isEditor) return;
            var go = new GameObject("@SpawnRepeatProbe");
            go.hideFlags = HideFlags.HideAndDontSave;
            DontDestroyOnLoad(go);
            go.AddComponent<SpawnRepeatProbe>();
        }

        void Update()
        {
            if (!_running && Input.GetKeyDown(KeyCode.F11)) StartCoroutine(Run());
        }

        void OnLog(string condition, string stack, LogType type)
        {
            if (type == LogType.Warning && condition.Contains("스폰 경합 감지")) _raceWarnings++;
        }

        IEnumerator Run()
        {
            var connection = FindFirstObjectByType<ConnectionManager>();
            if (connection == null)
            {
                Debug.LogError("[SpawnProbe] ConnectionManager 를 찾지 못했다 — main 씬에서 돌려라.");
                yield break;
            }

            _running = true;
            _raceWarnings = 0;
            Application.logMessageReceived += OnLog;
            ApiServices.EnsureInitialized();

            var rows = new List<string>();
            int airborne = 0, failed = 0;

            for (int i = 1; i <= Attempts; i++)
            {
                // 이전 회차를 확실히 끊고 프레임을 넘긴다 — 겹쳐 걸면 전송 계층이 원인을
                // 알려주지 않는 실패만 남는다 (T-182).
                connection.Shutdown();
                yield return null;

                var sessionTask = ApiServices.User.CreateWorldSessionAsync();
                while (!sessionTask.IsCompleted) yield return null;
                var session = sessionTask.Result;
                if (session == null)
                {
                    failed++;
                    rows.Add($"| {i} | — | — | **세션 발급 실패** |");
                    continue;
                }

                connection.StartClient(session, new ConnectionPayload
                {
                    userId = 4242,
                    nickname = "SpawnProbe",
                    avatarCode = "sk_01",
                });

                // 접속 + 소유 플레이어 스폰까지 기다린다. 둘 다 있어야 좌표가 의미를 갖는다.
                float deadline = Time.realtimeSinceStartup + ConnectTimeout;
                PlayerMovement mine = null;
                while (Time.realtimeSinceStartup < deadline)
                {
                    var nm = NetworkManager.Singleton;
                    if (nm != null && nm.IsConnectedClient)
                    {
                        var po = nm.LocalClient?.PlayerObject;
                        if (po != null) { mine = po.GetComponent<PlayerMovement>(); if (mine != null) break; }
                    }
                    yield return null;
                }

                if (mine == null)
                {
                    failed++;
                    rows.Add($"| {i} | — | — | **접속/스폰 실패 (타임아웃 {ConnectTimeout:F0}s)** |");
                    continue;
                }

                float settleUntil = Time.realtimeSinceStartup + SettleAfterSpawn;
                while (Time.realtimeSinceStartup < settleUntil) yield return null;

                var pos = mine.transform.position;
                var assigned = mine.ServerSpawnPosition.Value;
                bool inAir = pos.magnitude < OriginRadius;
                if (inAir) airborne++;

                rows.Add($"| {i} | {pos} | {assigned} | {(inAir ? "**허공(원점)**" : "정상")} |");

                float gapUntil = Time.realtimeSinceStartup + GapBetween;
                while (Time.realtimeSinceStartup < gapUntil) yield return null;
            }

            connection.Shutdown();
            Application.logMessageReceived -= OnLog;

            var sb = new StringBuilder();
            sb.AppendLine($"[SpawnProbe] 결과 — {Attempts}회 시도");
            sb.AppendLine("| 회차 | 스폰 좌표 | 서버 배정 | 판정 |");
            sb.AppendLine("|---:|---|---|---|");
            foreach (var r in rows) sb.AppendLine(r);
            sb.AppendLine();
            sb.AppendLine($"허공(원점 {OriginRadius}유닛 이내) 스폰 = **{airborne}회** / 실패 {failed}회 / " +
                          $"'스폰 경합 감지' 경고 {_raceWarnings}회");
            Debug.Log(sb.ToString());

            _running = false;
        }
    }
}
