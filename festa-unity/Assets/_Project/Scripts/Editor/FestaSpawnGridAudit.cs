// 스폰 격자가 실제 씬에서 쓸 만한지 확인하는 검증 도구.
// 이 파일이 있는 이유: 격자 칸 수를 늘릴 때 새로 생긴 칸이 바닥에 닿는지, 구조물을 물지 않는지
// 눈으로 확인할 방법이 없었다. 40칸 시절의 근거는 주석에만 남아 있어 재현할 수 없었다.
using Festa.Network;
using System.Text;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 스폰 슬롯 전부를 훑어 ① 바닥에 닿는지 ② 아바타 반경 안에 구조물이 있는지 보고한다.
    ///
    /// <para><b>왜 필요한가.</b> <see cref="ConnectionManager"/> 의 격자는 8x5=40 칸일 때
    /// "40 슬롯이 모두 바닥에 닿고 아무것도 물지 않는" 중심을 찾아 정한 값이다. 정원을 100 으로
    /// 올리며 행을 13 으로 늘렸는데, 늘어난 칸은 그 검증 밖이다. 런타임은 바닥을 못 찾으면
    /// 경고 후 폴백 y 로 떨어뜨리지만 <b>구조물을 무는 것은 조용히 넘어간다</b> — 사용자에게는
    /// 벽에 끼인 채 시작하는 것으로 나타난다.</para>
    ///
    /// <para>Edit 모드에서 물리 질의를 쓰므로 씬을 연 뒤 <see cref="Physics.SyncTransforms"/> 를
    /// 먼저 부른다. 그러지 않으면 방금 로드한 콜라이더가 물리 씬에 반영되지 않아 전부 "바닥 없음"
    /// 으로 나온다.</para>
    /// </summary>
    public static class FestaSpawnGridAudit
    {
        const string MainScene = "Assets/_Project/Scenes/main.unity";

        /// <summary>아바타 반경(월드 유닛). ConnectionManager 의 간격 주석과 같은 근거값이다.</summary>
        const float AvatarRadius = 2.2f;

        [MenuItem("Festa/World/스폰 격자 검증 (바닥 접지·구조물 간섭)")]
        public static void AuditFromMenu() => Run(false);

        /// <summary>배치 모드 진입점. 씬을 직접 열고 결과를 로그로 남긴다.</summary>
        public static void AuditBatch() => Run(true);

        static void Run(bool openScene)
        {
            if (openScene) EditorSceneManager.OpenScene(MainScene, OpenSceneMode.Single);
            Physics.SyncTransforms();

            int total = ConnectionManager.SpawnSlotCount;
            int noFloor = 0, blocked = 0;
            float minY = float.MaxValue, maxY = float.MinValue;
            var detail = new StringBuilder();

            for (int slot = 0; slot < total; slot++)
            {
                var pos = ConnectionManager.GetSpawnPosition(slot);
                minY = Mathf.Min(minY, pos.y);
                maxY = Mathf.Max(maxY, pos.y);

                // 바닥 확인 — GetSpawnPosition 과 같은 조건으로 다시 쏜다.
                bool onFloor = Physics.Raycast(new Vector3(pos.x, pos.y + 30f, pos.z), Vector3.down, 60f);
                if (!onFloor) { noFloor++; detail.AppendLine($"  슬롯 {slot,3} 바닥 없음 {pos}"); }

                // 구조물 간섭 — 발밑에서 살짝 띄운 구를 놓아 바닥 자체는 세지 않는다.
                var center = pos + Vector3.up * (AvatarRadius + 0.2f);
                foreach (var hit in Physics.OverlapSphere(center, AvatarRadius, ~0, QueryTriggerInteraction.Ignore))
                {
                    blocked++;
                    detail.AppendLine($"  슬롯 {slot,3} 간섭 '{hit.name}' {pos}");
                    break;
                }
            }

            var summary = $"[SpawnGridAudit] 슬롯 {total}칸 — 바닥 없음 {noFloor} · 구조물 간섭 {blocked} · 바닥 y {minY:F2}~{maxY:F2}";
            if (noFloor == 0 && blocked == 0) Debug.Log(summary + "\n전부 통과");
            else Debug.LogError(summary + "\n" + detail);
        }
    }
}
