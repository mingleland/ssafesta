// 오락실 캐비닛 20대에 서로 다른 로컬 2D 게임을 고정 배정하고, 선택된 한 대만 갱신한다.
using System;
using Festa.World;
using UnityEngine;

namespace Festa.Content.Arcade
{
    [DisallowMultipleComponent]
    public sealed class RetroArcadeGame : MonoBehaviour
    {
        const int Width = 128;
        const int Height = 96;
        const float Tick = 1f / 20f;

        static readonly string[] Titles =
        {
            "TRICKY CAT", "TURBO DODGE", "STAR RAID", "PIXEL SNAKE", "COIN CATCH",
            "MOON PONG", "FROG HOP", "ASTEROID ACE", "LUNAR LANDER", "TOWER STACK",
            "MOLE MATRIX", "BEAT LINE", "NEON MAZE", "MISSILE GUARD", "SKI SLALOM",
            "PINBALL PULSE", "RIVER FISHING", "MEMORY CIRCUIT", "BALLOON POP", "BOMB DEFUSE"
        };

        static readonly Color32[] Palettes =
        {
            new(19,236,255,255), new(255,77,109,255), new(255,225,86,255), new(112,255,129,255),
            new(196,120,255,255), new(255,138,61,255), new(75,155,255,255), new(255,99,214,255)
        };

        Texture2D _texture;
        Color32[] _pixels;
        Material _screenMaterial;
        int _index;
        bool _active;
        float _nextTick;
        float _x, _y, _vx, _vy;
        float _playerX;
        int _score;
        int _lives;
        uint _rng;

        public string Title => Titles[_index];

        void Awake()
        {
            CreateScreen();
            DrawAttract();
            enabled = false;
        }

        public void Configure(string machineId)
        {
            int parsed = 1;
            if (!string.IsNullOrEmpty(machineId))
            {
                int dash = machineId.LastIndexOf('-');
                if (dash >= 0) int.TryParse(machineId.Substring(dash + 1), out parsed);
            }
            _index = Mathf.Clamp(parsed - 1, 0, Titles.Length - 1);
            _rng = (uint)(0x9E3779B9u + _index * 977u);
            if (_texture != null) DrawAttract();
        }

        public bool Begin()
        {
            if (_texture == null || _screenMaterial == null) return false;
            _active = true;
            enabled = true;
            ResetRound();
            return true;
        }

        void ResetRound()
        {
            _score = 0;
            _lives = 3;
            _playerX = Width * 0.5f;
            _x = Width * 0.5f;
            _y = Height * 0.55f;
            float speed = 25f + (_index / 5) * 4f;
            _vx = ((_index & 1) == 0 ? 1f : -1f) * speed;
            _vy = speed * 0.72f;
            _nextTick = 0f;
            DrawGame();
        }

        void Update()
        {
            if (!_active) return;
            if (!InteractionFocusCamera.IsFocused)
            {
                _active = false;
                DrawAttract();
                enabled = false;
                return;
            }

            if (Time.unscaledTime < _nextTick) return;
            float dt = Mathf.Min(0.1f, Time.unscaledDeltaTime + Tick);
            _nextTick = Time.unscaledTime + Tick;

            float move = 0f;
            if (Input.GetKey(KeyCode.LeftArrow) || Input.GetKey(KeyCode.A)) move -= 1f;
            if (Input.GetKey(KeyCode.RightArrow) || Input.GetKey(KeyCode.D)) move += 1f;
            _playerX = Mathf.Clamp(_playerX + move * (42f + _index) * dt, 8f, Width - 8f);

            switch (_index)
            {
                case 0: TickCatPlatformer(dt); break;
                case 1: TickDodge(dt); break;
                case 2: TickShooter(dt); break;
                case 3: TickSnake(dt); break;
                case 4: TickCatch(dt); break;
                case 5: TickPong(dt); break;
                case 6: TickFrogger(dt); break;
                case 7: TickAsteroids(dt); break;
                case 8: TickLander(dt); break;
                case 9: TickStacker(dt); break;
                case 10: TickWhack(dt); break;
                case 11: TickRhythm(dt); break;
                case 12: TickMaze(dt); break;
                case 13: TickMissile(dt); break;
                case 14: TickSlalom(dt); break;
                case 15: TickPinball(dt); break;
                case 16: TickFishing(dt); break;
                case 17: TickMemory(dt); break;
                case 18: TickBalloon(dt); break;
                default: TickDefuse(dt); break;
            }
            DrawGame();
        }

        void TickBreakout(float dt)
        {
            _x += _vx * dt; _y += _vy * dt;
            if (_x < 3f || _x > Width - 4f) _vx = -_vx;
            if (_y > Height - 11f) { _vy = -Mathf.Abs(_vy); _score++; }
            if (_y < 13f && Mathf.Abs(_x - _playerX) < 17f) { _vy = Mathf.Abs(_vy); _score += 2; }
            if (_y < 0f) LoseLife();
        }

        void TickCatPlatformer(float dt)
        {
            bool grounded = _y <= 14.1f;
            if (grounded) { _y = 14f; if (_vy < 0f) _vy = 0f; }
            if (grounded && (Input.GetKeyDown(KeyCode.Space) || Input.GetKeyDown(KeyCode.UpArrow) || Input.GetKeyDown(KeyCode.W))) _vy = 34f;
            _vy -= 62f * dt;
            _y += _vy * dt;

            // 가운데 발판은 밟으면 꺼지고, 오른쪽 물음표 블록은 아래에서 치면 가시가 나온다.
            bool trapOpen = ((int)Time.unscaledTime + _score) % 6 >= 4;
            if (trapOpen && _playerX > 55f && _playerX < 72f && _y <= 15f) LoseLife();
            if (_playerX > Width - 13f) { _score += 10; _playerX = 8f; _y = 14f; }
        }

        void TickDodge(float dt)
        {
            _y -= (24f + _index) * dt;
            if (_y < 8f)
            {
                if (Mathf.Abs(_x - _playerX) < 12f) LoseLife(); else _score++;
                _y = Height - 12f; _x = 10f + Next01() * (Width - 20f);
            }
        }

        void TickShooter(float dt)
        {
            _x += _vx * dt;
            if (_x < 8f || _x > Width - 8f) _vx = -_vx;
            _y -= (10f + _index * 0.4f) * dt;
            if (Input.GetKeyDown(KeyCode.Space) && Mathf.Abs(_x - _playerX) < 11f) { _score += 5; _y = Height - 15f; _x = 8f + Next01() * (Width - 16f); }
            if (_y < 13f) LoseLife();
        }

        void TickSnake(float dt)
        {
            if (Input.GetKey(KeyCode.UpArrow) || Input.GetKey(KeyCode.W)) _vy = Mathf.Abs(_vy);
            if (Input.GetKey(KeyCode.DownArrow) || Input.GetKey(KeyCode.S)) _vy = -Mathf.Abs(_vy);
            _x = _playerX;
            _y += _vy * dt * 0.55f;
            if (_y < 8f || _y > Height - 10f) { _vy = -_vy; _score++; }
        }

        void TickCatch(float dt)
        {
            _y -= (18f + _index) * dt;
            if (_y < 10f)
            {
                if (Mathf.Abs(_x - _playerX) < 14f) _score += (_index >= 15 ? 3 : 1); else _lives--;
                _y = Height - 11f; _x = 8f + Next01() * (Width - 16f);
                if (_lives <= 0) ResetRound();
            }
        }

        void TickPong(float dt) { TickBreakout(dt); _playerX += Mathf.Sign(_x - _playerX) * 8f * dt; }
        void TickFrogger(float dt) { if (Input.GetKeyDown(KeyCode.UpArrow) || Input.GetKeyDown(KeyCode.W)) { _y += 9f; _score++; } if (_y > Height - 12) ResetRound(); _x = 12f + Mathf.PingPong(Time.unscaledTime * 31f, Width - 24f); }
        void TickAsteroids(float dt) { TickDodge(dt); if (Input.GetKeyDown(KeyCode.Space) && Mathf.Abs(_x - _playerX) < 18f) { _score += 2; _y = Height - 10f; } }
        void TickLander(float dt) { _vy -= 7f * dt; if (Input.GetKey(KeyCode.Space)) _vy += 15f * dt; _y += _vy * dt; _x = _playerX; if (_y <= 10f) { if (Mathf.Abs(_vy) < 8f) _score += 5; else _lives--; _y = Height - 12f; _vy = 0f; } }
        void TickStacker(float dt) { _x += _vx * dt; if (_x < 12 || _x > Width - 12) _vx = -_vx; if (Input.GetKeyDown(KeyCode.Space)) { _score++; _y += 6f; _vx *= 1.07f; if (_y > Height - 14) ResetRound(); } }
        void TickWhack(float dt) { _x = 18f + Mathf.Floor(Next01() * 4f) * 30f; if (Input.GetKeyDown(KeyCode.Space)) { if (Mathf.Abs(_playerX - _x) < 13f) _score += 2; else _lives--; } }
        void TickRhythm(float dt) { _y -= 28f * dt; if (Input.GetKeyDown(KeyCode.Space)) { if (Mathf.Abs(_y - 16f) < 7f) _score += 3; else _lives--; _y = Height - 10f; } if (_y < 5f) { _lives--; _y = Height - 10f; } }
        void TickMaze(float dt) { float vertical = 0f; if (Input.GetKey(KeyCode.UpArrow)||Input.GetKey(KeyCode.W)) vertical=1f; if(Input.GetKey(KeyCode.DownArrow)||Input.GetKey(KeyCode.S)) vertical=-1f; _y=Mathf.Clamp(_y+vertical*28f*dt,8f,Height-8f); if(Mathf.Abs(_playerX-_x)<7f&&Mathf.Abs(_y-Height*.5f)<7f){_score+=4;_x=10f+Next01()*(Width-20f);} }
        void TickMissile(float dt) { _y -= 15f*dt; if(Input.GetKeyDown(KeyCode.Space)&&Mathf.Abs(_playerX-_x)<15f){_score+=3;_y=Height-8f;_x=8f+Next01()*(Width-16f);} if(_y<8f)LoseLife(); }
        void TickSlalom(float dt) { _y-=22f*dt; if(_y<8f){if(Mathf.Abs(_playerX-_x)<18f)_score++;else _lives--;_y=Height-8f;_x=15f+Next01()*(Width-30f);} }
        void TickPinball(float dt) { _x+=_vx*dt;_y+=_vy*dt;_vy-=11f*dt;if(_x<4||_x>Width-4)_vx=-_vx;if(Input.GetKeyDown(KeyCode.Space)&&_y<24f)_vy=Mathf.Abs(_vy)+18f;if(_y<0)LoseLife();if(_y>Height-8){_vy=-Mathf.Abs(_vy);_score++;} }
        void TickFishing(float dt) { _x=Width*.5f+Mathf.Sin(Time.unscaledTime*2.3f)*45f; if(Input.GetKeyDown(KeyCode.Space)){if(Mathf.Abs(_playerX-_x)<9f)_score+=5;else _lives--;} }
        void TickMemory(float dt) { _x=18f+((_score+_index)%4)*30f; if(Input.GetKeyDown(KeyCode.Space)){if(Mathf.Abs(_playerX-_x)<12f)_score++;else{_score=0;_lives--;}} }
        void TickBalloon(float dt) { _y+=12f*dt;if(_y>Height-8f){_y=8f;_x=8f+Next01()*(Width-16f);}if(Input.GetKeyDown(KeyCode.Space)&&Mathf.Abs(_playerX-_x)<10f){_score+=2;_y=8f;} }
        void TickDefuse(float dt) { _y-=9f*dt;if(Input.GetKeyDown(KeyCode.Space)){if(_y>38f&&_y<58f)_score+=5;else _lives--;_y=Height-8f;}if(_y<5f)LoseLife(); }

        void LoseLife()
        {
            _lives--;
            if (_lives <= 0) { ResetRound(); return; }
            _x = Width * 0.5f; _y = Height * 0.55f; _vy = Mathf.Abs(_vy);
        }

        float Next01()
        {
            _rng ^= _rng << 13; _rng ^= _rng >> 17; _rng ^= _rng << 5;
            return (_rng & 0x00FFFFFFu) / 16777215f;
        }

        void CreateScreen()
        {
            foreach (var renderer in GetComponentsInChildren<MeshRenderer>(true))
            {
                var shared = renderer.sharedMaterials;
                for (int i = 0; i < shared.Length; i++)
                {
                    if (shared[i] == null || !shared[i].name.StartsWith(ArcadeScreenPower.ScreenMaterialName)) continue;
                    var materials = renderer.sharedMaterials;
                    _screenMaterial = new Material(shared[i]) { name = "RetroArcadeScreen_Runtime" };
                    materials[i] = _screenMaterial;
                    renderer.sharedMaterials = materials;
                    goto found;
                }
            }
        found:
            if (_screenMaterial == null) return;
            _texture = new Texture2D(Width, Height, TextureFormat.RGBA32, false, true)
            {
                name = "RetroArcade_" + (_index + 1),
                filterMode = FilterMode.Point,
                wrapMode = TextureWrapMode.Clamp
            };
            _pixels = new Color32[Width * Height];
            _screenMaterial.SetTexture("_BaseMap", _texture);
            _screenMaterial.mainTexture = _texture;
        }

        void DrawAttract()
        {
            if (_texture == null) return;
            Clear(new Color32(3, 5, 18, 255));
            Color32 primary = Palettes[_index % Palettes.Length];
            Rect(6, 6, Width - 12, Height - 12, new Color32(10, 16, 38, 255));
            Frame(6, 6, Width - 12, Height - 12, primary);
            int bars = 3 + _index % 6;
            for (int i = 0; i < bars; i++)
            {
                int w = 8 + ((_index * 11 + i * 7) % 31);
                Rect(14, 17 + i * 10, w, 4, primary);
            }
            // 기계 번호를 20개의 서로 다른 픽셀 문양으로 표현한다.
            int bits = _index + 1;
            for (int i = 0; i < 5; i++) if ((bits & (1 << i)) != 0) Rect(82 + i * 7, 38, 5, 28, Palettes[(_index + i + 2) % Palettes.Length]);
            Upload();
        }

        void DrawGame()
        {
            if (_texture == null) return;
            Clear(new Color32(2, 4, 13, 255));
            Color32 primary = Palettes[_index % Palettes.Length];
            Color32 accent = Palettes[(_index + 3) % Palettes.Length];
            Frame(2, 2, Width - 4, Height - 4, primary);
            for (int i = 0; i < Mathf.Min(20, _score); i++) Rect(5 + i * 5, Height - 7, 3, 2, accent);
            for (int i = 0; i < _lives; i++) Rect(Width - 8 - i * 6, Height - 7, 4, 3, primary);

            if (_index == 0)
            {
                Rect(3, 7, Width - 6, 6, new Color32(95, 61, 39, 255));
                for (int x = 4; x < Width - 4; x += 9) Rect(x, 13, 7, 3, new Color32(54, 190, 89, 255));
                Rect(42, 34, 24, 5, new Color32(176, 106, 54, 255));
                Rect(84, 48, 15, 15, new Color32(255, 190, 35, 255));
                // 고양이 주인공: 귀·얼굴·몸·꼬리까지 읽히는 16비트 실루엣.
                int cx = (int)_playerX, cy = (int)_y;
                Rect(cx - 5, cy, 10, 8, new Color32(255, 226, 166, 255));
                Rect(cx - 6, cy + 7, 4, 5, new Color32(255, 190, 139, 255));
                Rect(cx + 2, cy + 7, 4, 5, new Color32(255, 190, 139, 255));
                Rect(cx - 4, cy - 5, 8, 6, new Color32(245, 211, 151, 255));
                Rect(cx - 3, cy + 4, 2, 2, new Color32(33, 67, 91, 255));
                Rect(cx + 2, cy + 4, 2, 2, new Color32(33, 67, 91, 255));
                Rect(cx - 8, cy - 2, 4, 3, new Color32(255, 226, 166, 255));
                bool trapOpen = ((int)Time.unscaledTime + _score) % 6 >= 4;
                if (trapOpen) for (int x = 57; x < 72; x += 5) { Rect(x, 13, 3, 7, new Color32(255, 75, 82, 255)); Rect(x + 1, 20, 1, 4, Color.white); }
                Rect(115, 16, 3, 24, accent); Rect(118, 34, 7, 5, primary);
            }
            else if (_index == 1 || _index == 6 || _index == 14)
            {
                for (int x = 20; x < Width; x += 28) Rect(x, 5, 2, Height - 10, new Color32(40, 47, 72, 255));
                Rect((int)_playerX - 5, 8, 10, 13, primary); Rect((int)_x - 6, (int)_y - 7, 12, 14, accent);
            }
            else if (_index == 2 || _index == 7 || _index == 13)
            {
                Rect((int)_playerX - 7, 7, 14, 7, primary); Rect((int)_playerX - 2, 14, 4, 5, primary);
                Rect((int)_x - 8, (int)_y - 5, 16, 10, accent);
                if (Input.GetKey(KeyCode.Space)) Rect((int)_playerX, 19, 2, Mathf.Max(1, (int)_y - 24), primary);
            }
            else if (_index == 3 || _index == 12 || _index == 17)
            {
                for (int i = 0; i < 8 + _index / 5; i++) Rect((int)_x - i * 5, (int)_y, 4, 4, i == 0 ? accent : primary);
                Rect((int)_playerX - 3, Height / 2, 6, 6, accent);
            }
            else
            {
                Rect((int)_playerX - 13, 7, 26, 5, primary); Rect((int)_x - 4, (int)_y - 4, 8, 8, accent);
            }
            Upload();
        }

        void Clear(Color32 color) { for (int i = 0; i < _pixels.Length; i++) _pixels[i] = color; }
        void Frame(int x, int y, int w, int h, Color32 c) { Rect(x, y, w, 2, c); Rect(x, y + h - 2, w, 2, c); Rect(x, y, 2, h, c); Rect(x + w - 2, y, 2, h, c); }
        void Rect(int x, int y, int w, int h, Color32 c)
        {
            int x0 = Mathf.Clamp(x, 0, Width), y0 = Mathf.Clamp(y, 0, Height);
            int x1 = Mathf.Clamp(x + w, 0, Width), y1 = Mathf.Clamp(y + h, 0, Height);
            for (int py = y0; py < y1; py++) for (int px = x0; px < x1; px++) _pixels[py * Width + px] = c;
        }
        void Upload() { _texture.SetPixels32(_pixels); _texture.Apply(false, false); }

        void OnDestroy()
        {
            if (_texture != null) Destroy(_texture);
            if (_screenMaterial != null) Destroy(_screenMaterial);
        }
    }
}
