using System.IO;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// WebGL **측정용** 빌드 (GitLab #89 3단계).
    ///
    /// 왜 별도 메뉴인가 — 그냥 Build 를 누르면 릴리즈 빌드가 나오는데, 릴리즈에서는
    /// **측정이 불가능**하다. 두 가지가 동시에 막힌다.
    ///   ① Unity 가 프로파일러를 제거해 `ProfilerRecorder` 의 드로우콜·SetPass 통계가
    ///      전부 무효가 된다 — 이번 측정의 목적 자체가 사라진다.
    ///   ② 계측 도구가 `Debug.isDebugBuild` 로 가드돼 있어 F3/F6 이 뜨지 않는다
    ///      (릴리즈에서 실사용자가 아바타 40기를 소환하면 안 되므로 의도된 가드다).
    /// 그래서 Development Build 를 **강제로 켜서** 빌드한다.
    ///
    /// 배포용 빌드가 아니다. 배포는 기존 절차(릴리즈 빌드)를 그대로 쓴다.
    /// 출력: Builds/web-dev  (배포본 Builds/web 을 덮어쓰지 않는다)
    /// </summary>
    public static class FestaWebBuilder
    {
        const string OutDir = "Builds/web-dev";
        const string MeasureScene = "Assets/_Project/Scenes/main.unity";

        [MenuItem("Festa/부하테스트/WebGL 측정용 빌드 (Development)")]
        public static void BuildWebForMeasurement()
        {
            if (Application.isPlaying)
            {
                Debug.LogError("[WebBuilder] Play 중에는 빌드하지 않는다 — 종료 후 실행해라.");
                return;
            }

            var prevTarget = EditorUserBuildSettings.activeBuildTarget;
            var prevGroup = BuildPipeline.GetBuildTargetGroup(prevTarget);
            bool prevDev = EditorUserBuildSettings.development;
            var prevCompression = PlayerSettings.WebGL.compressionFormat;

            // 로컬 정적 서버로 열려면 압축이 없어야 한다 (gzip/br 은 서버가 헤더를 붙여줘야 한다).
            PlayerSettings.WebGL.compressionFormat = WebGLCompressionFormat.Disabled;

            Directory.CreateDirectory(OutDir);
            var options = new BuildPlayerOptions
            {
                scenes = new[] { MeasureScene },   // 로비를 거치면 측정 씬까지 손이 더 간다
                locationPathName = OutDir,
                target = BuildTarget.WebGL,
                targetGroup = BuildTargetGroup.WebGL,
                options = BuildOptions.Development,   // ← 이것이 이 메뉴의 존재 이유
            };

            Debug.Log($"[WebBuilder] 측정용 WebGL 빌드 시작 → {OutDir} (Development=ON, 압축 Disabled)");

            BuildReport report = null;
            try { report = BuildPipeline.BuildPlayer(options); }
            finally
            {
                EditorUserBuildSettings.development = prevDev;
                PlayerSettings.WebGL.compressionFormat = prevCompression;
                if (prevTarget != BuildTarget.WebGL)
                    EditorUserBuildSettings.SwitchActiveBuildTarget(prevGroup, prevTarget);
                Debug.Log($"[WebBuilder] 설정 복원 — development={prevDev}, 압축={prevCompression}, 타깃={prevTarget}");
            }

            var s = report.summary;
            if (s.result == BuildResult.Succeeded)
                Debug.Log($"[WebBuilder] 성공 — {s.totalSize / 1048576} MB, {s.totalTime.TotalMinutes:F1}분\n" +
                          "이제 Builds/web-dev 를 로컬 서버로 열고 F3(HUD)·F6(아바타 +5) 으로 측정한다.");
            else
                Debug.LogError($"[WebBuilder] 실패 — {s.result}, 오류 {s.totalErrors}건");
        }
    }
}
