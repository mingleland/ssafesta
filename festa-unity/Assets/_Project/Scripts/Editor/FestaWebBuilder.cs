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

            // **BuildPlayer 에 타깃을 넘기기 전에 활성 타깃부터 WebGL 로 바꿔야 한다.**
            // URP 전처리기가 활성 타깃 기준으로 품질 레벨을 걸러 RP 에셋을 정하는데,
            // Mobile 레벨은 Standalone 에서 제외돼 있어 평소 타깃(Linux Server) 그대로
            // 빌드하면 Mobile_RPAsset 이 빠진다. WebGL 기본 품질이 0=Mobile 이라
            // 그 산출물은 월드가 평평하게 렌더링된다 (S15P21A604-316).
            if (EditorUserBuildSettings.activeBuildTarget != BuildTarget.WebGL)
            {
                Debug.Log("[WebBuilder] 활성 타깃을 WebGL 로 먼저 전환한다 (S15P21A604-316)");
                if (!EditorUserBuildSettings.SwitchActiveBuildTarget(BuildTargetGroup.WebGL, BuildTarget.WebGL))
                {
                    Debug.LogError("[WebBuilder] WebGL 타깃 전환 실패 — 중단한다.");
                    PlayerSettings.WebGL.compressionFormat = prevCompression;
                    return;
                }
            }

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

                // 서버 타깃으로는 되돌리지 않는다 (T-228, T-219 의 방아쇠).
                // 그 상태면 에디터에 UNITY_SERVER 가 정의돼 캐릭터 로비가 월드로 넘어가고,
                // 다음 WebGL 빌드에서 Mobile_RPAsset 이 빠진다. 자세한 근거는
                // FestaReleaseBuilder 의 같은 지점 주석 참조.
                bool restingOnServer =
                    BuildPipeline.GetBuildTargetGroup(prevTarget) == BuildTargetGroup.Standalone &&
                    EditorUserBuildSettings.standaloneBuildSubtarget == StandaloneBuildSubtarget.Server;

                if (restingOnServer)
                    Debug.LogWarning($"[WebBuilder] 빌드 전 타깃이 {prevTarget}(Server) 였지만 " +
                                     "WebGL 로 둔다 — 서버 타깃은 에디터 플레이를 깨뜨린다 (T-228).");
                else if (prevTarget != BuildTarget.WebGL)
                    EditorUserBuildSettings.SwitchActiveBuildTarget(prevGroup, prevTarget);

                Debug.Log($"[WebBuilder] 설정 복원 — development={prevDev}, 압축={prevCompression}, " +
                          $"타깃={(restingOnServer ? BuildTarget.WebGL : prevTarget)}");
            }

            var s = report.summary;
            if (s.result == BuildResult.Succeeded)
            {
                WriteManifest(OutDir);
            }
            if (s.result == BuildResult.Succeeded)
                Debug.Log($"[WebBuilder] 성공 — {s.totalSize / 1048576} MB, {s.totalTime.TotalMinutes:F1}분\n" +
                          "로컬 서버로 열고 로비를 지나 월드 진입 후 F3(HUD) · F6(아바타 +5) 으로 측정한다.");
            else
                Debug.LogError($"[WebBuilder] 실패 — {s.result}, 오류 {s.totalErrors}건");
        }

        /// <summary>
        /// 빌드 URL 4종 machine-readable 산출물 (GitLab #60 ①).
        /// webGLNameFilesAsHashes=1 이라 FE 가 파일명을 알 수 없으므로,
        /// 빌드 루트에 manifest.json { loaderUrl, dataUrl, frameworkUrl, codeUrl } 을 생성한다.
        /// 경로는 빌드 루트 기준 상대 경로 — FE 는 <빌드 base URL>/manifest.json 을 읽는다.
        /// 압축(.br/.gz) 빌드에서는 실제 존재하는 파일명이 그대로 실린다
        /// (Unity loader 도 접미사 포함 URL 을 기대한다 — 서버 Content-Encoding 은 infra #82 몫).
        /// </summary>
        [MenuItem("Festa/부하테스트/manifest.json 재생성 (기존 빌드)")]
        public static void RegenerateManifest() => WriteManifest(OutDir);

        /// <summary>배포 빌더(FestaReleaseBuilder)도 자기 출력 디렉터리로 이걸 부른다.</summary>
        internal static void WriteManifest(string outDir)
        {
            var buildDir = Path.Combine(outDir, "Build");
            if (!Directory.Exists(buildDir))
            {
                Debug.LogError($"[WebBuilder] manifest: {buildDir} 가 없다 — 먼저 WebGL 빌드를 뽑아라.");
                return;
            }

            // 주의: Unity 는 재빌드 시 이전 해시 파일을 지우지 않아 잔재가 섞인다 (실측: 25일·26일
            // 빌드의 loader/framework/data/wasm 8개 공존). 이름순 첫 매치는 낡은 파일을 집으므로
            // 최신 수정시각 우선으로 고른다 — index.html 이 참조하는 세트와 일치한다.
            var files = Directory.GetFiles(buildDir)
                .OrderByDescending(File.GetLastWriteTimeUtc)
                .Select(Path.GetFileName)
                .ToArray();
            string Find(string label, System.Func<string, bool> match)
            {
                var hit = files.FirstOrDefault(match);
                if (hit == null)
                    Debug.LogError($"[WebBuilder] manifest: {label} 산출물을 찾지 못했다 (Build/ 내 {files.Length}개 파일).");
                return hit == null ? null : "Build/" + hit;
            }

            // 압축 빌드에서 로더에 .br 접미사가 붙는 구성이 있어 EndsWith 로는 놓친다.
            var loader    = Find("loader",    f => f.Contains(".loader.js"));
            var data      = Find("data",      f => f.Contains(".data"));
            var framework = Find("framework", f => f.Contains(".framework.js"));
            var code      = Find("code",      f => f.Contains(".wasm"));
            if (loader == null || data == null || framework == null || code == null) return;

            // ── provenance (GitLab #142 §8, 2026-09-10) ─────────────────────────────
            // 배포 파이프라인이 "이 산출물이 어느 커밋에서, 어떤 설정으로 나왔나" 를 manifest 하나로 판정한다.
            // 새 파일을 만들지 않고 **확장**한다 — FE 는 manifest 하나만 읽는다. 로더 URL 4종은 그대로.
            //   sourceCommit(40자)·dirty  → release-manifest.scm.commit 과 대조, dirty 면 배포 거부
            //   buildProfile              → release 가 아니면 배포 거부
            //   unityVersion/unityRevision → 재임포트 사고(#124) 기록 · apiEnvironment → Mock 유출(T-237) 기록
            //   compression               → 비압축 반려(-474) 기록
            // Registry 8자 관행이 "어느 커밋인지 자동 판정 불가" 를 만들었으므로 40자로 적는다.
            var prov = CollectProvenance();
            var json = "{\n"
                + "  \"schemaVersion\": \"1.0.0\",\n"
                + $"  \"loaderUrl\": \"{loader}\",\n"
                + $"  \"dataUrl\": \"{data}\",\n"
                + $"  \"frameworkUrl\": \"{framework}\",\n"
                + $"  \"codeUrl\": \"{code}\",\n"
                + $"  \"sourceCommit\": \"{prov.commit}\",\n"
                + $"  \"sourceBranch\": \"{prov.branch}\",\n"
                + $"  \"dirty\": {(prov.dirty ? "true" : "false")},\n"
                + $"  \"unityVersion\": \"{Application.unityVersion}\",\n"
                + $"  \"unityRevision\": \"{prov.unityRevision}\",\n"
                + $"  \"buildProfile\": \"{(EditorUserBuildSettings.development ? "development" : "release")}\",\n"
                + $"  \"apiEnvironment\": \"{prov.apiEnvironment}\",\n"
                + $"  \"compression\": \"{prov.compression}\",\n"
                + $"  \"builtAt\": \"{System.DateTime.UtcNow:yyyy-MM-ddTHH:mm:ssZ}\"\n"
                + "}\n";
            File.WriteAllText(Path.Combine(outDir, "manifest.json"), json);
            Debug.Log($"[WebBuilder] manifest.json 생성 — loader={loader} commit={prov.commit.Substring(0, System.Math.Min(8, prov.commit.Length))} dirty={prov.dirty} profile={(EditorUserBuildSettings.development ? "development" : "release")}");
        }

        struct Provenance { public string commit, branch, unityRevision, apiEnvironment, compression; public bool dirty; }

        /// <summary>git·ProjectVersion·PlayerSettings 에서 provenance 를 모은다. git 이 없으면 빈 값 — 파이프라인이 거부하게 둔다(조용히 통과시키지 않는다).</summary>
        static Provenance CollectProvenance()
        {
            var p = new Provenance { commit = "", branch = "", unityRevision = "", apiEnvironment = "", compression = "" };
            p.commit = Git("rev-parse HEAD");
            p.branch = Git("rev-parse --abbrev-ref HEAD");
            p.dirty = !string.IsNullOrEmpty(Git("status --porcelain --untracked-files=no"));
            try
            {
                // ProjectVersion.txt: "m_EditorVersionWithRevision: 6000.0.78f1 (ec8a99a872be)"
                foreach (var line in File.ReadAllLines(Path.Combine("ProjectSettings", "ProjectVersion.txt")))
                    if (line.StartsWith("m_EditorVersionWithRevision:"))
                    { int a = line.IndexOf('('), b = line.IndexOf(')'); if (a > 0 && b > a) p.unityRevision = line.Substring(a + 1, b - a - 1); }
            }
            catch { /* 기록용 — 없으면 빈 값 */ }
            var api = AssetDatabase.LoadAssetAtPath<ScriptableObject>("Assets/_Project/ScriptableObjects/ApiConfig.asset");
            if (api != null)
            {
                var so = new SerializedObject(api);
                var env = so.FindProperty("_activeEnvironment") ?? so.FindProperty("activeEnvironment");
                var mock = so.FindProperty("_useMock") ?? so.FindProperty("useMock");
                string envName = env != null ? (env.propertyType == SerializedPropertyType.Enum ? env.enumNames[env.enumValueIndex] : env.stringValue) : "?";
                p.apiEnvironment = mock != null && mock.boolValue ? $"Mock({envName})" : envName;
            }
            var fmt = PlayerSettings.WebGL.compressionFormat;
            p.compression = (fmt == WebGLCompressionFormat.Brotli ? "brotli" : fmt == WebGLCompressionFormat.Gzip ? "gzip" : "none")
                            + (PlayerSettings.WebGL.decompressionFallback ? "+fallback" : "");
            return p;
        }

        static string Git(string args)
        {
            try
            {
                var psi = new System.Diagnostics.ProcessStartInfo("git", args)
                { RedirectStandardOutput = true, UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = Directory.GetCurrentDirectory() };
                using var proc = System.Diagnostics.Process.Start(psi);
                var output = proc.StandardOutput.ReadToEnd().Trim();
                proc.WaitForExit(5000);
                return proc.ExitCode == 0 ? output : "";
            }
            catch (System.Exception ex)
            {
                Debug.LogWarning($"[WebBuilder] git {args} 실패 — provenance 를 비운다: {ex.Message}");
                return "";
            }
        }
    }
}