// 2번 오락기에서 2D Mega Pack 에셋으로 웨이브형 우주 슈팅 게임을 실행한다.
using System.Collections;
using System.Collections.Generic;
using Festa.World;
using TMPro;
using UnityEngine;
using UnityEngine.UI;

namespace Festa.Content.Arcade
{
    [DisallowMultipleComponent]
    public sealed class MegaPackSpaceShooterMiniGame : MonoBehaviour
    {
        const float WorldY = 10100f;
        const string ResourceRoot = "Arcade/MegaPackShooter/";

        sealed class Actor
        {
            public GameObject Go;
            public Vector2 Velocity;
            public float Radius;
            public int Hp;
            public float Phase;
            public bool Boss;
        }

        readonly List<GameObject> _runtime = new();
        readonly List<Actor> _enemies = new();
        readonly List<Actor> _playerBullets = new();
        readonly List<Actor> _enemyBullets = new();
        readonly List<GameObject> _previewActors = new();
        Camera _camera;
        RenderTexture _target;
        Material _screenMaterial;
        Texture _originalTexture;
        Sprite _playerSprite;
        Sprite[] _playerFrames;
        Sprite _enemySprite;
        Sprite _playerBulletSprite;
        Sprite _enemyBulletSprite;
        Sprite[] _explosionSprites;
        Sprite _solidSprite;
        GameObject _player;
        SpriteRenderer _playerRenderer;
        TextMeshPro _scoreText;
        TextMeshPro _waveText;
        TextMeshPro _livesText;
        TextMeshPro _messageText;
        TextMeshPro _previewRankingText;
        GameObject _previewRankingPanel;
        AudioSource _audio;
        AudioClip _shotClip;
        AudioClip _explosionClip;
        AudioClip _bonusClip;
        Canvas _playCanvas;
        Vector2 _playerPosition;
        int _score;
        int _lives;
        int _wave;
        int _combo;
        float _nextShot;
        float _nextWave;
        float _invincibleUntil;
        float _nextPlayerFrame;
        int _playerFrameIndex;
        bool _active;
        bool _ended;

        void Awake()
        {
            LoadAssets();
            CreateScreenTarget();
            BuildGame();
            PreparePreview();
            SetVisible(false);
            InteractionFocusCamera.Released += HandleReleased;
            enabled = false;
        }

        public bool Begin()
        {
            if (_camera == null || _screenMaterial == null || _playerSprite == null || _enemySprite == null) return false;
            NewGame();
            SetVisible(true);
            _active = true;
            enabled = true;
            return true;
        }

        void Update()
        {
            if (!_active) return;
            if (!InteractionFocusCamera.IsFocused) { HandleReleased(); return; }
            if (_ended)
            {
                if (Input.GetKeyDown(KeyCode.Space) || Input.GetKeyDown(KeyCode.R)) NewGame();
                return;
            }

            float horizontal = (Input.GetKey(KeyCode.D) || Input.GetKey(KeyCode.RightArrow) ? 1f : 0f) - (Input.GetKey(KeyCode.A) || Input.GetKey(KeyCode.LeftArrow) ? 1f : 0f);
            float vertical = (Input.GetKey(KeyCode.W) || Input.GetKey(KeyCode.UpArrow) ? 1f : 0f) - (Input.GetKey(KeyCode.S) || Input.GetKey(KeyCode.DownArrow) ? 1f : 0f);
            Vector2 input = Vector2.ClampMagnitude(new Vector2(horizontal, vertical), 1f);
            _playerPosition += input * (5.8f * Time.deltaTime);
            _playerPosition.x = Mathf.Clamp(_playerPosition.x, -5.2f, 4.45f);
            _playerPosition.y = Mathf.Clamp(_playerPosition.y, -3.35f, 2.35f);
            _player.transform.position = ToWorld(_playerPosition);
            _player.transform.rotation = Quaternion.Euler(0f, 0f, -horizontal * 9f);
            _playerRenderer.color = Time.time < _invincibleUntil && Mathf.FloorToInt(Time.time * 12f) % 2 == 0 ? new Color(1f, 1f, 1f, 0.25f) : Color.white;
            AnimatePlayer();

            if ((Input.GetKey(KeyCode.Space) || Input.GetKey(KeyCode.J)) && Time.time >= _nextShot) Fire();
            if (_enemies.Count == 0 && Time.time >= _nextWave) SpawnWave();
            StepActors();
            ResolveCollisions();
        }

        void LoadAssets()
        {
            _enemySprite = Resources.Load<Sprite>(ResourceRoot + "Enemy");
            // 플레이어는 Mega Pack 우주비행사 시트로 분리해 적 우주선과 실루엣부터 다르게 보이게 한다.
            var astronautAssets = Resources.LoadAll<Sprite>(ResourceRoot + "Astronaut");
            var astronautFrames = new List<Sprite>();
            foreach (var sprite in astronautAssets)
            {
                if (sprite != null && (sprite.name == "AstronautIdle" || sprite.name.StartsWith("AstronautWalk_")))
                    astronautFrames.Add(sprite);
            }
            _playerFrames = astronautFrames.ToArray();
            _playerSprite = _playerFrames != null && _playerFrames.Length > 0 ? _playerFrames[0] : null;
            _playerBulletSprite = Resources.Load<Sprite>(ResourceRoot + "PlayerBullet");
            _enemyBulletSprite = Resources.Load<Sprite>(ResourceRoot + "EnemyBullet");
            _explosionSprites = Resources.LoadAll<Sprite>(ResourceRoot + "Explosion");
            _shotClip = Resources.Load<AudioClip>(ResourceRoot + "Shot");
            _explosionClip = Resources.Load<AudioClip>(ResourceRoot + "Explosion");
            _bonusClip = Resources.Load<AudioClip>(ResourceRoot + "Bonus");
        }

        void BuildGame()
        {
            _solidSprite = CreateSolidSprite();
            var cameraObject = Track(new GameObject("@MegaShooterCamera"));
            _camera = cameraObject.AddComponent<Camera>();
            _camera.orthographic = true;
            _camera.orthographicSize = 4.5f;
            _camera.aspect = 16f / 9f;
            _camera.clearFlags = CameraClearFlags.SolidColor;
            _camera.backgroundColor = new Color(0.018f, 0.025f, 0.09f);
            _camera.transform.position = new Vector3(0f, WorldY, -10f);
            _target = new RenderTexture(640, 360, 16, RenderTextureFormat.ARGB32) { name = "MegaShooter_640x360", filterMode = FilterMode.Bilinear };
            _target.Create();
            _camera.targetTexture = _target;
            BuildPlayOverlay();

            for (int i = 0; i < 55; i++)
            {
                var star = SpriteObject("Star", _solidSprite, new Color(0.55f, 0.78f, 1f, Random.Range(0.25f, 0.8f)), new Vector2(Random.Range(-7f, 5.1f), Random.Range(-4.1f, 4.1f)), -3);
                float size = Random.Range(0.018f, 0.055f);
                star.transform.localScale = new Vector3(size, size, 1f);
            }
            Panel(new Vector2(6.1f, 0f), new Vector2(1.65f, 8.2f), new Color(0.06f, 0.11f, 0.24f), -2);
            Text("STAR\nDEFENDER", new Vector2(6.1f, 3.25f), 4.2f, new Color(0.35f, 0.95f, 1f), out _);
            Text("SCORE", new Vector2(6.1f, 2.05f), 2.7f, Color.white, out _);
            Text("0", new Vector2(6.1f, 1.55f), 4.2f, new Color(1f, 0.8f, 0.2f), out _scoreText);
            Text("WAVE 1", new Vector2(6.1f, 0.45f), 3.1f, new Color(0.75f, 0.9f, 1f), out _waveText);
            Text("LIVES 3", new Vector2(6.1f, -0.35f), 3.1f, new Color(1f, 0.45f, 0.52f), out _livesText);
            Text("WASD MOVE\nSPACE FIRE", new Vector2(6.1f, -2.9f), 2.15f, new Color(0.7f, 0.78f, 0.92f), out _);
            Text(string.Empty, new Vector2(-0.5f, 0f), 5.2f, Color.white, out _messageText);
            _previewRankingPanel = Panel(new Vector2(-0.5f, -0.55f), new Vector2(5.9f, 3.75f), new Color(0.01f, 0.02f, 0.075f, 0.9f), 25);
            Text(string.Empty, new Vector2(-0.5f, -0.55f), 3.0f, Color.white, out _previewRankingText);
            _previewRankingText.sortingOrder = 26;
            _previewRankingText.rectTransform.sizeDelta = new Vector2(6f, 4.2f);

            _player = SpriteObject("PlayerAstronaut", _playerSprite, Color.white, new Vector2(-0.5f, -3.15f), 10);
            SetSpriteHeight(_player, _playerSprite, 1.05f);
            _playerRenderer = _player.GetComponent<SpriteRenderer>();
            CreatePreviewActor("PreviewAstronaut", _playerSprite, new Vector2(-3.8f, -2.85f), 1.05f);
            CreatePreviewActor("PreviewEnemyLeft", _enemySprite, new Vector2(-4.2f, 2.65f), 0.78f);
            CreatePreviewActor("PreviewEnemyCenter", _enemySprite, new Vector2(-0.5f, 3.0f), 0.9f);
            CreatePreviewActor("PreviewEnemyRight", _enemySprite, new Vector2(3.2f, 2.65f), 0.78f);
            _audio = Track(new GameObject("MegaShooterAudio")).AddComponent<AudioSource>();
            _audio.playOnAwake = false;
            _audio.volume = 0.42f;
        }

        void NewGame()
        {
            SetPreviewActors(false);
            _previewRankingPanel.SetActive(false);
            _previewRankingText.gameObject.SetActive(false);
            ClearActors();
            _score = 0;
            _lives = 3;
            _wave = 0;
            _combo = 0;
            _ended = false;
            _playerPosition = new Vector2(-0.5f, -3.15f);
            _invincibleUntil = Time.time + 2f;
            _playerFrameIndex = 0;
            _nextPlayerFrame = Time.time;
            _player.transform.position = ToWorld(_playerPosition);
            _player.SetActive(true);
            _messageText.text = "READY";
            StartCoroutine(ClearMessageAfter(0.7f));
            _nextWave = Time.time + 0.45f;
            UpdateHud();
        }

        void PreparePreview()
        {
            StopAllCoroutines();
            ClearActors();
            _player.SetActive(false);
            _score = 0;
            _wave = 0;
            _lives = 3;
            _scoreText.text = "0";
            _waveText.text = "ARCADE 02";
            _livesText.text = "MEGA PACK";
            _messageText.text = string.Empty;
            _messageText.color = new Color(0.35f, 0.95f, 1f);
            _previewRankingPanel.SetActive(true);
            _previewRankingText.gameObject.SetActive(true);
            _previewRankingText.text = "STAR DEFENDER\nTOP 5\n1  FESTA     28,600\n2  PILOT     21,300\n3  NOVA      17,900\n4  ARCADE    12,400\n5  GUEST      8,100\n[F] PLAY";
            SetPreviewActors(true);
        }

        void SpawnWave()
        {
            _wave++;
            int count = Mathf.Min(4 + _wave, 10);
            bool bossWave = _wave % 5 == 0;
            if (bossWave)
            {
                SpawnEnemy(new Vector2(-0.5f, 2.6f), 12 + _wave * 2, true, 0f);
                _messageText.text = "WARNING!\nBOSS WAVE";
                StartCoroutine(ClearMessageAfter(1.1f));
            }
            else
            {
                for (int i = 0; i < count; i++)
                {
                    float x = Mathf.Lerp(-4.9f, 3.9f, count == 1 ? 0.5f : i / (float)(count - 1));
                    SpawnEnemy(new Vector2(x, 3.15f + (i % 2) * 0.55f), 1 + _wave / 4, false, i * 0.65f);
                }
            }
            UpdateHud();
        }

        void SpawnEnemy(Vector2 position, int hp, bool boss, float phase)
        {
            var go = SpriteObject(boss ? "Boss" : "Enemy", _enemySprite, boss ? new Color(1f, 0.35f, 0.75f) : Color.white, position, 6);
            SetSpriteHeight(go, _enemySprite, boss ? 1.7f : 0.85f);
            _enemies.Add(new Actor { Go = go, Radius = boss ? 0.8f : 0.42f, Hp = hp, Phase = phase, Boss = boss });
        }

        void Fire()
        {
            _nextShot = Time.time + 0.145f;
            SpawnBullet(_playerBullets, _playerBulletSprite, new Color(0.3f, 1f, 0.85f), _playerPosition + Vector2.up * 0.48f, Vector2.up * 10.5f, 0.17f, 8);
            if (_shotClip != null) _audio.PlayOneShot(_shotClip, 0.28f);
        }

        void EnemyFire(Actor enemy)
        {
            Vector2 origin = Local(enemy.Go.transform.position);
            Vector2 direction = (_playerPosition - origin).normalized;
            SpawnBullet(_enemyBullets, _enemyBulletSprite ?? _playerBulletSprite, new Color(1f, 0.35f, 0.3f), origin, direction * 4.6f, 0.2f, 7);
        }

        void SpawnBullet(List<Actor> list, Sprite sprite, Color color, Vector2 position, Vector2 velocity, float radius, int order)
        {
            var go = SpriteObject("Bullet", sprite ?? _solidSprite, color, position, order);
            SetSpriteHeight(go, sprite ?? _solidSprite, 0.32f);
            list.Add(new Actor { Go = go, Velocity = velocity, Radius = radius });
        }

        void StepActors()
        {
            float dt = Time.deltaTime;
            for (int i = _playerBullets.Count - 1; i >= 0; i--) if (!MoveBullet(_playerBullets, i, dt)) continue;
            for (int i = _enemyBullets.Count - 1; i >= 0; i--) if (!MoveBullet(_enemyBullets, i, dt)) continue;
            for (int i = _enemies.Count - 1; i >= 0; i--)
            {
                var enemy = _enemies[i];
                Vector2 p = Local(enemy.Go.transform.position);
                enemy.Phase += dt * (enemy.Boss ? 1.2f : 1.7f);
                p.x += Mathf.Sin(enemy.Phase) * dt * (enemy.Boss ? 1.8f : 1.15f);
                p.y -= dt * (enemy.Boss ? 0.055f : 0.075f + _wave * 0.006f);
                enemy.Go.transform.position = ToWorld(p);
                if (Random.value < dt * (enemy.Boss ? 1.15f : 0.07f + _wave * 0.008f)) EnemyFire(enemy);
                if (p.y < -3.55f) { RemoveEnemy(i, false); DamagePlayer(); }
            }
        }

        bool MoveBullet(List<Actor> list, int index, float dt)
        {
            var bullet = list[index];
            Vector2 p = Local(bullet.Go.transform.position) + bullet.Velocity * dt;
            bullet.Go.transform.position = ToWorld(p);
            if (Mathf.Abs(p.x) > 6f || Mathf.Abs(p.y) > 4.2f)
            {
                Destroy(bullet.Go);
                list.RemoveAt(index);
                return false;
            }
            return true;
        }

        void ResolveCollisions()
        {
            for (int b = _playerBullets.Count - 1; b >= 0; b--)
            for (int e = _enemies.Count - 1; e >= 0; e--)
            {
                if (b >= _playerBullets.Count || e >= _enemies.Count) continue;
                if (Vector2.Distance(Local(_playerBullets[b].Go.transform.position), Local(_enemies[e].Go.transform.position)) > _playerBullets[b].Radius + _enemies[e].Radius) continue;
                Destroy(_playerBullets[b].Go); _playerBullets.RemoveAt(b);
                _enemies[e].Hp--;
                if (_enemies[e].Hp <= 0) RemoveEnemy(e, true);
                break;
            }
            if (Time.time < _invincibleUntil) return;
            for (int i = _enemyBullets.Count - 1; i >= 0; i--)
            {
                if (Vector2.Distance(Local(_enemyBullets[i].Go.transform.position), _playerPosition) > 0.42f) continue;
                Destroy(_enemyBullets[i].Go); _enemyBullets.RemoveAt(i); DamagePlayer(); break;
            }
        }

        void RemoveEnemy(int index, bool killed)
        {
            var enemy = _enemies[index];
            Vector2 position = Local(enemy.Go.transform.position);
            if (killed)
            {
                _combo++;
                _score += (enemy.Boss ? 2500 : 100) * Mathf.Clamp(_combo, 1, 8);
                StartCoroutine(ExplosionAt(position, enemy.Boss ? 2.2f : 1f));
                if (_explosionClip != null) _audio.PlayOneShot(_explosionClip, enemy.Boss ? 0.7f : 0.28f);
                if (enemy.Boss && _bonusClip != null) _audio.PlayOneShot(_bonusClip, 0.7f);
            }
            Destroy(enemy.Go); _enemies.RemoveAt(index);
            if (_enemies.Count == 0) _nextWave = Time.time + 0.85f;
            UpdateHud();
        }

        void DamagePlayer()
        {
            if (Time.time < _invincibleUntil || _ended) return;
            _lives--;
            _combo = 0;
            _invincibleUntil = Time.time + 1.35f;
            StartCoroutine(ExplosionAt(_playerPosition, 0.9f));
            UpdateHud();
            if (_lives <= 0)
            {
                _ended = true;
                _player.SetActive(false);
                _messageText.text = "GAME OVER\nSPACE TO RETRY";
                _messageText.color = new Color(1f, 0.4f, 0.45f);
            }
        }

        IEnumerator ExplosionAt(Vector2 position, float scale)
        {
            var go = SpriteObject("Explosion", _explosionSprites != null && _explosionSprites.Length > 0 ? _explosionSprites[0] : _solidSprite, new Color(1f, 0.72f, 0.25f), position, 15);
            go.transform.localScale = Vector3.zero;
            var renderer = go.GetComponent<SpriteRenderer>();
            float elapsed = 0f;
            while (elapsed < 0.32f && go != null)
            {
                elapsed += Time.deltaTime;
                float t = Mathf.Clamp01(elapsed / 0.32f);
                if (_explosionSprites != null && _explosionSprites.Length > 0) renderer.sprite = _explosionSprites[Mathf.Min(_explosionSprites.Length - 1, Mathf.FloorToInt(t * _explosionSprites.Length))];
                go.transform.localScale = Vector3.one * scale * (0.4f + t * 1.5f);
                renderer.color = new Color(1f, 0.8f, 0.35f, 1f - t);
                yield return null;
            }
            if (go != null) Destroy(go);
        }

        IEnumerator ClearMessageAfter(float seconds)
        {
            yield return new WaitForSeconds(seconds);
            if (!_ended) _messageText.text = string.Empty;
        }

        void UpdateHud()
        {
            _scoreText.text = _score.ToString("N0");
            _waveText.text = "WAVE " + Mathf.Max(1, _wave);
            _livesText.text = "LIVES " + _lives;
        }

        void AnimatePlayer()
        {
            if (_playerRenderer == null || _playerFrames == null || _playerFrames.Length < 2 || Time.time < _nextPlayerFrame) return;
            _playerFrameIndex = (_playerFrameIndex + 1) % _playerFrames.Length;
            _playerRenderer.sprite = _playerFrames[_playerFrameIndex];
            SetSpriteHeight(_player, _playerRenderer.sprite, 1.05f);
            _nextPlayerFrame = Time.time + 0.09f;
        }

        void ClearActors()
        {
            ClearList(_enemies); ClearList(_playerBullets); ClearList(_enemyBullets);
        }

        static void ClearList(List<Actor> list)
        {
            foreach (var actor in list) if (actor.Go != null) Destroy(actor.Go);
            list.Clear();
        }

        Sprite CreateSolidSprite()
        {
            var texture = new Texture2D(1, 1, TextureFormat.RGBA32, false) { name = "MegaShooterSolid_Runtime" };
            texture.SetPixel(0, 0, Color.white); texture.Apply();
            return Sprite.Create(texture, new Rect(0, 0, 1, 1), Vector2.one * 0.5f, 1f);
        }

        GameObject SpriteObject(string name, Sprite sprite, Color color, Vector2 position, int order)
        {
            var go = Track(new GameObject(name));
            go.transform.position = ToWorld(position);
            var renderer = go.AddComponent<SpriteRenderer>(); renderer.sprite = sprite; renderer.color = color; renderer.sortingOrder = order;
            return go;
        }

        void CreatePreviewActor(string name, Sprite sprite, Vector2 position, float height)
        {
            var actor = SpriteObject(name, sprite, Color.white, position, 5);
            SetSpriteHeight(actor, sprite, height);
            _previewActors.Add(actor);
        }

        void SetPreviewActors(bool visible)
        {
            foreach (var actor in _previewActors) if (actor != null) actor.SetActive(visible);
        }

        static void SetSpriteHeight(GameObject go, Sprite sprite, float desiredHeight)
        {
            float sourceHeight = sprite != null ? Mathf.Max(0.001f, sprite.bounds.size.y) : 1f;
            go.transform.localScale = Vector3.one * (desiredHeight / sourceHeight);
        }

        GameObject Panel(Vector2 position, Vector2 size, Color color, int order)
        {
            var go = SpriteObject("Panel", _solidSprite, color, position, order); go.transform.localScale = new Vector3(size.x, size.y, 1f);
            return go;
        }

        void BuildPlayOverlay()
        {
            var canvasObject = Track(new GameObject("@MegaShooterPlayCanvas", typeof(RectTransform), typeof(Canvas), typeof(CanvasScaler), typeof(GraphicRaycaster)));
            _playCanvas = canvasObject.GetComponent<Canvas>();
            _playCanvas.renderMode = RenderMode.ScreenSpaceOverlay;
            _playCanvas.sortingOrder = 480;
            var scaler = canvasObject.GetComponent<CanvasScaler>();
            scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
            scaler.referenceResolution = new Vector2(1920f, 1080f);
            scaler.matchWidthOrHeight = 0.5f;

            var backdrop = new GameObject("Backdrop", typeof(RectTransform), typeof(Image));
            backdrop.transform.SetParent(canvasObject.transform, false);
            var backdropRect = backdrop.GetComponent<RectTransform>();
            backdropRect.anchorMin = Vector2.zero; backdropRect.anchorMax = Vector2.one;
            backdropRect.offsetMin = Vector2.zero; backdropRect.offsetMax = Vector2.zero;
            backdrop.GetComponent<Image>().color = new Color(0.005f, 0.008f, 0.025f, 0.98f);

            var screen = new GameObject("GameScreen", typeof(RectTransform), typeof(RawImage), typeof(AspectRatioFitter));
            screen.transform.SetParent(canvasObject.transform, false);
            var screenRect = screen.GetComponent<RectTransform>();
            screenRect.anchorMin = new Vector2(0.5f, 0.5f); screenRect.anchorMax = new Vector2(0.5f, 0.5f);
            screenRect.pivot = new Vector2(0.5f, 0.5f); screenRect.sizeDelta = new Vector2(1728f, 972f);
            screen.GetComponent<RawImage>().texture = _target;
            screen.GetComponent<RawImage>().color = Color.white;
            var fitter = screen.GetComponent<AspectRatioFitter>();
            fitter.aspectMode = AspectRatioFitter.AspectMode.FitInParent;
            fitter.aspectRatio = 16f / 9f;
            _playCanvas.gameObject.SetActive(false);
        }

        void Text(string value, Vector2 position, float size, Color color, out TextMeshPro text)
        {
            var go = Track(new GameObject("Text")); go.transform.position = ToWorld(position);
            text = go.AddComponent<TextMeshPro>(); text.text = value; text.fontSize = size; text.color = color; text.alignment = TextAlignmentOptions.Center; text.rectTransform.sizeDelta = new Vector2(4f, 2f); text.sortingOrder = 20;
        }

        GameObject Track(GameObject go) { go.transform.SetParent(null, false); _runtime.Add(go); return go; }
        static Vector3 ToWorld(Vector2 p) => new(p.x, WorldY + p.y, 0f);
        static Vector2 Local(Vector3 p) => new(p.x, p.y - WorldY);

        void CreateScreenTarget()
        {
            var power = GetComponent<ArcadeScreenPower>();
            foreach (var renderer in GetComponentsInChildren<MeshRenderer>(true))
            {
                var materials = renderer.sharedMaterials;
                for (int i = 0; i < materials.Length; i++)
                {
                    if (materials[i] == null || power == null || !power.IsScreenMaterial(materials[i])) continue;
                    _originalTexture = materials[i].GetTexture("_BaseMap");
                    _screenMaterial = new Material(materials[i]) { name = "MegaShooterScreen_Runtime" };
                    _screenMaterial.SetTextureScale("_BaseMap", Vector2.one); _screenMaterial.SetTextureOffset("_BaseMap", Vector2.zero);
                    _screenMaterial.mainTextureScale = Vector2.one; _screenMaterial.mainTextureOffset = Vector2.zero;
                    materials[i] = _screenMaterial; renderer.sharedMaterials = materials; return;
                }
            }
        }

        void SetVisible(bool visible)
        {
            // 완성된 게임만 비활성 중에도 오락기 전용 프리뷰를 렌더링한다.
            if (_camera != null) _camera.enabled = true;
            if (_playCanvas != null) _playCanvas.gameObject.SetActive(visible);
            if (_screenMaterial != null) _screenMaterial.SetTexture("_BaseMap", _target);
        }

        void HandleReleased()
        {
            if (!_active) return;
            _active = false; PreparePreview(); SetVisible(false); enabled = false;
        }

        void OnDestroy()
        {
            InteractionFocusCamera.Released -= HandleReleased;
            foreach (var go in _runtime) if (go != null) Destroy(go);
            if (_solidSprite != null) { var texture = _solidSprite.texture; Destroy(_solidSprite); if (texture != null) Destroy(texture); }
            if (_target != null) { _target.Release(); Destroy(_target); }
            if (_screenMaterial != null) Destroy(_screenMaterial);
        }
    }
}
