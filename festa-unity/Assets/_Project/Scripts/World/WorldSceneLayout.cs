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

        [SerializeField] Vector2 _worldSize = new(42f, 30f);
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

            CreateBox("Floor", root, new Vector3(0f, -0.15f, 0f),
                new Vector3(_worldSize.x, 0.3f, _worldSize.y), _floorMaterial, true);

            CreateBox("Wall_North", root, new Vector3(0f, 2.4f, _worldSize.y * 0.5f),
                new Vector3(_worldSize.x, 4.8f, 0.35f), _wallMaterial, true);
            CreateBox("Wall_South", root, new Vector3(0f, 2.4f, -_worldSize.y * 0.5f),
                new Vector3(_worldSize.x, 4.8f, 0.35f), _wallMaterial, true);
            CreateBox("Wall_East", root, new Vector3(_worldSize.x * 0.5f, 2.4f, 0f),
                new Vector3(0.35f, 4.8f, _worldSize.y), _wallMaterial, true);
            CreateBox("Wall_West", root, new Vector3(-_worldSize.x * 0.5f, 2.4f, 0f),
                new Vector3(0.35f, 4.8f, _worldSize.y), _wallMaterial, true);

            BuildCentralWalkway(root);
            BuildExternalBooths(root);
            BuildInteriorAnchors(root);
            BuildSpawnArea(root);
            BuildLighting(root);
        }

        void BuildCentralWalkway(Transform root)
        {
            var walkway = new GameObject("WalkableArea").transform;
            walkway.SetParent(root, false);
            CreateBox("MainAisle", walkway, new Vector3(0f, 0.02f, 0f),
                new Vector3(8f, 0.04f, _worldSize.y - 3f), _accentMaterial, false);
            CreateBox("CrossAisle", walkway, new Vector3(0f, 0.025f, 0f),
                new Vector3(_worldSize.x - 3f, 0.05f, 5f), _accentMaterial, false);
        }

        void BuildExternalBooths(Transform root)
        {
            var slots = new GameObject("ExternalBoothSlots").transform;
            slots.SetParent(root, false);

            for (var i = 0; i < _externalBoothCount; i++)
            {
                var left = i < _externalBoothCount / 2;
                var row = i % (_externalBoothCount / 2);
                var x = left ? -13f : 13f;
                var z = -10.5f + row * 7f;
                var slot = new GameObject($"ExternalBoothSlot_{i + 1:00}").transform;
                slot.SetParent(slots, false);
                slot.localPosition = new Vector3(x, 0f, z);
                slot.localRotation = Quaternion.Euler(0f, left ? 90f : -90f, 0f);

                CreateBox("Back", slot, new Vector3(0f, 1.6f, 2.2f),
                    new Vector3(6f, 3.2f, 0.25f), _boothMaterial, true);
                CreateBox("Side_L", slot, new Vector3(-2.9f, 1.6f, 0f),
                    new Vector3(0.2f, 3.2f, 4.5f), _boothMaterial, true);
                CreateBox("Side_R", slot, new Vector3(2.9f, 1.6f, 0f),
                    new Vector3(0.2f, 3.2f, 4.5f), _boothMaterial, true);

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

        void BuildSpawnArea(Transform root)
        {
            var spawns = new GameObject("PlayerSpawnPoints").transform;
            spawns.SetParent(root, false);
            for (var i = 0; i < 40; i++)
            {
                var point = new GameObject($"Spawn_{i + 1:00}").transform;
                point.SetParent(spawns, false);
                var column = i % 8;
                var row = i / 8;
                point.localPosition = new Vector3((column - 3.5f) * 1.35f, 0.1f, (row - 2f) * 1.35f);
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

        void BuildLighting(Transform root)
        {
            var lighting = new GameObject("WorldLighting").transform;
            lighting.SetParent(root, false);
            for (var i = -2; i <= 2; i++)
            {
                var go = new GameObject($"CeilingLight_{i + 3:00}");
                go.transform.SetParent(lighting, false);
                go.transform.localPosition = new Vector3(i * 8f, 4.3f, 0f);
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
