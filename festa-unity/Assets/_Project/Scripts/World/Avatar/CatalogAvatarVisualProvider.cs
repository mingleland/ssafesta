using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 외형 생성 구현.
    ///   - 모듈 조립 모드: Rukha93 AvatarAssembler로 파츠 생성
    ///   - 프리셋 모드: Catalog의 사전 제작 프리팹 Instantiate
    /// 어느 쪽이든 실패하면 placeholder로 폴백하고 월드는 계속 동작한다.
    /// </summary>
    public class CatalogAvatarVisualProvider : IAvatarVisualProvider
    {
        readonly AvatarCatalog _catalog;
        readonly Festa.Avatar.AvatarCatalog _modularCatalog;

        public CatalogAvatarVisualProvider(AvatarCatalog catalog, Festa.Avatar.AvatarCatalog modularCatalog = null)
        { _catalog = catalog; _modularCatalog = modularCatalog; }

        public GameObject CreateVisual(AvatarAppearance appearance, Transform parent)
        {
            GameObject go;
            if (appearance.IsModular)
            {
                go = CreateModular(appearance.ModularConfig, parent);
            }
            else if (appearance.PresetCode == AvatarAppearance.DefaultPreset && _modularCatalog != null)
            {
                // 메인 씬을 직접 실행하는 개발 테스트처럼 저장된 외형이 없을 때도
                // 삭제 예정인 구형 sk_01 프리팹 대신 현재 모듈형 기본 아바타를 사용한다.
                go = CreateModular(_modularCatalog.CreateDefault(Festa.Avatar.AvatarGender.Female), parent);
            }
            else
            {
                go = CreateFromCatalog(appearance, parent);
            }

            go ??= CreatePlaceholder(parent);

            go.transform.SetParent(parent, false);
            go.transform.localPosition = Vector3.zero;
            // 월드 플레이어의 이동 정면과 모듈 아바타 정면이 일치하므로
            // 생성 외형에는 별도의 180도 보정을 적용하지 않는다.
            go.transform.localRotation = Quaternion.identity;

            ApplyTint(go, appearance);
            return go;
        }

        // ---------- 모듈 조립 ----------

        GameObject CreateModular(Festa.Avatar.AvatarConfig config, Transform parent)
        {
            if (_modularCatalog == null)
            {
                Debug.LogError("[AvatarVisual] Modular AvatarCatalog 미할당");
                return null;
            }
            var root = new GameObject("AvatarVisual_Modular");
            root.transform.SetParent(parent, false);
            var assembler = root.AddComponent<Festa.Avatar.AvatarAssembler>();
            assembler.Catalog = _modularCatalog;
            assembler.Apply(config);
            if (!string.IsNullOrEmpty(assembler.LastError)) Debug.LogError($"[AvatarVisual] {assembler.LastError}");

            // 조립이 실패해도 root 는 만들어져 있어 `??=` 폴백이 절대 발동하지 않았다 — 그릴 것이 0개인 빈 오브젝트가
            // 그대로 진행돼 몸은 사라지고 이름표만 공중에 떴다(QA 2026-09-08 #56). 렌더러가 하나도 없으면 null 을
            // 돌려 호출자의 placeholder 폴백이 실제로 작동하게 한다.
            if (root.GetComponentsInChildren<Renderer>(true).Length == 0)
            {
                Debug.LogError("[AvatarVisual] 조립 결과에 렌더러가 없다 — placeholder 로 폴백한다" +
                               (string.IsNullOrEmpty(assembler.LastError) ? "" : $" ({assembler.LastError})"));
                Object.Destroy(root);
                return null;
            }
            return root;
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
