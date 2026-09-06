using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 대상 발밑의 부드러운 링. **로컬 오브젝트라 본인 화면에만 보인다** — 네트워크로 동기화하지
    /// 않는다(다른 사람 화면에 남의 조준선이 뜨면 안 된다).
    ///
    /// 포털과 부스 오브젝트가 같은 링을 쓰도록 여기로 옮겼다.
    /// </summary>
    public sealed class InteractRing
    {
        static Texture2D s_tex;

        GameObject _go;
        Material _mat;

        public void Show(Vector3 groundPos, float radius)
        {
            if (_go == null) Create();
            _go.SetActive(true);
            _go.transform.position = groundPos;
            float pulse = 1f + 0.06f * Mathf.Sin(Time.time * 4.2f);
            _go.transform.localScale = new Vector3(radius * 2f * pulse, 1f, radius * 2f * pulse);
        }

        public void Hide()
        {
            if (_go != null) _go.SetActive(false);
        }

        public void Dispose()
        {
            if (_go != null) Object.Destroy(_go);
            if (_mat != null) Object.Destroy(_mat);
            _go = null;
            _mat = null;
        }

        void Create()
        {
            _go = GameObject.CreatePrimitive(PrimitiveType.Quad);
            Object.Destroy(_go.GetComponent<Collider>());
            _go.name = "InteractHighlight (local)";
            _go.transform.rotation = Quaternion.Euler(90f, 0f, 0f);
            _mat = new Material(Shader.Find("Mobile/Particles/Additive")) { mainTexture = Texture() };
            var r = _go.GetComponent<Renderer>();
            r.sharedMaterial = _mat;
            r.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
        }

        static Texture2D Texture()
        {
            if (s_tex != null) return s_tex;
            const int S = 128;
            s_tex = new Texture2D(S, S, TextureFormat.RGBA32, false);
            for (int y = 0; y < S; y++)
            for (int x = 0; x < S; x++)
            {
                float d = Vector2.Distance(new Vector2(x, y), new Vector2(S / 2f, S / 2f)) / (S / 2f);
                // 가장자리 링 + 안쪽 은은한 채움
                float ring = Mathf.Exp(-Mathf.Pow((d - 0.82f) / 0.08f, 2f));
                float fill = d < 0.82f ? 0.10f * (1f - d) : 0f;
                float a = Mathf.Clamp01(ring * 0.85f + fill);
                s_tex.SetPixel(x, y, new Color(1f, 0.82f, 0.35f, 1f) * a);
            }
            s_tex.Apply();
            return s_tex;
        }
    }
}
