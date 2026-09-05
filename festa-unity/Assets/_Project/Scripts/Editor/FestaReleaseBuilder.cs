using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEditor.SceneManagement;
using UnityEngine;
using Debug = UnityEngine.Debug;

namespace Festa.EditorTools
{
    /// <summary>
    /// 배포용 통합 빌드 — Linux 데디케이티드 서버 + WebGL 클라이언트 + Docker 이미지.
    ///
    /// 기존 `Festa/부하테스트/WebGL 빌드` 는 **측정 전용**이라 배포에 쓰면 안 된다
    /// (Development ON · 압축 Disabled). 그런데 배포용 경로는 지금까지 손으로만 있었고,
    /// Build Settings 를 오가며 타깃을 두 번 바꾸고 docker 명령을 따로 쳐야 했다.
    /// 단계가 많을수록 빠뜨린다 — 특히 **서버만 빌드하고 웹을 잊거나, 그 반대**가 쉽다.
    /// 그 조합은 증상이 "고쳤는데 그대로"로 나타나 원인 추적에 시간을 다 쓰게 만든다 (T-23/T-25).
    ///
    /// 그래서 이 메뉴는 셋을 한 흐름으로 묶고, **중간에 하나라도 실패하면 그 자리에서 멈춘다.**
    /// 절반만 갱신된 산출물을 남기는 게 제일 나쁘다.
    ///
    /// ── 측정용 빌드와 다른 점 ──────────────────────────────
    ///   · Development **OFF** — 프로파일러·F3/F6 계측 도구가 빠진다 (실사용자가 아바타를 소환하면 안 된다)
    ///   · WebGL 압축 **Brotli + Decompression Fallback ON**
    ///   · 출력 디렉터리가 다르다 (`Builds/web-release`) — 측정본과 섞이지 않는다
    ///
    /// ── Decompression Fallback 을 켜는 이유 ────────────────
    /// Brotli 로만 뽑으면 서버가 `Content-Encoding: br` 를 붙여 줘야 로드된다. 그런데
    /// 이 저장소 어디에도 그 헤더 설정이 없다 — `infra/unity-server/nginx/world.conf.template`
    /// 은 월드 WebSocket 프록시일 뿐이고, WebGL 산출물 배포 경로는 아직 미결이다
    /// (FE `resolver.ts` 주석: 인프라 #30 후속). 헤더가 없는 정적 서버에 Brotli 만 올리면
    /// **로드 자체가 실패한다.** Fallback 을 켜면 로더가 JS 로 직접 풀어서 어디에 올리든 뜬다.
    /// 대가는 초기 압축 해제 시간이고, 전송량은 그대로 줄어든다.
    /// → 배포 경로에 Content-Encoding 이 확정되면 <see cref="WebDecompressionFallback"/> 를 false 로 내린다.
    ///
    /// ── 서버 빌드에 씬 목록을 그대로 쓰는 이유 ─────────────
    /// Build Settings 첫 씬은 `CharacterLobby` 이고 거기엔 NetworkManager 가 없다. 그대로면
    /// 서버가 로비에서 멈출 것 같지만, `CharacterLobbyController.Awake` 에 `#if UNITY_SERVER`
    /// 리다이렉트가 있어 월드 씬으로 넘어간다. 지금 도는 이미지가 이 경로로 만들어졌으므로
    /// **증명된 구성을 바꾸지 않는다.** (봇 빌드는 그 심볼이 없어서 main 하나만 넣는 것이다.)
    /// </summary>
    public static class FestaReleaseBuilder
    {
        const string ServerOutDir = "Builds/linux-server";
        const string WebOutDir = "Builds/web-release";
        const string ServerExeName = "festa-unity.x86_64";

        const string DockerfilePath = "Docker/Dockerfile";
        const string ImageName = "festa-world";
        const string ContainerName = "festa-world-01";
        const int HostPort = 7777;

        const WebGLCompressionFormat WebCompression = WebGLCompressionFormat.Brotli;
        const bool WebDecompressionFallback = true;

        /// <summary>Docker 는 이미지 레이어를 만드는 동안 응답이 없다 — 넉넉히 준다.</summary>
        const int DockerTimeoutMs = 20 * 60 * 1000;

        // ── 메뉴 ──────────────────────────────────────────────

        [MenuItem("Festa/배포/배포 빌드 (Linux 서버 + WebGL + Docker 이미지)", priority = 0)]
        public static void BuildRelease()
        {
            if (!PassesPreflight()) return;

            // 저장하지 않은 씬 변경이 빌드에 안 들어가는 것이 가장 흔한 "고쳤는데 그대로"다.
            if (!EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
            {
                Debug.LogWarning("[Release] 씬 저장을 취소해서 빌드를 중단한다.");
                return;
            }

            // 컨테이너 재시작 여부는 **여기서 한 번만** 묻는다. 빌드가 10분 넘게 걸리는데
            // 끝나고 물으면 자리를 지켜야 한다. 앞에서 정하면 그 뒤는 무인으로 돌아간다.
            int choice = EditorUtility.DisplayDialogComplex(
                "배포 빌드",
                $"Linux 서버 + WebGL 을 배포 설정(Development OFF)으로 빌드하고\n" +
                $"Docker 이미지 `{ImageName}` 를 갱신한다.\n\n" +
                $"· 서버 → {ServerOutDir}\n" +
                $"· 웹   → {WebOutDir} (Brotli + Fallback)\n\n" +
                "10분 이상 걸리고 그동안 에디터를 쓸 수 없다.",
                "빌드 + 이미지 + 컨테이너 재시작",
                "취소",
                "빌드 + 이미지만");

            if (choice == 1) return;
            bool restartContainer = choice == 0;

            Run(restartContainer);
        }

        [MenuItem("Festa/배포/Docker 이미지만 갱신 (기존 서버 빌드)", priority = 1)]
        public static void RefreshImageOnly()
        {
            if (!File.Exists(Path.Combine(ServerOutDir, ServerExeName)))
            {
                Debug.LogError($"[Release] {ServerOutDir}/{ServerExeName} 이 없다 — 먼저 배포 빌드를 돌려라.");
                return;
            }
            if (!EnsureDockerAvailable()) return;

            var tags = BuildImage();
            if (tags == null) return;
            Debug.Log($"[Release] 이미지 갱신 완료 — {string.Join(", ", tags)}\n" +
                      "⚠ 서버 빌드는 다시 뽑지 않았다. 코드를 고쳤다면 배포 빌드를 돌려야 한다.");
        }

        // ── 본체 ──────────────────────────────────────────────

        static void Run(bool restartContainer)
        {
            var started = DateTime.Now;

            // 되돌릴 것들. 여기서 놓치면 이후 에디터 작업이 조용히 어긋난다.
            var prevTarget = EditorUserBuildSettings.activeBuildTarget;
            var prevGroup = BuildPipeline.GetBuildTargetGroup(prevTarget);
            var prevSubtarget = EditorUserBuildSettings.standaloneBuildSubtarget;
            bool prevDev = EditorUserBuildSettings.development;
            var prevCompression = PlayerSettings.WebGL.compressionFormat;
            bool prevFallback = PlayerSettings.WebGL.decompressionFallback;
            var apiConfig = LoadApiConfig();
            bool prevMock = apiConfig != null && apiConfig.useMockApi;
            var prevEnv = apiConfig != null ? apiConfig.activeEnvironment : Festa.Integration.ApiEnvironment.Local;

            try
            {
                EditorUserBuildSettings.development = false;
                // 배포본은 실서버·Prod 다. 에셋의 커밋 기본값은 에디터 편의를 위한 Mock+Local 이라,
                // 강제하지 않으면 깨끗한 체크아웃에서 뽑은 WebGL 이 Mock 으로 나간다 (S15P21A604-419).
                if (!ForceApiEnvironment(apiConfig, Festa.Integration.ApiEnvironment.Prod)) return;

                if (!BuildLinuxServer()) return;
                if (!BuildWebGL()) return;

                if (!EnsureDockerAvailable())
                {
                    Debug.LogError("[Release] 빌드 2종은 성공했지만 이미지는 갱신하지 못했다.\n" +
                                   "Docker Desktop 을 켠 뒤 `Festa/배포/Docker 이미지만 갱신` 을 실행해라.");
                    return;
                }

                var tags = BuildImage();
                if (tags == null) return;

                if (restartContainer && !RecreateContainer(tags[0])) return;

                var elapsed = DateTime.Now - started;
                Debug.Log($"[Release] ✅ 배포 빌드 완료 — {elapsed.TotalMinutes:F1}분\n" +
                          $"· 서버   {ServerOutDir}\n" +
                          $"· 웹     {WebOutDir} (manifest.json 포함)\n" +
                          $"· 이미지 {string.Join(", ", tags)}\n" +
                          (restartContainer
                              ? $"· 컨테이너 {ContainerName} 재시작됨 — `docker logs -f {ContainerName}` 로 기동 확인\n"
                              : $"· 컨테이너는 건드리지 않았다 — 반영하려면 재시작해야 한다\n") +
                          $"다음: {WebOutDir} 를 배포 경로에 올린다 (경로 미확정 — 인프라 #30).");
            }
            finally
            {
                EditorUserBuildSettings.development = prevDev;
                EditorUserBuildSettings.standaloneBuildSubtarget = prevSubtarget;
                PlayerSettings.WebGL.compressionFormat = prevCompression;
                PlayerSettings.WebGL.decompressionFallback = prevFallback;
                RestoreApiEnvironment(apiConfig, prevMock, prevEnv);

                // **"원래대로" 가 고장난 상태면 복원이 고장을 보존한다** (T-228).
                //
                // 데디케이티드 서버 타깃은 에디터에 `UNITY_SERVER` 를 정의한다. 그 상태에서는
                //   · `CharacterLobbyController.Awake()` 가 곧장 main 으로 넘겨
                //     **커스터마이징 화면을 아예 볼 수 없고**
                //   · 거기서 WebGL 을 빌드하면 `Mobile_RPAsset` 이 통째로 빠진다 (T-219)
                // T-219 는 이 복원 자체를 "방아쇠" 로 지목해 뒀는데, 복원 대상만 그대로 뒀다.
                //
                // 이 프로젝트의 클라이언트는 WebGL 이다. 쉬는 상태도 WebGL 이어야 한다.
                bool restingOnServer =
                    BuildPipeline.GetBuildTargetGroup(prevTarget) == BuildTargetGroup.Standalone &&
                    prevSubtarget == StandaloneBuildSubtarget.Server;

                var restoreTarget = restingOnServer ? BuildTarget.WebGL : prevTarget;
                var restoreGroup = restingOnServer ? BuildTargetGroup.WebGL : prevGroup;

                if (EditorUserBuildSettings.activeBuildTarget != restoreTarget)
                    EditorUserBuildSettings.SwitchActiveBuildTarget(restoreGroup, restoreTarget);

                if (restingOnServer)
                    Debug.LogWarning(
                        $"[Release] 빌드 전 타깃이 {prevTarget}(Server) 였지만 **WebGL 로 되돌린다** — " +
                        "서버 타깃으로 두면 에디터에 UNITY_SERVER 가 정의돼 캐릭터 로비가 월드로 " +
                        "넘어가고(T-228), 그 상태에서 WebGL 을 빌드하면 Mobile_RPAsset 이 빠진다(T-219).");

                Debug.Log($"[Release] 설정 복원 — 타깃={restoreTarget}({prevSubtarget}), development={prevDev}, " +
                          $"WebGL 압축={prevCompression}/fallback={prevFallback}");
            }
        }

        static bool PassesPreflight()
        {
            if (Application.isPlaying)
            {
                Debug.LogError("[Release] Play 중에는 빌드하지 않는다 — 종료 후 실행해라.");
                return false;
            }
            if (EditorApplication.isCompiling)
            {
                Debug.LogError("[Release] 스크립트 컴파일 중이다 — 끝난 뒤 실행해라.");
                return false;
            }
            // 컴파일 실패 상태로 빌드하면 Unity 가 **직전 성공 어셈블리**로 빌드해버린다.
            // 산출물은 나오는데 방금 고친 코드가 안 들어간 최악의 조합이다.
            if (EditorUtility.scriptCompilationFailed)
            {
                Debug.LogError("[Release] 스크립트 컴파일 에러가 있다 — 고치기 전에는 빌드하지 않는다.");
                return false;
            }
            if (EnabledScenes().Length == 0)
            {
                Debug.LogError("[Release] Build Settings 에 활성 씬이 없다.");
                return false;
            }
            return true;
        }

        static string[] EnabledScenes() =>
            EditorBuildSettings.scenes.Where(s => s.enabled).Select(s => s.path).ToArray();

        // ── Linux 데디케이티드 서버 ────────────────────────────

        const string ApiConfigPath = "Assets/_Project/ScriptableObjects/ApiConfig.asset";

        internal static Festa.Integration.ApiConfig LoadApiConfig() =>
            AssetDatabase.LoadAssetAtPath<Festa.Integration.ApiConfig>(ApiConfigPath);

        /// <summary>
        /// 빌드에 들어갈 API 환경을 강제한다. 에셋을 실제로 저장한다 — BuildPlayer 는 디스크의
        /// 직렬화 상태를 읽으므로 메모리만 바꾸면 반영이 보장되지 않는다. 무엇으로 뽑았는지는
        /// 로그에 반드시 남긴다 — 산출물만 보고는 알 수 없기 때문이다 (S15P21A604-419).
        /// </summary>
        internal static bool ForceApiEnvironment(Festa.Integration.ApiConfig cfg, Festa.Integration.ApiEnvironment env)
        {
            if (cfg == null)
            {
                Debug.LogError($"[Release] {ApiConfigPath} 를 찾지 못했다 — 어떤 API 를 부를지 정할 수 없어 멈춘다.");
                return false;
            }
            cfg.useMockApi = false;
            cfg.activeEnvironment = env;
            var entry = cfg.Active;
            if (entry == null || string.IsNullOrEmpty(entry.springBaseUrl) || entry.springBaseUrl.Contains("example.com"))
            {
                Debug.LogError($"[Release] ApiConfig {env} 항목의 springBaseUrl 이 비었거나 자리표시다: '{entry?.springBaseUrl}' — 이대로 뽑으면 아무 서버에도 붙지 않는다.");
                return false;
            }
            EditorUtility.SetDirty(cfg);
            AssetDatabase.SaveAssets();
            Debug.Log($"[Release] 이 빌드의 API — env={env} mock=false spring={entry.springBaseUrl} ai={entry.aiBaseUrl}");
            return true;
        }

        internal static void RestoreApiEnvironment(Festa.Integration.ApiConfig cfg, bool prevMock, Festa.Integration.ApiEnvironment prevEnv)
        {
            // 빌드 전에 잡아 둔 참조를 믿지 않는다. 빌드 타깃 전환·리임포트를 거치면 그 참조는
            // 파괴된 오브젝트(가짜 null)가 되고, 2026-09-05 Prod 빌드에서 바로 그 경로로 복원이
            // **조용히 빠져** 작업본 에셋이 Prod 로 남았다. 경로로 다시 읽고, 못 읽으면 소리 낸다.
            cfg = LoadApiConfig();
            if (cfg == null)
            {
                Debug.LogError($"[Release] ApiConfig 복원 실패 — {ApiConfigPath} 를 다시 읽지 못했다. " +
                               $"에셋이 env={prevEnv} mock={prevMock} 로 돌아가지 않았을 수 있다 — 손으로 확인해라.");
                return;
            }
            cfg.useMockApi = prevMock;
            cfg.activeEnvironment = prevEnv;
            EditorUtility.SetDirty(cfg);
            AssetDatabase.SaveAssets();
            Debug.Log($"[Release] ApiConfig 복원 — env={prevEnv} mock={prevMock}");
        }

        static bool BuildLinuxServer()
        {
            Directory.CreateDirectory(ServerOutDir);
            WriteServerDockerIgnore();

            var options = new BuildPlayerOptions
            {
                scenes = EnabledScenes(),
                locationPathName = Path.Combine(ServerOutDir, ServerExeName),
                target = BuildTarget.StandaloneLinux64,
                targetGroup = BuildTargetGroup.Standalone,
                subtarget = (int)StandaloneBuildSubtarget.Server,
                options = BuildOptions.None,
            };

            Debug.Log($"[Release] 1/3 Linux 서버 빌드 시작 → {options.locationPathName} " +
                      $"(씬 {options.scenes.Length}개, Development OFF)");
            return Succeeded("Linux 서버", BuildPipeline.BuildPlayer(options));
        }

        /// <summary>
        /// Unity 는 서버 빌드에 심볼 파일과 Burst 디버그 정보를 같이 떨군다. Dockerfile 이
        /// `COPY . /app` 이라 그대로 두면 **이미지에 실려 간다** — 폴더 이름부터가 DoNotShip 이다.
        /// 파일을 지우는 대신 `.dockerignore` 로 거른다: 지우면 다음 증분 빌드가 다시 만들 뿐이고,
        /// 빌드 출력물을 삭제하는 습관 자체가 위험하다.
        /// </summary>
        static void WriteServerDockerIgnore()
        {
            var body = string.Join("\n", new[]
            {
                "# FestaReleaseBuilder 가 생성한다 — 손으로 고치지 마라 (빌드마다 덮어쓴다).",
                "# 이미지에 들어가면 안 되는 Unity 산출물.",
                "*_BurstDebugInformation_DoNotShip/",
                "*_DoNotShip/",
                "*.debug",
                "*.pdb",
                ".dockerignore",
                "",
            });
            File.WriteAllText(Path.Combine(ServerOutDir, ".dockerignore"), body);
        }

        // ── WebGL 클라이언트 ──────────────────────────────────

        static bool BuildWebGL()
        {
            // Unity 는 재빌드 시 이전 해시 파일을 지우지 않는다 (실측: 서로 다른 날짜 빌드의
            // loader/framework/data/wasm 8개 공존). 배포본에 낡은 파일이 섞이면 manifest 가
            // 무엇을 집었는지 사람이 알 수 없다. 배포 디렉터리는 매번 비우고 시작한다.
            if (Directory.Exists(WebOutDir))
            {
                if (!WebOutDir.StartsWith("Builds/", StringComparison.Ordinal))
                {
                    Debug.LogError($"[Release] 안전장치 — 출력 경로가 Builds/ 밖이다: {WebOutDir}");
                    return false;
                }
                Directory.Delete(WebOutDir, true);
                Debug.Log($"[Release] {WebOutDir} 를 비웠다 (이전 해시 산출물 혼입 방지)");
            }
            Directory.CreateDirectory(WebOutDir);

            // **BuildPlayer 에 타깃을 넘기는 것만으로는 늦다 — 먼저 전환해야 한다.**
            //
            // URP 빌드 전처리기는 **에디터의 현재 활성 타깃** 기준으로 품질 레벨을 걸러
            // 빌드에 포함할 RP 에셋을 정한다. 이 프로젝트의 Mobile 품질 레벨은
            // excludedTargetPlatforms 에 Standalone 이 들어 있어서, 활성 타깃이
            // Standalone(=이 프로젝트의 평소 상태, Linux Server) 인 채로 WebGL 을 빌드하면
            // Mobile 레벨이 통째로 제외되고 **Mobile_RPAsset 이 빌드에서 빠진다.**
            //
            // 그런데 WebGL 의 기본 품질 레벨은 0 = Mobile 이다. 즉 런타임이 쓸 파이프라인
            // 에셋이 빌드에 없는 상태가 되고, 월드 머티리얼이 평평하게 렌더링된다
            // (실측: 셰이더 6.7MB → 5.7MB, "2 URP assets" → "1 URP asset". S15P21A604-316).
            //
            // 에디터에서는 재현되지 않는다 — 빌드에서만 드러나는 함정이다.
            if (EditorUserBuildSettings.activeBuildTarget != BuildTarget.WebGL)
            {
                Debug.Log("[Release] 활성 타깃을 WebGL 로 먼저 전환한다 " +
                          "(URP 가 포함할 RP 에셋을 이 시점에 결정한다 — S15P21A604-316)");
                if (!EditorUserBuildSettings.SwitchActiveBuildTarget(BuildTargetGroup.WebGL, BuildTarget.WebGL))
                {
                    Debug.LogError("[Release] WebGL 타깃 전환 실패 — 여기서 멈춘다. " +
                                   "그대로 빌드하면 Mobile_RPAsset 이 빠진 산출물이 나온다.");
                    return false;
                }
            }

            PlayerSettings.WebGL.compressionFormat = WebCompression;
            PlayerSettings.WebGL.decompressionFallback = WebDecompressionFallback;

            var options = new BuildPlayerOptions
            {
                scenes = EnabledScenes(),
                locationPathName = WebOutDir,
                target = BuildTarget.WebGL,
                targetGroup = BuildTargetGroup.WebGL,
                options = BuildOptions.None,
            };

            Debug.Log($"[Release] 2/3 WebGL 빌드 시작 → {WebOutDir} " +
                      $"(Development OFF, 압축 {WebCompression}, fallback {WebDecompressionFallback})");
            var report = BuildPipeline.BuildPlayer(options);
            if (!Succeeded("WebGL", report)) return false;
            if (!VerifyRenderPipelineAssetsPacked(report)) return false;

            // FE 는 해시 파일명을 알 수 없어 manifest.json 으로만 빌드 URL 을 찾는다.
            FestaWebBuilder.WriteManifest(WebOutDir);
            return true;
        }

        /// <summary>
        /// WebGL 산출물에 **Mobile_RPAsset 이 실제로 들어갔는지** 확인한다.
        ///
        /// 이게 빠지면 빌드는 성공으로 끝나고 월드만 평평하게 렌더링된다 — 조용한 실패라
        /// 원인 추적에 빌드를 여러 번 태웠다 (S15P21A604-316). 활성 타깃을 먼저 WebGL 로
        /// 바꾸는 것으로 막았지만, 경로가 하나 더 생기면 또 조용히 재발할 수 있다.
        /// **성공했다고 보고하기 전에 실제로 들어갔는지 본다** (T-24: 조용한 실패 금지).
        /// </summary>
        static bool VerifyRenderPipelineAssetsPacked(BuildReport report)
        {
            const string required = "Mobile_RPAsset";
            foreach (var packed in report.packedAssets)
                foreach (var info in packed.contents)
                    if (info.sourceAssetPath != null && info.sourceAssetPath.Contains(required))
                        return true;

            Debug.LogError(
                $"[Release] {required} 가 빌드 산출물에 없다 — 이대로 배포하면 월드가 평평하게 렌더링된다.\n" +
                "WebGL 기본 품질 레벨은 0(Mobile) 이고 그 레벨의 렌더 파이프라인이 이 에셋이다.\n" +
                "원인은 대개 빌드 시작 시점의 활성 타깃이 WebGL 이 아닌 것이다 (S15P21A604-316).");
            return false;
        }

        static bool Succeeded(string what, BuildReport report)
        {
            if (report == null)
            {
                // BuildPlayer 는 타깃 모듈 미설치 등에서 리포트 없이 끝난다.
                Debug.LogError($"[Release] {what} 빌드가 리포트 없이 끝났다 — 해당 플랫폼 모듈이 설치돼 있는지 확인해라.");
                return false;
            }
            var s = report.summary;
            if (s.result != BuildResult.Succeeded)
            {
                Debug.LogError($"[Release] {what} 빌드 실패 — {s.result}, 오류 {s.totalErrors}건. 여기서 멈춘다.");
                return false;
            }
            Debug.Log($"[Release] {what} 빌드 성공 — {s.totalSize / 1048576} MB, {s.totalTime.TotalMinutes:F1}분");
            return true;
        }

        // ── Docker ────────────────────────────────────────────

        static bool EnsureDockerAvailable()
        {
            var probe = RunProcess("docker", "version --format {{.Server.Version}}", ProjectRoot(), 30_000);
            if (probe.ExitCode == 0)
            {
                Debug.Log($"[Release] Docker 엔진 {probe.Output.Trim()} 확인");
                return true;
            }
            Debug.LogError("[Release] Docker 에 연결하지 못했다 — Docker Desktop 이 실행 중인지 확인해라.\n" +
                           $"{probe.Output.Trim()}");
            return false;
        }

        /// <summary>이미지를 빌드하고 붙인 태그를 돌려준다. 실패면 null.</summary>
        static string[] BuildImage()
        {
            var root = ProjectRoot();
            if (!File.Exists(Path.Combine(root, DockerfilePath)))
            {
                Debug.LogError($"[Release] Dockerfile 을 찾지 못했다: {DockerfilePath}");
                return null;
            }

            // 커밋 해시를 태그로 남긴다 — "지금 도는 컨테이너가 어느 코드냐"에 답할 수 있어야 한다.
            // 커밋되지 않은 변경이 섞였으면 -dirty 를 붙인다. 재현 불가를 숨기지 않기 위해서다.
            var tags = new[] { $"{ImageName}:dev", $"{ImageName}:{SourceStamp(root)}" };
            var args = new StringBuilder("build");
            foreach (var tag in tags) args.Append($" -t {tag}");
            args.Append($" -f {DockerfilePath} {ServerOutDir}");

            Debug.Log($"[Release] 3/3 Docker 이미지 빌드 — {string.Join(", ", tags)}");
            var result = RunProcess("docker", args.ToString(), root, DockerTimeoutMs);
            if (result.ExitCode != 0)
            {
                Debug.LogError($"[Release] Docker 이미지 빌드 실패 (exit {result.ExitCode})\n{result.Output}");
                return null;
            }
            return tags;
        }

        static bool RecreateContainer(string imageTag)
        {
            var root = ProjectRoot();

            // 없으면 실패해도 무방하다 — 첫 실행이면 지울 컨테이너가 없다.
            RunProcess("docker", $"rm -f {ContainerName}", root, 60_000);

            var run = RunProcess("docker",
                $"run -d --name {ContainerName} -p {HostPort}:{HostPort} {imageTag}",
                root, 120_000);
            if (run.ExitCode != 0)
            {
                Debug.LogError($"[Release] 컨테이너 재시작 실패 (exit {run.ExitCode})\n{run.Output}\n" +
                               $"이미지는 이미 갱신됐다 — `docker run` 을 손으로 다시 시도해도 된다.");
                return false;
            }
            Debug.Log($"[Release] 컨테이너 {ContainerName} 기동 — {run.Output.Trim()}");
            return true;
        }

        // ── 프로세스 실행 ─────────────────────────────────────

        struct ProcessResult
        {
            public int ExitCode;
            public string Output;
        }

        /// <summary>
        /// stdout·stderr 를 **비동기로** 읽는다. 동기 ReadToEnd 로 한쪽만 비우면 다른 쪽 파이프
        /// 버퍼가 차면서 교착한다 — docker build 처럼 출력이 많은 명령에서 실제로 걸린다.
        /// </summary>
        static ProcessResult RunProcess(string file, string args, string workingDir, int timeoutMs)
        {
            var info = new ProcessStartInfo(file, args)
            {
                WorkingDirectory = workingDir,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
            };

            var buffer = new StringBuilder();
            try
            {
                using var process = new Process { StartInfo = info };
                void Collect(object _, DataReceivedEventArgs e)
                {
                    if (e.Data == null) return;
                    lock (buffer) buffer.AppendLine(e.Data);
                }
                process.OutputDataReceived += Collect;
                process.ErrorDataReceived += Collect;

                process.Start();
                process.BeginOutputReadLine();
                process.BeginErrorReadLine();

                if (!process.WaitForExit(timeoutMs))
                {
                    process.Kill();
                    lock (buffer) buffer.AppendLine($"(타임아웃 {timeoutMs / 1000}초 — 강제 종료했다)");
                    return new ProcessResult { ExitCode = -1, Output = buffer.ToString() };
                }
                // 인자 없는 WaitForExit 가 비동기 출력 핸들러의 종료까지 보장한다.
                process.WaitForExit();
                lock (buffer) return new ProcessResult { ExitCode = process.ExitCode, Output = buffer.ToString() };
            }
            catch (Exception e)
            {
                return new ProcessResult { ExitCode = -1, Output = $"{file} 실행 실패: {e.Message}" };
            }
        }

        /// <summary>
        /// Unity 프로젝트 루트(`<repo>/festa-unity`). Application.dataPath 가 그 아래 Assets 다.
        /// docker 인자의 `Docker/Dockerfile`·`Builds/linux-server` 가 전부 이 기준의 상대 경로이고,
        /// git 은 상위로 올라가며 저장소를 찾으므로 여기서 실행해도 된다.
        /// </summary>
        static string ProjectRoot() => Directory.GetParent(Application.dataPath).FullName;

        static string SourceStamp(string root)
        {
            var head = RunProcess("git", "rev-parse --short HEAD", root, 15_000);
            if (head.ExitCode != 0) return "unknown";

            var sha = head.Output.Trim();
            var dirty = RunProcess("git", "status --porcelain", root, 30_000);
            bool hasLocalChanges = dirty.ExitCode == 0 &&
                                   dirty.Output.Split('\n').Any(l => !string.IsNullOrWhiteSpace(l) && !l.StartsWith("??"));
            return hasLocalChanges ? $"{sha}-dirty" : sha;
        }
    }
}
