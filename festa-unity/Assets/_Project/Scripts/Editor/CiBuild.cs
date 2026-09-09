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
            // Unity 는 재빌드 시 이전 해시 파일을 지우지 않는다. 메뉴 빌더(FestaReleaseBuilder.BuildWebGL)는
            // 이 안전장치를 갖고 있는데 CI 경로에는 빠져 있었다 — 2026-09-08 에 실제로 09-07 산출물
            // (38544b30….data.unityweb, 141 MB)이 남아 .data.unityweb 가 두 개인 채로 나왔다.
            // 산출물이 두 벌이면 "빌드가 두 번 돌았나" 로 읽히고, 압축해서 넘기면 배포본에 낡은 파일이 섞인다.
            if (Directory.Exists(WebOutDir))
            {
                if (!WebOutDir.StartsWith("Builds/", StringComparison.Ordinal))
                    Fail($"안전장치 — 출력 경로가 Builds/ 밖이다: {WebOutDir}");
                Directory.Delete(WebOutDir, true);
                Log($"{WebOutDir} 를 비웠다 (이전 해시 산출물 혼입 방지)");
            }
            Directory.CreateDirectory(WebOutDir);

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

            // 압축도 **여기서 강제한다.** 지정하지 않으면 커밋된 ProjectSettings 값이 그대로
            // 배포본이 된다 — 그 값은 Disabled/폴백 OFF 라서 CI 산출물이 비압축으로 나갔다
            // (S15P21A604-474, GitLab #127 에서 인프라가 반려한 사유 중 하나).
            //
            // Brotli 만으로는 부족하다. 서버가 `Content-Encoding: br` 를 붙여 줘야 로드되는데
            // 헤더 없는 정적 경로에서는 폴백이 있어야 산다 — 그래서 둘을 함께 켠다
            // (메뉴 빌더 FestaReleaseBuilder 와 같은 결정, 28~35행).
            var prevCompression = PlayerSettings.WebGL.compressionFormat;
            bool prevFallback = PlayerSettings.WebGL.decompressionFallback;
            PlayerSettings.WebGL.compressionFormat = WebGLCompressionFormat.Brotli;
            PlayerSettings.WebGL.decompressionFallback = true;
            Log("WebGL 압축 = Brotli + Decompression Fallback (배포 설정)");

            var options = new BuildPlayerOptions
            {
                scenes = scenes,
                locationPathName = WebOutDir,
                target = BuildTarget.WebGL,
                options = BuildOptions.None,   // Development OFF — 배포 설정
            };
            try { RunBuild(options, "WebGL"); }
            finally
            {
                FestaReleaseBuilder.RestoreApiEnvironment(apiConfig, prevMock, prevEnv);
                PlayerSettings.WebGL.compressionFormat = prevCompression;
                PlayerSettings.WebGL.decompressionFallback = prevFallback;
            }

            // FE 는 <빌드 base>/manifest.json 에서 로더 URL 4종을 읽는다 (GitLab #60).
            // 메뉴 빌더만 이 파일을 만들고 CI 경로는 빠져 있어서, CI 산출물은 빌드는
            // 성공했는데 월드 진입이 404 로 실패했다 (S15P21A604-417). 파일명이 해시라
            // FE 가 디렉터리를 추측할 수도 없다 — manifest 없는 산출물은 산출물이 아니다.
            FestaWebBuilder.WriteManifest(WebOutDir);

            // 검증용 probe.html 을 산출물에 같이 넣는다 — FE 없이 빌드를 열어 SendMessage 로 상태를 주입하는 게임 파트의
            // 유일한 릴리스 검증 수단인데, 빌드 폴더가 비워지면서 매번 사라졐다(QA #42). 정본은 Tools/probe.html.
            // 배포 zip 은 manifest 가 가리키는 파일만 담으므로 실사용자에게 나가지 않는다.
            var probeSrc = Path.Combine("Tools", "probe.html");
            if (File.Exists(probeSrc)) { File.Copy(probeSrc, Path.Combine(WebOutDir, "probe.html"), true); Log("probe.html 동봉 (검증용, 배포 zip 제외)"); }

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
