using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 카탈로그(프리셋 프리팹) 기반 구현. 현재 프리팹은 Synty Sidekick으로 제작하지만
    /// 이 클래스는 Sidekick 전용 API를 쓰지 않는다 — 단순 Instantiate라 에셋 출처가 바뀌어도 무관.
    /// </summary>
    public class CatalogAvatarVisualProvider : IAvatarVisualProvider
    {
        readonly AvatarCatalog _catalog;

        public CatalogAvatarVisualProvider(AvatarCatalog catalog) => _catalog = catalog;

        public GameObject CreateVisual(string avatarCode, Transform parent)
        {
            if (_catalog == null)
            {
                Debug.LogError("[AvatarVisual] AvatarCatalog가 할당되지 않음 — " +
                               "PlayerAvatar 프리팹의 PlayerAvatarVisual > Catalog 슬롯을 확인하세요");
                return CreatePlaceholder(parent);
            }

            var prefab = _catalog.GetPrefab(avatarCode);

            if (prefab == null)
            {
                Debug.LogWarning($"[AvatarVisual] '{avatarCode}' 프리팹 없음 — placeholder 캡슐 사용. " +
                                 $"Catalog 등록 코드: [{string.Join(", ", _catalog.AllCodes)}]");
                return CreatePlaceholder(parent);
            }

            var go = Object.Instantiate(prefab, parent);
            go.transform.localPosition = Vector3.zero;
            go.transform.localRotation = Quaternion.identity;
            go.name = $"Visual_{avatarCode}";
            return go;
        }

        /// <summary>카탈로그 미설정 상태에서도 월드가 동작하도록 하는 폴백 (POC 캡슐과 동일).</summary>
        static GameObject CreatePlaceholder(Transform parent)
        {
            var go = GameObject.CreatePrimitive(PrimitiveType.Capsule);
            go.name = "Visual_Placeholder";
            go.transform.SetParent(parent, false);
            var collider = go.GetComponent<Collider>();
            if (collider != null) Object.Destroy(collider); // 외형 전용 — 충돌은 루트가 담당
            return go;
        }
    }
}
