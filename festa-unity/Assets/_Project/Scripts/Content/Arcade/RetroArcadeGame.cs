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
        float _catWorldX;
        float _catCheckpoint;
        float _catEnemyX;
        float _catInvincibleUntil;
        bool _catCleared;
        PixelFrame[] _catIdle;
        PixelFrame[] _catRun;
        PixelFrame[] _catJumpUp;
        PixelFrame[] _catJumpFall;
        PixelFrame[] _catHurt;

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
            if (_index == 0 && _catIdle == null) LoadCatArt();
            if (_texture != null) DrawAttract();
        }

        void LoadCatArt()
        {
            _catIdle = LoadFrames("Arcade/TrickyCat/Sprites/01_Idle");
            _catRun = LoadFrames("Arcade/TrickyCat/Sprites/02_Run");
            _catJumpUp = LoadFrames("Arcade/TrickyCat/Sprites/03_Jump/01_Up");
            _catJumpFall = LoadFrames("Arcade/TrickyCat/Sprites/03_Jump/02_Fall");
            _catHurt = LoadFrames("Arcade/TrickyCat/Sprites/04_Hurt");
        }

        static PixelFrame[] LoadFrames(string path)
        {
            var textures = Resources.LoadAll<Texture2D>(path);
            Array.Sort(textures, (a, b) => string.CompareOrdinal(a.name, b.name));
            var frames = new PixelFrame[textures.Length];
            for (int i = 0; i < textures.Length; i++) frames[i] = PixelFrame.Crop(textures[i]);
            return frames;
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
            if (_index == 0) ResetCatStage(false);
            DrawGame();
        }

        void ResetCatStage(bool keepCheckpoint)
        {
            if (!keepCheckpoint) { _catCheckpoint = 12f; _score = 0; _lives = 3; }
            _catWorldX = _catCheckpoint;
            _playerX = 28f;
            _y = 15f;
            _vy = 0f;
            _catEnemyX = 205f;
            _catInvincibleUntil = 0f;
            _catCleared = false;
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

            if (_index == 0)
            {
                TickCatPlatformer(dt);
                DrawGame();
                return;
            }

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
            if (_catCleared)
            {
                if (Input.GetKeyDown(KeyCode.R) || Input.GetKeyDown(KeyCode.Space)) ResetCatStage(false);
                return;
            }

            float move = 0f;
            if (Input.GetKey(KeyCode.LeftArrow) || Input.GetKey(KeyCode.A)) move--;
            if (Input.GetKey(KeyCode.RightArrow) || Input.GetKey(KeyCode.D)) move++;
            _catWorldX = Mathf.Clamp(_catWorldX + move * 38f * dt, 4f, 624f);

            float ground = CatGroundAt(_catWorldX);
            bool grounded = _y <= ground + 0.5f && _vy <= 0f;
            if (grounded) { _y = ground; _vy = 0f; }
            if (grounded && (Input.GetKeyDown(KeyCode.Space) || Input.GetKeyDown(KeyCode.UpArrow) || Input.GetKeyDown(KeyCode.W))) _vy = 39f;
            _vy -= 74f * dt;
            _y += _vy * dt;

            // 1) 첫 구덩이, 2) 접근하면 솟는 가시, 3) 움직이는 적, 4) 낙하 발판을 순서대로 넘는다.
            bool risingSpikes = _catWorldX > 254f && _catWorldX < 292f;
            if ((_y < 1f || risingSpikes && _y < 23f) && Time.unscaledTime >= _catInvincibleUntil) CatHit();

            _catEnemyX = 205f + Mathf.PingPong(Time.unscaledTime * 18f, 34f);
            if (Mathf.Abs(_catWorldX - _catEnemyX) < 9f && Mathf.Abs(_y - 14f) < 10f && Time.unscaledTime >= _catInvincibleUntil)
            {
                if (_vy < -8f && _y > 19f) { _score += 100; _vy = 28f; _catEnemyX = -1000f; }
                else CatHit();
            }

            if (_catWorldX >= 318f && _catCheckpoint < 318f) { _catCheckpoint = 318f; _score += 250; }
            if (_catWorldX >= 612f) { _catCleared = true; _score += 1000; }

            float cameraX = Mathf.Clamp(_catWorldX - 28f, 0f, 512f);
            _playerX = _catWorldX - cameraX;
        }

        float CatGroundAt(float x)
        {
            if (x > 80f && x < 116f || x > 370f && x < 414f || x > 505f && x < 536f) return -20f;
            if (x > 145f && x < 183f) return 28f;
            if (x > 430f && x < 468f) return 38f;
            return 14f;
        }

        void CatHit()
        {
            _lives--;
            if (_lives <= 0) { ResetCatStage(false); return; }
            _catWorldX = _catCheckpoint;
            _y = 18f;
            _vy = 16f;
            _catInvincibleUntil = Time.unscaledTime + 1.2f;
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
            var screenPower = GetComponent<ArcadeScreenPower>();
            foreach (var renderer in GetComponentsInChildren<MeshRenderer>(true))
            {
                var shared = renderer.sharedMaterials;
                for (int i = 0; i < shared.Length; i++)
                {
                    if (shared[i] == null || (screenPower != null
                            ? !screenPower.IsScreenMaterial(shared[i])
                            : !shared[i].name.StartsWith(ArcadeScreenPower.ScreenMaterialName))) continue;
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
            // 캐비닛 모델의 화면 UV가 좌우 반전되어 있어 로컬 게임 텍스처만 보정한다.
            _screenMaterial.SetTextureScale("_BaseMap", new Vector2(-1f, 1f));
            _screenMaterial.SetTextureOffset("_BaseMap", new Vector2(1f, 0f));
            _screenMaterial.mainTextureScale = new Vector2(-1f, 1f);
            _screenMaterial.mainTextureOffset = new Vector2(1f, 0f);
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
                DrawCatStage(primary, accent);
                int cx = (int)_playerX, cy = (int)_y;
                Color32 cat = Time.unscaledTime < _catInvincibleUntil && ((int)(Time.unscaledTime * 12f) & 1) == 0
                    ? new Color32(255,255,255,90) : new Color32(255, 226, 166, 255);
                PixelFrame[] animation = Time.unscaledTime < _catInvincibleUntil ? _catHurt
                    : _vy > 2f ? _catJumpUp : _vy < -2f ? _catJumpFall
                    : (Input.GetKey(KeyCode.A) || Input.GetKey(KeyCode.D) || Input.GetKey(KeyCode.LeftArrow) || Input.GetKey(KeyCode.RightArrow)) ? _catRun : _catIdle;
                if (animation != null && animation.Length > 0)
                {
                    int frame = Mathf.FloorToInt(Time.unscaledTime * 10f) % animation.Length;
                    DrawSprite(animation[frame], cx, cy - 7, 27, Input.GetKey(KeyCode.LeftArrow) || Input.GetKey(KeyCode.A));
                }
                else Rect(cx - 8, cy, 16, 20, cat);
                if (_catCleared) { Rect(18,35,92,30,new Color32(4,8,24,235));Frame(18,35,92,30,primary);DrawText(30,52,"STAGE CLEAR",primary,2);DrawText(34,40,"SPACE AGAIN",Color.white,1); }
            }
            else
            {
                switch (_index)
                {
                    case 1: DrawRoad(primary, accent); break;
                    case 2: DrawSpace(primary, accent, false); break;
                    case 3: DrawSnake(primary, accent); break;
                    case 4: DrawCatch(primary, accent, 0); break;
                    case 5: DrawPong(primary, accent); break;
                    case 6: DrawFrog(primary, accent); break;
                    case 7: DrawSpace(primary, accent, true); break;
                    case 8: DrawLander(primary, accent); break;
                    case 9: DrawStack(primary, accent); break;
                    case 10: DrawMoles(primary, accent); break;
                    case 11: DrawRhythm(primary, accent); break;
                    case 12: DrawMaze(primary, accent); break;
                    case 13: DrawMissile(primary, accent); break;
                    case 14: DrawSki(primary, accent); break;
                    case 15: DrawPinball(primary, accent); break;
                    case 16: DrawFishing(primary, accent); break;
                    case 17: DrawMemory(primary, accent); break;
                    case 18: DrawBalloon(primary, accent); break;
                    default: DrawBomb(primary, accent); break;
                }
            }
            Upload();
        }

        void DrawCatStage(Color32 p, Color32 a)
        {
            float cameraX = Mathf.Clamp(_catWorldX - 28f, 0f, 512f);
            // 하늘, 구름, 먼 산은 카메라보다 느리게 움직여 깊이를 만든다.
            Rect(3,3,Width-6,Height-6,new Color32(20,36,78,255));
            for(int i=0;i<5;i++){int x=(int)(i*41-cameraX*.18f)%180;Rect(x,65+(i%2)*9,22,5,new Color32(80,103,160,255));Rect(x+5,70+(i%2)*9,12,5,new Color32(80,103,160,255));}
            for(int wx=0;wx<640;wx+=10){int sx=(int)(wx-cameraX);float gy=CatGroundAt(wx+5);if(gy>-10f){Rect(sx,3,10,(int)gy,new Color32(104,62,42,255));Rect(sx,(int)gy,10,3,new Color32(65,210,94,255));}}
            DrawWorldRect(145,28,38,5,cameraX,new Color32(176,106,54,255));
            DrawWorldRect(430,38,38,5,cameraX,new Color32(176,106,54,255));
            // 접근하면 솟는 가시.
            if(_catWorldX>235f)for(int wx=256;wx<292;wx+=7){int sx=(int)(wx-cameraX);Rect(sx,14,5,9,new Color32(255,70,86,255));Rect(sx+2,23,1,5,Color.white);}
            // 순찰 적.
            if(_catEnemyX>0f){int ex=(int)(_catEnemyX-cameraX);Rect(ex-6,14,12,7,new Color32(120,255,90,255));Rect(ex-4,21,3,3,Color.white);Rect(ex+2,21,3,3,Color.white);}
            // 체크포인트와 골인 깃발.
            DrawWorldRect(318,14,3,27,cameraX,p);DrawWorldRect(321,34,12,7,cameraX,new Color32(255,190,35,255));
            DrawWorldRect(612,14,3,42,cameraX,Color.white);DrawWorldRect(615,48,12,8,cameraX,a);
            // 첫 화면에는 조작법을 직접 표시한다.
            if(_catWorldX<48f){Rect(4,61,120,23,new Color32(3,8,24,220));DrawText(8,78,"A D MOVE",Color.white,1);DrawText(68,78,"SPACE JUMP",p,1);}
            DrawText(5,88,"TRICKY CAT",p,1);
        }

        void DrawWorldRect(float wx,int y,int w,int h,float cameraX,Color32 c) => Rect((int)(wx-cameraX),y,w,h,c);

        void DrawText(int x,int y,string text,Color32 color,int scale)
        {
            int cursor=x;
            foreach(char ch in text){uint bits=Glyph(ch);for(int row=0;row<5;row++)for(int col=0;col<3;col++)if((bits&(1u<<((4-row)*3+(2-col))))!=0)Rect(cursor+col*scale,y-row*scale,scale,scale,color);cursor+=4*scale;}
        }

        void DrawSprite(PixelFrame frame, int centerX, int bottomY, int targetHeight, bool flipX)
        {
            if (frame == null || frame.Width == 0 || frame.Height == 0) return;
            int targetWidth = Mathf.Max(1, Mathf.RoundToInt(targetHeight * (float)frame.Width / frame.Height));
            int left = centerX - targetWidth / 2;
            for (int dy = 0; dy < targetHeight; dy++)
            {
                int sy = Mathf.Clamp(dy * frame.Height / targetHeight, 0, frame.Height - 1);
                for (int dx = 0; dx < targetWidth; dx++)
                {
                    int sx = Mathf.Clamp(dx * frame.Width / targetWidth, 0, frame.Width - 1);
                    if (flipX) sx = frame.Width - 1 - sx;
                    Color32 color = frame.Pixels[sy * frame.Width + sx];
                    if (color.a < 24) continue;
                    int px = left + dx, py = bottomY + dy;
                    if (px >= 0 && px < Width && py >= 0 && py < Height) _pixels[py * Width + px] = color;
                }
            }
        }

        sealed class PixelFrame
        {
            public readonly int Width;
            public readonly int Height;
            public readonly Color32[] Pixels;

            PixelFrame(int width, int height, Color32[] pixels) { Width = width; Height = height; Pixels = pixels; }

            public static PixelFrame Crop(Texture2D source)
            {
                var pixels = source.GetPixels32();
                int minX = source.width, minY = source.height, maxX = -1, maxY = -1;
                for (int y = 0; y < source.height; y++) for (int x = 0; x < source.width; x++)
                    if (pixels[y * source.width + x].a >= 24) { minX = Mathf.Min(minX, x); minY = Mathf.Min(minY, y); maxX = Mathf.Max(maxX, x); maxY = Mathf.Max(maxY, y); }
                if (maxX < minX || maxY < minY) return new PixelFrame(0, 0, Array.Empty<Color32>());
                int width = maxX - minX + 1, height = maxY - minY + 1;
                var cropped = new Color32[width * height];
                for (int y = 0; y < height; y++) Array.Copy(pixels, (minY + y) * source.width + minX, cropped, y * width, width);
                return new PixelFrame(width, height, cropped);
            }
        }

        static uint Glyph(char c)
        {
            return c switch
            {
                'A'=>0b010_101_111_101_101u,'C'=>0b111_100_100_100_111u,'D'=>0b110_101_101_101_110u,
                'E'=>0b111_100_110_100_111u,'G'=>0b111_100_101_101_111u,'I'=>0b111_010_010_010_111u,
                'J'=>0b001_001_001_101_111u,'L'=>0b100_100_100_100_111u,'M'=>0b101_111_111_101_101u,
                'N'=>0b101_111_111_111_101u,'O'=>0b111_101_101_101_111u,'P'=>0b110_101_110_100_100u,
                'R'=>0b110_101_110_101_101u,'S'=>0b111_100_111_001_111u,'T'=>0b111_010_010_010_010u,
                'V'=>0b101_101_101_101_010u,'Y'=>0b101_101_010_010_010u,' '=>0u,_=>0b111_001_010_000_010u
            };
        }

        void DrawRoad(Color32 p, Color32 a) { for (int y=5;y<90;y+=12) Rect(62,y,4,7,Color.white); Rect((int)_playerX-5,7,10,15,p); Rect((int)_x-6,(int)_y-6,12,12,a); }
        void DrawSpace(Color32 p, Color32 a, bool rocks) { for(int i=0;i<18;i++) Rect((i*37)%124+2,(i*19)%78+8,1,1,Color.white); Rect((int)_playerX-7,7,14,6,p); Rect((int)_playerX-2,13,4,5,p); if(rocks){Rect((int)_x-7,(int)_y-5,14,10,a);Rect((int)_x-2,(int)_y+5,5,3,a);}else{Rect((int)_x-8,(int)_y-4,16,8,a);Rect((int)_x-3,(int)_y-8,6,4,a);} if(Input.GetKey(KeyCode.Space))Rect((int)_playerX,19,2,Mathf.Max(1,(int)_y-24),p); }
        void DrawSnake(Color32 p, Color32 a) { for(int i=0;i<12;i++)Rect((int)_x-i*5,(int)_y+(i%2)*2,4,4,i==0?a:p); Rect((int)_playerX-3,Height/2,6,6,Color.white); }
        void DrawCatch(Color32 p, Color32 a, int kind) { Rect((int)_playerX-13,7,26,5,p); Rect((int)_x-4,(int)_y-4,8,8,a); Rect((int)_x-1,(int)_y+4,2,3,kind==0?Color.yellow:p); }
        void DrawPong(Color32 p, Color32 a) { Rect(5,(int)_y-12,4,24,p); Rect(Width-9,(int)_playerX/2,4,24,p); Rect((int)_x-3,(int)_y-3,6,6,a); Rect(Width/2,5,1,Height-10,new Color32(55,60,85,255)); }
        void DrawFrog(Color32 p, Color32 a) { for(int y=18;y<76;y+=18){Rect(3,y,Width-6,2,new Color32(28,74,120,255)); for(int x=(y*3+(int)Time.unscaledTime*18)%45-20;x<Width;x+=45)Rect(x,y+2,19,6,a);} Rect((int)_playerX-5,(int)_y-4,10,8,p); Rect((int)_playerX-7,(int)_y+2,3,3,p);Rect((int)_playerX+4,(int)_y+2,3,3,p); }
        void DrawLander(Color32 p, Color32 a) { for(int x=3;x<Width-3;x+=8)Rect(x,6+(x*7)%9,8,3,new Color32(90,85,110,255)); Rect((int)_playerX-7,(int)_y-4,14,7,p);Rect((int)_playerX-4,(int)_y+3,3,4,a);Rect((int)_playerX+2,(int)_y+3,3,4,a); }
        void DrawStack(Color32 p, Color32 a) { for(int i=0;i<Mathf.Min(10,_score+1);i++)Rect(43+i%2*3,8+i*7,42-i*3,5,i%2==0?p:a); Rect((int)_x-14,(int)_y,28,5,Color.white); }
        void DrawMoles(Color32 p, Color32 a) { for(int i=0;i<4;i++){int hx=18+i*30;Rect(hx-9,16,18,6,new Color32(73,42,38,255));if(Mathf.Abs(_x-hx)<8){Rect(hx-6,21,12,12,a);Rect(hx-3,29,2,2,p);Rect(hx+2,29,2,2,p);}} Rect((int)_playerX-2,38,4,20,p); }
        void DrawRhythm(Color32 p, Color32 a) { for(int i=0;i<4;i++)Rect(14+i*28,5,18,10,i==((_score+1)%4)?a:p); Rect((int)_playerX-8,(int)_y-3,16,6,Color.white); Rect(3,15,Width-6,2,a); }
        void DrawMaze(Color32 p, Color32 a) { for(int x=12;x<Width-8;x+=20)Rect(x,12+(x/20%2)*18,3,50,p);for(int y=25;y<75;y+=24)Rect(12,y,82,3,p);Rect((int)_playerX-3,(int)_y-3,6,6,a);Rect((int)_x-4,Height/2-4,8,8,Color.yellow); }
        void DrawMissile(Color32 p, Color32 a) { for(int i=0;i<3;i++){int bx=20+i*43;Rect(bx,6,18,5,p);Rect(bx+5,11,8,4,p);} Rect((int)_x-2,(int)_y-8,4,16,a);Rect((int)_x-5,(int)_y+5,10,4,a);if(Input.GetKey(KeyCode.Space))Rect((int)_playerX,8,2,(int)_y-8,Color.white); }
        void DrawSki(Color32 p, Color32 a) { for(int y=12;y<85;y+=18){Rect(24+(y*3)%76,y,3,10,Color.white);Rect(20+(y*3)%76,y+8,11,3,Color.white);} Rect((int)_playerX-4,8,8,12,p);Rect((int)_x-9,(int)_y,18,3,a); }
        void DrawPinball(Color32 p, Color32 a) { Frame(18,8,92,72,p);for(int i=0;i<5;i++)Rect(31+i*16,28+(i%2)*20,7,7,a);Rect((int)_x-3,(int)_y-3,6,6,Color.white);Rect(30,10,25,3,p);Rect(73,10,25,3,p); }
        void DrawFishing(Color32 p, Color32 a) { Rect(3,5,Width-6,42,new Color32(13,65,112,255));Rect((int)_playerX,48,2,31,p);Rect((int)_x-8,25,16,6,a);Rect((int)_x+8,28,5,3,a); }
        void DrawMemory(Color32 p, Color32 a) { for(int i=0;i<4;i++){int x=14+i*29;Color32 c=i==((_score+_index)%4)?a:new Color32(35,45,70,255);Rect(x,31,20,20,c);Frame(x,31,20,20,p);}Rect((int)_playerX-2,22,4,7,Color.white); }
        void DrawBalloon(Color32 p, Color32 a) { for(int i=0;i<5;i++){int x=15+i*23,y=20+(i*17+(int)Time.unscaledTime*9)%55;Rect(x-5,y-5,10,12,i%2==0?p:a);Rect(x,y-10,1,6,Color.white);}Rect((int)_playerX-2,7,4,18,p); }
        void DrawBomb(Color32 p, Color32 a) { Rect((int)_x-9,(int)_y-9,18,18,a);Rect((int)_x-3,(int)_y+9,6,5,p);Rect((int)_x+2,(int)_y+14,8,2,Color.yellow);Rect(28,42,72,8,new Color32(45,50,70,255));Rect(28,42,Mathf.Clamp((int)(_y/Height*72),0,72),8,p);Rect((int)_playerX-3,28,6,10,Color.white); }

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
