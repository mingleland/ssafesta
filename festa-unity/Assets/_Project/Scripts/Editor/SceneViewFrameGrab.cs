using UnityEditor;
using UnityEngine;
using UnityEngine.Rendering;

namespace Festa.EditorTools
{
    /// <summary>
    /// 씬 뷰가 실제로 그린 프레임을 렌더 파이프라인 콜백에서 가로채 PNG 로 남긴다.
    ///
    /// 배경: 씬 뷰만 하얗게 뜨는 증상을 쫓는데, 같은 위치·회전·FOV·클리핑으로 만든
    /// 일반 카메라는 멀쩡하게 렌더된다. Camera.Render() 는 씬 뷰의 Fx 설정과 내부 렌더
    /// 경로를 타지 않기 때문에, 그 방식으로는 증상을 볼 수 없다.
    /// 그래서 endCameraRendering 시점에 씬 뷰 카메라의 결과를 직접 복사한다.
    /// </summary>
    [InitializeOnLoad]
    public static class SceneViewFrameGrab
    {
        static bool s_armed;
        static string s_path;

        static SceneViewFrameGrab()
        {
            RenderPipelineManager.endCameraRendering -= OnEndCamera;
            RenderPipelineManager.endCameraRendering += OnEndCamera;
        }

        /// <summary>다음 씬 뷰 프레임 한 장을 잡아 지정 경로에 저장한다.</summary>
        public static void Arm(string path)
        {
            s_path = path;
            s_armed = true;
            var sv = SceneView.lastActiveSceneView;
            if (sv != null) sv.Repaint();
        }

        public static bool Pending { get { return s_armed; } }

        static void OnEndCamera(ScriptableRenderContext ctx, Camera cam)
        {
            if (!s_armed || cam == null) return;
            if (cam.cameraType != CameraType.SceneView) return;

            s_armed = false;

            var src = RenderTexture.active;
            int w = cam.pixelWidth, h = cam.pixelHeight;
            if (w <= 0 || h <= 0) return;

            var tmp = RenderTexture.GetTemporary(w, h, 0, RenderTextureFormat.ARGB32);
            // 현재 활성 타깃(씬 뷰가 방금 그린 것)을 그대로 복사한다.
            if (src != null) Graphics.Blit(src, tmp);
            else return;

            var prev = RenderTexture.active;
            RenderTexture.active = tmp;
            var tex = new Texture2D(w, h, TextureFormat.RGB24, false);
            tex.ReadPixels(new Rect(0, 0, w, h), 0, 0);
            tex.Apply();
            RenderTexture.active = prev;

            System.IO.File.WriteAllBytes(s_path, tex.EncodeToPNG());
            Object.DestroyImmediate(tex);
            RenderTexture.ReleaseTemporary(tmp);

            Debug.Log("[SceneViewFrameGrab] 저장 " + w + "x" + h + " → " + s_path);
        }
    }
}
