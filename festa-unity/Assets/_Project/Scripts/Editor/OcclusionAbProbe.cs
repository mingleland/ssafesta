using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;
using UnityEditor;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 오클루전 컬링 A/B 실측기. 플레이 모드에서 카메라를 고정 포즈로 옮기며
    /// <see cref="UnityStats"/> 의 드로우콜·삼각형을 재고, 같은 포즈의 스크린샷을 남긴다.
    ///
    /// <para><b>왜 필요한가.</b> `main.unity` 는 2026-08-31 11:57 커밋(<c>69956993</c> "낡은 오클루전 제거")
    /// 이후 오클루전 데이터 없이 돌고 있다. 부스 방 크기를 바꾸면서 베이크가 낡아 제거한 것까지는 옳았는데
    /// **재베이크를 하지 않았다.** 그 뒤 09-01~09-06 에 축제장이 채워졌다(성·관람차·아케이드·슬롯머신·직원).
    /// S15P21A604-322 실측 기준 축제 구역에서 드로우콜 2,173 → 465(−79%) 였으므로, 지금은 그 장치가 빠진 채
    /// 씬만 무거워진 상태다.</para>
    ///
    /// <para><b>무엇을 증명해야 하는가.</b> 성능이 오르는 것만으로는 채택할 수 없다.
    /// 오클루전은 과다 컬링(<b>보여야 할 것이 사라짐</b>)이 실제로 터졌던 영역이라(T-217),
    /// **화면이 동일한지**를 픽셀로 확인해야 한다. 그래서 이 도구는 수치와 스크린샷을 함께 남긴다.</para>
    ///
    /// <para>사용법: <c>Festa/측정/오클루전 A·B</c> 메뉴. 플레이 모드에 자동으로 들어갔다 나온다.
    /// 결과는 <c>docs/KHS/verify/occlusion-ab/</c> 에 쌓인다. 도메인 리로드를 넘겨야 해서
    /// 진행 상태를 <see cref="SessionState"/> 에 둔다.</para>
    /// </summary>
    [InitializeOnLoad]
    public static class OcclusionAbProbe
    {
        const string KeyActive = "FESTA_OCC_AB_ACTIVE";
        const string KeyLabel = "FESTA_OCC_AB_LABEL";
        const string KeyStage = "FESTA_OCC_AB_STAGE";
        const string KeyPose = "FESTA_OCC_AB_POSE";
        const string KeyFrames = "FESTA_OCC_AB_FRAMES";
        const string KeyRows = "FESTA_OCC_AB_ROWS";

        const int SettleFramesFirst = 240;   // 월드 로드·스폰이 끝나기를 기다린다
        const int SettleFramesPose = 20;     // 포즈 이동 후 컬링·배칭이 안정될 시간
        const int SampleFrames = 30;         // 표본 프레임 수 (평균)

        /// <summary>측정 지점. 좌표는 월드 단위다 — <b>1 m = 13.26 유닛</b> (FestaInteriorBuilder.cs:27).</summary>
        struct Pose
        {
            public string Name;
            public Vector3 Position;
            public Vector3 Euler;
            public Pose(string n, Vector3 p, Vector3 e) { Name = n; Position = p; Euler = e; }
        }

        static readonly Pose[] Poses =
        {
            // S15P21A604-322 가 쓴 좌표 + 이번에 문제가 보고된 끝벽 아케이드.
            new Pose("축제중심_동향", new Vector3(-575f, 20f, 145f), new Vector3(0f, 90f, 0f)),
            new Pose("축제중심_서향", new Vector3(-575f, 20f, 145f), new Vector3(0f, 270f, 0f)),
            new Pose("끝벽_동향",     new Vector3(-890f, 20f, 150f), new Vector3(0f, 90f, 0f)),
            new Pose("끝벽_서향",     new Vector3(-890f, 20f, 150f), new Vector3(0f, 270f, 0f)),
            // 벽 밀착 — 과다 컬링이 가장 잘 드러나는 시점이다 (T-217 이 터졌던 유형).
            new Pose("끝벽_밀착",     new Vector3(-925f, 20f, 150f), new Vector3(0f, 270f, 0f)),
            new Pose("복도",         new Vector3(-121f, 25f, -115f), new Vector3(0f, 0f, 0f)),
            new Pose("로비",         new Vector3(-30f, 25f, -248f), new Vector3(0f, 0f, 0f)),
        };

        static string OutDir => Path.Combine(Directory.GetParent(Application.dataPath).Parent.FullName,
                                             "docs", "KHS", "verify", "occlusion-ab");

        static OcclusionAbProbe()
        {
            EditorApplication.update -= Tick;
            EditorApplication.update += Tick;
        }

        [MenuItem("Festa/측정/오클루전 A·B — 현재 상태로 측정")]
        static void RunCurrent()
        {
            string label = StaticOcclusionCulling.umbraDataSize > 0 ? "occlusion-on" : "occlusion-off";
            Start(label);
        }

        static void Start(string label)
        {
            if (EditorApplication.isPlaying)
            {
                Debug.LogError("[OcclusionAB] 이미 플레이 모드다. 정지한 뒤 다시 실행해라.");
                return;
            }
            Directory.CreateDirectory(OutDir);
            SessionState.SetBool(KeyActive, true);
            SessionState.SetString(KeyLabel, label);
            SessionState.SetInt(KeyStage, 0);
            SessionState.SetInt(KeyPose, 0);
            SessionState.SetInt(KeyFrames, 0);
            SessionState.SetString(KeyRows, "");
            Debug.Log($"[OcclusionAB] 시작 — 라벨 '{label}', 포즈 {Poses.Length}개. umbraDataSize={StaticOcclusionCulling.umbraDataSize}");
            EditorApplication.EnterPlaymode();
        }

        // 누적 표본
        static int _drawSum, _triSum, _setPassSum, _sampleCount;

        static void Tick()
        {
            if (!SessionState.GetBool(KeyActive, false)) return;
            if (!EditorApplication.isPlaying) return;

            int stage = SessionState.GetInt(KeyStage, 0);
            int frames = SessionState.GetInt(KeyFrames, 0) + 1;
            SessionState.SetInt(KeyFrames, frames);

            var cam = Camera.main;
            if (cam == null) return;

            // 0: 월드가 뜨기를 기다린다
            if (stage == 0)
            {
                if (frames < SettleFramesFirst) return;
                // 플레이어 이동·카메라 추종이 포즈를 덮어쓰지 않게 끈다.
                FreezeCameraDrivers(cam);
                SessionState.SetInt(KeyStage, 1);
                SessionState.SetInt(KeyFrames, 0);
                return;
            }

            int poseIndex = SessionState.GetInt(KeyPose, 0);
            if (poseIndex >= Poses.Length) { Finish(); return; }
            var pose = Poses[poseIndex];

            // 1: 포즈로 옮기고 안정될 때까지 기다린다
            if (stage == 1)
            {
                cam.transform.SetPositionAndRotation(pose.Position, Quaternion.Euler(pose.Euler));
                if (frames < SettleFramesPose) return;
                _drawSum = _triSum = _setPassSum = _sampleCount = 0;
                SessionState.SetInt(KeyStage, 2);
                SessionState.SetInt(KeyFrames, 0);
                return;
            }

            // 2: 표본 수집
            if (stage == 2)
            {
                cam.transform.SetPositionAndRotation(pose.Position, Quaternion.Euler(pose.Euler));
                _drawSum += UnityStats.drawCalls;
                _triSum += UnityStats.triangles;
                _setPassSum += UnityStats.setPassCalls;
                _sampleCount++;
                if (_sampleCount < SampleFrames) return;

                string label = SessionState.GetString(KeyLabel, "unknown");
                int draws = _drawSum / _sampleCount;
                int tris = _triSum / _sampleCount;
                int setPass = _setPassSum / _sampleCount;

                string rows = SessionState.GetString(KeyRows, "");
                rows += $"{pose.Name}\t{draws}\t{tris}\t{setPass}\n";
                SessionState.SetString(KeyRows, rows);

                string shot = Path.Combine(OutDir, $"{label}__{pose.Name}.png");
                ScreenCapture.CaptureScreenshot(shot);

                Debug.Log($"[OcclusionAB] {pose.Name} — 드로우콜 {draws} / 삼각형 {tris} / SetPass {setPass}");

                SessionState.SetInt(KeyPose, poseIndex + 1);
                SessionState.SetInt(KeyStage, 1);
                SessionState.SetInt(KeyFrames, 0);
            }
        }

        /// <summary>카메라를 움직이는 것들을 멈춘다 — 안 그러면 포즈가 매 프레임 덮어써진다.</summary>
        static void FreezeCameraDrivers(Camera cam)
        {
            int off = 0;
            foreach (var mb in cam.GetComponentsInParent<MonoBehaviour>(true))
            {
                if (mb == null || mb is Camera) continue;
                var n = mb.GetType().Name;
                if (n.Contains("Camera") || n.Contains("Follow") || n.Contains("Orbit") || n.Contains("Look"))
                { mb.enabled = false; off++; }
            }
            var player = GameObject.FindWithTag("Player");
            if (player != null)
                foreach (var mb in player.GetComponentsInChildren<MonoBehaviour>(true))
                {
                    var n = mb.GetType().Name;
                    if (n.Contains("Movement") || n.Contains("Controller")) { mb.enabled = false; off++; }
                }
            Debug.Log($"[OcclusionAB] 카메라·이동 스크립트 {off}개를 껐다 (포즈 고정용)");
        }

        static void Finish()
        {
            string label = SessionState.GetString(KeyLabel, "unknown");
            string rows = SessionState.GetString(KeyRows, "");

            var sb = new StringBuilder();
            sb.AppendLine($"# 오클루전 A/B — {label}");
            sb.AppendLine($"umbraDataSize = {StaticOcclusionCulling.umbraDataSize}");
            sb.AppendLine($"측정 프레임 = {SampleFrames} 평균");
            sb.AppendLine();
            sb.AppendLine("| 포즈 | 드로우콜 | 삼각형 | SetPass |");
            sb.AppendLine("|---|---:|---:|---:|");
            foreach (var line in rows.Split('\n'))
            {
                if (string.IsNullOrWhiteSpace(line)) continue;
                var p = line.Split('\t');
                if (p.Length < 4) continue;
                sb.AppendLine($"| {p[0]} | {int.Parse(p[1], CultureInfo.InvariantCulture):N0} | {int.Parse(p[2], CultureInfo.InvariantCulture):N0} | {int.Parse(p[3], CultureInfo.InvariantCulture):N0} |");
            }

            Directory.CreateDirectory(OutDir);
            File.WriteAllText(Path.Combine(OutDir, $"{label}.md"), sb.ToString(), new UTF8Encoding(false));
            File.WriteAllText(Path.Combine(OutDir, $"{label}.tsv"), rows, new UTF8Encoding(false));

            Debug.Log("[OcclusionAB] 완료\n" + sb);

            SessionState.SetBool(KeyActive, false);
            EditorApplication.ExitPlaymode();
        }
    }
}
