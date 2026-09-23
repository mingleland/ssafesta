// 1번 오락기의 고양이 함정 플랫폼 게임을 독립 2D 카메라와 RenderTexture로 실행한다.
using System.Collections.Generic;
using Festa.World;
using UnityEngine;

namespace Festa.Content.Arcade
{
    [DisallowMultipleComponent]
    public sealed class TrickyCatMiniGame : MonoBehaviour
    {
        const float WorldY = 10000f;
        const int RenderWidth = 640;
        const int RenderHeight = 360;

        readonly List<GameObject> _runtimeObjects = new();
        readonly List<Sprite> _runtimeSprites = new();
        Camera _camera;
        RenderTexture _renderTexture;
        Material _screenMaterial;
        Rigidbody2D _playerBody;
        SpriteRenderer _playerRenderer;
        Sprite[] _idle;
        Sprite[] _run;
        Sprite[] _jump;
        Sprite[] _fall;
        float _checkpointX = -8f;
        int _lives = 3;
        bool _active;
        bool _cleared;
        float _nextTrapAt;
        int _animFrame;
        float _nextAnim;

        void Awake()
        {
            CreateScreenTarget();
            BuildGame();
            SetGameVisible(false);
            enabled = false;
        }

        public bool Begin()
        {
            if (_camera == null || _screenMaterial == null || _playerBody == null) return false;
            SetGameVisible(true);
            ResetStage(false);
            _active = true;
            enabled = true;
            return true;
        }

        void Update()
        {
            if (!_active) return;
            if (!InteractionFocusCamera.IsFocused) { _active = false; SetGameVisible(false); enabled = false; return; }
            if (_cleared)
            {
                if (Input.GetKeyDown(KeyCode.R) || Input.GetKeyDown(KeyCode.Space)) ResetStage(false);
                return;
            }

            float move = 0f;
            if (Input.GetKey(KeyCode.A) || Input.GetKey(KeyCode.LeftArrow)) move--;
            if (Input.GetKey(KeyCode.D) || Input.GetKey(KeyCode.RightArrow)) move++;
            _playerBody.linearVelocity = new Vector2(move * 6.5f, _playerBody.linearVelocity.y);
            bool grounded = Physics2D.Raycast(_playerBody.position + Vector2.down * 0.72f, Vector2.down, 0.12f, Physics2D.AllLayers).collider != null;
            if (grounded && (Input.GetKeyDown(KeyCode.Space) || Input.GetKeyDown(KeyCode.W) || Input.GetKeyDown(KeyCode.UpArrow)))
                _playerBody.linearVelocity = new Vector2(_playerBody.linearVelocity.x, 10.5f);

            Animate(move, grounded);
            float camX = Mathf.Clamp(_playerBody.position.x, -4f, 22f);
            _camera.transform.position = new Vector3(camX, WorldY + 1.8f, -10f);

            if (_playerBody.position.y < WorldY - 4f) Hit();
            if (_playerBody.position.x > 8f && _checkpointX < 8f) _checkpointX = 8f;
            if (_playerBody.position.x > 13f && Time.time >= _nextTrapAt)
            {
                _nextTrapAt = float.PositiveInfinity;
                CreateSpikes(15f, 4);
            }
            if (_playerBody.position.x > 27f) { _cleared = true; _playerBody.linearVelocity = Vector2.zero; }
        }

        void Animate(float move, bool grounded)
        {
            Sprite[] frames = !grounded ? (_playerBody.linearVelocity.y >= 0f ? _jump : _fall) : Mathf.Abs(move) > 0.1f ? _run : _idle;
            if (frames == null || frames.Length == 0) return;
            if (Time.unscaledTime >= _nextAnim) { _nextAnim = Time.unscaledTime + 0.09f; _animFrame = (_animFrame + 1) % frames.Length; }
            _playerRenderer.sprite = frames[_animFrame % frames.Length];
            _playerRenderer.flipX = move < 0f;
        }

        void BuildGame()
        {
            var cameraGo = Track(new GameObject("@TrickyCatCamera"));
            _camera = cameraGo.AddComponent<Camera>();
            _camera.orthographic = true;
            _camera.orthographicSize = 4.5f;
            _camera.clearFlags = CameraClearFlags.SolidColor;
            _camera.backgroundColor = new Color(0.12f, 0.25f, 0.48f);
            _camera.transform.position = new Vector3(-4f, WorldY + 1.8f, -10f);
            _renderTexture = new RenderTexture(RenderWidth, RenderHeight, 16, RenderTextureFormat.ARGB32) { name = "TrickyCat_640x360", filterMode = FilterMode.Bilinear };
            _renderTexture.Create();
            _camera.targetTexture = _renderTexture;

            _idle = LoadSprites("Arcade/TrickyCat/Sprites/01_Idle");
            _run = LoadSprites("Arcade/TrickyCat/Sprites/02_Run");
            _jump = LoadSprites("Arcade/TrickyCat/Sprites/03_Jump/01_Up");
            _fall = LoadSprites("Arcade/TrickyCat/Sprites/03_Jump/02_Fall");

            var player = Track(new GameObject("CatHero"));
            player.transform.position = new Vector3(-8f, WorldY + 0.4f, 0f);
            _playerRenderer = player.AddComponent<SpriteRenderer>();
            _playerRenderer.sprite = _idle.Length > 0 ? _idle[0] : null;
            _playerRenderer.sortingOrder = 20;
            _playerBody = player.AddComponent<Rigidbody2D>();
            _playerBody.gravityScale = 2.7f;
            _playerBody.freezeRotation = true;
            var capsule = player.AddComponent<CapsuleCollider2D>();
            capsule.size = new Vector2(0.85f, 1.35f);

            CreatePlatform(-9f, -1.1f, 8f, 1f);
            CreatePlatform(1f, -1.1f, 6f, 1f);
            CreatePlatform(9f, -1.1f, 8f, 1f);
            CreatePlatform(20f, -1.1f, 17f, 1f);
            CreatePlatform(3f, 1.1f, 3f, 0.45f);
            CreatePlatform(18f, 1.8f, 3f, 0.45f);
            CreateSpikes(5.5f, 3);
            CreateFinish(28f);
        }

        void CreateScreenTarget()
        {
            var power = GetComponent<ArcadeScreenPower>();
            foreach (var renderer in GetComponentsInChildren<MeshRenderer>(true))
            {
                var materials = renderer.sharedMaterials;
                for (int i = 0; i < materials.Length; i++)
                {
                    if (materials[i] == null || power == null || !power.IsScreenMaterial(materials[i])) continue;
                    _screenMaterial = new Material(materials[i]) { name = "TrickyCatScreen_Runtime" };
                    _screenMaterial.SetTextureScale("_BaseMap", Vector2.one);
                    _screenMaterial.SetTextureOffset("_BaseMap", Vector2.zero);
                    _screenMaterial.mainTextureScale = Vector2.one;
                    _screenMaterial.mainTextureOffset = Vector2.zero;
                    materials[i] = _screenMaterial;
                    renderer.sharedMaterials = materials;
                    return;
                }
            }
        }

        void SetGameVisible(bool visible)
        {
            if (_camera != null) _camera.enabled = visible;
            if (_screenMaterial != null) _screenMaterial.SetTexture("_BaseMap", visible ? _renderTexture : null);
        }

        void ResetStage(bool keepCheckpoint)
        {
            if (!keepCheckpoint) { _checkpointX = -8f; _lives = 3; }
            _cleared = false;
            _nextTrapAt = 0f;
            _playerBody.position = new Vector2(_checkpointX, WorldY + 0.3f);
            _playerBody.linearVelocity = Vector2.zero;
        }

        void Hit()
        {
            _lives--;
            ResetStage(_lives > 0);
        }

        void CreatePlatform(float x, float y, float width, float height)
        {
            var go = Track(new GameObject("Platform"));
            go.transform.position = new Vector3(x + width * 0.5f, WorldY + y, 0f);
            var renderer = go.AddComponent<SpriteRenderer>();
            renderer.sprite = SolidSprite(new Color(0.25f, 0.62f, 0.26f), 16, 16);
            renderer.drawMode = SpriteDrawMode.Tiled;
            renderer.size = new Vector2(width, height);
            var collider = go.AddComponent<BoxCollider2D>();
            collider.size = new Vector2(width, height);
        }

        void CreateSpikes(float x, int count)
        {
            for (int i = 0; i < count; i++)
            {
                var go = Track(new GameObject("Spike"));
                go.transform.position = new Vector3(x + i * 0.6f, WorldY - 0.25f, 0f);
                var renderer = go.AddComponent<SpriteRenderer>();
                renderer.sprite = SolidSprite(new Color(0.95f, 0.15f, 0.18f), 8, 14);
                var collider = go.AddComponent<BoxCollider2D>(); collider.isTrigger = true;
                go.AddComponent<TrickyCatHazard>().Owner = this;
            }
        }

        void CreateFinish(float x)
        {
            var pole = Track(new GameObject("Finish")); pole.transform.position = new Vector3(x, WorldY + 1f, 0f);
            var sr = pole.AddComponent<SpriteRenderer>(); sr.sprite = SolidSprite(new Color(1f, 0.85f, 0.2f), 6, 48); sr.size = new Vector2(0.18f, 3f); sr.drawMode = SpriteDrawMode.Sliced;
        }

        Sprite[] LoadSprites(string path)
        {
            var textures = Resources.LoadAll<Texture2D>(path);
            System.Array.Sort(textures, (a,b) => string.CompareOrdinal(a.name,b.name));
            var sprites = new Sprite[textures.Length];
            for (int i=0;i<textures.Length;i++)
            {
                var pixels = textures[i].GetPixels32();
                int minX=textures[i].width,minY=textures[i].height,maxX=-1,maxY=-1;
                for(int y=0;y<textures[i].height;y++) for(int x=0;x<textures[i].width;x++)
                    if(pixels[y*textures[i].width+x].a>20){minX=Mathf.Min(minX,x);minY=Mathf.Min(minY,y);maxX=Mathf.Max(maxX,x);maxY=Mathf.Max(maxY,y);}
                var rect=maxX>=minX ? new Rect(minX,minY,maxX-minX+1,maxY-minY+1) : new Rect(0,0,textures[i].width,textures[i].height);
                // 캐릭터 실제 높이를 약 1.45 world unit로 맞춘다.
                float ppu=rect.height/1.45f;
                sprites[i]=Sprite.Create(textures[i],rect,new Vector2(.5f,.04f),ppu);
                _runtimeSprites.Add(sprites[i]);
            }
            return sprites;
        }

        Sprite SolidSprite(Color color, int width, int height)
        {
            var texture = new Texture2D(width,height,TextureFormat.RGBA32,false); var colors=new Color[width*height]; for(int i=0;i<colors.Length;i++) colors[i]=color; texture.SetPixels(colors); texture.Apply();
            var sprite=Sprite.Create(texture,new Rect(0,0,width,height),new Vector2(.5f,.5f),16f); _runtimeSprites.Add(sprite); return sprite;
        }

        GameObject Track(GameObject go)
        {
            // 오락기 모델은 약 13배 스케일이라 자식으로 붙이면 게임 월드와 콜라이더도 같이 커진다.
            // 전용 카메라가 보는 격리 좌표계에 루트 오브젝트로 두고 수명만 목록으로 관리한다.
            go.transform.SetParent(null, false);
            _runtimeObjects.Add(go);
            return go;
        }
        internal void HazardHit() { if (_active) Hit(); }

        void OnDestroy()
        {
            foreach (var runtimeObject in _runtimeObjects)
                if (runtimeObject != null) Destroy(runtimeObject);
            if (_renderTexture != null) { _renderTexture.Release(); Destroy(_renderTexture); }
            if (_screenMaterial != null) Destroy(_screenMaterial);
            foreach (var sprite in _runtimeSprites) if (sprite != null) { var texture=sprite.texture; Destroy(sprite); if (texture != null && !texture.name.StartsWith("__Cat_")) Destroy(texture); }
        }
    }

    sealed class TrickyCatHazard : MonoBehaviour
    {
        public TrickyCatMiniGame Owner;
        void OnTriggerEnter2D(Collider2D other) { if (other.GetComponent<Rigidbody2D>() != null) Owner?.HazardHit(); }
    }
}
