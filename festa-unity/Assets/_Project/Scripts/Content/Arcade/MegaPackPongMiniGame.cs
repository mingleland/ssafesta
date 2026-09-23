// 1번 오락기에서 2D Mega Pack 에셋으로 CPU 대전형 네온 퐁을 실행한다.
using System.Collections;
using System.Collections.Generic;
using Festa.World;
using TMPro;
using UnityEngine;
using UnityEngine.UI;

namespace Festa.Content.Arcade
{
    [DisallowMultipleComponent]
    public sealed class MegaPackPongMiniGame : MonoBehaviour
    {
        const float WorldY = 10300f;
        const string Root = "Arcade/MegaPackPong/";
        readonly List<GameObject> _runtime = new();
        Camera _camera; RenderTexture _target; Material _screenMaterial; Sprite _solid, _paddleSprite, _ballSprite;
        GameObject _left, _right, _ball; TextMeshPro _leftScore, _rightScore, _message, _preview; Canvas _overlay;
        AudioSource _audio; AudioClip _hit, _scoreClip; Vector2 _ballPos, _ballVelocity; float _leftY, _rightY,_worldY; int _playerScore, _cpuScore, _rally; bool _active, _ended, _smashReady;

        void Awake() { _worldY=GetComponent<ArcadeMachineInteractable>()?.MachineId=="arcade-11"?WorldY+5000f:WorldY;LoadAssets(); CreateScreen(); Build(); PreparePreview(); Show(false); InteractionFocusCamera.Released += Released; enabled = false; }
        public bool Begin() { if (_camera == null || _screenMaterial == null || _paddleSprite == null || _ballSprite == null) return false; ArcadeRuntimeSuspension.Resume(_runtime); ArcadeOverlayHudSuppressor.Acquire(); NewGame(); Show(true); _active = true; enabled = true; return true; }

        void Update()
        {
            if (!_active) return; if (!InteractionFocusCamera.IsFocused) { Released(); return; }
            if (_ended) { if (ArcadeRetryInput.WasPressed()) NewGame(); return; }
            float input=(Input.GetKey(KeyCode.W)||Input.GetKey(KeyCode.UpArrow)?1f:0f)-(Input.GetKey(KeyCode.S)||Input.GetKey(KeyCode.DownArrow)?1f:0f);
            _leftY=Mathf.Clamp(_leftY+input*7.2f*Time.deltaTime,-3.05f,3.05f);
            if(_smashReady&&Input.GetKeyDown(KeyCode.Space)){_smashReady=false;_ballVelocity=_ballVelocity.normalized*Mathf.Min(14.5f,_ballVelocity.magnitude*1.55f);_ball.GetComponent<SpriteRenderer>().color=new Color(1f,.85f,.15f);_message.text="POWER SMASH!";StartCoroutine(ClearMessage(.55f));Play(_scoreClip,.55f);}
            float aiTarget=_ballVelocity.x>0?_ballPos.y:Mathf.Sin(Time.time*1.4f)*.55f;
            _rightY=Mathf.MoveTowards(_rightY,Mathf.Clamp(aiTarget,-3.05f,3.05f),(4.4f+Mathf.Min(2.2f,_rally*.12f))*Time.deltaTime);
            _ballPos+=_ballVelocity*Time.deltaTime;
            if(Mathf.Abs(_ballPos.y)>3.75f){_ballPos.y=Mathf.Sign(_ballPos.y)*3.75f;_ballVelocity.y*=-1f;Play(_hit,.22f);}
            PaddleHit(-5.45f,_leftY,true); PaddleHit(5.45f,_rightY,false);
            if(_ballPos.x>6.45f)Score(true);else if(_ballPos.x< -6.45f)Score(false);
            _left.transform.position=ToWorld(new Vector2(-5.65f,_leftY));_right.transform.position=ToWorld(new Vector2(5.65f,_rightY));_ball.transform.position=ToWorld(_ballPos);
        }

        void PaddleHit(float x,float y,bool left)
        {
            if(left?_ballVelocity.x>=0:_ballVelocity.x<=0)return;
            if(Mathf.Abs(_ballPos.x-x)>.28f||Mathf.Abs(_ballPos.y-y)>1.05f)return;
            float offset=Mathf.Clamp((_ballPos.y-y)/1.05f,-1f,1f);float speed=Mathf.Min(11.5f,_ballVelocity.magnitude+0.38f);
            _ballVelocity=new Vector2(left?Mathf.Abs(speed):-Mathf.Abs(speed),offset*speed*.82f).normalized*speed;_ballPos.x=x+(left?.31f:-.31f);_rally++;if(left&&_rally>=4&&!_smashReady){_smashReady=true;_message.text="SMASH READY — SPACE";StartCoroutine(ClearMessage(1.1f));}_ball.GetComponent<SpriteRenderer>().color=_smashReady?new Color(1f,.85f,.15f):Color.white;Play(_hit,.34f);
        }

        void Score(bool player)
        {
            if(player)_playerScore++;else _cpuScore++;_leftScore.text=_playerScore.ToString();_rightScore.text=_cpuScore.ToString();Play(_scoreClip,.45f);
            if(_playerScore>=7||_cpuScore>=7){EndGame();return;}StartCoroutine(ServeAfter(player?1f:-1f));
        }

        IEnumerator ServeAfter(float toward){_ball.SetActive(false);yield return new WaitForSeconds(.55f);Serve(toward);_ball.SetActive(true);}
        void Serve(float toward){_ballPos=Vector2.zero;_rally=0;_smashReady=false;_ball.GetComponent<SpriteRenderer>().color=Color.white;float angle=Random.Range(-.48f,.48f);_ballVelocity=new Vector2(toward,angle).normalized*6.7f;}
        void NewGame(){StopAllCoroutines();_preview.gameObject.SetActive(false);_playerScore=_cpuScore=0;_leftY=_rightY=0;_ended=false;_leftScore.text=_rightScore.text="0";_message.text="FIRST TO 7\nW/S MOVE • SPACE SMASH";_ball.SetActive(true);Serve(Random.value>.5f?1f:-1f);StartCoroutine(ClearMessage());}
        IEnumerator ClearMessage(float delay=1.35f){yield return new WaitForSeconds(delay);if(!_ended)_message.text=string.Empty;}
        void EndGame(){_ended=true;_ballVelocity=Vector2.zero;_message.text=string.Empty;_preview.gameObject.SetActive(true);ArcadeRankingBoard.ShowResult(this,_preview,_camera,"NEON PONG",$"{(_playerScore>_cpuScore?"YOU WIN!":"CPU WINS")}   {_playerScore} : {_cpuScore}",_playerScore);}

        void Build()
        {
            _solid=Solid();var cg=Track(new GameObject("@PongCamera"));_camera=cg.AddComponent<Camera>();_camera.orthographic=true;_camera.orthographicSize=4.5f;_camera.aspect=16f/9f;_camera.clearFlags=CameraClearFlags.SolidColor;_camera.backgroundColor=new Color(.008f,.012f,.04f);_camera.transform.position=new Vector3(0,_worldY,-10);
            _target=new RenderTexture(640,360,16){name="NeonPong_640x360",filterMode=FilterMode.Bilinear};_target.Create();_camera.targetTexture=_target;
            for(int i=-3;i<=3;i++)Panel(new Vector2(0,i*1.05f),new Vector2(.05f,.52f),new Color(.25f,.85f,1f,.65f),0);
            Text("PLAYER",new Vector2(-4.25f,3.5f),2.5f,new Color(.35f,1f,.85f),out var playerLabel);Text("CPU",new Vector2(4.25f,3.5f),2.5f,new Color(1f,.35f,.72f),out var cpuLabel);
            Text("0",new Vector2(-1.65f,3.15f),5.2f,Color.white,out _leftScore);Text("0",new Vector2(1.65f,3.15f),5.2f,Color.white,out _rightScore);Text("",Vector2.zero,4.5f,Color.white,out _message);Text("",new Vector2(0,-.25f),3f,Color.white,out _preview);
            playerLabel.rectTransform.sizeDelta=cpuLabel.rectTransform.sizeDelta=new Vector2(2.6f,1.2f);_leftScore.rectTransform.sizeDelta=_rightScore.rectTransform.sizeDelta=new Vector2(1.8f,1.6f);
            _left=SpriteObject("PlayerPaddle",_paddleSprite,new Color(.3f,1f,.85f),new Vector2(-5.65f,0),5);_right=SpriteObject("CpuPaddle",_paddleSprite,new Color(1f,.28f,.68f),new Vector2(5.65f,0),5);_ball=SpriteObject("Ball",_ballSprite,Color.white,Vector2.zero,7);
            Scale(_left,.42f,1.85f);Scale(_right,.42f,1.85f);SetHeight(_ball,.42f);BuildOverlay();_audio=Track(new GameObject("PongAudio")).AddComponent<AudioSource>();
        }
        void PreparePreview(){_message.text=string.Empty;_leftScore.text="7";_rightScore.text="5";_preview.gameObject.SetActive(true);ArcadeRankingBoard.ShowPreview(this,_preview,_camera,"NEON PONG");}
        void LoadAssets(){_paddleSprite=Resources.Load<Sprite>(Root+"Paddle");_ballSprite=Resources.Load<Sprite>(Root+"Ball");_hit=Resources.Load<AudioClip>(Root+"Hit");_scoreClip=Resources.Load<AudioClip>(Root+"Score");}
        void Play(AudioClip clip,float volume){if(clip!=null)_audio.PlayOneShot(clip,volume);}
        void CreateScreen(){var power=GetComponent<ArcadeScreenPower>();foreach(var r in GetComponentsInChildren<MeshRenderer>(true)){var ms=r.sharedMaterials;for(int i=0;i<ms.Length;i++)if(ms[i]!=null&&power!=null&&power.IsScreenMaterial(ms[i])){_screenMaterial=new Material(ms[i]);_screenMaterial.SetTextureScale("_BaseMap",Vector2.one);_screenMaterial.SetTextureOffset("_BaseMap",Vector2.zero);ms[i]=_screenMaterial;r.sharedMaterials=ms;return;}}}
        void BuildOverlay(){var go=Track(new GameObject("@PongPlayCanvas",typeof(RectTransform),typeof(Canvas),typeof(CanvasScaler),typeof(GraphicRaycaster)));_overlay=go.GetComponent<Canvas>();_overlay.renderMode=RenderMode.ScreenSpaceOverlay;_overlay.sortingOrder=32000;var sc=go.GetComponent<CanvasScaler>();sc.uiScaleMode=CanvasScaler.ScaleMode.ScaleWithScreenSize;sc.referenceResolution=new Vector2(1920,1080);sc.matchWidthOrHeight=.5f;var back=new GameObject("Backdrop",typeof(RectTransform),typeof(Image));back.transform.SetParent(go.transform,false);var br=back.GetComponent<RectTransform>();br.anchorMin=Vector2.zero;br.anchorMax=Vector2.one;br.offsetMin=br.offsetMax=Vector2.zero;back.GetComponent<Image>().color=Color.black;var image=new GameObject("GameScreen",typeof(RectTransform),typeof(RawImage),typeof(AspectRatioFitter));image.transform.SetParent(go.transform,false);var rect=image.GetComponent<RectTransform>();rect.anchorMin=Vector2.zero;rect.anchorMax=Vector2.one;rect.offsetMin=rect.offsetMax=Vector2.zero;image.GetComponent<RawImage>().texture=_target;var fit=image.GetComponent<AspectRatioFitter>();fit.aspectMode=AspectRatioFitter.AspectMode.FitInParent;fit.aspectRatio=16f/9f;}
        void Show(bool play){if(_overlay!=null)_overlay.gameObject.SetActive(play);if(_screenMaterial!=null)_screenMaterial.SetTexture("_BaseMap",_target);if(_preview!=null)_preview.gameObject.SetActive(!play);if(_camera!=null){_camera.enabled=play;if(!play)_camera.Render();}if(!play)ArcadeRuntimeSuspension.Suspend(_runtime);}
        void Released(){if(!_active)return;_active=false;ArcadeOverlayHudSuppressor.Release();PreparePreview();Show(false);enabled=false;}
        GameObject SpriteObject(string n,Sprite s,Color c,Vector2 p,int o){var g=Track(new GameObject(n));g.transform.position=ToWorld(p);var r=g.AddComponent<SpriteRenderer>();r.sprite=s??_solid;r.color=c;r.sortingOrder=o;return g;}GameObject Panel(Vector2 p,Vector2 size,Color c,int o){var g=SpriteObject("Panel",_solid,c,p,o);g.transform.localScale=new Vector3(size.x,size.y,1);return g;}
        void Text(string v,Vector2 p,float size,Color c,out TextMeshPro t){var g=Track(new GameObject("Text"));g.transform.position=ToWorld(p);t=g.AddComponent<TextMeshPro>();t.text=v;t.fontSize=size;t.color=c;t.alignment=TextAlignmentOptions.Center;t.rectTransform.sizeDelta=new Vector2(7,5);t.sortingOrder=20;}Sprite Solid(){var t=new Texture2D(1,1);t.SetPixel(0,0,Color.white);t.Apply();return Sprite.Create(t,new Rect(0,0,1,1),Vector2.one*.5f,1);}static void SetHeight(GameObject g,float h){var s=g.GetComponent<SpriteRenderer>().sprite;g.transform.localScale=Vector3.one*(h/Mathf.Max(.001f,s.bounds.size.y));}static void Scale(GameObject g,float w,float h){var s=g.GetComponent<SpriteRenderer>().sprite;g.transform.localScale=new Vector3(w/Mathf.Max(.001f,s.bounds.size.x),h/Mathf.Max(.001f,s.bounds.size.y),1);}
        GameObject Track(GameObject g){g.transform.SetParent(null,false);_runtime.Add(g);return g;}Vector3 ToWorld(Vector2 p)=>new(p.x,_worldY+p.y,0);void OnDestroy(){if(_active)ArcadeOverlayHudSuppressor.Release();InteractionFocusCamera.Released-=Released;foreach(var g in _runtime)if(g!=null)Destroy(g);if(_target!=null){_target.Release();Destroy(_target);}if(_screenMaterial!=null)Destroy(_screenMaterial);}
    }
}
