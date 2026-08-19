using UnityEngine;
using UnityEngine.Rendering;

namespace Festa.World
{
    /// <summary>
    /// 11층 MVP 월드의 고정 공간 뼈대를 구성한다.
    /// 네트워크 상태나 Published Booth Layout과 분리된 로컬 정적 환경이다.
    /// </summary>
    public sealed class WorldSceneLayout : MonoBehaviour
    {
        const string RootName = "@World_11F";

        [Header("11층 원본 모델")]
        [SerializeField] GameObject _worldModelPrefab;
        [SerializeField] Vector3 _worldModelPosition = Vector3.zero;
        [SerializeField] Vector3 _worldModelRotation = Vector3.zero;
        [SerializeField] Vector3 _worldModelScale = Vector3.one;

        [Header("좌우 부스 섹션")]
        [SerializeField, Min(2f)] float _sidePassageLength = 10f;
        [SerializeField, Min(2f)] float _sidePassageWidth = 6f;
        [SerializeField] Vector2 _sectionSize = new(18f, 22f);
        [SerializeField] int _externalBoothCount = 8;
        [SerializeField] int _interiorSlotCount = 8;

        Material _floorMaterial;
        Material _wallMaterial;
        Material _accentMaterial;
        Material _boothMaterial;

        void Awake()
        {
            if (GameObject.Find(RootName) != null) return;
            BuildWorld();
        }

        void BuildWorld()
        {
            var root = new GameObject(RootName).transform;
            BuildMaterials();

            var modelBounds = BuildWorldModel(root);
            var leftSectionX = modelBounds.min.x - _sidePassageLength - _sectionSize.x * 0.5f;
            var rightSectionX = modelBounds.max.x + _sidePassageLength + _sectionSize.x * 0.5f;

            BuildSidePassages(root, modelBounds, leftSectionX, rightSectionX);
            BuildBoothSections(root, leftSectionX, rightSectionX, modelBounds.min.y, modelBounds.center.z);
            BuildInteriorAnchors(root);
            BuildSpawnArea(root, new Vector3(-75f, 0.08f, -235f));
            BuildLighting(root, leftSectionX, rightSectionX, modelBounds.center.z);
        }

        Bounds BuildWorldModel(Transform root)
        {
            if (!_worldModelPrefab)
            {
                Debug.LogWarning("[WorldSceneLayout] 11층 모델이 없어 안전용 바닥을 생성합니다.");
                CreateBox("FallbackFloor", root, new Vector3(0f, -0.15f, 0f),
                    new Vector3(42f, 0.3f, 30f), _floorMaterial, true);
                return new Bounds(Vector3.zero, new Vector3(42f, 0.3f, 30f));
            }

            var instance = Instantiate(_worldModelPrefab, root);
            instance.name = "11th-0819";
            instance.transform.localPosition = _worldModelPosition;
            instance.transform.localRotation = Quaternion.Euler(_worldModelRotation);
            instance.transform.localScale = _worldModelScale;

            var renderers = instance.GetComponentsInChildren<Renderer>(true);
            if (renderers.Length == 0)
                return new Bounds(instance.transform.position, new Vector3(42f, 0.3f, 30f));

            var bounds = renderers[0].bounds;
            for (var i = 1; i < renderers.Length; i++) bounds.Encapsulate(renderers[i].bounds);
            return bounds;
        }

        void BuildSidePassages(Transform root, Bounds modelBounds, float leftSectionX, float rightSectionX)
        {
            var passages = new GameObject("SidePassages").transform;
            passages.SetParent(root, false);

            var leftStart = modelBounds.min.x;
            var rightStart = modelBounds.max.x;
            CreateBox("Passage_West", passages,
                new Vector3((leftStart + leftSectionX + _sectionSize.x * 0.5f) * 0.5f, modelBounds.min.y - 0.08f, modelBounds.center.z),
                new Vector3(_sidePassageLength, 0.16f, _sidePassageWidth), _accentMaterial, true);
            CreateBox("Passage_East", passages,
                new Vector3((rightStart + rightSectionX - _sectionSize.x * 0.5f) * 0.5f, modelBounds.min.y - 0.08f, modelBounds.center.z),
                new Vector3(_sidePassageLength, 0.16f, _sidePassageWidth), _accentMaterial, true);
        }

        void BuildBoothSections(Transform root, float leftSectionX, float rightSectionX, float groundY, float centerZ)
        {
            var sections = new GameObject("BoothSections").transform;
            sections.SetParent(root, false);
            BuildSection("Section_West", sections, leftSectionX, groundY, centerZ, true, 0);
            BuildSection("Section_East", sections, rightSectionX, groundY, centerZ, false, _externalBoothCount / 2);
        }

        void BuildSection(string name, Transform parent, float centerX, float groundY, float centerZ, bool west, int firstSlotIndex)
        {
            var section = new GameObject(name).transform;
            section.SetParent(parent, false);
            section.localPosition = new Vector3(centerX, groundY, centerZ);
            CreateBox("SectionFloor", section, new Vector3(0f, -0.12f, 0f),
                new Vector3(_sectionSize.x, 0.24f, _sectionSize.y), _floorMaterial, true);

            var count = Mathf.Max(1, _externalBoothCount / 2);
            for (var localIndex = 0; localIndex < count; localIndex++)
            {
                var column = localIndex % 2;
                var row = localIndex / 2;
                var x = (column - 0.5f) * (_sectionSize.x * 0.48f);
                var z = (row - 0.5f) * (_sectionSize.y * 0.48f);
                var slotIndex = firstSlotIndex + localIndex;

                var slot = new GameObject($"ExternalBoothSlot_{slotIndex + 1:00}").transform;
                slot.SetParent(section, false);
                slot.localPosition = new Vector3(x, 0f, z);
                slot.localRotation = Quaternion.Euler(0f, west ? 90f : -90f, 0f);

                CreateBox("Back", slot, new Vector3(0f, 1.6f, 2.2f),
                    new Vector3(6.5f, 3.2f, 0.18f), _boothMaterial, true);

                var entrance = new GameObject("EntranceAnchor").transform;
                entrance.SetParent(slot, false);
                entrance.localPosition = new Vector3(0f, 0f, -2.7f);
            }
        }

        void BuildInteriorAnchors(Transform root)
        {
            var anchors = new GameObject("InteriorBoothAnchors").transform;
            anchors.SetParent(root, false);

            for (var i = 0; i < _interiorSlotCount; i++)
            {
                var slot = new GameObject($"InteriorBoothSlot_{i + 1:00}").transform;
                slot.SetParent(anchors, false);
                slot.localPosition = new Vector3((i % 4 - 1.5f) * 20f, 0f, 80f + (i / 4) * 20f);

                CreateAnchor("EntryAnchor", slot, new Vector3(0f, 0.1f, -3f), Quaternion.identity);
                CreateAnchor("ExitAnchor", slot, new Vector3(0f, 0.1f, 3f), Quaternion.Euler(0f, 180f, 0f));
                CreateAnchor("ContentAnchor", slot, Vector3.zero, Quaternion.identity);
            }
        }

        void BuildSpawnArea(Transform root, Vector3 center)
        {
            var spawns = new GameObject("PlayerSpawnPoints").transform;
            spawns.SetParent(root, false);
            for (var i = 0; i < 40; i++)
            {
                var point = new GameObject($"Spawn_{i + 1:00}").transform;
                point.SetParent(spawns, false);
                var column = i % 8;
                var row = i / 8;
                point.localPosition = center + new Vector3((column - 3.5f) * 2.25f, 0.1f, (row - 2f) * 2.25f);
                point.localRotation = Quaternion.identity;
            }
        }

        static Transform CreateAnchor(string name, Transform parent, Vector3 position, Quaternion rotation)
        {
            var anchor = new GameObject(name).transform;
            anchor.SetParent(parent, false);
            anchor.localPosition = position;
            anchor.localRotation = rotation;
            return anchor;
        }

        void BuildLighting(Transform root, float leftSectionX, float rightSectionX, float centerZ)
        {
            var lighting = new GameObject("WorldLighting").transform;
            lighting.SetParent(root, false);
            var centers = new[] { leftSectionX, 0f, rightSectionX };
            for (var i = 0; i < centers.Length; i++)
            {
                var go = new GameObject($"CeilingLight_{i + 1:00}");
                go.transform.SetParent(lighting, false);
                go.transform.localPosition = new Vector3(centers[i], 4.3f, centerZ);
                go.transform.localRotation = Quaternion.Euler(90f, 0f, 0f);
                var light = go.AddComponent<Light>();
                light.type = LightType.Point;
                light.color = new Color(0.82f, 0.91f, 1f);
                light.intensity = 5f;
                light.range = 12f;
                light.shadows = LightShadows.None;
            }
        }

        void BuildMaterials()
        {
            _floorMaterial = CreateMaterial("World_Floor", new Color(0.12f, 0.15f, 0.19f), 0.12f);
            _wallMaterial = CreateMaterial("World_Wall", new Color(0.82f, 0.86f, 0.9f), 0.05f);
            _accentMaterial = CreateMaterial("World_Aisle", new Color(0.08f, 0.36f, 0.62f), 0.2f);
            _boothMaterial = CreateMaterial("World_Booth", new Color(0.18f, 0.23f, 0.29f), 0.15f);
        }

        static Material CreateMaterial(string name, Color color, float metallic)
        {
            var shader = Shader.Find("Universal Render Pipeline/Lit") ?? Shader.Find("Standard");
            var material = new Material(shader) { name = name, color = color };
            if (material.HasProperty("_Metallic")) material.SetFloat("_Metallic", metallic);
            if (material.HasProperty("_Smoothness")) material.SetFloat("_Smoothness", 0.45f);
            return material;
        }

        static GameObject CreateBox(string name, Transform parent, Vector3 position,
            Vector3 scale, Material material, bool collider)
        {
            var go = GameObject.CreatePrimitive(PrimitiveType.Cube);
            go.name = name;
            go.transform.SetParent(parent, false);
            go.transform.localPosition = position;
            go.transform.localScale = scale;
            go.GetComponent<MeshRenderer>().sharedMaterial = material;
            if (!collider) Destroy(go.GetComponent<Collider>());
            return go;
        }
    }
}
