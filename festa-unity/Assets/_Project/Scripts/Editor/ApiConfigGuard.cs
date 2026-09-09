using UnityEditor;
using UnityEngine;

namespace Festa.EditorTools
{
    /// <summary>
    /// 에디터가 켜지거나 스크립트가 다시 컴파일될 때 <c>ApiConfig.asset</c> 이 Prod 로 남아 있으면 시끄럽게 알린다.
    ///
    /// <para><b>왜.</b> 릴리스 빌드는 ApiConfig 를 Prod·실서버로 **디스크에 저장한다** (S15P21A604-419).
    /// 정상 경로 <see cref="FestaReleaseBuilder"/>.BuildAll 은 finally 로 되돌리지만, 빌드가 중간에 죽거나
    /// 단계를 따로 호출하면(모달 회피 등) Prod 가 그대로 남는다. 그 상태로 개발 빌드를 뜨면
    /// 로컬에서 <c>https://api.ssafesta.world</c> 로 붙어 전부 CORS 로 실패하고,
    /// 부스가 빈 월드가 떠서 성능 측정·기능 검증이 통째로 무효가 된다.
    /// 2026-09-08 에 실제로 발생해 측정 결과를 버렸다 — 조용히 넘어가면 안 되는 상태다 (T-24 원칙).</para>
    ///
    /// <para>자동으로 되돌리지는 않는다. Dev/Prod 로 일부러 맞춰 놓고 확인하는 경우가 있어서,
    /// 판단은 사람이 하고 여기서는 사실만 드러낸다.</para>
    /// </summary>
    [InitializeOnLoad]
    static class ApiConfigGuard
    {
        static ApiConfigGuard() => EditorApplication.delayCall += Check;

        static void Check()
        {
            var guids = AssetDatabase.FindAssets("t:ScriptableObject ApiConfig");
            foreach (var guid in guids)
            {
                var path = AssetDatabase.GUIDToAssetPath(guid);
                if (!path.EndsWith("/ApiConfig.asset")) continue;

                var cfg = AssetDatabase.LoadAssetAtPath<Festa.Integration.ApiConfig>(path);
                if (cfg == null || cfg.activeEnvironment != Festa.Integration.ApiEnvironment.Prod) continue;

                Debug.LogWarning(
                    $"[ApiConfigGuard] {path} 가 **Prod** 로 남아 있다 (useMockApi={cfg.useMockApi}).\n" +
                    "이 상태로 개발 빌드를 뜨면 로컬에서 실서버로 붙어 CORS 로 전부 실패하고 부스가 빈 월드가 뜬다.\n" +
                    "릴리스 산출물을 뽑는 중이 아니라면 Local 로 되돌려라 — git checkout 이 가장 빠르다:\n" +
                    "  git checkout -- festa-unity/Assets/_Project/ScriptableObjects/ApiConfig.asset");
            }
        }
    }
}
