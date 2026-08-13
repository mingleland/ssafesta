using System;
using System.Collections.Generic;
using System.Linq;
using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.UI;

namespace Festa.Avatar
{
    public sealed class CharacterLobbyController : MonoBehaviour
    {
        [SerializeField] AvatarCatalog _catalog;
        [SerializeField] AvatarAssembler _assembler;
        [SerializeField] Camera _previewCamera;
        AvatarConfig _config;
        AvatarPartCategory _category = AvatarPartCategory.Hair;
        AvatarColorSlot _colorSlot = AvatarColorSlot.Hair;
        RectTransform _itemGrid;
        Text _status;
        Vector3 _cameraTarget;
        float _cameraDistance = 4.5f;
        float _lookHeight = 1.05f;
        bool _dragging;
        Vector2 _lastPointer;
        static Font s_uiFont;

        static readonly Color[] Palette = { new(1,.8f,.69f), new(.73f,.48f,.34f), new(.42f,.23f,.16f), new(.18f,.12f,.1f), new(.95f,.78f,.55f), new(.12f,.08f,.06f), new(.35f,.18f,.08f), new(.12f,.28f,.45f), new(.2f,.45f,.28f), new(.55f,.18f,.22f), new(.9f,.35f,.45f), new(.1f,.18f,.38f), new(.7f,.12f,.18f), new(.12f,.42f,.48f), new(.15f,.15f,.18f), Color.white };

        public void Configure(AvatarCatalog catalog, AvatarAssembler assembler, Camera previewCamera)
        {
            _catalog = catalog; _assembler = assembler; _previewCamera = previewCamera;
        }

        void Awake()
        {
            if (!_assembler) _assembler = GetComponentInChildren<AvatarAssembler>();
            if (!_previewCamera) _previewCamera = Camera.main;
            if (!_catalog || !_assembler || !_previewCamera) { Debug.LogError("[CharacterLobby] 필수 참조가 비어 있습니다.", this); enabled = false; return; }
            _assembler.Catalog = _catalog;
            _config = _catalog.CreateDefault(AvatarGender.Female);
            _assembler.Apply(_config);
            BuildUi(); SetCamera(0); RefreshItems();
        }

        void Update()
        {
            _previewCamera.transform.position = Vector3.Lerp(_previewCamera.transform.position, _cameraTarget, Time.deltaTime * 6f);
            _previewCamera.transform.LookAt(new Vector3(0, _lookHeight, 0));
            if (Input.GetMouseButtonDown(0) && !EventSystem.current.IsPointerOverGameObject()) { _dragging = true; _lastPointer = Input.mousePosition; }
            if (Input.GetMouseButtonUp(0)) _dragging = false;
            if (_dragging)
            {
                Vector2 p = Input.mousePosition; float dx = p.x - _lastPointer.x; _lastPointer = p;
                _assembler.transform.Rotate(0, -dx * .25f, 0, Space.World);
            }
            float wheel = Input.mouseScrollDelta.y;
            if (Mathf.Abs(wheel) > .01f) { _cameraDistance = Mathf.Clamp(_cameraDistance - wheel * .25f, 1.35f, 6f); SetCameraPosition(); }
        }

        void BuildUi()
        {
            if (!FindFirstObjectByType<EventSystem>()) { var e = new GameObject("EventSystem", typeof(EventSystem), typeof(StandaloneInputModule)); }
            var canvas = new GameObject("Avatar UI", typeof(Canvas), typeof(CanvasScaler), typeof(GraphicRaycaster)).GetComponent<Canvas>();
            canvas.renderMode = RenderMode.ScreenSpaceOverlay; canvas.GetComponent<CanvasScaler>().uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize; canvas.GetComponent<CanvasScaler>().referenceResolution = new Vector2(1920,1080);
            Label(canvas.transform,"나만의 캐릭터",34,52,new Vector2(.28f,.92f),new Vector2(.72f,.99f)).color=new Color(.91f,.76f,.42f);
            Label(canvas.transform,"당신의 개성을 담은 새로운 모습을 만들어 보세요",13,28,new Vector2(.3f,.88f),new Vector2(.7f,.93f)).color=new Color(.62f,.68f,.77f);
            var left = Panel(canvas.transform, "Categories", new Vector2(0,0), new Vector2(.18f,1), new Color(.018f,.025f,.045f,.97f));
            var leftList=Vertical(left,22); Label(leftList, "외형 설정", 25, 60).color=new Color(.91f,.76f,.42f);
            Button(leftList, "◈  여성 / 남성 전환", () => { _config = _catalog.CreateDefault(_config.gender == AvatarGender.Female ? AvatarGender.Male : AvatarGender.Female); Apply(); RefreshItems(); },245,48,new Color(.22f,.16f,.08f,1));
            foreach (AvatarPartCategory c in Enum.GetValues(typeof(AvatarPartCategory))) { var captured=c; Button(leftList, "  " + CategoryName(c), () => { _category=captured; RefreshItems(); },245,44,new Color(.055f,.075f,.115f,1)); }
            var right = Panel(canvas.transform, "Items", new Vector2(.75f,.14f), new Vector2(1,1), new Color(.018f,.025f,.045f,.97f));
            var itemTitle=Label(right, "스타일 선택", 24, 58);itemTitle.color=new Color(.91f,.76f,.42f); _itemGrid = Vertical(right, 68);
            var bottom = Panel(canvas.transform, "Colors", new Vector2(.18f,0), new Vector2(.75f,.19f), new Color(.022f,.032f,.055f,.98f));
            var slots = Horizontal(bottom, 42); foreach (AvatarColorSlot s in Enum.GetValues(typeof(AvatarColorSlot))) { var captured=s; Button(slots, ColorSlotName(s), () => _colorSlot=captured, 92,34); }
            var colors = Horizontal(bottom, 85); for(byte i=0;i<Palette.Length;i++){byte id=(byte)(i+1); Button(colors,"",()=>{_config.SetColor(_colorSlot,id);Apply();},38,38,Palette[i]);}
            var actions = Horizontal(bottom, 135); Button(actions,"초기화",()=>{_config=_catalog.CreateDefault(_config.gender);Apply();RefreshItems();},110,40,new Color(.09f,.12f,.18f,1)); Button(actions,"무작위",Randomize,110,40,new Color(.09f,.12f,.18f,1)); Button(actions,"월드 입장",()=>Debug.Log("[CharacterLobby] 월드 입장은 이번 세션 범위 밖입니다."),160,40,new Color(.48f,.32f,.1f,1));
            var camera = Horizontal(canvas.transform, 35, new Vector2(.36f,.81f), new Vector2(.68f,.87f)); Button(camera,"전신",()=>SetCamera(0),100,38,new Color(.08f,.11f,.17f,.94f)); Button(camera,"상체",()=>SetCamera(1),100,38,new Color(.08f,.11f,.17f,.94f)); Button(camera,"얼굴",()=>SetCamera(2),100,38,new Color(.08f,.11f,.17f,.94f));
            _status = Label(canvas.transform,"",18,30,new Vector2(.22f,.02f),new Vector2(.7f,.07f));
        }

        void RefreshItems()
        {
            foreach(Transform c in _itemGrid) Destroy(c.gameObject);
            IEnumerable<AvatarItemDefinition> defs = _catalog.GetItems(_category,_config.gender);
            if(_category==AvatarPartCategory.Hat) defs=defs.GroupBy(x=>x.familyId).Select(x=>x.First());
            if(_category!=AvatarPartCategory.Head) Button(_itemGrid,"착용 안 함",()=>{_config.SetItem(_category,0);Apply();},220,42);
            foreach(var d in defs){var captured=d;Button(_itemGrid,d.displayName,()=>{_config.SetItem(_category,_category==AvatarPartCategory.Hat?captured.familyId:captured.itemId);Apply();},220,42);}
        }

        void Apply(){_assembler.Apply(_config);_status.text=string.IsNullOrEmpty(_assembler.LastError)?$"{_config.gender} / {_category}":_assembler.LastError;}
        void Randomize(){var rng=new System.Random();foreach(AvatarPartCategory c in Enum.GetValues(typeof(AvatarPartCategory))){var a=_catalog.GetItems(c,_config.gender).ToArray();if(a.Length>0){var d=a[rng.Next(a.Length)];_config.SetItem(c,c==AvatarPartCategory.Hat?d.familyId:d.itemId);}}foreach(AvatarColorSlot s in Enum.GetValues(typeof(AvatarColorSlot)))_config.SetColor(s,(byte)rng.Next(1,Palette.Length+1));Apply();RefreshItems();}
        public void VerifyGenderToggle(){_config=_catalog.CreateDefault(_config.gender==AvatarGender.Female?AvatarGender.Male:AvatarGender.Female);Apply();RefreshItems();}
        public void VerifyRandomize(){Randomize();}
        public void VerifyCameraPreset(int preset){SetCamera(Mathf.Clamp(preset,0,2));}
        public void VerifyColorIsolation(int slot,int colorId){_config.SetColor((AvatarColorSlot)Mathf.Clamp(slot,0,6),(byte)Mathf.Clamp(colorId,1,Palette.Length));Apply();}
        public void VerifyFirstItem(int category){_category=(AvatarPartCategory)Mathf.Clamp(category,0,7);var d=_catalog.GetItems(_category,_config.gender).FirstOrDefault();if(d){_config.SetItem(_category,_category==AvatarPartCategory.Hat?d.familyId:d.itemId);Apply();RefreshItems();}}
        public string VerificationState()=>$"gender={_config.gender}; head={_config.headId}; hair={_config.hairId}; hat={_config.hatId}; top={_config.topId}; bottom={_config.bottomId}; outfit={_config.outfitId}; error={_assembler.LastError}";
        void SetCamera(int preset){_cameraDistance=preset==0?4.5f:preset==1?3f:1.65f;_lookHeight=preset==0?1.05f:preset==1?1.3f:1.62f;SetCameraPosition(_lookHeight);}
        void SetCameraPosition(float y=1.05f){_cameraTarget=new Vector3(0,y,-_cameraDistance);}

        static RectTransform Panel(Transform p,string n,Vector2 min,Vector2 max,Color c){var r=new GameObject(n,typeof(RectTransform),typeof(Image)).GetComponent<RectTransform>();r.SetParent(p,false);r.anchorMin=min;r.anchorMax=max;r.offsetMin=r.offsetMax=Vector2.zero;r.GetComponent<Image>().color=c;return r;}
        static RectTransform Vertical(Transform p,float top){var r=new GameObject("List",typeof(RectTransform),typeof(VerticalLayoutGroup),typeof(ContentSizeFitter)).GetComponent<RectTransform>();r.SetParent(p,false);r.anchorMin=new Vector2(.08f,0);r.anchorMax=new Vector2(.92f,1);r.offsetMin=new Vector2(0,18);r.offsetMax=new Vector2(0,-top);var l=r.GetComponent<VerticalLayoutGroup>();l.spacing=7;l.childAlignment=TextAnchor.UpperCenter;l.childControlHeight=false;l.childForceExpandHeight=false;return r;}
        static RectTransform Horizontal(Transform p,float y,Vector2? min=null,Vector2? max=null){var r=new GameObject("Row",typeof(RectTransform),typeof(HorizontalLayoutGroup)).GetComponent<RectTransform>();r.SetParent(p,false);r.anchorMin=min??new Vector2(.03f,1);r.anchorMax=max??new Vector2(.97f,1);r.pivot=new Vector2(.5f,1);r.anchoredPosition=new Vector2(0,-y);r.sizeDelta=new Vector2(0,45);var l=r.GetComponent<HorizontalLayoutGroup>();l.spacing=6;l.childAlignment=TextAnchor.MiddleCenter;return r;}
        static Text Label(Transform p,string s,int size,float h,Vector2? min=null,Vector2? max=null){var t=new GameObject("Label",typeof(RectTransform),typeof(Text)).GetComponent<Text>();t.transform.SetParent(p,false);t.text=s;s_uiFont??=Resources.Load<Font>("Fonts/MalgunGothicLight");t.font=s_uiFont?s_uiFont:Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");t.fontSize=size;t.color=Color.white;t.alignment=TextAnchor.MiddleCenter;var r=t.rectTransform;r.anchorMin=min??new Vector2(0,1);r.anchorMax=max??new Vector2(1,1);r.pivot=new Vector2(.5f,1);r.sizeDelta=new Vector2(0,h);return t;}
        static Button Button(Transform p,string s,UnityEngine.Events.UnityAction click,float w=250,float h=44,Color? color=null){var b=new GameObject(s,typeof(RectTransform),typeof(Image),typeof(Button),typeof(LayoutElement)).GetComponent<Button>();b.transform.SetParent(p,false);b.image.color=color??new Color(.14f,.17f,.25f,1);var le=b.GetComponent<LayoutElement>();le.preferredWidth=w;le.preferredHeight=h;var t=Label(b.transform,s,16,h);t.rectTransform.anchorMin=Vector2.zero;t.rectTransform.anchorMax=Vector2.one;t.rectTransform.offsetMin=t.rectTransform.offsetMax=Vector2.zero;b.onClick.AddListener(click);return b;}
        static string CategoryName(AvatarPartCategory c)=>c switch{AvatarPartCategory.Head=>"얼굴",AvatarPartCategory.Hair=>"헤어",AvatarPartCategory.Hat=>"모자",AvatarPartCategory.Glasses=>"안경",AvatarPartCategory.Top=>"상의",AvatarPartCategory.Bottom=>"하의",AvatarPartCategory.Outfit=>"한벌옷",AvatarPartCategory.Shoes=>"신발",_=>c.ToString()};
        static string ColorSlotName(AvatarColorSlot s)=>s switch{AvatarColorSlot.Skin=>"피부",AvatarColorSlot.Hair=>"헤어",AvatarColorSlot.Iris=>"눈동자",AvatarColorSlot.Eyebrow=>"눈썹",AvatarColorSlot.Lips=>"입술",AvatarColorSlot.Top=>"상의",AvatarColorSlot.Bottom=>"하의",_=>s.ToString()};
    }
}
