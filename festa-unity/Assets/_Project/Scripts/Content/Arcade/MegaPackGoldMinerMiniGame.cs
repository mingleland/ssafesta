// 3번 오락기에서 2D Mega Pack 에셋으로 제한 시간 갈고리 채굴 게임을 실행한다.
using System.Collections;
using System.Collections.Generic;
using Festa.World;
using TMPro;
using UnityEngine;
using UnityEngine.UI;

namespace Festa.Content.Arcade
{
    [DisallowMultipleComponent]
    public sealed class MegaPackGoldMinerMiniGame : MonoBehaviour
    {
        const float WorldY = 10200f;
        const string Root = "Arcade/MegaPackGoldMiner/";
        sealed class Prize { public GameObject Go; public int Score; public float Weight; public float Radius; }

        readonly List<GameObject> _runtime = new();
        readonly List<Prize> _prizes = new();
        Camera _camera; RenderTexture _target; Material _screenMaterial; Sprite _solid, _miner, _hook, _gold, _diamond, _ground, _pyramids;
        Transform _pivot, _rope, _hookObject; TextMeshPro _scoreText, _timeText, _message, _preview;
        Canvas _overlay; AudioSource _audio; AudioClip _bonus, _hit;
        bool _active, _ended, _extending, _returning; float _angle, _direction = 1f, _length = 1.15f, _time, _worldY; int _score, _combo, _bestCombo; Prize _caught;

        void Awake()
        {
            _worldY = GetComponent<ArcadeMachineInteractable>()?.MachineId == "arcade-13" ? WorldY + 5000f : WorldY;
            LoadAssets(); CreateScreen(); Build(); PreparePreview(); Show(false);
            InteractionFocusCamera.Released += Released; enabled = false;
        }

        public bool Begin()
        {
            if (_camera == null || _screenMaterial == null || _miner == null || _hook == null || _gold == null) return false;
            ArcadeOverlayHudSuppressor.Acquire(); NewGame(); Show(true); _active = true; enabled = true; return true;
        }

        void LoadAssets()
        {
            _miner = Resources.Load<Sprite>(Root + "Miner"); _hook = Resources.Load<Sprite>(Root + "Hook");
            _gold = Resources.Load<Sprite>(Root + "Gold"); _diamond = Resources.Load<Sprite>(Root + "Diamond");
            _ground = Resources.Load<Sprite>(Root + "Ground"); _pyramids = Resources.Load<Sprite>(Root + "Pyramids");
            _bonus = Resources.Load<AudioClip>(Root + "Bonus"); _hit = Resources.Load<AudioClip>(Root + "Hit");
        }

        void Update()
        {
            if (!_active) return; if (!InteractionFocusCamera.IsFocused) { Released(); return; }
            if (_ended) { if (ArcadeRetryInput.WasPressed()) NewGame(); return; }
            _time -= Time.deltaTime; if (_time <= 0f) { EndGame(); return; }
            _timeText.text = Mathf.CeilToInt(_time).ToString();
            if (!_extending && !_returning)
            {
                _angle += _direction * 62f * Time.deltaTime;
                if (_angle > 68f || _angle < -68f) { _angle = Mathf.Clamp(_angle, -68f, 68f); _direction *= -1f; }
                if (Input.GetKeyDown(KeyCode.Space)) _extending = true;
            }
            else
            {
                float speed = _returning ? 6.8f / (_caught?.Weight ?? 1f) : 10.5f;
                _length += (_returning ? -1f : 1f) * speed * Time.deltaTime;
                if (_extending) CheckCatch();
                if (_length > 7.1f) { _extending = false; _returning = true; }
                if (_length <= 1.15f) Collect();
            }
            UpdateHook();
        }

        void CheckCatch()
        {
            Vector2 hp = Local(_hookObject.position);
            foreach (var prize in _prizes)
            {
                if (prize.Go == null || Vector2.Distance(hp, Local(prize.Go.transform.position)) > prize.Radius) continue;
                _caught = prize; _extending = false; _returning = true; if (_hit != null) _audio.PlayOneShot(_hit, .35f); break;
            }
        }

        void Collect()
        {
            _length = 1.15f; _returning = false;
            if (_caught == null) { _combo = 0; return; }
            _combo++; _bestCombo = Mathf.Max(_bestCombo, _combo); bool diamond = _caught.Score >= 900;
            int multiplier = Mathf.Clamp(1 + _combo / 3, 1, 4); _score += _caught.Score * multiplier;
            if (diamond) _time = Mathf.Min(75f, _time + 2.5f);
            if (_combo >= 3) { _message.text = diamond ? $"DIAMOND! +2.5s  COMBO x{_combo}" : $"MINING COMBO x{_combo}"; StopCoroutine(nameof(ClearReadyMessage)); StartCoroutine(ClearReadyMessage()); }
            if (_combo % 5 == 0) { _time = Mathf.Min(75f, _time + 3f); _score += 1000; _message.text = "JACKPOT! +3s +1,000"; }
            _scoreText.text = _score.ToString("N0"); _timeText.text = Mathf.CeilToInt(_time).ToString();
            if (_bonus != null) _audio.PlayOneShot(_bonus, .45f); DestroyTracked(_caught.Go); _prizes.Remove(_caught); _caught = null;
            if (_prizes.Count == 0) SpawnField();
        }

        void UpdateHook()
        {
            _pivot.localRotation = Quaternion.Euler(0, 0, _angle);
            _rope.localScale = new Vector3(.045f, _length, 1f); _rope.localPosition = new Vector3(0, -_length * .5f, 0);
            _hookObject.localPosition = new Vector3(0, -_length, 0);
            if (_caught != null && _caught.Go != null) _caught.Go.transform.position = _hookObject.position + Vector3.down * .18f;
        }

        void NewGame()
        {
            ClearPrizes(); _score = _combo = _bestCombo = 0; _time = 60f; _ended = false; _scoreText.text = "0"; _message.text = "SPACE DROP HOOK\nCHAIN CATCHES FOR JACKPOT";
            _angle = -35f; _length = 1.15f; _extending = _returning = false; _caught = null; SpawnField(); UpdateHook();
            StopAllCoroutines(); StartCoroutine(ClearReadyMessage());
        }

        void SpawnField()
        {
            for (int i = 0; i < 13; i++)
            {
                bool gem = i % 5 == 0; Vector2 p = new(Random.Range(-4.8f, 4.2f), Random.Range(-3.25f, .55f));
                var go = SpriteObject(gem ? "Diamond" : "Gold", gem ? _diamond : _gold, Color.white, p, 3);
                SetHeight(go, gem ? .62f : Random.Range(.72f, 1.15f));
                _prizes.Add(new Prize { Go = go, Score = gem ? 900 : 250, Weight = gem ? .8f : Random.Range(1.15f, 1.8f), Radius = .42f });
            }
        }

        void EndGame()
        {
            _ended = true; _extending = _returning = false; _message.text = string.Empty;
            _preview.gameObject.SetActive(true);
            ArcadeRankingBoard.ShowResult(this, _preview, _camera, "GOLD RUSH", $"TIME UP   SCORE {_score:N0}\nBEST COMBO {_bestCombo}", _score);
        }
        IEnumerator ClearReadyMessage() { yield return new WaitForSeconds(1.4f); if (!_ended) _message.text = string.Empty; }

        void Build()
        {
            _solid = Solid(); var cameraGo = Track(new GameObject("@GoldMinerCamera")); _camera = cameraGo.AddComponent<Camera>();
            _camera.orthographic = true; _camera.orthographicSize = 4.5f; _camera.aspect = 16f / 9f; _camera.clearFlags = CameraClearFlags.SolidColor;
            _camera.backgroundColor = new Color(.16f, .07f, .025f); _camera.transform.position = new Vector3(0, _worldY, -10);
            _target = new RenderTexture(640, 360, 16) { name = "GoldRush_640x360" }; _target.Create(); _camera.targetTexture = _target;
            SpriteObject("Pyramids", _pyramids, new Color(1,.82f,.48f), new Vector2(-1,1.15f), -5); SpriteObject("Ground", _ground, Color.white, new Vector2(-1,-3.7f), -4);
            var minerGo = SpriteObject("Miner", _miner, Color.white, new Vector2(-.8f,3.05f), 5); SetHeight(minerGo, 1.25f);
            _pivot = Track(new GameObject("HookPivot")).transform; _pivot.position = ToWorld(new Vector2(-.55f,2.55f));
            _rope = SpriteObject("Rope", _solid, new Color(.92f,.75f,.45f), Vector2.zero, 2).transform; _rope.SetParent(_pivot, false);
            _hookObject = SpriteObject("Hook", _hook, Color.white, Vector2.zero, 6).transform; _hookObject.SetParent(_pivot, false); SetHeight(_hookObject.gameObject, .62f);
            Panel(new Vector2(5.65f,0), new Vector2(2.1f,8.3f), new Color(.12f,.055f,.02f), -2);
            Text("GOLD\nRUSH", new Vector2(5.65f,3.2f), 4.2f, new Color(1,.72f,.12f), out _); Text("SCORE", new Vector2(5.65f,1.8f), 2.5f, Color.white, out _);
            Text("0", new Vector2(5.65f,1.25f), 4f, new Color(1,.88f,.25f), out _scoreText); Text("TIME", new Vector2(5.65f,.25f), 2.5f, Color.white, out _);
            Text("60", new Vector2(5.65f,-.3f), 4f, new Color(.4f,1f,.9f), out _timeText); Text("", new Vector2(-.7f,.2f), 4.5f, Color.white, out _message);
            Text("", new Vector2(-.8f,-.3f), 3f, Color.white, out _preview); BuildOverlay(); _audio = Track(new GameObject("GoldAudio")).AddComponent<AudioSource>();
        }

        void PreparePreview() { ClearPrizes(); _message.text = ""; _preview.gameObject.SetActive(true); ArcadeRankingBoard.ShowPreview(this, _preview, _camera, "GOLD RUSH"); }
        void Show(bool playing) { if (_overlay != null) _overlay.gameObject.SetActive(playing); if (_screenMaterial != null) _screenMaterial.SetTexture("_BaseMap", _target); if (_preview != null) _preview.gameObject.SetActive(!playing); if (_camera != null) { _camera.enabled = playing; if (!playing) _camera.Render(); } }
        void Released() { if (!_active) return; _active = false; ArcadeOverlayHudSuppressor.Release(); PreparePreview(); Show(false); enabled = false; }

        void CreateScreen()
        {
            var power = GetComponent<ArcadeScreenPower>(); foreach (var renderer in GetComponentsInChildren<MeshRenderer>(true)) { var mats = renderer.sharedMaterials;
                for (int i=0;i<mats.Length;i++) if (mats[i]!=null && power!=null && power.IsScreenMaterial(mats[i])) { _screenMaterial=new Material(mats[i]); _screenMaterial.SetTextureScale("_BaseMap",Vector2.one); _screenMaterial.SetTextureOffset("_BaseMap",Vector2.zero); mats[i]=_screenMaterial; renderer.sharedMaterials=mats; return; } }
        }
        void BuildOverlay()
        {
            var go=Track(new GameObject("@GoldPlayCanvas",typeof(RectTransform),typeof(Canvas),typeof(CanvasScaler),typeof(GraphicRaycaster))); _overlay=go.GetComponent<Canvas>(); _overlay.renderMode=RenderMode.ScreenSpaceOverlay; _overlay.sortingOrder=32000;
            var scaler=go.GetComponent<CanvasScaler>(); scaler.uiScaleMode=CanvasScaler.ScaleMode.ScaleWithScreenSize; scaler.referenceResolution=new Vector2(1920,1080); scaler.matchWidthOrHeight=.5f;
            var backdrop=new GameObject("Backdrop",typeof(RectTransform),typeof(Image)); backdrop.transform.SetParent(go.transform,false); var backRect=backdrop.GetComponent<RectTransform>(); backRect.anchorMin=Vector2.zero; backRect.anchorMax=Vector2.one; backRect.offsetMin=backRect.offsetMax=Vector2.zero; backdrop.GetComponent<Image>().color=new Color(.01f,.006f,.003f,1f);
            var image=new GameObject("GameScreen",typeof(RectTransform),typeof(RawImage),typeof(AspectRatioFitter)); image.transform.SetParent(go.transform,false); var rect=image.GetComponent<RectTransform>(); rect.anchorMin=Vector2.zero; rect.anchorMax=Vector2.one; rect.offsetMin=rect.offsetMax=Vector2.zero; image.GetComponent<RawImage>().texture=_target; var fit=image.GetComponent<AspectRatioFitter>(); fit.aspectMode=AspectRatioFitter.AspectMode.FitInParent; fit.aspectRatio=16f/9f;
        }
        GameObject SpriteObject(string n,Sprite s,Color c,Vector2 p,int o){var g=Track(new GameObject(n));g.transform.position=ToWorld(p);var r=g.AddComponent<SpriteRenderer>();r.sprite=s??_solid;r.color=c;r.sortingOrder=o;return g;}
        GameObject Panel(Vector2 p,Vector2 size,Color c,int o){var g=SpriteObject("Panel",_solid,c,p,o);g.transform.localScale=new Vector3(size.x,size.y,1);return g;}
        void Text(string v,Vector2 p,float size,Color c,out TextMeshPro t){var g=Track(new GameObject("Text"));g.transform.position=ToWorld(p);t=g.AddComponent<TextMeshPro>();t.text=v;t.fontSize=size;t.color=c;t.alignment=TextAlignmentOptions.Center;t.rectTransform.sizeDelta=new Vector2(6,5);t.sortingOrder=20;}
        Sprite Solid(){var t=new Texture2D(1,1);t.SetPixel(0,0,Color.white);t.Apply();return Sprite.Create(t,new Rect(0,0,1,1),Vector2.one*.5f,1);}
        static void SetHeight(GameObject g,Sprite s,float h){float sh=s!=null?Mathf.Max(.001f,s.bounds.size.y):1;g.transform.localScale=Vector3.one*(h/sh);} void SetHeight(GameObject g,float h)=>SetHeight(g,g.GetComponent<SpriteRenderer>().sprite,h);
        GameObject Track(GameObject g){g.transform.SetParent(null,false);_runtime.Add(g);return g;} Vector3 ToWorld(Vector2 p)=>new(p.x,_worldY+p.y,0); Vector2 Local(Vector3 p)=>new(p.x,p.y-_worldY);
        void DestroyTracked(GameObject g){if(g==null)return;_runtime.Remove(g);Destroy(g);}void ClearPrizes(){foreach(var p in _prizes)if(p.Go!=null)DestroyTracked(p.Go);_prizes.Clear();}
        void OnDestroy(){if(_active)ArcadeOverlayHudSuppressor.Release();InteractionFocusCamera.Released-=Released;foreach(var g in _runtime)if(g!=null)Destroy(g);if(_target!=null){_target.Release();Destroy(_target);}if(_screenMaterial!=null)Destroy(_screenMaterial);}
    }
}
