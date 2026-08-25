using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 외형 생성 구현.
    ///   - 런타임 조립 모드: SidekickRuntimeService로 파츠 병합 생성
    ///   - 프리셋 모드: Catalog의 사전 제작 프리팹 Instantiate
    /// 어느 쪽이든 실패하면 placeholder로 폴백하고 월드는 계속 동작한다.
    /// </summary>
    public class CatalogAvatarVisualProvider : IAvatarVisualProvider
    {
        readonly AvatarCatalog _catalog;

        public CatalogAvatarVisualProvider(AvatarCatalog catalog) => _catalog = catalog;

        public GameObject CreateVisual(AvatarAppearance appearance, Transform parent)
        {
            GameObject go = appearance.IsRuntime
                ? CreateRuntime(appearance, parent)
                : CreateFromCatalog(appearance, parent);

            go ??= CreatePlaceholder(parent);

            go.transform.SetParent(parent, false);
            go.transform.localPosition = Vector3.zero;
            go.transform.localRotation = Quaternion.identity;

            ApplyTint(go, appearance);
            return go;
        }

        // ---------- 런타임 조립 ----------

        static GameObject CreateRuntime(AvatarAppearance appearance, Transform parent)
        {
            var service = SidekickRuntimeService.Instance;

            if (!service.IsReady)
            {
                // 초기화가 아직이면 시작만 걸어두고 이번엔 폴백 — 준비되면 재요청 시 정상 생성된다.
                _ = service.EnsureInitializedAsync();
                Debug.Log("[AvatarVisual] Sidekick 런타임 준비 중 — 임시 외형 사용");
                return null;
            }

            var go = service.BuildCharacter(appearance.Parts, "AvatarVisual_Runtime");
            if (go == null)
                Debug.LogWarning("[AvatarVisual] 런타임 조립 실패 — placeholder 사용");
            return go;
        }

        // ---------- 프리셋 ----------

        GameObject CreateFromCatalog(AvatarAppearance appearance, Transform parent)
        {
            if (_catalog == null)
            {
                Debug.LogError("[AvatarVisual] AvatarCatalog 미할당 — " +
                               "PlayerAvatar 프리팹의 PlayerAvatarVisual > Catalog 슬롯 확인");
                return null;
            }

            var prefab = _catalog.GetPrefab(appearance.PresetCode);
            if (prefab == null)
            {
                Debug.LogWarning($"[AvatarVisual] '{appearance.PresetCode}' 프리팹 없음. " +
                                 $"등록 코드: [{string.Join(", ", _catalog.AllCodes)}]");
                return null;
            }

            var go = Object.Instantiate(prefab, parent);
            go.name = $"Visual_{appearance.PresetCode}";
            return go;
        }

        // ---------- 공통 ----------

        static void ApplyTint(GameObject go, AvatarAppearance appearance)
        {
            if (string.IsNullOrEmpty(appearance.TintHex)) return;

            var tint = appearance.GetTintColor();
            foreach (var renderer in go.GetComponentsInChildren<Renderer>())
            {
                var mat = renderer.material; // 인스턴스 복제본 — 공유 머티리얼 보호
                if (mat.HasProperty("_BaseColor")) mat.SetColor("_BaseColor", tint);
                else if (mat.HasProperty("_Color")) mat.SetColor("_Color", tint);
            }
        }

        static GameObject CreatePlaceholder(Transform parent)
        {
            var go = GameObject.CreatePrimitive(PrimitiveType.Capsule);
            go.name = "Visual_Placeholder";
            go.transform.SetParent(parent, false);
            var collider = go.GetComponent<Collider>();
            if (collider != null) Object.Destroy(collider);
            return go;
        }
    }
}
