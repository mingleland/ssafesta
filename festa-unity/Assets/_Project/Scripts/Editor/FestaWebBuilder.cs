using System.IO;
using System.Linq;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// WebGL 빌드 (GitLab #89 3단계 측정 겸용).
    ///
    /// **Development Build 를 강제로 켠다** — 이게 이 메뉴의 존재 이유다.
    /// 릴리즈 빌드에서는 측정이 두 겹으로 막힌다.
    ///   ① Unity 가 프로파일러를 제거해 `ProfilerRecorder` 의 드로우콜·SetPass 통계가 전부 무효.
    ///   ② 계측 도구가 `Debug.isDebugBuild` 로 가드돼 F3/F6 이 뜨지 않는다
    ///      (릴리즈에서 실사용자가 아바타 40기를 소환하면 안 되므로 의도된 가드다).
    ///
    /// 출력은 `Builds/web` 하나로 통일한다 — 개발 중에는 측정본과 배포본을 나누지 않는다는
    /// 결정(2026-08-25). **실제 배포 시점에는 Development 를 끄고 다시 뽑아야 한다.**
    /// 씬은 Build Settings 의 활성 씬을 그대로 쓴다(로비 → main 흐름 유지).
    /// </summary>
    public static class FestaWebBuilder
    {
        const string OutDir = "Builds/web";

        [MenuItem("Festa/부하테스트/WebGL 빌드 (Development · 측정용)")]
        public static void BuildWeb()
        {
            if (Application.isPlaying)
            {
                Debug.LogError("[WebBuilder] Play 중에는 빌드하지 않는다 — 종료 후 실행해라.");
                return;
            }

            var scenes = EditorBuildSettings.scenes.Where(s => s.enabled).Select(s => s.path).ToArray();
            if (scenes.Length == 0)
            {
                Debug.LogError("[WebBuilder] Build Settings 에 활성 씬이 없다.");
                return;
            }

            var prevTarget = EditorUserBuildSettings.activeBuildTarget;
            var prevGroup = BuildPipeline.GetBuildTargetGroup(prevTarget);
            bool prevDev = EditorUserBuildSettings.development;
            var prevCompression = PlayerSettings.WebGL.compressionFormat;

            // 압축을 끄는 이유: gzip/br 로 뽑으면 서버가 Content-Encoding 헤더를 붙여 줘야 한다.
            // 로컬 정적 서버로 바로 열려면 Disabled 가 편하다. 배포 때는 다시 켜는 게 맞다.
            PlayerSettings.WebGL.compressionFormat = WebGLCompressionFormat.Disabled;

            Directory.CreateDirectory(OutDir);
            var options = new BuildPlayerOptions
            {
                scenes = scenes,
                locationPathName = OutDir,
                target = BuildTarget.WebGL,
                targetGroup = BuildTargetGroup.WebGL,
                options = BuildOptions.Development,
            };

            Debug.Log($"[WebBuilder] 빌드 시작 → {OutDir} (Development=ON, 압축 Disabled, 씬 {scenes.Length}개)");

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
                          "로컬 서버로 열고 로비를 지나 월드 진입 후 F3(HUD) · F6(아바타 +5) 으로 측정한다.");
            else
                Debug.LogError($"[WebBuilder] 실패 — {s.result}, 오류 {s.totalErrors}건");
        }
    }
}
