using System;
using System.IO;
using System.Linq;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// CI 배치 모드 전용 빌드 진입점.
    ///
    /// 메뉴 빌더(<see cref="FestaReleaseBuilder"/>)는 다이얼로그로 사용자에게 묻기 때문에
    /// `-batchmode` 에서 쓸 수 없다. 여기서는 인자만 읽고 무인으로 돈다.
    ///
    /// 사용:
    ///   Unity -batchmode -quit -projectPath . \
    ///         -executeMethod Festa.EditorTools.CiBuild.Build -festaTarget webgl
    ///
    /// 실패는 반드시 0 이 아닌 코드로 끝낸다 — CI 가 초록으로 넘어가면 안 된다.
    /// </summary>
    public static class CiBuild
    {
        const string WebOutDir = "Builds/webgl";
        const string ServerOutDir = "Builds/linux-server";
        const string ServerExeName = "festa-unity.x86_64";

        public static void Build()
        {
            try
            {
                var target = ArgValue("-festaTarget") ?? "all";
                Log($"타깃 = {target}");

                var scenes = EnabledScenes();
                if (scenes.Length == 0)
                    Fail("빌드 설정에 활성화된 씬이 없다.");

                Log($"씬 {scenes.Length}개: {string.Join(", ", scenes)}");

                switch (target)
                {
                    case "webgl":        BuildWeb(scenes); break;
                    case "linux-server": BuildServer(scenes); break;
                    case "all":          BuildServer(scenes); BuildWeb(scenes); break;
                    default:             Fail($"알 수 없는 타깃: {target} (webgl|linux-server|all)"); break;
                }

                Log("빌드 성공");
                EditorApplication.Exit(0);
            }
            catch (Exception e)
            {
                // 예외를 삼키면 CI 가 성공으로 본다. 반드시 로그 + 실패 코드.
                Debug.LogError($"[CiBuild] 실패: {e}");
                EditorApplication.Exit(1);
            }
        }

        static void BuildWeb(string[] scenes)
        {
            // URP 는 **활성 빌드 타깃 시점에** 포함할 RP 에셋을 결정한다 (S15P21A604-316).
            // BuildPlayer 에 타깃만 넘기면 늦다 — 먼저 전환해야 Mobile_RPAsset 이 들어간다.
            if (EditorUserBuildSettings.activeBuildTarget != BuildTarget.WebGL)
            {
                Log("활성 타깃을 WebGL 로 전환한다 (RP 에셋 결정 시점 때문에 필수)");
                if (!EditorUserBuildSettings.SwitchActiveBuildTarget(BuildTargetGroup.WebGL, BuildTarget.WebGL))
                    Fail("WebGL 타깃 전환 실패");
            }

            // 배포본이 부를 API. 기본 prod — 에셋 커밋값(Mock+Local)이 그대로 나가면 사용자 WebGL 이
            // Mock 으로 동작한다 (S15P21A604-419). -festaEnv dev|local 로 바꿀 수 있다. Mock 은 CI 에서 항상 끈다.
            var envArg = (ArgValue("-festaEnv") ?? "prod").ToLowerInvariant();
            var env = envArg switch
            {
                "prod" => Festa.Integration.ApiEnvironment.Prod,
                "dev" => Festa.Integration.ApiEnvironment.Dev,
                "local" => Festa.Integration.ApiEnvironment.Local,
                _ => throw new Exception($"알 수 없는 -festaEnv: {envArg} (prod|dev|local)"),
            };
            var apiConfig = FestaReleaseBuilder.LoadApiConfig();
            bool prevMock = apiConfig != null && apiConfig.useMockApi;
            var prevEnv = apiConfig != null ? apiConfig.activeEnvironment : Festa.Integration.ApiEnvironment.Local;
            if (!FestaReleaseBuilder.ForceApiEnvironment(apiConfig, env))
                Fail($"ApiConfig 를 {env} 로 강제하지 못했다");

            var options = new BuildPlayerOptions
            {
                scenes = scenes,
                locationPathName = WebOutDir,
                target = BuildTarget.WebGL,
                options = BuildOptions.None,   // Development OFF — 배포 설정
            };
            try { RunBuild(options, "WebGL"); }
            finally { FestaReleaseBuilder.RestoreApiEnvironment(apiConfig, prevMock, prevEnv); }

            // FE 는 <빌드 base>/manifest.json 에서 로더 URL 4종을 읽는다 (GitLab #60).
            // 메뉴 빌더만 이 파일을 만들고 CI 경로는 빠져 있어서, CI 산출물은 빌드는
            // 성공했는데 월드 진입이 404 로 실패했다 (S15P21A604-417). 파일명이 해시라
            // FE 가 디렉터리를 추측할 수도 없다 — manifest 없는 산출물은 산출물이 아니다.
            FestaWebBuilder.WriteManifest(WebOutDir);

            // 파이프라인이 이 네 가지를 확인한다. 여기서 먼저 잡아 실패를 앞당긴다.
            foreach (var required in new[] { "index.html", "Build", "TemplateData", "manifest.json" })
            {
                var path = Path.Combine(WebOutDir, required);
                if (!File.Exists(path) && !Directory.Exists(path))
                    Fail($"WebGL 산출물에 {required} 가 없다: {path}");
            }
        }

        static void BuildServer(string[] scenes)
        {
            // 데디케이티드 서버 서브타깃을 켜야 UNITY_SERVER 가 정의된다 (T-182).
            EditorUserBuildSettings.standaloneBuildSubtarget = StandaloneBuildSubtarget.Server;

            var options = new BuildPlayerOptions
            {
                scenes = scenes,
                locationPathName = Path.Combine(ServerOutDir, ServerExeName),
                target = BuildTarget.StandaloneLinux64,
                subtarget = (int)StandaloneBuildSubtarget.Server,
                options = BuildOptions.None,
            };
            RunBuild(options, "Linux 서버");
        }

        static void RunBuild(BuildPlayerOptions options, string label)
        {
            Log($"{label} 빌드 시작 → {options.locationPathName}");
            var report = BuildPipeline.BuildPlayer(options);
            var summary = report.summary;

            Log($"{label} 결과: {summary.result} " +
                $"({summary.totalSize / 1048576} MB, {summary.totalTime.TotalSeconds:F0}s, " +
                $"에러 {summary.totalErrors})");

            if (summary.result != BuildResult.Succeeded)
                Fail($"{label} 빌드 실패: {summary.result}");
        }

        static string[] EnabledScenes() =>
            EditorBuildSettings.scenes.Where(s => s.enabled).Select(s => s.path).ToArray();

        static string ArgValue(string name)
        {
            var args = Environment.GetCommandLineArgs();
            for (int i = 0; i < args.Length - 1; i++)
                if (args[i] == name) return args[i + 1];
            return null;
        }

        static void Log(string message) => Debug.Log($"[CiBuild] {message}");

        static void Fail(string message) => throw new Exception(message);
    }
}
