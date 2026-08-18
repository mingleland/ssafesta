#if UNITY_EDITOR
using System.Collections.Generic;
using Festa.Booth;
using Festa.Content;
using UnityEditor;
using UnityEngine;

namespace Festa.Editor.Booth
{
    /// <summary>정식 부스 프리팹 10종과 Registry를 동일 규칙으로 재생성한다.</summary>
    [InitializeOnLoad]
    public static class BoothPrefabCatalogBuilder
    {
        const string PrefabFolder = "Assets/_Project/Prefabs/Booth";
        const string MaterialFolder = "Assets/_Project/Art/Booth/Materials";
        const string RegistryPath = "Assets/_Project/ScriptableObjects/BoothObjectRegistry.asset";

        static BoothPrefabCatalogBuilder()
        {
            EditorApplication.delayCall += BuildWhenRegistryIsEmpty;
        }

        [MenuItem("FESTA/Booth/Rebuild Formal Prefab Catalog")]
        public static void Rebuild()
        {
            EnsureFolder("Assets/_Project/Prefabs", "Booth");
            EnsureFolder("Assets/_Project/Art", "Booth");
            EnsureFolder("Assets/_Project/Art/Booth", "Materials");

            var entries = new List<(BoothObjectType type, GameObject prefab)>();
            foreach (var type in CatalogTypes)
                entries.Add((type, BuildPrefab(type)));

            var registry = AssetDatabase.LoadAssetAtPath<BoothObjectRegistry>(RegistryPath);
            if (registry == null)
            {
                registry = ScriptableObject.CreateInstance<BoothObjectRegistry>();
                AssetDatabase.CreateAsset(registry, RegistryPath);
            }

            var serialized = new SerializedObject(registry);
            var list = serialized.FindProperty("_entries");
            list.arraySize = entries.Count;
            for (int i = 0; i < entries.Count; i++)
            {
                var entry = list.GetArrayElementAtIndex(i);
                entry.FindPropertyRelative("type").enumValueIndex = (int)entries[i].type;
                entry.FindPropertyRelative("assetCode").stringValue = DefaultAssetCode(entries[i].type);
                entry.FindPropertyRelative("prefab").objectReferenceValue = entries[i].prefab;
            }
            serialized.ApplyModifiedPropertiesWithoutUndo();
            EditorUtility.SetDirty(registry);
            AssetDatabase.SaveAssets();
            AssetDatabase.Refresh();
            Debug.Log($"[BoothPrefabCatalog] {entries.Count} prefabs and registry rebuilt.");
        }

        static readonly BoothObjectType[] CatalogTypes =
        {
            BoothObjectType.AiAgent, BoothObjectType.VideoScreen, BoothObjectType.ProjectPanel,
            BoothObjectType.SurveyKiosk, BoothObjectType.RecruitmentBoard,
            BoothObjectType.ConsultationDesk, BoothObjectType.Laptop, BoothObjectType.LikeVote,
            BoothObjectType.Furniture, BoothObjectType.Decoration,
        };

        static string DefaultAssetCode(BoothObjectType type) => type switch
        {
            BoothObjectType.Furniture => "FURNITURE_DEFAULT",
            BoothObjectType.Decoration => "DECORATION_DEFAULT",
            _ => string.Empty,
        };

        static void BuildWhenRegistryIsEmpty()
        {
            if (EditorApplication.isPlayingOrWillChangePlaymode) return;
            var registry = AssetDatabase.LoadAssetAtPath<BoothObjectRegistry>(RegistryPath);
            if (registry == null) return;
            var serialized = new SerializedObject(registry);
            if (serialized.FindProperty("_entries").arraySize == 0) Rebuild();
        }

        static GameObject BuildPrefab(BoothObjectType type)
        {
            var root = new GameObject(type.ToString());
            root.AddComponent<BoothRuntimeObject>();
            var target = root.AddComponent<BoothInteractionTarget>();
            bool interactive = type is not BoothObjectType.Furniture and not BoothObjectType.Decoration;
            target.Configure(interactive ? 3f : 2.2f, interactive);

            switch (type)
            {
                case BoothObjectType.AiAgent:
                    Part(root, PrimitiveType.Capsule, "Body", new Vector3(0, 1, 0), new Vector3(.55f, 1, .55f), new Color(.25f, .55f, .9f));
                    root.AddComponent<AiNpcInteractable>();
                    break;
                case BoothObjectType.VideoScreen:
                    Part(root, PrimitiveType.Cube, "Frame", new Vector3(0, 1.2f, 0), new Vector3(2.4f, 1.4f, .16f), new Color(.06f, .08f, .12f));
                    Part(root, PrimitiveType.Cube, "Display", new Vector3(0, 1.2f, -.09f), new Vector3(2.15f, 1.15f, .03f), new Color(.12f, .45f, .7f));
                    root.AddComponent<VideoScreenPlaceholder>();
                    break;
                case BoothObjectType.ProjectPanel:
                case BoothObjectType.RecruitmentBoard:
                    Part(root, PrimitiveType.Cube, "Panel", new Vector3(0, 1.1f, 0), new Vector3(1.5f, 1.8f, .12f), type == BoothObjectType.ProjectPanel ? new Color(.85f, .9f, .95f) : new Color(.9f, .65f, .25f));
                    Part(root, PrimitiveType.Cube, "Stand", new Vector3(0, .45f, .08f), new Vector3(.14f, .9f, .14f), new Color(.18f, .2f, .24f));
                    break;
                case BoothObjectType.SurveyKiosk:
                    Part(root, PrimitiveType.Cube, "Body", new Vector3(0, .65f, 0), new Vector3(.65f, 1.3f, .55f), new Color(.2f, .55f, .48f));
                    Part(root, PrimitiveType.Cube, "TouchScreen", new Vector3(0, 1.15f, -.3f), new Vector3(.5f, .38f, .04f), new Color(.08f, .16f, .2f));
                    break;
                case BoothObjectType.ConsultationDesk:
                    Part(root, PrimitiveType.Cube, "DeskTop", new Vector3(0, .78f, 0), new Vector3(1.8f, .14f, .8f), new Color(.42f, .24f, .12f));
                    Part(root, PrimitiveType.Cube, "Front", new Vector3(0, .38f, .28f), new Vector3(1.65f, .7f, .12f), new Color(.32f, .18f, .1f));
                    break;
                case BoothObjectType.Laptop:
                    BuildLaptop(root);
                    root.AddComponent<LaptopInteractable>();
                    break;
                case BoothObjectType.LikeVote:
                    Part(root, PrimitiveType.Cylinder, "VoteStand", new Vector3(0, .45f, 0), new Vector3(.35f, .45f, .35f), new Color(.75f, .18f, .3f));
                    Part(root, PrimitiveType.Sphere, "VoteButton", new Vector3(0, 1f, 0), Vector3.one * .48f, new Color(1f, .35f, .5f));
                    break;
                case BoothObjectType.Furniture:
                    Part(root, PrimitiveType.Cube, "Seat", new Vector3(0, .48f, 0), new Vector3(1.2f, .18f, .65f), new Color(.28f, .32f, .38f));
                    Part(root, PrimitiveType.Cube, "Back", new Vector3(0, .9f, .27f), new Vector3(1.2f, .7f, .14f), new Color(.28f, .32f, .38f));
                    break;
                case BoothObjectType.Decoration:
                    Part(root, PrimitiveType.Cylinder, "Pot", new Vector3(0, .25f, 0), new Vector3(.35f, .25f, .35f), new Color(.45f, .24f, .12f));
                    Part(root, PrimitiveType.Sphere, "Plant", new Vector3(0, .9f, 0), new Vector3(.75f, 1.1f, .75f), new Color(.15f, .55f, .25f));
                    break;
            }

            var collider = root.AddComponent<BoxCollider>();
            FitCollider(root, collider);
            string path = $"{PrefabFolder}/{type}.prefab";
            var prefab = PrefabUtility.SaveAsPrefabAsset(root, path);
            Object.DestroyImmediate(root);
            return prefab;
        }

        static void BuildLaptop(GameObject root)
        {
            var imported = AssetDatabase.LoadAssetAtPath<GameObject>("Assets/_Project/Models/Laptop/laptop.prefab");
            if (imported != null)
            {
                var model = (GameObject)PrefabUtility.InstantiatePrefab(imported);
                model.name = "LaptopModel";
                model.transform.SetParent(root.transform, false);
                model.transform.localScale = Vector3.one * .7f;
                return;
            }
            Part(root, PrimitiveType.Cube, "Keyboard", new Vector3(0, .08f, 0), new Vector3(.75f, .08f, .5f), new Color(.12f, .14f, .18f));
            var display = Part(root, PrimitiveType.Cube, "Display", new Vector3(0, .38f, .22f), new Vector3(.75f, .52f, .05f), new Color(.08f, .12f, .18f));
            display.transform.localRotation = Quaternion.Euler(-12f, 0, 0);
        }

        static GameObject Part(GameObject root, PrimitiveType primitive, string name, Vector3 position, Vector3 scale, Color color)
        {
            var part = GameObject.CreatePrimitive(primitive);
            part.name = name;
            part.transform.SetParent(root.transform, false);
            part.transform.localPosition = position;
            part.transform.localScale = scale;
            Object.DestroyImmediate(part.GetComponent<Collider>());
            var renderer = part.GetComponent<Renderer>();
            renderer.sharedMaterial = GetMaterial(name + ColorUtility.ToHtmlStringRGB(color), color);
            return part;
        }

        static Material GetMaterial(string key, Color color)
        {
            string safe = key.Replace(" ", "_");
            string path = $"{MaterialFolder}/{safe}.mat";
            var material = AssetDatabase.LoadAssetAtPath<Material>(path);
            if (material != null) return material;
            var shader = Shader.Find("Universal Render Pipeline/Lit") ?? Shader.Find("Standard");
            material = new Material(shader) { color = color, name = safe };
            AssetDatabase.CreateAsset(material, path);
            return material;
        }

        static void FitCollider(GameObject root, BoxCollider collider)
        {
            var renderers = root.GetComponentsInChildren<Renderer>(true);
            if (renderers.Length == 0) return;
            var bounds = renderers[0].bounds;
            for (int i = 1; i < renderers.Length; i++) bounds.Encapsulate(renderers[i].bounds);
            collider.center = root.transform.InverseTransformPoint(bounds.center);
            collider.size = bounds.size;
        }

        static void EnsureFolder(string parent, string name)
        {
            string path = parent + "/" + name;
            if (!AssetDatabase.IsValidFolder(path)) AssetDatabase.CreateFolder(parent, name);
        }
    }
}
#endif
