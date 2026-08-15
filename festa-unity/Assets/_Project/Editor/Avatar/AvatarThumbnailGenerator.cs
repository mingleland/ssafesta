#if UNITY_EDITOR
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Festa.Avatar;
using UnityEditor;
using UnityEngine;

namespace Festa.Editor.Avatar
{
    public static class AvatarThumbnailGenerator
    {
        const string Output = "Assets/_Project/Art/UI/AvatarThumbnails";
        const int PreviewLayer = 30;
        static Queue<AvatarItemDefinition> s_queue;
        static AvatarCatalog s_catalog;

        [MenuItem("Festa/Avatar/Generate Item Thumbnails")]
        public static void Generate()
        {
            EnsureFolder(Output);
            s_catalog=AssetDatabase.LoadAssetAtPath<AvatarCatalog>("Assets/_Project/ScriptableObjects/Avatar/AvatarCatalog.asset");
            s_queue=new Queue<AvatarItemDefinition>(AssetDatabase.FindAssets("t:AvatarItemDefinition",new[]{"Assets/_Project/ScriptableObjects/Avatar"})
                .Select(g=>AssetDatabase.LoadAssetAtPath<AvatarItemDefinition>(AssetDatabase.GUIDToAssetPath(g))).Where(x=>x));
            EditorApplication.update-=Tick;EditorApplication.update+=Tick;
            Debug.Log($"[AvatarThumbnailGenerator] {s_queue.Count}개 실제 조립 외형 렌더를 시작합니다.");
        }

        static void Tick()
        {
            if(s_queue==null||s_queue.Count==0)
            {
                EditorApplication.update-=Tick;AssetDatabase.SaveAssets();AssetDatabase.Refresh();
                Debug.Log("[AvatarThumbnailGenerator] 완료: 실제 조립 외형 썸네일 생성 및 Catalog 연결");return;
            }
            RenderAndSave(s_queue.Dequeue());
        }

        static void RenderAndSave(AvatarItemDefinition definition)
        {
            var gender=definition.gender==AvatarGender.Male?AvatarGender.Male:AvatarGender.Female;
            var config=s_catalog.CreateDefault(gender);config.SetItem(definition.category,definition.category==AvatarPartCategory.Hat?definition.familyId:definition.itemId);
            if(definition.category==AvatarPartCategory.Outfit){config.topId=0;config.bottomId=0;}
            if(definition.category==AvatarPartCategory.Top){config.outfitId=0;config.bottomId=s_catalog.Default(AvatarPartCategory.Bottom,gender)?.itemId??0;}
            if(definition.category==AvatarPartCategory.Bottom){config.outfitId=0;config.topId=s_catalog.Default(AvatarPartCategory.Top,gender)?.itemId??0;}

            var root=new GameObject("Avatar Thumbnail Preview");root.transform.rotation=Quaternion.Euler(0,180,0);
            var assembler=root.AddComponent<AvatarAssembler>();assembler.Catalog=s_catalog;assembler.Apply(config);
            var animator=root.GetComponentInChildren<Animator>();if(animator){animator.Rebind();animator.Update(.2f);}
            foreach(var transform in root.GetComponentsInChildren<Transform>(true))transform.gameObject.layer=PreviewLayer;

            float centerY,height;
            switch(definition.category)
            {
                case AvatarPartCategory.Head:case AvatarPartCategory.Hair:case AvatarPartCategory.Hat:case AvatarPartCategory.Glasses:centerY=1.5f;height=.52f;break;
                case AvatarPartCategory.Top:case AvatarPartCategory.Outfit:centerY=1.05f;height=1.3f;break;
                case AvatarPartCategory.Bottom:centerY=.55f;height=.9f;break;
                default:centerY=.15f;height=.46f;break;
            }
            const float fov=24f;float distance=height/(2*Mathf.Tan(fov*.5f*Mathf.Deg2Rad));
            var cameraGo=new GameObject("Thumbnail Camera");var camera=cameraGo.AddComponent<Camera>();camera.cullingMask=1<<PreviewLayer;camera.clearFlags=CameraClearFlags.SolidColor;camera.backgroundColor=new Color(.025f,.06f,.09f,1);camera.fieldOfView=fov;camera.transform.position=new Vector3(0,centerY,-distance);camera.transform.LookAt(new Vector3(0,centerY,0));
            var lightGo=new GameObject("Thumbnail Key");lightGo.layer=PreviewLayer;var light=lightGo.AddComponent<Light>();light.type=LightType.Directional;light.cullingMask=1<<PreviewLayer;light.intensity=1.5f;light.color=new Color(1,.9f,.82f);light.transform.rotation=Quaternion.Euler(35,-25,0);
            var rimGo=new GameObject("Thumbnail Rim");rimGo.layer=PreviewLayer;var rim=rimGo.AddComponent<Light>();rim.type=LightType.Directional;rim.cullingMask=1<<PreviewLayer;rim.intensity=.9f;rim.color=new Color(.35f,.65f,1);rim.transform.rotation=Quaternion.Euler(135,35,0);

            var rt=RenderTexture.GetTemporary(192,192,24,RenderTextureFormat.ARGB32,RenderTextureReadWrite.sRGB);camera.targetTexture=rt;camera.Render();
            var previous=RenderTexture.active;RenderTexture.active=rt;var texture=new Texture2D(192,192,TextureFormat.RGBA32,false);texture.ReadPixels(new Rect(0,0,192,192),0,0);texture.Apply();RenderTexture.active=previous;RenderTexture.ReleaseTemporary(rt);
            Object.DestroyImmediate(cameraGo);Object.DestroyImmediate(lightGo);Object.DestroyImmediate(rimGo);Object.DestroyImmediate(root);

            string path=$"{Output}/{definition.itemId}.png";File.WriteAllBytes(path,texture.EncodeToPNG());Object.DestroyImmediate(texture);AssetDatabase.ImportAsset(path,ImportAssetOptions.ForceSynchronousImport);
            if(AssetImporter.GetAtPath(path) is TextureImporter importer){importer.textureType=TextureImporterType.Sprite;importer.spriteImportMode=SpriteImportMode.Single;importer.alphaIsTransparency=false;importer.mipmapEnabled=false;importer.SaveAndReimport();}
            definition.thumbnail=AssetDatabase.LoadAssetAtPath<Sprite>(path);EditorUtility.SetDirty(definition);
        }

        static void EnsureFolder(string path){if(AssetDatabase.IsValidFolder(path))return;string parent=Path.GetDirectoryName(path).Replace('\\','/');EnsureFolder(parent);AssetDatabase.CreateFolder(parent,Path.GetFileName(path));}
    }
}
#endif
