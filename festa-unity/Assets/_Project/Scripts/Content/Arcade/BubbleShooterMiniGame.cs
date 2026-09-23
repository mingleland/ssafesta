// 1번 오락기에서 독립 RenderTexture로 실행되는 색상 매칭 버블슈터를 제공한다.
using System.Collections;
using System.Collections.Generic;
using Festa.World;
using TMPro;
using UnityEngine;

namespace Festa.Content.Arcade
{
    [DisallowMultipleComponent]
    public sealed class BubbleShooterMiniGame : MonoBehaviour
    {
        const float WorldY = 10000f;
        const int Columns = 14;
        const int Rows = 9;
        const float Radius = 0.36f;
        const float ColumnStep = 0.76f;
        const float RowStep = 0.66f;
        const float BoardLeft = -5.25f;
        const float BoardTop = 3.2f;
        // 오락기 화면 메시는 RenderTexture 하단을 일부 마스킹하므로 실제 가시 안전선 위에 둔다.
        const float LauncherY = -1.65f;

        static readonly Color[] Palette =
        {
            new(0.98f, 0.24f, 0.22f), new(0.20f, 0.62f, 1f), new(0.27f, 0.88f, 0.34f),
            new(0.96f, 0.77f, 0.12f), new(0.77f, 0.25f, 0.95f)
        };

        sealed class Bubble
        {
            public int Row;
            public int Column;
            public int ColorIndex;
            public GameObject GameObject;
        }

        readonly List<GameObject> _runtimeObjects = new();
        readonly List<Texture2D> _runtimeTextures = new();
        readonly Bubble[,] _grid = new Bubble[Rows, Columns];
        Camera _camera;
        RenderTexture _renderTexture;
        Material _screenMaterial;
        Texture _originalScreenTexture;
        Sprite _bubbleSprite;
        Sprite _panelSprite;
        GameObject _projectile;
        SpriteRenderer _projectileRenderer;
        GameObject _aimArrow;
        GameObject _aimHead;
        TextMeshPro _scoreText;
        TextMeshPro _messageText;
        TextMeshPro _previewRankingText;
        GameObject _previewRankingPanel;
        Vector2 _projectilePosition;
        Vector2 _projectileVelocity;
        float _aimDegrees;
        int _currentColor;
        int _nextColor;
        int _score;
        int _shotsUntilDrop = 6;
        bool _flying;
        bool _active;
        bool _ended;

        void Awake()
        {
            CreateScreenTarget();
            BuildGame();
            PreparePreview();
            SetGameVisible(false);
            InteractionFocusCamera.Released += HandleFocusReleased;
            enabled = false;
        }

        void HandleFocusReleased()
        {
            if (!_active) return;
            _active = false;
            _flying = false;
            PreparePreview();
            SetGameVisible(false);
            enabled = false;
        }

        public bool Begin()
        {
            if (_camera == null || _screenMaterial == null) return false;
            NewGame();
            SetGameVisible(true);
            _active = true;
            enabled = true;
            return true;
        }

        void Update()
        {
            if (!_active) return;
            if (!InteractionFocusCamera.IsFocused)
            {
                _active = false;
                SetGameVisible(false);
                enabled = false;
                return;
            }

            if (_ended)
            {
                if (Input.GetKeyDown(KeyCode.R) || Input.GetKeyDown(KeyCode.Space)) NewGame();
                return;
            }

            if (!_flying)
            {
                float input = 0f;
                if (Input.GetKey(KeyCode.LeftArrow) || Input.GetKey(KeyCode.A)) input -= 1f;
                if (Input.GetKey(KeyCode.RightArrow) || Input.GetKey(KeyCode.D)) input += 1f;
                _aimDegrees = Mathf.Clamp(_aimDegrees + input * 85f * Time.deltaTime, -72f, 72f);
                UpdateLauncher();
                if (Input.GetKeyDown(KeyCode.Space)) Fire();
            }
            else
            {
                StepProjectile(Time.deltaTime);
            }
        }

        void BuildGame()
        {
            _bubbleSprite = MakeCircleSprite(64);
            _panelSprite = MakeSolidSprite(Color.white);

            var cameraObject = Track(new GameObject("@BubbleShooterCamera"));
            _camera = cameraObject.AddComponent<Camera>();
            _camera.orthographic = true;
            _camera.orthographicSize = 4.5f;
            _camera.aspect = 16f / 9f;
            _camera.clearFlags = CameraClearFlags.SolidColor;
            _camera.backgroundColor = new Color(0.055f, 0.075f, 0.16f);
            _camera.transform.position = new Vector3(0f, WorldY, -10f);
            _renderTexture = new RenderTexture(640, 360, 16, RenderTextureFormat.ARGB32)
            {
                name = "BubbleShooter_640x360",
                filterMode = FilterMode.Bilinear
            };
            _renderTexture.Create();
            _camera.targetTexture = _renderTexture;

            CreatePanel("Board", new Vector2(-0.55f, 0.2f), new Vector2(12.2f, 7.2f), new Color(0.065f, 0.105f, 0.22f), -5);
            CreatePanel("Hud", new Vector2(6.15f, 0.2f), new Vector2(1.55f, 7.2f), new Color(0.11f, 0.18f, 0.34f), -5);
            CreateText("BUBBLE\nBLAST", new Vector2(6.15f, 3.05f), 0.48f, new Color(0.35f, 0.95f, 1f), out _);
            CreateText("SCORE", new Vector2(6.15f, 1.65f), 0.31f, Color.white, out _);
            CreateText("NEXT", new Vector2(6.15f, -0.25f), 0.29f, new Color(0.8f, 0.88f, 1f), out _);
            CreateText("A/D  AIM\nSPACE  FIRE", new Vector2(6.15f, -2.95f), 0.22f, new Color(0.75f, 0.82f, 0.95f), out _);
            CreateText("0", new Vector2(6.15f, 1.13f), 0.48f, new Color(1f, 0.82f, 0.2f), out _scoreText);
            CreateText(string.Empty, new Vector2(-0.55f, 0f), 0.62f, Color.white, out _messageText);
            _previewRankingPanel = CreatePanel("PreviewRanking", new Vector2(-0.55f, -0.55f), new Vector2(5.8f, 3.65f), new Color(0.015f, 0.025f, 0.08f, 0.9f), 25);
            CreateText(string.Empty, new Vector2(-0.55f, -0.55f), 0.34f, Color.white, out _previewRankingText);
            _previewRankingText.sortingOrder = 26;

            _aimArrow = CreateSpriteObject("AimGuide", _panelSprite, new Color(0.45f, 0.92f, 1f, 0.75f), new Vector2(-0.55f, LauncherY + 0.8f), 5);
            _aimArrow.transform.localScale = new Vector3(0.065f, 1.05f, 1f);
            _aimHead = CreateSpriteObject("AimHead", MakeTriangleSprite(48), new Color(0.45f, 0.92f, 1f, 0.9f), new Vector2(-0.55f, LauncherY + 1.35f), 5);
            _aimHead.transform.localScale = Vector3.one * 0.34f;
            _projectile = CreateSpriteObject("Projectile", _bubbleSprite, Color.white, new Vector2(-0.55f, LauncherY), 10);
            _projectileRenderer = _projectile.GetComponent<SpriteRenderer>();
            CreatePanel("ShooterBase", new Vector2(-0.55f, -2.2f), new Vector2(1.45f, 0.22f), new Color(0.35f, 0.75f, 0.95f), 3);
        }

        void NewGame()
        {
            _previewRankingPanel.SetActive(false);
            _previewRankingText.gameObject.SetActive(false);
            ClearBoard();
            var random = new System.Random(604);
            for (int row = 0; row < 4; row++)
            for (int column = 0; column < Columns; column++)
            {
                if (row == 3 && (column < 1 || column > 12)) continue;
                AddBubble(row, column, random.Next(Palette.Length), true, row * 0.055f + column * 0.008f);
            }

            _score = 0;
            _shotsUntilDrop = 6;
            _aimDegrees = 0f;
            _currentColor = Random.Range(0, Palette.Length);
            _nextColor = Random.Range(0, Palette.Length);
            _flying = false;
            _ended = false;
            _messageText.text = string.Empty;
            PrepareProjectile();
            UpdateHud();
        }

        void PreparePreview()
        {
            StopAllCoroutines();
            ClearBoard();
            var random = new System.Random(604);
            for (int row = 0; row < 3; row++)
            for (int column = 1; column < Columns - 1; column++)
                AddBubble(row, column, random.Next(Palette.Length));
            _projectile.SetActive(false);
            _aimArrow.SetActive(false);
            _aimHead.SetActive(false);
            _score = 0;
            _scoreText.text = "0";
            _messageText.text = string.Empty;
            _messageText.color = new Color(0.35f, 0.95f, 1f);
            _previewRankingPanel.SetActive(true);
            _previewRankingText.gameObject.SetActive(true);
            _previewRankingText.text = "BUBBLE BLAST\nTOP 5\n1  FESTA     12,400\n2  ARCADE     9,850\n3  BUBBLE     7,600\n4  PLAYER     5,200\n5  GUEST      3,100\n[F] PLAY";
        }

        void Fire()
        {
            float radians = _aimDegrees * Mathf.Deg2Rad;
            _projectileVelocity = new Vector2(Mathf.Sin(radians), Mathf.Cos(radians)) * 17f;
            _flying = true;
            _aimArrow.SetActive(false);
            _aimHead.SetActive(false);
        }

        void StepProjectile(float deltaTime)
        {
            _projectilePosition += _projectileVelocity * deltaTime;
            if (_projectilePosition.x < -6.05f + Radius)
            {
                _projectilePosition.x = -6.05f + Radius;
                _projectileVelocity.x = Mathf.Abs(_projectileVelocity.x);
            }
            else if (_projectilePosition.x > 4.95f - Radius)
            {
                _projectilePosition.x = 4.95f - Radius;
                _projectileVelocity.x = -Mathf.Abs(_projectileVelocity.x);
            }
            _projectile.transform.position = ToWorld(_projectilePosition);

            if (_projectilePosition.y >= BoardTop || TouchesBubble(_projectilePosition))
                AttachProjectile();
        }

        bool TouchesBubble(Vector2 point)
        {
            float hitDistance = Radius * 1.78f;
            foreach (var bubble in _grid)
                if (bubble != null && Vector2.Distance(point, CellPosition(bubble.Row, bubble.Column)) <= hitDistance)
                    return true;
            return false;
        }

        void AttachProjectile()
        {
            FindNearestEmpty(_projectilePosition, out int row, out int column);
            var placed = AddBubble(row, column, _currentColor);
            if (placed == null)
            {
                EndGame(false);
                return;
            }

            var match = CollectMatch(placed);
            if (match.Count >= 3)
            {
                foreach (var bubble in match) RemoveBubble(bubble);
                int fallen = RemoveFloatingBubbles();
                _score += match.Count * 100 + fallen * 200;
            }

            _shotsUntilDrop--;
            if (_shotsUntilDrop <= 0)
            {
                PushBoardDown();
                _shotsUntilDrop = 6;
            }

            if (RemainingBubbles() == 0) EndGame(true);
            else if (HasReachedDangerLine()) EndGame(false);
            else
            {
                _currentColor = _nextColor;
                _nextColor = Random.Range(0, Palette.Length);
                PrepareProjectile();
                UpdateHud();
            }
        }

        Bubble AddBubble(int row, int column, int colorIndex, bool animate = false, float delay = 0f)
        {
            if (row < 0 || row >= Rows || column < 0 || column >= Columns || _grid[row, column] != null) return null;
            var go = CreateSpriteObject("Bubble", _bubbleSprite, Palette[colorIndex], CellPosition(row, column), 4);
            Vector3 targetScale = Vector3.one * (Radius * 2f);
            go.transform.localScale = animate ? Vector3.zero : targetScale;
            if (animate) StartCoroutine(AnimateSpawn(go, delay, targetScale));
            var bubble = new Bubble { Row = row, Column = column, ColorIndex = colorIndex, GameObject = go };
            _grid[row, column] = bubble;
            return bubble;
        }

        List<Bubble> CollectMatch(Bubble start)
        {
            var found = new List<Bubble>();
            var queue = new Queue<Bubble>();
            var visited = new HashSet<Bubble>();
            queue.Enqueue(start);
            visited.Add(start);
            while (queue.Count > 0)
            {
                var bubble = queue.Dequeue();
                found.Add(bubble);
                foreach (var neighbor in Neighbors(bubble.Row, bubble.Column))
                    if (neighbor.ColorIndex == start.ColorIndex && visited.Add(neighbor)) queue.Enqueue(neighbor);
            }
            return found;
        }

        int RemoveFloatingBubbles()
        {
            var connected = new HashSet<Bubble>();
            var queue = new Queue<Bubble>();
            for (int column = 0; column < Columns; column++)
                if (_grid[0, column] != null && connected.Add(_grid[0, column])) queue.Enqueue(_grid[0, column]);
            while (queue.Count > 0)
            {
                var bubble = queue.Dequeue();
                foreach (var neighbor in Neighbors(bubble.Row, bubble.Column))
                    if (connected.Add(neighbor)) queue.Enqueue(neighbor);
            }
            var floating = new List<Bubble>();
            foreach (var bubble in _grid) if (bubble != null && !connected.Contains(bubble)) floating.Add(bubble);
            for (int i = 0; i < floating.Count; i++) DropBubble(floating[i], i * 0.025f);
            return floating.Count;
        }

        void DropBubble(Bubble bubble, float delay)
        {
            _grid[bubble.Row, bubble.Column] = null;
            if (bubble.GameObject != null) StartCoroutine(AnimateDrop(bubble.GameObject, delay));
        }

        IEnumerator AnimateDrop(GameObject go, float delay)
        {
            if (delay > 0f) yield return new WaitForSeconds(delay);
            if (go == null) yield break;
            var renderer = go.GetComponent<SpriteRenderer>();
            Color color = renderer.color;
            Vector3 velocity = new Vector3(Random.Range(-0.7f, 0.7f), Random.Range(0.3f, 1.1f), 0f);
            float elapsed = 0f;
            const float duration = 0.72f;
            while (elapsed < duration && go != null)
            {
                float dt = Time.deltaTime;
                elapsed += dt;
                velocity.y -= 13f * dt;
                go.transform.position += velocity * dt;
                go.transform.Rotate(0f, 0f, velocity.x * 90f * dt);
                color.a = 1f - Mathf.Clamp01(elapsed / duration);
                renderer.color = color;
                yield return null;
            }
            if (go != null) Destroy(go);
        }

        IEnumerator AnimateSpawn(GameObject go, float delay, Vector3 targetScale)
        {
            if (delay > 0f) yield return new WaitForSeconds(delay);
            if (go == null) yield break;
            float elapsed = 0f;
            const float duration = 0.16f;
            while (elapsed < duration && go != null)
            {
                elapsed += Time.deltaTime;
                float t = Mathf.Clamp01(elapsed / duration);
                float eased = 1f - Mathf.Pow(1f - t, 3f);
                go.transform.localScale = targetScale * eased;
                yield return null;
            }
            if (go != null) go.transform.localScale = targetScale;
        }

        IEnumerable<Bubble> Neighbors(int row, int column)
        {
            int offset = row % 2 == 0 ? -1 : 1;
            int[,] directions = { { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 }, { -1, offset }, { 1, offset } };
            for (int i = 0; i < 6; i++)
            {
                int nextRow = row + directions[i, 0];
                int nextColumn = column + directions[i, 1];
                if (nextRow >= 0 && nextRow < Rows && nextColumn >= 0 && nextColumn < Columns && _grid[nextRow, nextColumn] != null)
                    yield return _grid[nextRow, nextColumn];
            }
        }

        void FindNearestEmpty(Vector2 point, out int bestRow, out int bestColumn)
        {
            bestRow = -1;
            bestColumn = -1;
            float bestDistance = float.MaxValue;
            for (int row = 0; row < Rows; row++)
            for (int column = 0; column < Columns; column++)
            {
                if (_grid[row, column] != null) continue;
                float distance = Vector2.SqrMagnitude(point - CellPosition(row, column));
                if (distance < bestDistance) { bestDistance = distance; bestRow = row; bestColumn = column; }
            }
        }

        void PushBoardDown()
        {
            for (int row = Rows - 1; row >= 1; row--)
            for (int column = 0; column < Columns; column++)
            {
                _grid[row, column] = _grid[row - 1, column];
                if (_grid[row, column] != null)
                {
                    _grid[row, column].Row = row;
                    _grid[row, column].Column = column;
                    _grid[row, column].GameObject.transform.position = ToWorld(CellPosition(row, column));
                }
            }
            for (int column = 0; column < Columns; column++)
            {
                _grid[0, column] = null;
                AddBubble(0, column, Random.Range(0, Palette.Length));
            }
        }

        Vector2 CellPosition(int row, int column)
        {
            float offset = row % 2 == 0 ? 0f : ColumnStep * 0.5f;
            return new Vector2(BoardLeft + column * ColumnStep + offset, BoardTop - row * RowStep);
        }

        void PrepareProjectile()
        {
            _flying = false;
            _projectilePosition = new Vector2(-0.55f, LauncherY);
            _projectile.transform.position = ToWorld(_projectilePosition);
            _projectileRenderer.color = Palette[_currentColor];
            _projectile.transform.localScale = Vector3.one * (Radius * 2f);
            _aimArrow.SetActive(true);
            _aimHead.SetActive(true);
            UpdateLauncher();
        }

        void UpdateLauncher()
        {
            float radians = _aimDegrees * Mathf.Deg2Rad;
            Vector2 direction = new Vector2(Mathf.Sin(radians), Mathf.Cos(radians));
            _aimArrow.transform.position = ToWorld(new Vector2(-0.55f, LauncherY) + direction * 0.72f);
            _aimArrow.transform.rotation = Quaternion.Euler(0f, 0f, -_aimDegrees);
            _aimHead.transform.position = ToWorld(new Vector2(-0.55f, LauncherY) + direction * 1.28f);
            _aimHead.transform.rotation = Quaternion.Euler(0f, 0f, -_aimDegrees);
        }

        void UpdateHud()
        {
            _scoreText.text = _score.ToString("N0");
            var preview = GameObject.Find("@BubbleShooterNext");
            if (preview == null)
            {
                preview = CreateSpriteObject("@BubbleShooterNext", _bubbleSprite, Palette[_nextColor], new Vector2(6.15f, -0.93f), 5);
                preview.transform.localScale = Vector3.one * 0.72f;
            }
            preview.GetComponent<SpriteRenderer>().color = Palette[_nextColor];
        }

        void EndGame(bool won)
        {
            _ended = true;
            _flying = false;
            _projectile.SetActive(false);
            _aimArrow.SetActive(false);
            _aimHead.SetActive(false);
            _messageText.text = won ? "STAGE CLEAR!\nSPACE: AGAIN" : "GAME OVER\nSPACE: RETRY";
            _messageText.color = won ? new Color(0.35f, 1f, 0.55f) : new Color(1f, 0.35f, 0.35f);
        }

        int RemainingBubbles()
        {
            int count = 0;
            foreach (var bubble in _grid) if (bubble != null) count++;
            return count;
        }

        bool HasReachedDangerLine()
        {
            for (int column = 0; column < Columns; column++) if (_grid[Rows - 1, column] != null) return true;
            return false;
        }

        void RemoveBubble(Bubble bubble)
        {
            _grid[bubble.Row, bubble.Column] = null;
            if (bubble.GameObject != null) Destroy(bubble.GameObject);
        }

        void ClearBoard()
        {
            for (int row = 0; row < Rows; row++)
            for (int column = 0; column < Columns; column++)
            {
                if (_grid[row, column]?.GameObject != null) Destroy(_grid[row, column].GameObject);
                _grid[row, column] = null;
            }
            if (_projectile != null) _projectile.SetActive(true);
        }

        GameObject CreateSpriteObject(string name, Sprite sprite, Color color, Vector2 position, int order)
        {
            var go = Track(new GameObject(name));
            go.transform.position = ToWorld(position);
            var renderer = go.AddComponent<SpriteRenderer>();
            renderer.sprite = sprite;
            renderer.color = color;
            renderer.sortingOrder = order;
            return go;
        }

        GameObject CreatePanel(string name, Vector2 position, Vector2 size, Color color, int order)
        {
            var panel = CreateSpriteObject(name, _panelSprite, color, position, order);
            panel.transform.localScale = new Vector3(size.x, size.y, 1f);
            return panel;
        }

        void CreateText(string value, Vector2 position, float size, Color color, out TextMeshPro text)
        {
            var go = Track(new GameObject("Text_" + value.Replace("\n", "_")));
            go.transform.position = ToWorld(position);
            text = go.AddComponent<TextMeshPro>();
            text.text = value;
            text.fontSize = size * 10f;
            text.color = color;
            text.alignment = TextAlignmentOptions.Center;
            text.rectTransform.sizeDelta = new Vector2(4f, 2f);
            text.sortingOrder = 20;
        }

        Sprite MakeCircleSprite(int size)
        {
            var texture = new Texture2D(size, size, TextureFormat.RGBA32, false) { name = "BubbleCircle_Runtime", filterMode = FilterMode.Bilinear };
            var pixels = new Color[size * size];
            Vector2 center = Vector2.one * (size - 1) * 0.5f;
            float radius = size * 0.47f;
            for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
            {
                float distance = Vector2.Distance(new Vector2(x, y), center);
                float alpha = Mathf.Clamp01(radius - distance + 1f);
                float highlight = Mathf.Clamp01(1f - Vector2.Distance(new Vector2(x, y), center + new Vector2(-12f, 13f)) / 13f);
                pixels[y * size + x] = new Color(0.72f + highlight * 0.28f, 0.72f + highlight * 0.28f, 0.72f + highlight * 0.28f, alpha);
            }
            texture.SetPixels(pixels); texture.Apply(); _runtimeTextures.Add(texture);
            return Sprite.Create(texture, new Rect(0, 0, size, size), Vector2.one * 0.5f, size);
        }

        Sprite MakeSolidSprite(Color color)
        {
            var texture = new Texture2D(1, 1, TextureFormat.RGBA32, false) { name = "BubblePanel_Runtime" };
            texture.SetPixel(0, 0, color); texture.Apply(); _runtimeTextures.Add(texture);
            return Sprite.Create(texture, new Rect(0, 0, 1, 1), Vector2.one * 0.5f, 1f);
        }

        Sprite MakeTriangleSprite(int size)
        {
            var texture = new Texture2D(size, size, TextureFormat.RGBA32, false) { name = "BubbleAimHead_Runtime", filterMode = FilterMode.Bilinear };
            var pixels = new Color[size * size];
            for (int y = 0; y < size; y++)
            {
                float halfWidth = (1f - y / (float)(size - 1)) * size * 0.48f;
                for (int x = 0; x < size; x++)
                    pixels[y * size + x] = Mathf.Abs(x - (size - 1) * 0.5f) <= halfWidth ? Color.white : Color.clear;
            }
            texture.SetPixels(pixels); texture.Apply(); _runtimeTextures.Add(texture);
            return Sprite.Create(texture, new Rect(0, 0, size, size), new Vector2(0.5f, 0.05f), size);
        }

        Vector3 ToWorld(Vector2 local) => new(local.x, WorldY + local.y, 0f);

        GameObject Track(GameObject go)
        {
            go.transform.SetParent(null, false);
            _runtimeObjects.Add(go);
            return go;
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
                    _originalScreenTexture = materials[i].GetTexture("_BaseMap");
                    _screenMaterial = new Material(materials[i]) { name = "BubbleShooterScreen_Runtime" };
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
            // 완성 게임기는 비활성 중에도 전용 타이틀 프리뷰를 화면에 유지한다.
            if (_camera != null) _camera.enabled = true;
            if (_screenMaterial != null) _screenMaterial.SetTexture("_BaseMap", _renderTexture);
        }

        void OnDestroy()
        {
            InteractionFocusCamera.Released -= HandleFocusReleased;
            foreach (var runtimeObject in _runtimeObjects) if (runtimeObject != null) Destroy(runtimeObject);
            foreach (var texture in _runtimeTextures) if (texture != null) Destroy(texture);
            if (_renderTexture != null) { _renderTexture.Release(); Destroy(_renderTexture); }
            if (_screenMaterial != null) Destroy(_screenMaterial);
        }
    }
}
