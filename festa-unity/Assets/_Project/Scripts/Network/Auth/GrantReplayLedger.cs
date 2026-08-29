using System;
using System.Collections.Generic;
using System.IO;
using UnityEngine;

namespace Festa.Network
{
    /// <summary>
    /// 이미 쓴 월드 입장 grant 를 다시 못 쓰게 막는다 (spec 002 FR-014).
    ///
    /// **Backend 는 이 일을 하지 않는다.** 발급자 주석이 그 이유를 적어뒀다 — Spring 에 "사용된 토큰"
    /// 테이블을 두면 아무도 읽지 않거나, 읽는 순간 Spring 이 다시 입장 경로에 올라선다. 헌법 14조가
    /// 막으려던 바로 그 구조다. 그래서 소비 판정은 게임 서버가 자기 원장으로 한다.
    ///
    /// **재배포를 넘어 살아남아야 한다** — 그래서 메모리가 아니라 퍼시스턴트 볼륨의 파일이다
    /// (`game-runtime.md`: `/var/lib/festa-world/used-grants.log`).
    ///
    /// **쓰기에 실패하면 입장을 거부한다(fail-closed).** 계약이 그렇게 정하고 있다. 기록을 못 남긴 채
    /// 통과시키면 그 grant 는 무제한 재사용 가능해진다 — 조용히 열어두느니 막고 드러낸다.
    /// </summary>
    public static class GrantReplayLedger
    {
        const string PathEnv = "WORLD_REPLAY_LEDGER";
        const string DefaultPath = "/var/lib/festa-world/used-grants.log";

        static readonly Dictionary<string, long> s_used = new Dictionary<string, long>();
        static string s_path;
        static bool s_loaded;
        static bool s_unusable;

        /// <summary>
        /// grant 를 소비한다. 처음 보는 것이면 기록하고 true, 이미 쓴 것이거나 기록에
        /// 실패하면 false 다.
        /// </summary>
        public static bool TryConsume(string jti, long expiresAtUnix, out string reason)
        {
            if (!s_loaded) Load();

            if (s_unusable)
            {
                // 원장을 못 쓰는 상태다. 여기서 통과시키면 재사용이 전면 허용된다.
                reason = "원장을 사용할 수 없다 (fail-closed)";
                return false;
            }

            if (s_used.ContainsKey(jti))
            {
                reason = "이미 사용된 grant 다 (재사용 시도)";
                return false;
            }

            if (!Append(jti, expiresAtUnix, out var why))
            {
                reason = $"원장 기록 실패 — {why} (fail-closed)";
                return false;
            }

            s_used[jti] = expiresAtUnix;
            reason = null;
            return true;
        }

        /// <summary>기동 시 한 번 호출해 원장을 열어두고, 못 열면 그 사실을 미리 드러낸다.</summary>
        public static void Warmup()
        {
            if (!s_loaded) Load();
            if (s_unusable)
                Debug.LogError($"[ReplayLedger] 원장을 쓸 수 없어 모든 입장이 거부된다 — {s_path}");
            else
                Debug.Log($"[ReplayLedger] 준비 완료 — 기존 {s_used.Count}건 (경로 {s_path})");
        }

        static void Load()
        {
            s_loaded = true;
            s_path = Environment.GetEnvironmentVariable(PathEnv);
            if (string.IsNullOrEmpty(s_path))
            {
                // 에디터에서 `/var/lib/...` 는 존재하지도, 만들 수도 없다.
                // 배포 경로만 계약값을 쓰고 그 외에는 앱 로컬로 떨어뜨린다.
                s_path = Application.isEditor
                    ? Path.Combine(Application.persistentDataPath, "used-grants.log")
                    : DefaultPath;
            }

            try
            {
                var dir = Path.GetDirectoryName(s_path);
                if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);

                if (File.Exists(s_path))
                {
                    var now = WorldEntryToken.UnixNow();
                    foreach (var line in File.ReadAllLines(s_path))
                    {
                        // 형식: <jti>\t<exp>
                        var tab = line.IndexOf('\t');
                        if (tab <= 0) continue;
                        var jti = line.Substring(0, tab);
                        if (!long.TryParse(line.Substring(tab + 1), out var exp)) continue;
                        // 이미 만료된 grant 는 서명 검사에서 어차피 걸린다. 메모리에 들고 있을 이유가 없다.
                        if (exp < now) continue;
                        s_used[jti] = exp;
                    }
                }

                // 실제로 쓸 수 있는지 지금 확인한다. 첫 입장 때 알게 되면 그 사용자가 대가를 치른다.
                using (var probe = new FileStream(s_path, FileMode.Append, FileAccess.Write, FileShare.Read))
                    probe.Flush();
            }
            catch (Exception e)
            {
                s_unusable = true;
                Debug.LogError($"[ReplayLedger] 원장 준비 실패 ({e.GetType().Name}) — 경로 {s_path}");
            }
        }

        static bool Append(string jti, long expiresAtUnix, out string why)
        {
            try
            {
                using var stream = new FileStream(s_path, FileMode.Append, FileAccess.Write, FileShare.Read);
                using var writer = new StreamWriter(stream);
                writer.WriteLine($"{jti}\t{expiresAtUnix}");
                writer.Flush();
                // 버퍼만 비우면 프로세스가 죽을 때 디스크에 안 남는다. 재사용 차단은
                // 재배포를 넘어야 하므로 여기서 실제로 내려쓴다.
                stream.Flush(true);
                why = null;
                return true;
            }
            catch (Exception e)
            {
                why = e.GetType().Name;
                return false;
            }
        }
    }
}
