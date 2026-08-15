#if UNITY_EDITOR
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Festa.Avatar;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.Rendering;

namespace Festa.Editor.Avatar
{
    public static class CharacterLobbyBuilder
    {
        const string VendorAssets = "Assets/Rukha93/ModularAnimeCharacter/Samples/Customization/Assets";
        const string Output = "Assets/_Project/ScriptableObjects/Avatar";
        const string CatalogPath = Output + "/AvatarCatalog.asset";

        [MenuItem("Festa/Avatar/Build Character Lobby")]
        public static void Build()
        {
            EnsureFolder("Assets/_Project/ScriptableObjects"); EnsureFolder(Output);
            var definitions = new List<AvatarItemDefinition>();
            foreach (string guid in AssetDatabase.FindAssets("t:ScriptableObject", new[] { VendorAssets }))
            {
                string path = AssetDatabase.GUIDToAssetPath(guid);
                if (!TryClassify(path, out var category, out var gender)) continue;
                var source = AssetDatabase.LoadMainAssetAtPath(path); if (!source) continue;
                var so = new SerializedObject(source);
                var defPath = $"{Output}/{Sanitize(path[(VendorAssets.Length + 1)..])}.asset";
                var def = AssetDatabase.LoadAssetAtPath<AvatarItemDefinition>(defPath);
                if (!def) { def = ScriptableObject.CreateInstance<AvatarItemDefinition>(); AssetDatabase.CreateAsset(def, defPath); }
                def.itemId = StableId(guid); def.displayName = Path.GetFileNameWithoutExtension(path); def.category = category; def.gender = gender;
                def.isDefault = definitions.All(x => x.category != category || x.gender != gender);
                def.meshes = ReadRefs<SkinnedMeshRenderer>(so.FindProperty("meshes"));
                var objects = so.FindProperty("objects");
                var prefabs = new List<GameObject>(); var bones = new List<HumanBodyBones>();
                if (objects != null) for (int i = 0; i < objects.arraySize; i++) { var e=objects.GetArrayElementAtIndex(i); prefabs.Add(e.FindPropertyRelative("prefab").objectReferenceValue as GameObject); bones.Add((HumanBodyBones)e.FindPropertyRelative("targetBone").enumValueIndex); }
                def.objectPrefabs = prefabs.Where(x=>x).ToArray(); def.targetBones = bones.ToArray(); def.hiddenBodyParts = ReadInts(so.FindProperty("bodyParts"));
                // Top.02 exposes the waist below its cropped inner layer. Keeping Torso_Spine01 hidden leaves a visible hole.
                if(category==AvatarPartCategory.Top&&def.displayName=="Top.02")def.hiddenBodyParts=def.hiddenBodyParts.Where(x=>x!=1).ToArray();
                if(category==AvatarPartCategory.Hair) def.hairGroup = HairFromName(VisualName(def));
                if(category==AvatarPartCategory.Hat) { string n=VisualName(def); def.familyId=n.Contains("hat.001")?1001:n.Contains("hat.002")?1002:1003; def.requiredHairGroup=HairFromName(n); }
                EditorUtility.SetDirty(def); definitions.Add(def);
            }
            var catalog = AssetDatabase.LoadAssetAtPath<AvatarCatalog>(CatalogPath);
            if (!catalog) { catalog=ScriptableObject.CreateInstance<AvatarCatalog>(); AssetDatabase.CreateAsset(catalog,CatalogPath); }
            catalog.maleBody=CloneBody("Assets/Rukha93/ModularAnimeCharacter/Prefabs/M_FullBody.prefab","Assets/_Project/Prefabs/Avatar/M_Body.prefab");
            catalog.femaleBody=CloneBody("Assets/Rukha93/ModularAnimeCharacter/Prefabs/F_FullBody.prefab","Assets/_Project/Prefabs/Avatar/F_Body.prefab");
            catalog.animatorController=AssetDatabase.LoadAssetAtPath<RuntimeAnimatorController>("Assets/_Project/Animators/AvatarAnimator.controller");
            catalog.items=definitions.OrderBy(x=>x.category).ThenBy(x=>x.displayName).ToArray();
            catalog.palette=BuildPalette(); EditorUtility.SetDirty(catalog); AssetDatabase.SaveAssets();
            BuildScene(catalog); Debug.Log($"[CharacterLobbyBuilder] 완료: ItemDefinition {definitions.Count}개, Catalog/Scene 생성");
        }

        static void BuildScene(AvatarCatalog catalog)
        {
            var scene=EditorSceneManager.NewScene(NewSceneSetup.EmptyScene,NewSceneMode.Single);
            var cameraGo=new GameObject("Preview Camera",typeof(Camera),typeof(AudioListener));cameraGo.tag="MainCamera";cameraGo.transform.position=new Vector3(0,1.05f,-4.5f);cameraGo.transform.LookAt(new Vector3(0,1.05f,0));var cam=cameraGo.GetComponent<Camera>();cam.fieldOfView=35;cam.clearFlags=CameraClearFlags.SolidColor;cam.backgroundColor=new Color(.025f,.035f,.07f);
            Light("Key Light",new Vector3(2.5f,4,-2),new Vector3(45,-25,0),1.45f,new Color(1,.84f,.72f));
            Light("Rim Light",new Vector3(-2,3,2),new Vector3(130,35,0),1.8f,new Color(.35f,.55f,1));
            var stage=GameObject.CreatePrimitive(PrimitiveType.Cylinder);stage.name="Stage";stage.transform.position=new Vector3(0,-.08f,0);stage.transform.localScale=new Vector3(1.25f,.08f,1.25f);SetMaterial(stage,new Color(.11f,.14f,.23f));
            var backdrop=GameObject.CreatePrimitive(PrimitiveType.Quad);backdrop.name="Cinematic Backdrop";backdrop.transform.position=new Vector3(0,2.05f,1.35f);backdrop.transform.rotation=Quaternion.identity;backdrop.transform.localScale=new Vector3(8,4.5f,1);SetBackdropMaterial(backdrop);UnityEngine.Object.DestroyImmediate(backdrop.GetComponent<Collider>());
            var root=new GameObject("Avatar Preview");var assembler=root.AddComponent<AvatarAssembler>();assembler.Catalog=catalog;
            var flow=new GameObject("Character Lobby Flow").AddComponent<CharacterLobbyController>();flow.Configure(catalog,assembler,cam);EditorUtility.SetDirty(flow);
            EditorSceneManager.SaveScene(scene,"Assets/_Project/Scenes/CharacterLobby.unity");
        }
        static void Light(string name,Vector3 position,Vector3 rotation,float intensity,Color color){var go=new GameObject(name,typeof(Light));go.transform.position=position;go.transform.rotation=Quaternion.Euler(rotation);var l=go.GetComponent<Light>();l.type=LightType.Directional;l.intensity=intensity;l.color=color;l.shadows=LightShadows.Soft;}
        static void SetMaterial(GameObject go,Color color){var shader=Shader.Find("Universal Render Pipeline/Lit");var m=new Material(shader){color=color};go.GetComponent<Renderer>().sharedMaterial=m;}
        static void SetBackdropMaterial(GameObject go)
        {
            var texture=AssetDatabase.LoadAssetAtPath<Texture2D>("Assets/_Project/Art/UI/CharacterLobbyBackdrop.png");
            var shader=Shader.Find("Universal Render Pipeline/Unlit");
            var material=new Material(shader){name="Character Lobby Backdrop"};
            material.SetTexture("_BaseMap",texture);material.SetColor("_BaseColor",new Color(.56f,.58f,.62f,1));
            go.GetComponent<Renderer>().sharedMaterial=material;
        }
        static GameObject CloneBody(string sourcePath,string targetPath)
        {
            EnsureFolder("Assets/_Project/Prefabs"); EnsureFolder("Assets/_Project/Prefabs/Avatar");
            var existing=AssetDatabase.LoadAssetAtPath<GameObject>(targetPath);
            if(existing)return existing;
            var source=AssetDatabase.LoadAssetAtPath<GameObject>(sourcePath);var instance=PrefabUtility.InstantiatePrefab(source) as GameObject;
            foreach(var t in instance.GetComponentsInChildren<Transform>(true)) GameObjectUtility.RemoveMonoBehavioursWithMissingScript(t.gameObject);
            var result=PrefabUtility.SaveAsPrefabAsset(instance,targetPath);UnityEngine.Object.DestroyImmediate(instance);return result;
        }
        static AvatarPaletteColor[] BuildPalette(){Color[] c={new(1,.8f,.69f),new(.73f,.48f,.34f),new(.42f,.23f,.16f),new(.18f,.12f,.1f),new(.95f,.78f,.55f),new(.12f,.08f,.06f),new(.35f,.18f,.08f),new(.12f,.28f,.45f),new(.2f,.45f,.28f),new(.55f,.18f,.22f),new(.9f,.35f,.45f),new(.1f,.18f,.38f),new(.7f,.12f,.18f),new(.12f,.42f,.48f),new(.15f,.15f,.18f),Color.white};return c.Select((x,i)=>new AvatarPaletteColor{id=(byte)(i+1),color=x}).ToArray();}
        static bool TryClassify(string path,out AvatarPartCategory c,out AvatarGender g){g=path.Contains("/Assets/M/")?AvatarGender.Male:path.Contains("/Assets/F/")?AvatarGender.Female:AvatarGender.Both;string n=Path.GetFileName(path);if(n.StartsWith("Head."))c=AvatarPartCategory.Head;else if(n.StartsWith("Top."))c=AvatarPartCategory.Top;else if(n.StartsWith("Bot."))c=AvatarPartCategory.Bottom;else if(n.StartsWith("Outfit."))c=AvatarPartCategory.Outfit;else if(n.StartsWith("Hairstyle."))c=AvatarPartCategory.Hair;else if(n.StartsWith("Hat."))c=AvatarPartCategory.Hat;else if(n.StartsWith("Glasses."))c=AvatarPartCategory.Glasses;else if(n.StartsWith("Shoes."))c=AvatarPartCategory.Shoes;else{c=default;return false;}return true;}
        static T[] ReadRefs<T>(SerializedProperty p) where T:UnityEngine.Object{if(p==null)return Array.Empty<T>();var a=new List<T>();for(int i=0;i<p.arraySize;i++)if(p.GetArrayElementAtIndex(i).objectReferenceValue is T x)a.Add(x);return a.ToArray();}
        static int[] ReadInts(SerializedProperty p){if(p==null)return Array.Empty<int>();var a=new int[p.arraySize];for(int i=0;i<a.Length;i++)a[i]=p.GetArrayElementAtIndex(i).intValue;return a;}
        static string VisualName(AvatarItemDefinition d)=>d.objectPrefabs.FirstOrDefault()?.name??d.meshes.FirstOrDefault()?.name??d.displayName;
        static HairGroup HairFromName(string n){n=n.ToLowerInvariant();if(n.Contains("buzz")||n.Contains("afro"))return HairGroup.Buzzcut;if(n.Contains("short")||n.Contains("bangs"))return HairGroup.Short;if(n.Contains("medium")||n.Contains("curly"))return HairGroup.Medium;if(n.Contains("long"))return HairGroup.Long;if(n.Contains("tied"))return HairGroup.Tied;return HairGroup.None;}
        static int StableId(string guid)=>unchecked((int)(Convert.ToUInt32(guid[..8],16)&0x7fffffff));
        static string Sanitize(string path)=>path.Replace('/','_').Replace('\\','_').Replace(".asset","");
        static void EnsureFolder(string path){if(AssetDatabase.IsValidFolder(path))return;string parent=Path.GetDirectoryName(path).Replace('\\','/');EnsureFolder(parent);AssetDatabase.CreateFolder(parent,Path.GetFileName(path));}
    }
}
#endif
