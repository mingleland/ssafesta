using System.IO;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 부하 테스트 봇용 Windows 플레이어 빌드 (GitLab #89 2단계 / S15P21A604-164).
    ///
    /// 설계 결정 세 가지 — 잘못 잡으면 측정이 무의미해진다.
    ///
    /// 1) **일반 플레이어 빌드다** (Dedicated Server 아님). 서버 빌드로 만들면 클라이언트
    ///    코드 경로(소유권·접속 승인 요청)를 타지 않아 실제 사용자와 트래픽 모양이 달라진다.
    /// 2) **씬은 main 하나만** 넣는다. Build Settings 첫 씬이 CharacterLobby 라 그대로 쓰면
    ///    봇이 로비에서 멈춰 접속을 시도하지 않는다.
    /// 3) **빌드 타깃을 원래대로 되돌린다.** 이 프로젝트의 활성 타깃은 Linux 서버라
    ///    Windows 로 바꾼 채 두면 이후 서버 빌드·에디터 작업이 어긋난다.
    ///
    /// 출력: Builds/bot/festa-bot.exe
    /// 실행: run-bots.ps1 (festa-unity/Tools/loadtest/)
    /// </summary>
    public static class FestaBotBuilder
    {
        const string OutDir = "Builds/bot";
        const string ExeName = "festa-bot.exe";
        const string BotScene = "Assets/_Project/Scenes/main.unity";

        [MenuItem("Festa/부하테스트/봇 클라이언트 빌드 (Windows)")]
        public static void BuildBot()
        {
            if (Application.isPlaying)
            {
                Debug.LogError("[BotBuilder] Play 중에는 빌드하지 않는다 — 종료 후 실행해라.");
                return;
            }
            if (!File.Exists(BotScene))
            {
                Debug.LogError($"[BotBuilder] 씬을 찾지 못했다: {BotScene}");
                return;
            }

            // 되돌리기 위해 현재 타깃을 기억한다.
            var prevTarget = EditorUserBuildSettings.activeBuildTarget;
            var prevGroup = BuildPipeline.GetBuildTargetGroup(prevTarget);
            var prevSubtarget = EditorUserBuildSettings.standaloneBuildSubtarget;

            Directory.CreateDirectory(OutDir);
            var options = new BuildPlayerOptions
            {
                scenes = new[] { BotScene },
                locationPathName = Path.Combine(OutDir, ExeName),
                target = BuildTarget.StandaloneWindows64,
                targetGroup = BuildTargetGroup.Standalone,
                subtarget = (int)StandaloneBuildSubtarget.Player,
                // Development 여야 ProfilerRecorder(드로우콜 등)와 Debug.Log 가 살아 있다.
                options = BuildOptions.Development,
            };

            Debug.Log($"[BotBuilder] 빌드 시작 → {options.locationPathName} " +
                      $"(타깃 {prevTarget} → StandaloneWindows64, 끝나면 되돌린다)");

            BuildReport report = null;
            try
            {
                report = BuildPipeline.BuildPlayer(options);
            }
            finally
            {
                EditorUserBuildSettings.standaloneBuildSubtarget = prevSubtarget;
                EditorUserBuildSettings.SwitchActiveBuildTarget(prevGroup, prevTarget);
                Debug.Log($"[BotBuilder] 빌드 타깃 복원 → {prevTarget} ({prevSubtarget})");
            }

            var summary = report.summary;
            if (summary.result == BuildResult.Succeeded)
                Debug.Log($"[BotBuilder] 성공 — {summary.totalSize / 1048576} MB, {summary.totalTime.TotalMinutes:F1}분\n" +
                          "다음: Tools/loadtest/run-bots.ps1 -Count 10");
            else
                Debug.LogError($"[BotBuilder] 실패 — {summary.result}, 오류 {summary.totalErrors}건");
        }
    }
}
