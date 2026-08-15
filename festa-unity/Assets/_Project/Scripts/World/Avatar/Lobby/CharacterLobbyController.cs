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
        AvatarPartCategory _category = AvatarPartCategory.Head;
        AvatarPartCategory _wardrobeCategory = AvatarPartCategory.Outfit;
        AvatarColorSlot _colorSlot = AvatarColorSlot.Hair;
        AvatarGarmentColorSlot _garmentColorSlot = AvatarGarmentColorSlot.A1;
        AvatarPartCategory _garmentColorCategory = AvatarPartCategory.Outfit;
        bool _editingGarmentColor;
        RectTransform _itemGrid;
        Text _status;
        Vector3 _cameraTarget;
        Vector3 _cameraFocus;
        Vector3 _cameraLook;
        float _cameraDistance = 4.5f;
        float _lookHeight = 1.05f;
        bool _characterDragging;
        bool _cameraOrbiting;
        Vector2 _lastCharacterPointer;
        Vector2 _lastCameraPointer;
        float _cameraYaw;
        float _cameraPitch;
        float _pickerHue;
        float _pickerSaturation = .65f;
        float _pickerValue = .85f;
        AvatarColorPicker _svPicker;
        AvatarColorPicker _valuePicker;
        Image _colorPreview;
        InputField _hexColorInput;
        Text _hexColorHint;
        bool _syncingHexColor;
        RectTransform _colorPopup;
        Text _colorPopupTitle;
        Text _categoryTitle;
        RectTransform _categoryTabs;
        RectTransform _colorSlots;
        RectTransform _wardrobeTabs;
        RectTransform _wardrobeGrid;
        RectTransform _wardrobeItemScroll;
        RectTransform _wardrobeColorSlots;
        Text _wardrobeTitle;
        Text _wardrobeColorTitle;
        RectTransform _itemScroll;
        Text _colorTitle;
        CanvasScaler _uiScaler;
        RectTransform _responsiveFrame;
        RectTransform _previewArea;
        int _lastScreenWidth = -1;
        int _lastScreenHeight = -1;
        PointerEventData _pointerEventData;
        readonly List<RaycastResult> _uiRaycastResults = new();
        readonly List<RectTransform> _previewInputBlockers = new();
        static readonly Dictionary<AvatarPartCategory,Sprite> s_categoryIcons = new();
        static readonly Dictionary<string,Sprite> s_colorIcons = new();
        static readonly Dictionary<int,Sprite> s_faceThumbnails = new();
        static readonly Dictionary<int,Sprite> s_hairThumbnails = new();
        static readonly string[] s_hairDisplayNames =
        {
            "숏 아프로","볼륨 아프로","사이드 아프로","버즈컷","헤어밴드 업스타일",
            "내추럴 숏컷","스파이크 숏컷","사이드뱅 롱헤어","센터뱅 롱헤어","풀뱅 롱헤어",
            "사이드뱅 미디엄헤어","사이드뱅 보브","풀뱅 보브","사이드뱅 숏헤어","센터뱅 숏헤어",
            "풀뱅 로우테일","사이드뱅 로우번","센터뱅 로우번","풀뱅 로우번","로우 포니테일",
            "사이드뱅 포니테일","풀뱅 포니테일","사이드뱅 더블번","센터뱅 더블번","풀뱅 더블번","사이드뱅 트윈테일","센터뱅 트윈테일","풀뱅 트윈테일","레이어드 숏컷","가르마 숏컷","컬링 트윈테일"
        };
        static Sprite s_circleSprite;
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
            _assembler.transform.localRotation = Quaternion.Euler(0, 180f, 0);
            _config = _catalog.CreateDefault(AvatarGender.Female);
            _assembler.Apply(_config);
            BuildUi(); SetCamera(1); RefreshAll();
        }

        void Update()
        {
            UpdateResponsiveLayout();
            float cameraSmoothing=1f-Mathf.Exp(-Time.deltaTime*8f);
            _previewCamera.transform.position=Vector3.Lerp(_previewCamera.transform.position,_cameraTarget,cameraSmoothing);
            _cameraLook=Vector3.Lerp(_cameraLook,_cameraFocus,cameraSmoothing);
            _previewCamera.transform.LookAt(_cameraLook);
            bool pointerInPreview = IsPointerInPreviewArea(Input.mousePosition);

            if (Input.GetMouseButtonDown(0) && pointerInPreview) { _characterDragging = true; _lastCharacterPointer = Input.mousePosition; }
            if (Input.GetMouseButtonUp(0)) _characterDragging = false;
            if (_characterDragging)
            {
                Vector2 pointer = Input.mousePosition;
                float deltaX = pointer.x - _lastCharacterPointer.x;
                _lastCharacterPointer = pointer;
                _assembler.transform.Rotate(0, -deltaX * .25f, 0, Space.World);
            }

            if (Input.GetMouseButtonDown(1) && pointerInPreview) { _cameraOrbiting = true; _lastCameraPointer = Input.mousePosition; }
            if (Input.GetMouseButtonUp(1)) _cameraOrbiting = false;
            if (_cameraOrbiting)
            {
                Vector2 pointer = Input.mousePosition;
                Vector2 delta = pointer - _lastCameraPointer;
                _lastCameraPointer = pointer;
                _cameraYaw += delta.x * .25f;
                _cameraPitch = Mathf.Clamp(_cameraPitch - delta.y * .2f, -30f, 55f);
                SetCameraPosition();
            }
            float wheel = Input.mouseScrollDelta.y;
            if (Mathf.Abs(wheel) > .01f && pointerInPreview) ZoomAt(Input.mousePosition, wheel);
        }

        bool IsPointerInPreviewArea(Vector2 pointer)
        {
            if (Screen.width <= 0 || Screen.height <= 0) return false;
            if (_previewArea && !RectTransformUtility.RectangleContainsScreenPoint(_previewArea, pointer, null)) return false;
            foreach (var blocker in _previewInputBlockers)
                if (blocker && blocker.gameObject.activeInHierarchy && RectTransformUtility.RectangleContainsScreenPoint(blocker, pointer, null))
                    return false;
            if (EventSystem.current == null) return true;
            _pointerEventData ??= new PointerEventData(EventSystem.current);
            _pointerEventData.position = pointer;
            _uiRaycastResults.Clear();
            EventSystem.current.RaycastAll(_pointerEventData, _uiRaycastResults);
            return _uiRaycastResults.Count == 0;
        }

        void BuildUi()
        {
            if (!FindFirstObjectByType<EventSystem>()) { var e = new GameObject("EventSystem", typeof(EventSystem), typeof(StandaloneInputModule)); }
            var canvas = new GameObject("Avatar UI", typeof(Canvas), typeof(CanvasScaler), typeof(GraphicRaycaster)).GetComponent<Canvas>();
            canvas.renderMode = RenderMode.ScreenSpaceOverlay;
            _uiScaler=canvas.GetComponent<CanvasScaler>();_uiScaler.uiScaleMode=CanvasScaler.ScaleMode.ScaleWithScreenSize;_uiScaler.referenceResolution=new Vector2(1600,900);_uiScaler.screenMatchMode=CanvasScaler.ScreenMatchMode.MatchWidthOrHeight;
            _responsiveFrame=new GameObject("Responsive 16:9 Frame",typeof(RectTransform),typeof(AspectRatioFitter)).GetComponent<RectTransform>();
            _responsiveFrame.SetParent(canvas.transform,false);Anchor(_responsiveFrame,Vector2.zero,Vector2.one);
            var frameAspect=_responsiveFrame.GetComponent<AspectRatioFitter>();frameAspect.aspectMode=AspectRatioFitter.AspectMode.FitInParent;frameAspect.aspectRatio=16f/9f;
            UpdateResponsiveLayout(true);

            // Reference composition: wardrobe rail / focused portrait / appearance inspector.
            var left=Panel(_responsiveFrame,"Preset Rail",Vector2.zero,new Vector2(.205f,1),new Color(.002f,.005f,.012f,.985f));
            var centerShade=ImageLayer(_responsiveFrame,"Portrait Shade",new Vector2(.205f,0),new Vector2(.735f,1),new Color(.005f,.012f,.022f,.16f));centerShade.raycastTarget=false;_previewArea=centerShade.rectTransform;
            var wardrobeHeader=Label(left,"의상 선택",23,48,new Vector2(.06f,.93f),new Vector2(.94f,.99f));wardrobeHeader.fontStyle=FontStyle.Bold;wardrobeHeader.color=new Color(.94f,.88f,.72f);
            var wardrobeHeaderRect=wardrobeHeader.rectTransform;
            wardrobeHeaderRect.anchorMin=new Vector2(0,1);wardrobeHeaderRect.anchorMax=new Vector2(1,1);wardrobeHeaderRect.pivot=new Vector2(.5f,1);
            wardrobeHeaderRect.anchoredPosition=Vector2.zero;wardrobeHeaderRect.sizeDelta=new Vector2(0,54);
            _wardrobeTabs=Horizontal(left,60,new Vector2(.05f,1),new Vector2(.95f,1));_wardrobeTabs.sizeDelta=new Vector2(0,78);
            _wardrobeTitle=Label(left,"한벌옷 설정",17,34,new Vector2(.06f,.78f),new Vector2(.94f,.83f));_wardrobeTitle.alignment=TextAnchor.MiddleLeft;_wardrobeTitle.color=new Color(.82f,.88f,.93f);
            _wardrobeGrid=ScrollGrid(left,new Vector2(.06f,.485f),new Vector2(.94f,.75f),2,new Vector2(126,98));_wardrobeItemScroll=(RectTransform)_wardrobeGrid.parent;
            _wardrobeColorTitle=Label(left,"의상 세부 색상",17,34,new Vector2(.06f,.45f),new Vector2(.94f,.50f));_wardrobeColorTitle.alignment=TextAnchor.MiddleLeft;_wardrobeColorTitle.color=new Color(.82f,.88f,.93f);
            _wardrobeColorSlots=ColorList(left);Anchor(_wardrobeColorSlots,new Vector2(.06f,.145f),new Vector2(.94f,.425f));
            var quickRow=Horizontal(left,820,new Vector2(.05f,1),new Vector2(.95f,1));quickRow.sizeDelta=new Vector2(0,52);
            Anchor(_wardrobeTitle.rectTransform,new Vector2(.06f,.755f),new Vector2(.94f,.81f));
            Anchor(_wardrobeColorTitle.rectTransform,new Vector2(.06f,.425f),new Vector2(.94f,.475f));
            Button(quickRow,"성별",()=>{_config=_catalog.CreateDefault(_config.gender==AvatarGender.Female?AvatarGender.Male:AvatarGender.Female);Apply();RefreshAll();},66,42,new Color(.23f,.17f,.08f,1));
            Button(quickRow,"무작위",Randomize,66,42);Button(quickRow,"초기화",()=>{_config=_catalog.CreateDefault(_config.gender);Apply();RefreshAll();},66,42);

            var right=Panel(_responsiveFrame,"Detail Inspector",new Vector2(.735f,0),Vector2.one,new Color(.002f,.007f,.016f,.985f));
            var title=Label(right,"✦  캐릭터 생성  ✦",27,54);title.fontStyle=FontStyle.Bold;title.color=new Color(.96f,.86f,.64f);
            _categoryTabs=Horizontal(right,58,new Vector2(.045f,1),new Vector2(.955f,1));_categoryTabs.sizeDelta=new Vector2(0,108);
            var tabLayout=_categoryTabs.GetComponent<HorizontalLayoutGroup>();tabLayout.spacing=8;tabLayout.childAlignment=TextAnchor.UpperCenter;
            _categoryTitle=Label(right,"얼굴형 선택",18,38,new Vector2(.055f,.73f),new Vector2(.945f,.785f));_categoryTitle.alignment=TextAnchor.MiddleLeft;_categoryTitle.fontStyle=FontStyle.Bold;_categoryTitle.color=new Color(.9f,.88f,.82f);
            _itemGrid=ScrollGrid(right,new Vector2(.055f,.545f),new Vector2(.945f,.715f),4,new Vector2(84,94));
            _itemScroll=(RectTransform)_itemGrid.parent;
            _colorTitle=Label(right,"얼굴 색상",18,36,new Vector2(.055f,.47f),new Vector2(.945f,.52f));_colorTitle.alignment=TextAnchor.MiddleLeft;_colorTitle.fontStyle=FontStyle.Bold;_colorTitle.color=new Color(.9f,.88f,.82f);
            _colorSlots=ColorList(right);
            Anchor(_categoryTitle.rectTransform,new Vector2(.055f,.73f),new Vector2(.945f,.785f));

            var previewBar=ActionButton(right,"미리보기 설정","▣",()=>SetStatus("좌클릭 드래그는 캐릭터, 우클릭 드래그는 카메라를 회전하며 휠로 확대할 수 있습니다."));Anchor(previewBar.transform as RectTransform,new Vector2(.055f,.085f),new Vector2(.945f,.145f));
            var infoBar=ActionButton(right,"현재 외형 정보","▤",()=>SetStatus(VerificationState()));Anchor(infoBar.transform as RectTransform,new Vector2(.055f,.018f),new Vector2(.945f,.078f));

            _colorPopup=Panel(_responsiveFrame,"Color Popup",new Vector2(.595f,.18f),new Vector2(.885f,.82f),new Color(.007f,.013f,.024f,.996f));
            _previewInputBlockers.Add(_colorPopup);
            var innerFrame=Panel(_colorPopup,"Inner Gold Frame",new Vector2(.018f,.018f),new Vector2(.982f,.982f),new Color(.007f,.013f,.024f,.995f));innerFrame.GetComponent<Image>().raycastTarget=false;
            _colorPopupTitle=Label(_colorPopup,"색상 변경",25,58);_colorPopupTitle.color=new Color(.98f,.88f,.61f);_colorPopupTitle.fontStyle=FontStyle.Bold;
            DecorativeDivider(_colorPopup,63);
            BuildColorPicker(_colorPopup,86);
            BuildHexColorInput(_colorPopup,326);
            var popupActions=Horizontal(_colorPopup,500,new Vector2(.075f,1),new Vector2(.925f,1));popupActions.sizeDelta=new Vector2(0,64);popupActions.GetComponent<HorizontalLayoutGroup>().spacing=14;
            var cancelColor=Button(popupActions,"취소",()=>_colorPopup.gameObject.SetActive(false),190,60,new Color(.035f,.065f,.11f,.99f));cancelColor.GetComponentInChildren<Text>().fontSize=20;BorderFrame(cancelColor.transform,new Color(.32f,.48f,.64f,.9f));
            var finishColor=Button(popupActions,"완료",()=>_colorPopup.gameObject.SetActive(false),190,60,new Color(.45f,.29f,.055f,1));finishColor.GetComponentInChildren<Text>().fontSize=20;BorderFrame(finishColor.transform,new Color(.86f,.61f,.2f,.95f));
            _colorPopup.gameObject.SetActive(false);

        }

        void UpdateResponsiveLayout(bool force=false)
        {
            if (!_uiScaler || Screen.width <= 0 || Screen.height <= 0) return;
            if (!force && Screen.width == _lastScreenWidth && Screen.height == _lastScreenHeight) return;
            _lastScreenWidth = Screen.width;
            _lastScreenHeight = Screen.height;
            const float referenceAspect = 16f / 9f;
            float screenAspect = (float)Screen.width / Screen.height;
            _uiScaler.matchWidthOrHeight = screenAspect < referenceAspect ? 0f : 1f;
            if (_responsiveFrame) LayoutRebuilder.ForceRebuildLayoutImmediate(_responsiveFrame);
        }

        void SetStatus(string message)
        {
            if(_status)_status.text=message;
        }

        void SelectWardrobeItem(AvatarPartCategory category,int itemId)
        {
            _config.SetItem(category,itemId);
            Apply();SetCamera(CategoryCameraPreset(category));RefreshAll();
        }

        void RefreshAll(){RefreshWardrobe();RefreshTabs();RefreshItems();RefreshColors();}
        void RefreshWardrobe()
        {
            foreach(Transform child in _wardrobeTabs)Destroy(child.gameObject);
            foreach(var category in new[]{AvatarPartCategory.Top,AvatarPartCategory.Bottom,AvatarPartCategory.Outfit,AvatarPartCategory.Shoes})
            {
                var captured=category;
                ImageButton(_wardrobeTabs,CategoryName(category),CategoryIcon(category),()=>{_wardrobeCategory=captured;_editingGarmentColor=false;SetCamera(CategoryCameraPreset(captured));RefreshWardrobe();},66,74,_wardrobeCategory==category);
            }
            if(_wardrobeTitle)_wardrobeTitle.text=CategoryName(_wardrobeCategory)+" 선택";
            foreach(Transform child in _wardrobeGrid)Destroy(child.gameObject);
            ImageButton(_wardrobeGrid,"없음",null,()=>SelectWardrobeItem(_wardrobeCategory,0),96,86,CurrentItemId(_wardrobeCategory)==0);
            foreach(var item in _catalog.GetItems(_wardrobeCategory,_config.gender))
            {
                var captured=item;
                ImageButton(_wardrobeGrid,PrettyName(captured.displayName),captured.thumbnail,()=>SelectWardrobeItem(_wardrobeCategory,captured.itemId),126,98,IsSelected(_wardrobeCategory,captured));
            }
            RefreshWardrobeColors();
        }

        void RefreshWardrobeColors()
        {
            foreach(Transform child in _wardrobeColorSlots)Destroy(child.gameObject);
            bool hasItem=CurrentItemId(_wardrobeCategory)!=0;
            if(_wardrobeColorTitle)_wardrobeColorTitle.text=hasItem?"원단 영역 A/B/C · 영역별 명암 2색":"의상 세부 색상 · 의상을 선택하세요";
            if(!hasItem)return;
            foreach(AvatarGarmentColorSlot slot in Enum.GetValues(typeof(AvatarGarmentColorSlot)))
            {
                var captured=slot;
                ColorRow(_wardrobeColorSlots,GarmentColorSlotName(slot),CurrentGarmentColor(_wardrobeCategory,slot),()=>OpenGarmentColor(_wardrobeCategory,captured),_editingGarmentColor&&_garmentColorCategory==_wardrobeCategory&&_garmentColorSlot==slot);
            }
        }
        void RefreshTabs()
        {
            foreach(Transform child in _categoryTabs)Destroy(child.gameObject);
            foreach(var category in new[]{AvatarPartCategory.Head,AvatarPartCategory.Hair,AvatarPartCategory.Hat,AvatarPartCategory.Glasses})
            {
                var captured=category;
                CategoryButton(_categoryTabs,CategoryName(category),CategoryIcon(category),()=>{_category=captured;_editingGarmentColor=false;SetCamera(CategoryCameraPreset(captured));RefreshAll();},84,104,_category==category);
            }
        }

        void RefreshColors()
        {
            foreach(Transform child in _colorSlots)Destroy(child.gameObject);
            if(_category==AvatarPartCategory.Hat||_category==AvatarPartCategory.Glasses)
            {
                bool hasItem=CurrentItemId(_category)!=0;
                Anchor(_colorTitle.rectTransform,new Vector2(.055f,.47f),new Vector2(.945f,.52f));
                Anchor(_colorSlots,new Vector2(.055f,.155f),new Vector2(.945f,.46f));
                if(_colorTitle)_colorTitle.text=hasItem?CategoryName(_category)+" 파츠별 색상":CategoryName(_category)+" 색상 · 파츠를 선택해 주세요";
                if(hasItem)
                    foreach(AvatarGarmentColorSlot slot in Enum.GetValues(typeof(AvatarGarmentColorSlot)))
                    {
                        var captured=slot;
                        ColorRow(_colorSlots,_category==AvatarPartCategory.Hat?HatColorSlotName(slot):GlassesColorSlotName(slot),CurrentGarmentColor(_category,slot),()=>OpenGarmentColor(_category,captured),_editingGarmentColor&&_garmentColorCategory==_category&&_garmentColorSlot==slot);
                    }
                return;
            }
            var slots=SlotsFor(_category).ToArray();
            bool face=_category==AvatarPartCategory.Head;
            Anchor(_colorTitle.rectTransform,face?new Vector2(.055f,.47f):new Vector2(.055f,.29f),face?new Vector2(.945f,.52f):new Vector2(.945f,.35f));
            Anchor(_colorSlots,face?new Vector2(.055f,.155f):new Vector2(.055f,.17f),face?new Vector2(.945f,.46f):new Vector2(.945f,.28f));
            if(_colorTitle)_colorTitle.text=slots.Length>0?(face?"얼굴 색상":"색상 설정"):"색상 설정 · 변경 가능한 색상 없음";
            foreach(var slot in slots){var captured=slot;var color=_config.GetColor(slot,_catalog);ColorRow(_colorSlots,ColorSlotName(slot),color,()=>OpenColor(captured),_colorSlot==slot);}
        }

        IEnumerable<AvatarColorSlot> SlotsFor(AvatarPartCategory category)
        {
            if(category==AvatarPartCategory.Head)return new[]{AvatarColorSlot.Skin,AvatarColorSlot.Sclera,AvatarColorSlot.Iris,AvatarColorSlot.Pupil,AvatarColorSlot.Eyebrow,AvatarColorSlot.Lips};
            if(category==AvatarPartCategory.Hair)return new[]{AvatarColorSlot.Hair};
            if(category==AvatarPartCategory.Top||category==AvatarPartCategory.Outfit)return new[]{AvatarColorSlot.Top};
            if(category==AvatarPartCategory.Bottom)return new[]{AvatarColorSlot.Bottom};
            return Array.Empty<AvatarColorSlot>();
        }

        void OpenColor(AvatarColorSlot slot)
        {
            _editingGarmentColor=false;
            _colorSlot=slot;var current=_config.GetColor(slot,_catalog);Color.RGBToHSV(current,out _pickerHue,out _pickerSaturation,out _pickerValue);
            if(_colorPopupTitle)_colorPopupTitle.text=ColorSlotName(slot)+" 색상 변경";
            if(_svPicker){_svPicker.Rebuild(_pickerHue,_pickerSaturation);_svPicker.SetSelection(_pickerHue,_pickerSaturation);}
            if(_valuePicker){_valuePicker.Rebuild(_pickerHue,_pickerSaturation);_valuePicker.SetSelection(0,_pickerValue);}
            if(_colorPreview)_colorPreview.color=current;
            SyncHexColor(current);
            if(_colorPopup)_colorPopup.gameObject.SetActive(true);
            RefreshColors();
        }

        void OpenGarmentColor(AvatarPartCategory category,AvatarGarmentColorSlot slot)
        {
            _editingGarmentColor=true;_garmentColorCategory=category;_garmentColorSlot=slot;var current=CurrentGarmentColor(category,slot);Color.RGBToHSV(current,out _pickerHue,out _pickerSaturation,out _pickerValue);
            if(_colorPopupTitle)_colorPopupTitle.text=CategoryName(category)+" · "+(category==AvatarPartCategory.Hat?HatColorSlotName(slot):category==AvatarPartCategory.Glasses?GlassesColorSlotName(slot):GarmentColorSlotName(slot));
            if(_svPicker){_svPicker.Rebuild(_pickerHue,_pickerSaturation);_svPicker.SetSelection(_pickerHue,_pickerSaturation);}
            if(_valuePicker){_valuePicker.Rebuild(_pickerHue,_pickerSaturation);_valuePicker.SetSelection(0,_pickerValue);}
            if(_colorPreview)_colorPreview.color=current;
            SyncHexColor(current);
            if(_colorPopup)_colorPopup.gameObject.SetActive(true);
            if(category==AvatarPartCategory.Hat||category==AvatarPartCategory.Glasses)RefreshColors();else RefreshWardrobeColors();
        }

        void RefreshItems()
        {
            bool face=_category==AvatarPartCategory.Head;
            if(_itemScroll){_itemScroll.gameObject.SetActive(true);Anchor(_itemScroll,face?new Vector2(.055f,.545f):new Vector2(.055f,.37f),face?new Vector2(.945f,.715f):new Vector2(.945f,.69f));}
            if(_categoryTitle)_categoryTitle.text=face?"얼굴형 선택":CategoryName(_category)+" 설정";
            foreach(Transform c in _itemGrid) Destroy(c.gameObject);
            IEnumerable<AvatarItemDefinition> defs = _catalog.GetItems(_category,_config.gender);
            if(_category==AvatarPartCategory.Hat) defs=defs.GroupBy(x=>x.familyId).Select(x=>x.First());
            if(_category!=AvatarPartCategory.Head) ImageButton(_itemGrid,"없음",null,()=>{_config.SetItem(_category,0);Apply();RefreshItems();},70,82,CurrentItemId(_category)==0);
            var definitions=defs.ToArray();
            for(int index=0;index<definitions.Length;index++)
            {
                var captured=definitions[index];var select=new UnityEngine.Events.UnityAction(()=>{_config.SetItem(_category,_category==AvatarPartCategory.Hat?captured.familyId:captured.itemId);Apply();RefreshItems();RefreshColors();});
                if(face)FaceCardButton(_itemGrid,FaceDisplayName(index),FaceThumbnail(index)??captured.thumbnail,select,82,92,IsSelected(_category,captured));
                else if(_category==AvatarPartCategory.Hair)HairCardButton(_itemGrid,HairDisplayName(index),HairThumbnail(index)??captured.thumbnail,select,70,82,IsSelected(_category,captured));
                else ImageButton(_itemGrid,PrettyName(captured.displayName),captured.thumbnail,select,70,82,IsSelected(_category,captured));
            }
        }

        void Apply()
        {
            // A full outfit and separate upper/lower garments are mutually exclusive.
            // Keeping both active is the main cause of overlapping meshes and skin seams.
            if (_category == AvatarPartCategory.Outfit && _config.outfitId != 0)
            {
                _config.topId = 0; _config.bottomId = 0;
            }
            else if ((_category == AvatarPartCategory.Top && _config.topId != 0) || (_category == AvatarPartCategory.Bottom && _config.bottomId != 0))
            {
                _config.outfitId = 0;
            }
            _assembler.Apply(_config);if(_status&&!string.IsNullOrEmpty(_assembler.LastError))_status.text=_assembler.LastError;
        }
        void BuildColorPicker(Transform parent,float y=82)
        {
            var row = Horizontal(parent, y,new Vector2(.065f,1),new Vector2(.935f,1)); row.sizeDelta = new Vector2(0, 220);
            var layout=row.GetComponent<HorizontalLayoutGroup>();layout.spacing=12;layout.childControlHeight=false;layout.childAlignment=TextAnchor.MiddleCenter;
            RawImage MakeRaw(string name, float width,float height=118)
            {
                var raw = new GameObject(name, typeof(RectTransform), typeof(RawImage), typeof(LayoutElement), typeof(AvatarColorPicker)).GetComponent<RawImage>();
                raw.transform.SetParent(row, false);raw.rectTransform.sizeDelta=new Vector2(width,height); var le = raw.GetComponent<LayoutElement>(); le.preferredWidth = width; le.preferredHeight = height; return raw;
            }
            var sv = MakeRaw("색상환", 210,210); _svPicker = sv.GetComponent<AvatarColorPicker>();
            _svPicker.Configure(AvatarColorPicker.PickerMode.HueSaturationWheel, (h,s) => { _pickerHue=h; _pickerSaturation=s;_valuePicker.Rebuild(_pickerHue,_pickerSaturation);ApplyPickerColor(); });
            _valuePicker = MakeRaw("밝기", 36,210).GetComponent<AvatarColorPicker>();
            _valuePicker.Configure(AvatarColorPicker.PickerMode.Value, (_,v) => { _pickerValue=v;ApplyPickerColor(); });
            var previewColumn=new GameObject("선택 색상",typeof(RectTransform),typeof(VerticalLayoutGroup),typeof(LayoutElement)).GetComponent<RectTransform>();
            previewColumn.SetParent(row,false);previewColumn.sizeDelta=new Vector2(128,186);var columnLayout=previewColumn.GetComponent<LayoutElement>();columnLayout.preferredWidth=128;columnLayout.preferredHeight=186;
            var vertical=previewColumn.GetComponent<VerticalLayoutGroup>();vertical.spacing=10;vertical.childAlignment=TextAnchor.MiddleCenter;vertical.childControlWidth=true;vertical.childForceExpandWidth=false;vertical.childControlHeight=true;vertical.childForceExpandHeight=false;
            var previewLabel=Label(previewColumn,"선택 색상",18,36);previewLabel.color=new Color(.94f,.82f,.57f);previewLabel.fontStyle=FontStyle.Bold;
            _colorPreview = new GameObject("현재 색상", typeof(RectTransform), typeof(Image), typeof(LayoutElement),typeof(Outline)).GetComponent<Image>();
            _colorPreview.transform.SetParent(previewColumn, false); var previewLayout=_colorPreview.GetComponent<LayoutElement>(); previewLayout.preferredWidth=110; previewLayout.preferredHeight=110;
            var previewOutline=_colorPreview.GetComponent<Outline>();previewOutline.effectColor=new Color(.95f,.68f,.24f,.95f);previewOutline.effectDistance=new Vector2(2,-2);
            _colorPreview.color=_config.GetColor(_colorSlot,_catalog);
        }

        void BuildHexColorInput(Transform parent,float y)
        {
            var section=Label(parent,"색상 코드",19,34);section.alignment=TextAnchor.MiddleLeft;section.color=new Color(.96f,.84f,.60f);section.fontStyle=FontStyle.Bold;
            section.rectTransform.anchorMin=new Vector2(.075f,1);section.rectTransform.anchorMax=new Vector2(.925f,1);section.rectTransform.anchoredPosition=new Vector2(0,-(y-10));
            var row=Horizontal(parent,y+38,new Vector2(.075f,1),new Vector2(.925f,1));row.sizeDelta=new Vector2(0,56);row.GetComponent<HorizontalLayoutGroup>().spacing=10;
            _hexColorInput=HexInput(row,260,52);
            _hexColorInput.onValueChanged.AddListener(HandleHexColorChanged);
            var applyHex=Button(row,"코드 적용",ApplyHexColor,140,52,new Color(.055f,.09f,.145f,1));applyHex.GetComponentInChildren<Text>().fontSize=17;BorderFrame(applyHex.transform,new Color(.86f,.61f,.2f,.95f));
            _hexColorHint=Label(parent,"#RRGGBB · 입력 즉시 반영",15,28);_hexColorHint.alignment=TextAnchor.MiddleLeft;_hexColorHint.color=new Color(.52f,.61f,.71f);
            _hexColorHint.rectTransform.anchorMin=new Vector2(.075f,1);_hexColorHint.rectTransform.anchorMax=new Vector2(.925f,1);_hexColorHint.rectTransform.anchoredPosition=new Vector2(0,-(y+98));
        }

        void HandleHexColorChanged(string value)
        {
            if(_syncingHexColor)return;
            var normalized=value.Trim();if(!normalized.StartsWith("#"))normalized="#"+normalized;
            if(normalized.Length!=7)return;
            ApplyHexColor(normalized);
        }

        void ApplyHexColor()
        {
            if(_hexColorInput)ApplyHexColor(_hexColorInput.text);
        }

        void ApplyHexColor(string value)
        {
            var normalized=(value??string.Empty).Trim();if(!normalized.StartsWith("#"))normalized="#"+normalized;
            if(!ColorUtility.TryParseHtmlString(normalized,out var color))
            {
                if(_hexColorHint){_hexColorHint.text="6자리 HEX 코드를 확인해 주세요";_hexColorHint.color=new Color(1f,.48f,.42f);}
                return;
            }
            color.a=1f;Color.RGBToHSV(color,out _pickerHue,out _pickerSaturation,out _pickerValue);
            if(_svPicker){_svPicker.Rebuild(_pickerHue,_pickerSaturation);_svPicker.SetSelection(_pickerHue,_pickerSaturation);}
            if(_valuePicker){_valuePicker.Rebuild(_pickerHue,_pickerSaturation);_valuePicker.SetSelection(0,_pickerValue);}
            ApplyPickerColor();
            if(_hexColorHint){_hexColorHint.text="적용됨 · "+ColorHex(color);_hexColorHint.color=new Color(.48f,.86f,.62f);}
        }

        void SyncHexColor(Color color)
        {
            if(!_hexColorInput)return;
            _syncingHexColor=true;_hexColorInput.text=ColorHex(color);_syncingHexColor=false;
            if(_hexColorHint){_hexColorHint.text="#RRGGBB · 입력 즉시 반영";_hexColorHint.color=new Color(.52f,.61f,.71f);}
        }

        static string ColorHex(Color color)=>"#"+ColorUtility.ToHtmlStringRGB(color);
        void ApplyPickerColor()
        {
            var color=Color.HSVToRGB(_pickerHue,_pickerSaturation,_pickerValue);
            if(_editingGarmentColor){_config.SetGarmentColor(_garmentColorCategory,_garmentColorSlot,color);if(_garmentColorCategory==AvatarPartCategory.Hat||_garmentColorCategory==AvatarPartCategory.Glasses)RefreshColors();else RefreshWardrobeColors();}
            else{_config.SetColor(_colorSlot,color);RefreshColors();}
            if(_colorPreview)_colorPreview.color=color;
            SyncHexColor(color);
            _assembler.ApplyColorsOnly(_config);
        }
        void ApplyToWorld()
        {
            var nm = Unity.Netcode.NetworkManager.Singleton;
            var player = nm != null && nm.IsClient ? nm.LocalClient?.PlayerObject : null;
            var controller = player ? player.GetComponent<Festa.World.PlayerAppearanceController>() : null;
            if (controller) { controller.RequestChange(Festa.World.AvatarAppearance.FromModularConfig(_config)); SetStatus("월드 아바타에 적용했습니다."); }
            else SetStatus("외형이 준비되었습니다. 월드 접속 후 자동 적용할 수 있습니다.");
        }
        void Randomize(){var rng=new System.Random();foreach(AvatarPartCategory c in Enum.GetValues(typeof(AvatarPartCategory))){var a=_catalog.GetItems(c,_config.gender).ToArray();if(a.Length>0){var d=a[rng.Next(a.Length)];_config.SetItem(c,c==AvatarPartCategory.Hat?d.familyId:d.itemId);}}foreach(AvatarColorSlot s in Enum.GetValues(typeof(AvatarColorSlot)))_config.SetColor(s,(byte)rng.Next(1,Palette.Length+1));foreach(var c in new[]{AvatarPartCategory.Top,AvatarPartCategory.Bottom,AvatarPartCategory.Outfit,AvatarPartCategory.Shoes,AvatarPartCategory.Hat,AvatarPartCategory.Glasses})foreach(AvatarGarmentColorSlot s in Enum.GetValues(typeof(AvatarGarmentColorSlot)))_config.SetGarmentColor(c,s,Palette[rng.Next(Palette.Length)]);Apply();RefreshAll();}
        public void VerifyGenderToggle(){_config=_catalog.CreateDefault(_config.gender==AvatarGender.Female?AvatarGender.Male:AvatarGender.Female);Apply();RefreshAll();}
        public void VerifyRandomize(){Randomize();}
        public void VerifyCameraPreset(int preset){SetCamera(Mathf.Clamp(preset,0,2));}
        public string VerifyPointerZoom(float normalizedX,float normalizedY,float wheel){var before=_cameraFocus;ZoomAt(new Vector2(Screen.width*Mathf.Clamp01(normalizedX),Screen.height*Mathf.Clamp01(normalizedY)),wheel);return $"focus {before:F3} -> {_cameraFocus:F3}; distance={_cameraDistance:F3}";}
        public void VerifyColorIsolation(int slot,int colorId){_config.SetColor((AvatarColorSlot)Mathf.Clamp(slot,0,8),(byte)Mathf.Clamp(colorId,1,Palette.Length));Apply();}
        public void VerifyFirstItem(int category){_category=(AvatarPartCategory)Mathf.Clamp(category,0,7);var d=_catalog.GetItems(_category,_config.gender).FirstOrDefault();if(d){_config.SetItem(_category,_category==AvatarPartCategory.Hat?d.familyId:d.itemId);Apply();RefreshItems();}}
        public string VerificationState()=>$"gender={_config.gender}; head={_config.headId}; hair={_config.hairId}; hat={_config.hatId}; top={_config.topId}; bottom={_config.bottomId}; outfit={_config.outfitId}; error={_assembler.LastError}";
        void SetCamera(int preset){_cameraDistance=preset==0?3.55f:preset==1?1.45f:.78f;_lookHeight=preset==0?.92f:preset==1?1.16f:1.42f;_cameraFocus=new Vector3(0,_lookHeight,0);_cameraLook=_cameraFocus;_cameraYaw=0f;_cameraPitch=0f;SetCameraPosition();}
        void SetCameraPosition(){var orbit=Quaternion.Euler(_cameraPitch,_cameraYaw,0);_cameraTarget=_cameraFocus+orbit*new Vector3(.12f,0,-_cameraDistance);}
        void ZoomAt(Vector2 screenPosition,float wheel)
        {
            float previousDistance=_cameraDistance;
            float zoomInput=Mathf.Clamp(wheel,-1f,1f);
            float nextDistance=Mathf.Clamp(previousDistance*Mathf.Exp(-zoomInput*.08f),.68f,6f);
            if(TryGetAvatarBounds(out var avatarBounds))
            {
                if(nextDistance<previousDistance)
                {
                    if(TryGetClosestAvatarFocus(screenPosition,out var pointerFocus))
                    {
                        float zoomRatio=1f-nextDistance/previousDistance;
                        _cameraFocus=Vector3.Lerp(_cameraFocus,pointerFocus,zoomRatio);
                    }
                    else _cameraFocus=avatarBounds.center;
                }
                _cameraFocus.x=Mathf.Clamp(_cameraFocus.x,avatarBounds.min.x,avatarBounds.max.x);
                _cameraFocus.y=Mathf.Clamp(_cameraFocus.y,avatarBounds.min.y,avatarBounds.max.y);
                _cameraFocus.z=Mathf.Clamp(_cameraFocus.z,avatarBounds.min.z,avatarBounds.max.z);
            }
            _cameraDistance=nextDistance;SetCameraPosition();
        }

        bool TryGetAvatarBounds(out Bounds bounds)
        {
            bounds=default;
            if(!_assembler)return false;
            var renderers=_assembler.GetComponentsInChildren<Renderer>(false);
            bool found=false;
            foreach(var renderer in renderers)
            {
                if(!renderer||!renderer.enabled)continue;
                if(!found){bounds=renderer.bounds;found=true;}
                else bounds.Encapsulate(renderer.bounds);
            }
            return found;
        }

        bool TryGetClosestAvatarFocus(Vector2 pointer,out Vector3 worldFocus)
        {
            worldFocus=default;
            var animator=_assembler?_assembler.GetComponentInChildren<Animator>():null;
            if(!animator||!animator.isHuman)return false;
            var chains=new[]
            {
                new[]{HumanBodyBones.Hips,HumanBodyBones.Spine,HumanBodyBones.Chest,HumanBodyBones.UpperChest,HumanBodyBones.Neck,HumanBodyBones.Head},
                new[]{HumanBodyBones.Chest,HumanBodyBones.LeftShoulder,HumanBodyBones.LeftUpperArm,HumanBodyBones.LeftLowerArm,HumanBodyBones.LeftHand},
                new[]{HumanBodyBones.Chest,HumanBodyBones.RightShoulder,HumanBodyBones.RightUpperArm,HumanBodyBones.RightLowerArm,HumanBodyBones.RightHand},
                new[]{HumanBodyBones.Hips,HumanBodyBones.LeftUpperLeg,HumanBodyBones.LeftLowerLeg,HumanBodyBones.LeftFoot},
                new[]{HumanBodyBones.Hips,HumanBodyBones.RightUpperLeg,HumanBodyBones.RightLowerLeg,HumanBodyBones.RightFoot}
            };
            float bestDistance=float.PositiveInfinity;
            bool found=false;
            foreach(var chain in chains)
            {
                Transform previous=null;
                foreach(var bone in chain)
                {
                    var current=animator.GetBoneTransform(bone);
                    if(!current)continue;
                    if(previous)
                    {
                        Vector3 fromScreen3=_previewCamera.WorldToScreenPoint(previous.position);
                        Vector3 toScreen3=_previewCamera.WorldToScreenPoint(current.position);
                        if(fromScreen3.z>0f&&toScreen3.z>0f)
                        {
                            Vector2 fromScreen=fromScreen3,toScreen=toScreen3;
                            Vector2 segment=toScreen-fromScreen;
                            float t=segment.sqrMagnitude>.001f?Mathf.Clamp01(Vector2.Dot(pointer-fromScreen,segment)/segment.sqrMagnitude):0f;
                            Vector2 closest=Vector2.Lerp(fromScreen,toScreen,t);
                            float distance=(pointer-closest).sqrMagnitude;
                            if(distance<bestDistance)
                            {
                                bestDistance=distance;
                                worldFocus=Vector3.Lerp(previous.position,current.position,t);
                                found=true;
                            }
                        }
                    }
                    previous=current;
                }
            }
            return found;
        }
        static int CategoryCameraPreset(AvatarPartCategory category)=>category==AvatarPartCategory.Head?2:category==AvatarPartCategory.Bottom||category==AvatarPartCategory.Shoes?0:1;

        Color CurrentGarmentColor(AvatarPartCategory category,AvatarGarmentColorSlot slot)
        {
            Color32 custom=_config.GetGarmentColor(category,slot);if(custom.a>0)return custom;
            var item=category==AvatarPartCategory.Hat
                ?_catalog.ResolveHat(_config.hatId,_catalog.Get(_config.hairId)?.hairGroup??HairGroup.None)
                :_catalog.Get(CurrentItemId(category));
            string property=GarmentPropertyName(slot);
            if(item)
            {
                IEnumerable<Renderer> renderers=item.meshes.Where(x=>x).Cast<Renderer>();
                renderers=renderers.Concat(item.objectPrefabs.Where(x=>x).SelectMany(x=>x.GetComponentsInChildren<Renderer>(true)));
                foreach(var renderer in renderers)
                    foreach(var material in renderer.sharedMaterials)
                        if(material&&material.HasProperty(property))
                        {
                            var source=material.GetColor(property);source.a=1f;return source;
                        }
            }
            return new Color(.45f,.47f,.5f,1);
        }

        int CurrentItemId(AvatarPartCategory category)=>category switch{AvatarPartCategory.Head=>_config.headId,AvatarPartCategory.Hair=>_config.hairId,AvatarPartCategory.Hat=>_config.hatId,AvatarPartCategory.Glasses=>_config.glassesId,AvatarPartCategory.Top=>_config.topId,AvatarPartCategory.Bottom=>_config.bottomId,AvatarPartCategory.Outfit=>_config.outfitId,AvatarPartCategory.Shoes=>_config.shoesId,_=>0};
        bool IsSelected(AvatarPartCategory category,AvatarItemDefinition item)=>CurrentItemId(category)==(category==AvatarPartCategory.Hat?item.familyId:item.itemId);

        static RectTransform Panel(Transform p,string n,Vector2 min,Vector2 max,Color c){var r=new GameObject(n,typeof(RectTransform),typeof(Image),typeof(Outline)).GetComponent<RectTransform>();r.SetParent(p,false);r.anchorMin=min;r.anchorMax=max;r.offsetMin=r.offsetMax=Vector2.zero;r.GetComponent<Image>().color=c;var o=r.GetComponent<Outline>();o.effectColor=new Color(.55f,.39f,.15f,.42f);o.effectDistance=new Vector2(1,-1);return r;}
        static Image ImageLayer(Transform p,string n,Vector2 min,Vector2 max,Color c){var image=new GameObject(n,typeof(RectTransform),typeof(Image)).GetComponent<Image>();image.transform.SetParent(p,false);Anchor(image.rectTransform,min,max);image.color=c;return image;}
        static void BorderFrame(Transform parent,Color color,float thickness=2f)
        {
            var top=ImageLayer(parent,"Border Top",new Vector2(0,1),new Vector2(1,1),color);top.rectTransform.pivot=new Vector2(.5f,1);top.rectTransform.sizeDelta=new Vector2(0,thickness);
            var bottom=ImageLayer(parent,"Border Bottom",new Vector2(0,0),new Vector2(1,0),color);bottom.rectTransform.pivot=new Vector2(.5f,0);bottom.rectTransform.sizeDelta=new Vector2(0,thickness);
            var left=ImageLayer(parent,"Border Left",new Vector2(0,0),new Vector2(0,1),color);left.rectTransform.pivot=new Vector2(0,.5f);left.rectTransform.sizeDelta=new Vector2(thickness,0);
            var right=ImageLayer(parent,"Border Right",new Vector2(1,0),new Vector2(1,1),color);right.rectTransform.pivot=new Vector2(1,.5f);right.rectTransform.sizeDelta=new Vector2(thickness,0);
            foreach(var image in new[]{top,bottom,left,right})image.raycastTarget=false;
        }
        static void DecorativeDivider(Transform parent,float y)
        {
            var holder=new GameObject("Gold Divider",typeof(RectTransform)).GetComponent<RectTransform>();holder.SetParent(parent,false);holder.anchorMin=new Vector2(.07f,1);holder.anchorMax=new Vector2(.93f,1);holder.pivot=new Vector2(.5f,1);holder.anchoredPosition=new Vector2(0,-y);holder.sizeDelta=new Vector2(0,18);
            var gold=new Color(.82f,.58f,.20f,.88f);
            ImageLayer(holder,"Left",new Vector2(0,.44f),new Vector2(.47f,.56f),gold);
            ImageLayer(holder,"Right",new Vector2(.53f,.44f),new Vector2(1,.56f),gold);
            var diamond=ImageLayer(holder,"Diamond",new Vector2(.485f,.14f),new Vector2(.515f,.86f),gold);diamond.rectTransform.localRotation=Quaternion.Euler(0,0,45);
        }
        static void SectionRule(Transform parent,Vector2 min,Vector2 max)
        {
            var gold=new Color(.62f,.45f,.22f,.55f);
            var line=ImageLayer(parent,"Section Rule",min,max,gold);line.raycastTarget=false;
            var diamond=ImageLayer(parent,"Section Diamond",new Vector2(min.x-.018f,min.y-.004f),new Vector2(min.x-.003f,max.y+.004f),new Color(.78f,.57f,.27f,.8f));diamond.raycastTarget=false;diamond.rectTransform.localRotation=Quaternion.Euler(0,0,45);
        }
        static void Anchor(RectTransform r,Vector2 min,Vector2 max){r.anchorMin=min;r.anchorMax=max;r.offsetMin=r.offsetMax=Vector2.zero;}
        static RectTransform Vertical(Transform p,float top){var r=new GameObject("List",typeof(RectTransform),typeof(VerticalLayoutGroup),typeof(ContentSizeFitter)).GetComponent<RectTransform>();r.SetParent(p,false);r.anchorMin=new Vector2(.08f,0);r.anchorMax=new Vector2(.92f,1);r.offsetMin=new Vector2(0,18);r.offsetMax=new Vector2(0,-top);var l=r.GetComponent<VerticalLayoutGroup>();l.spacing=7;l.childAlignment=TextAnchor.UpperCenter;l.childControlWidth=true;l.childForceExpandWidth=true;l.childControlHeight=true;l.childForceExpandHeight=false;return r;}
        static RectTransform Horizontal(Transform p,float y,Vector2? min=null,Vector2? max=null){var r=new GameObject("Row",typeof(RectTransform),typeof(HorizontalLayoutGroup)).GetComponent<RectTransform>();r.SetParent(p,false);r.anchorMin=min??new Vector2(.03f,1);r.anchorMax=max??new Vector2(.97f,1);r.pivot=new Vector2(.5f,1);r.anchoredPosition=new Vector2(0,-y);r.sizeDelta=new Vector2(0,45);var l=r.GetComponent<HorizontalLayoutGroup>();l.spacing=6;l.childAlignment=TextAnchor.MiddleCenter;l.childControlWidth=true;l.childForceExpandWidth=false;l.childControlHeight=true;l.childForceExpandHeight=false;return r;}
        static RectTransform HorizontalScroll(Transform p,float y){var view=new GameObject("Wardrobe Scroll",typeof(RectTransform),typeof(Image),typeof(Mask),typeof(ScrollRect)).GetComponent<RectTransform>();view.SetParent(p,false);view.anchorMin=new Vector2(.01f,1);view.anchorMax=new Vector2(.99f,1);view.pivot=new Vector2(.5f,1);view.anchoredPosition=new Vector2(0,-y);view.sizeDelta=new Vector2(0,70);view.GetComponent<Image>().color=new Color(.01f,.025f,.045f,.78f);view.GetComponent<Mask>().showMaskGraphic=false;var content=new GameObject("Wardrobe Row",typeof(RectTransform),typeof(HorizontalLayoutGroup),typeof(ContentSizeFitter)).GetComponent<RectTransform>();content.SetParent(view,false);content.anchorMin=new Vector2(0,0);content.anchorMax=new Vector2(0,1);content.pivot=new Vector2(0,.5f);content.anchoredPosition=Vector2.zero;content.sizeDelta=Vector2.zero;var layout=content.GetComponent<HorizontalLayoutGroup>();layout.padding=new RectOffset(3,3,1,1);layout.spacing=4;layout.childAlignment=TextAnchor.MiddleLeft;layout.childControlWidth=true;layout.childForceExpandWidth=false;layout.childControlHeight=true;layout.childForceExpandHeight=false;content.GetComponent<ContentSizeFitter>().horizontalFit=ContentSizeFitter.FitMode.PreferredSize;var scroll=view.GetComponent<ScrollRect>();scroll.viewport=view;scroll.content=content;scroll.horizontal=true;scroll.vertical=false;scroll.scrollSensitivity=24;return content;}
        static RectTransform ColorList(Transform p){var r=new GameObject("Color List",typeof(RectTransform),typeof(VerticalLayoutGroup)).GetComponent<RectTransform>();r.SetParent(p,false);Anchor(r,new Vector2(.06f,.20f),new Vector2(.94f,.52f));var layout=r.GetComponent<VerticalLayoutGroup>();layout.spacing=5;layout.padding=new RectOffset(0,0,2,2);layout.childAlignment=TextAnchor.UpperCenter;layout.childControlWidth=true;layout.childForceExpandWidth=true;layout.childControlHeight=true;layout.childForceExpandHeight=false;return r;}
        static RectTransform ScrollGrid(Transform p)=>ScrollGrid(p,new Vector2(.06f,.37f),new Vector2(.94f,.71f),4,new Vector2(70,82));
        static RectTransform ScrollGrid(Transform p,Vector2 min,Vector2 max,int columns,Vector2 cellSize){var view=new GameObject("Item Scroll",typeof(RectTransform),typeof(Image),typeof(Mask),typeof(ScrollRect)).GetComponent<RectTransform>();view.SetParent(p,false);view.anchorMin=min;view.anchorMax=max;view.offsetMin=view.offsetMax=Vector2.zero;view.GetComponent<Image>().color=new Color(.02f,.03f,.05f,.45f);view.GetComponent<Mask>().showMaskGraphic=false;var content=new GameObject("Grid",typeof(RectTransform),typeof(GridLayoutGroup),typeof(ContentSizeFitter)).GetComponent<RectTransform>();content.SetParent(view,false);content.anchorMin=new Vector2(0,1);content.anchorMax=new Vector2(1,1);content.pivot=new Vector2(.5f,1);content.anchoredPosition=Vector2.zero;content.sizeDelta=Vector2.zero;var grid=content.GetComponent<GridLayoutGroup>();grid.cellSize=cellSize;grid.spacing=new Vector2(7,8);grid.padding=new RectOffset(5,5,5,5);grid.constraint=GridLayoutGroup.Constraint.FixedColumnCount;grid.constraintCount=columns;content.GetComponent<ContentSizeFitter>().verticalFit=ContentSizeFitter.FitMode.PreferredSize;var scroll=view.GetComponent<ScrollRect>();scroll.viewport=view;scroll.content=content;scroll.horizontal=false;scroll.vertical=true;scroll.scrollSensitivity=30;return content;}
        static Text Label(Transform p,string s,int size,float h,Vector2? min=null,Vector2? max=null){var t=new GameObject("Label",typeof(RectTransform),typeof(Text)).GetComponent<Text>();t.transform.SetParent(p,false);t.text=s;s_uiFont??=Resources.Load<Font>("Fonts/MalgunGothicLight");t.font=s_uiFont?s_uiFont:Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");t.fontSize=size;t.color=Color.white;t.alignment=TextAnchor.MiddleCenter;var r=t.rectTransform;r.anchorMin=min??new Vector2(0,1);r.anchorMax=max??new Vector2(1,1);r.pivot=new Vector2(.5f,1);r.sizeDelta=new Vector2(0,h);return t;}
        static InputField HexInput(Transform parent,float width,float height)
        {
            var input=new GameObject("HEX 색상 코드",typeof(RectTransform),typeof(Image),typeof(InputField),typeof(LayoutElement),typeof(Outline)).GetComponent<InputField>();input.transform.SetParent(parent,false);
            input.image.color=new Color(.025f,.045f,.07f,.99f);var le=input.GetComponent<LayoutElement>();le.preferredWidth=width;le.preferredHeight=height;
            var outline=input.GetComponent<Outline>();outline.effectColor=new Color(.35f,.52f,.68f,.72f);outline.effectDistance=new Vector2(1,-1);
            var text=Label(input.transform,"#FFFFFF",22,height);Anchor(text.rectTransform,new Vector2(.08f,0),new Vector2(.95f,1));text.alignment=TextAnchor.MiddleLeft;
            input.textComponent=text;input.characterLimit=7;input.lineType=InputField.LineType.SingleLine;input.contentType=InputField.ContentType.Standard;
            BorderFrame(input.transform,new Color(.32f,.58f,.76f,.95f));
            return input;
        }
        static Button Button(Transform p,string s,UnityEngine.Events.UnityAction click,float w=250,float h=44,Color? color=null){var b=new GameObject(s,typeof(RectTransform),typeof(Image),typeof(Button),typeof(LayoutElement),typeof(Outline)).GetComponent<Button>();b.transform.SetParent(p,false);b.image.color=color??new Color(.10f,.13f,.20f,.98f);var le=b.GetComponent<LayoutElement>();le.preferredWidth=w;le.preferredHeight=h;var colors=b.colors;colors.normalColor=Color.white;colors.highlightedColor=new Color(1.16f,1.11f,.98f,1);colors.pressedColor=new Color(.72f,.66f,.54f,1);colors.selectedColor=new Color(1.08f,.96f,.72f,1);colors.fadeDuration=.12f;b.colors=colors;var o=b.GetComponent<Outline>();o.effectColor=new Color(.65f,.48f,.22f,.42f);o.effectDistance=new Vector2(1,-1);var t=Label(b.transform,s,16,h);t.fontStyle=FontStyle.Bold;t.rectTransform.anchorMin=Vector2.zero;t.rectTransform.anchorMax=Vector2.one;t.rectTransform.offsetMin=t.rectTransform.offsetMax=Vector2.zero;b.onClick.AddListener(click);return b;}
        static Button ActionButton(Transform p,string label,string icon,UnityEngine.Events.UnityAction click)
        {
            var button=Button(p,label,click,300,52,new Color(.055f,.06f,.075f,.99f));
            button.GetComponent<Outline>().effectColor=new Color(.67f,.49f,.24f,.78f);
            BorderFrame(button.transform,new Color(.51f,.37f,.18f,.72f),1.4f);
            var text=button.GetComponentInChildren<Text>();text.fontSize=17;text.color=new Color(.91f,.87f,.76f);Anchor(text.rectTransform,new Vector2(.08f,0),new Vector2(.82f,1));
            var iconHolder=new GameObject("Action Icon",typeof(RectTransform)).GetComponent<RectTransform>();iconHolder.SetParent(button.transform,false);iconHolder.anchorMin=iconHolder.anchorMax=new Vector2(.88f,.5f);iconHolder.pivot=new Vector2(.5f,.5f);iconHolder.anchoredPosition=Vector2.zero;iconHolder.sizeDelta=new Vector2(27,27);
            var gold=new Color(.91f,.82f,.62f,.96f);
            if(icon=="▣")
            {
                var body=ImageLayer(iconHolder,"Camera Body",new Vector2(0,.14f),new Vector2(1,.82f),new Color(.08f,.085f,.09f,1));BorderFrame(body.transform,gold,1.7f);
                var lens=ImageLayer(iconHolder,"Camera Lens",new Vector2(.31f,.25f),new Vector2(.69f,.69f),gold);lens.sprite=CircleSprite();
                var top=ImageLayer(iconHolder,"Camera Top",new Vector2(.17f,.78f),new Vector2(.47f,1),gold);top.raycastTarget=false;
            }
            else
            {
                var page=ImageLayer(iconHolder,"Document",new Vector2(.08f,0),new Vector2(.92f,1),new Color(.08f,.085f,.09f,1));BorderFrame(page.transform,gold,1.7f);
                ImageLayer(page.transform,"Line 1",new Vector2(.22f,.62f),new Vector2(.78f,.70f),gold).raycastTarget=false;
                ImageLayer(page.transform,"Line 2",new Vector2(.22f,.40f),new Vector2(.78f,.48f),gold).raycastTarget=false;
                ImageLayer(page.transform,"Line 3",new Vector2(.22f,.18f),new Vector2(.65f,.26f),gold).raycastTarget=false;
            }
            return button;
        }
        static Button CategoryButton(Transform parent,string label,Sprite sprite,UnityEngine.Events.UnityAction click,float width,float height,bool selected)
        {
            var root=new GameObject(label,typeof(RectTransform),typeof(Image),typeof(Button),typeof(LayoutElement)).GetComponent<Button>();root.transform.SetParent(parent,false);root.image.color=Color.clear;
            var layout=root.GetComponent<LayoutElement>();layout.preferredWidth=width;layout.preferredHeight=height;
            var circle=new GameObject("Circular Icon",typeof(RectTransform),typeof(Image),typeof(Outline),typeof(Mask)).GetComponent<Image>();circle.transform.SetParent(root.transform,false);Anchor(circle.rectTransform,new Vector2(.12f,.30f),new Vector2(.88f,.94f));circle.sprite=CircleSprite();circle.color=selected?new Color(.30f,.22f,.08f,.98f):new Color(.025f,.045f,.07f,.98f);circle.GetComponent<Mask>().showMaskGraphic=true;
            var outline=circle.GetComponent<Outline>();outline.effectColor=selected?new Color(1f,.76f,.26f,1):new Color(.53f,.45f,.33f,.72f);outline.effectDistance=selected?new Vector2(3,-3):new Vector2(1.5f,-1.5f);
            if(sprite){var icon=new GameObject("Icon",typeof(RectTransform),typeof(Image)).GetComponent<Image>();icon.transform.SetParent(circle.transform,false);Anchor(icon.rectTransform,new Vector2(.17f,.17f),new Vector2(.83f,.83f));icon.sprite=sprite;icon.preserveAspect=true;icon.color=new Color(.98f,.91f,.75f);icon.raycastTarget=false;}
            var text=Label(root.transform,label,16,28,new Vector2(0,0),new Vector2(1,.29f));text.fontStyle=FontStyle.Bold;text.color=selected?new Color(1f,.86f,.56f):new Color(.88f,.86f,.79f);
            if(selected){var sparkle=Label(root.transform,"✦",11,14,new Vector2(.42f,.01f),new Vector2(.58f,.14f));sparkle.color=new Color(1f,.79f,.3f);}
            root.onClick.AddListener(click);return root;
        }
        static Sprite CircleSprite()
        {
            if(s_circleSprite)return s_circleSprite;
            const int size=96;var texture=new Texture2D(size,size,TextureFormat.RGBA32,false){name="Runtime Circle UI"};var pixels=new Color32[size*size];float center=(size-1)*.5f;float radius=center-1;
            for(int y=0;y<size;y++)for(int x=0;x<size;x++){float distance=Vector2.Distance(new Vector2(x,y),new Vector2(center,center));byte alpha=(byte)Mathf.RoundToInt(Mathf.Clamp01(radius-distance+1f)*255f);pixels[y*size+x]=new Color32(255,255,255,alpha);}
            texture.SetPixels32(pixels);texture.Apply();texture.wrapMode=TextureWrapMode.Clamp;texture.filterMode=FilterMode.Bilinear;
            s_circleSprite=Sprite.Create(texture,new Rect(0,0,size,size),new Vector2(.5f,.5f),100f);s_circleSprite.name="Runtime Circle UI Sprite";return s_circleSprite;
        }
        static Button ImageButton(Transform p,string label,Sprite sprite,UnityEngine.Events.UnityAction click,float w,float h,bool selected=false,Color? swatch=null)
        {
            var b=new GameObject(string.IsNullOrEmpty(label)?"Preview":label,typeof(RectTransform),typeof(Image),typeof(Button),typeof(LayoutElement),typeof(Outline)).GetComponent<Button>();b.transform.SetParent(p,false);
            b.image.color=selected?new Color(.25f,.19f,.08f,.98f):new Color(.025f,.045f,.07f,.98f);var le=b.GetComponent<LayoutElement>();le.preferredWidth=w;le.preferredHeight=h;
            var o=b.GetComponent<Outline>();o.effectColor=selected?new Color(.95f,.72f,.25f,.95f):new Color(.16f,.38f,.55f,.75f);o.effectDistance=new Vector2(1.5f,-1.5f);
            if(sprite){var preview=new GameObject("Preview",typeof(RectTransform),typeof(Image)).GetComponent<Image>();preview.transform.SetParent(b.transform,false);preview.sprite=sprite;preview.preserveAspect=true;preview.color=Color.white;preview.rectTransform.anchorMin=new Vector2(.06f,.23f);preview.rectTransform.anchorMax=new Vector2(.94f,.96f);preview.rectTransform.offsetMin=preview.rectTransform.offsetMax=Vector2.zero;}
            else if(swatch.HasValue){var preview=new GameObject("Color Swatch",typeof(RectTransform),typeof(Image),typeof(Outline)).GetComponent<Image>();preview.transform.SetParent(b.transform,false);preview.color=swatch.Value;Anchor(preview.rectTransform,new Vector2(.28f,.34f),new Vector2(.72f,.84f));var border=preview.GetComponent<Outline>();border.effectColor=new Color(1,1,1,.7f);border.effectDistance=new Vector2(1,-1);}
            if(!string.IsNullOrEmpty(label)){var text=Label(b.transform,label,12,h*.23f);text.rectTransform.anchorMin=Vector2.zero;text.rectTransform.anchorMax=new Vector2(1,.24f);text.rectTransform.offsetMin=text.rectTransform.offsetMax=Vector2.zero;text.fontStyle=FontStyle.Bold;}
            b.onClick.AddListener(click);return b;
        }
        static Button FaceCardButton(Transform p,string label,Sprite sprite,UnityEngine.Events.UnityAction click,float w,float h,bool selected)
        {
            var button=ImageButton(p,label,sprite,click,w,h,selected);
            button.image.color=selected?new Color(.20f,.15f,.055f,.99f):new Color(.025f,.04f,.065f,.99f);
            var outline=button.GetComponent<Outline>();outline.effectColor=selected?new Color(1f,.75f,.22f,1):new Color(.38f,.40f,.39f,.8f);outline.effectDistance=selected?new Vector2(2,-2):new Vector2(1,-1);
            BorderFrame(button.transform,selected?new Color(.95f,.69f,.21f,.9f):new Color(.33f,.34f,.32f,.62f),1f);
            var text=button.GetComponentInChildren<Text>();if(text){text.fontSize=13;text.color=new Color(.95f,.88f,.72f);}
            return button;
        }
        static Button HairCardButton(Transform p,string label,Sprite sprite,UnityEngine.Events.UnityAction click,float w,float h,bool selected)
        {
            var button=ImageButton(p,label,sprite,click,w,h,selected);
            var text=button.GetComponentInChildren<Text>();if(text){text.fontSize=10;text.resizeTextForBestFit=true;text.resizeTextMinSize=8;text.resizeTextMaxSize=10;Anchor(text.rectTransform,new Vector2(.02f,0),new Vector2(.98f,.33f));}
            var preview=button.transform.Find("Preview") as RectTransform;if(preview)Anchor(preview,new Vector2(.07f,.34f),new Vector2(.93f,.96f));
            return button;
        }
        static Button ColorRow(Transform p,string label,Color swatch,UnityEngine.Events.UnityAction click,bool selected)
        {
            var b=new GameObject(label,typeof(RectTransform),typeof(Image),typeof(Button),typeof(LayoutElement),typeof(Outline)).GetComponent<Button>();b.transform.SetParent(p,false);b.image.color=selected?new Color(.24f,.18f,.07f,.98f):new Color(.025f,.045f,.07f,.98f);var le=b.GetComponent<LayoutElement>();le.preferredHeight=35;le.minHeight=33;
            var outline=b.GetComponent<Outline>();outline.effectColor=selected?new Color(.95f,.72f,.25f,.95f):new Color(.16f,.38f,.55f,.75f);outline.effectDistance=new Vector2(1,-1);
            le.preferredHeight=40;le.minHeight=38;
            var iconSprite=ColorIcon(label);
            if(iconSprite){var icon=new GameObject(label+" Icon",typeof(RectTransform),typeof(Image)).GetComponent<Image>();icon.transform.SetParent(b.transform,false);Anchor(icon.rectTransform,new Vector2(.025f,.12f),new Vector2(.13f,.88f));icon.sprite=iconSprite;icon.preserveAspect=true;icon.color=Color.white;icon.raycastTarget=false;}
            else{var glyph=Label(b.transform,ColorGlyph(label),17,40);Anchor(glyph.rectTransform,new Vector2(.035f,0),new Vector2(.13f,1));glyph.color=GlyphColor(label);glyph.fontStyle=FontStyle.Bold;}
            var text=Label(b.transform,label,14,40);Anchor(text.rectTransform,new Vector2(.15f,0),new Vector2(.70f,1));text.alignment=TextAnchor.MiddleLeft;text.fontStyle=FontStyle.Bold;text.color=new Color(.91f,.89f,.82f);
            var color=new GameObject("Color Swatch",typeof(RectTransform),typeof(Image),typeof(Outline)).GetComponent<Image>();color.transform.SetParent(b.transform,false);Anchor(color.rectTransform,new Vector2(.78f,.16f),new Vector2(.94f,.84f));color.color=swatch;var border=color.GetComponent<Outline>();border.effectColor=new Color(.88f,.86f,.8f,.85f);border.effectDistance=new Vector2(1,-1);
            BorderFrame(b.transform,new Color(.25f,.29f,.34f,.65f),1f);
            b.onClick.AddListener(click);return b;
        }
        static string ColorGlyph(string label)=>label switch{"피부"=>"●","흰자위"=>"◉","홍채"=>"◉","동공"=>"●","눈썹"=>"⌒","입술"=>"●",_=>"◆"};
        static Color GlyphColor(string label)=>label switch{"피부"=>new Color(.88f,.73f,.62f),"흰자위"=>new Color(.93f,.95f,.96f),"홍채"=>new Color(.20f,.40f,.68f),"동공"=>new Color(.08f,.07f,.06f),"눈썹"=>new Color(.40f,.31f,.23f),"입술"=>new Color(.68f,.31f,.36f),_=>new Color(.82f,.65f,.35f)};
        static Sprite ColorIcon(string label)
        {
            if(s_colorIcons.TryGetValue(label,out var cached))return cached;
            string assetName=label switch{"피부"=>"color_skin","흰자위"=>"color_sclera","홍채"=>"color_iris","동공"=>"color_pupil","눈썹"=>"color_eyebrow","입술"=>"color_lips",_=>string.Empty};
            if(string.IsNullOrEmpty(assetName)){s_colorIcons[label]=null;return null;}
            var source=Resources.Load<Texture2D>("Avatar/UI/ColorIcons/"+assetName);
            var sprite=source?CreateTransparentIcon(source,assetName):null;s_colorIcons[label]=sprite;return sprite;
        }
        static string FaceDisplayName(int index)=>index switch{0=>"또렷한 얼굴",1=>"차분한 얼굴",2=>"순한 얼굴",3=>"날렵한 얼굴",_=>$"얼굴 {index+1:00}"};
        static Sprite FaceThumbnail(int index)
        {
            if(s_faceThumbnails.TryGetValue(index,out var cached))return cached;
            var source=Resources.Load<Texture2D>("Avatar/UI/FaceThumbnails/face_shapes");
            var sprite=source&&index>=0&&index<4?CreateTransparentFaceThumbnail(source,index):null;s_faceThumbnails[index]=sprite;return sprite;
        }
        static string HairDisplayName(int index)=>index>=0&&index<s_hairDisplayNames.Length?s_hairDisplayNames[index]:$"헤어 {index+1:00}";
        static Sprite HairThumbnail(int index)
        {
            if(s_hairThumbnails.TryGetValue(index,out var cached))return cached;
            if(index<0||index>=s_hairDisplayNames.Length){s_hairThumbnails[index]=null;return null;}
            var source=Resources.Load<Texture2D>($"Avatar/UI/HairThumbnails/Individual/hair_{index+1:00}");
            var sprite=source?CreateTransparentGridThumbnail(source,Vector2.one,Vector2.zero,$"Hair {index+1:00}"):null;
            s_hairThumbnails[index]=sprite;
            return sprite;
        }
        static Sprite CreateTransparentGridThumbnail(Texture2D source,Vector2 scale,Vector2 offset,string name)
        {
            int sourceWidth=Mathf.Max(2,Mathf.RoundToInt(source.width*scale.x)),sourceHeight=Mathf.Max(2,Mathf.RoundToInt(source.height*scale.y));
            float resize=Mathf.Min(1f,224f/Mathf.Max(sourceWidth,sourceHeight));int width=Mathf.Max(2,Mathf.RoundToInt(sourceWidth*resize)),height=Mathf.Max(2,Mathf.RoundToInt(sourceHeight*resize));
            var previous=RenderTexture.active;var temporary=RenderTexture.GetTemporary(width,height,0,RenderTextureFormat.ARGB32,RenderTextureReadWrite.sRGB);Graphics.Blit(source,temporary,scale,offset);RenderTexture.active=temporary;
            var sampled=new Texture2D(width,height,TextureFormat.RGBA32,false){name=name+" Sampled Thumbnail"};sampled.ReadPixels(new Rect(0,0,width,height),0,0,false);sampled.Apply();RenderTexture.active=previous;RenderTexture.ReleaseTemporary(temporary);
            var pixels=sampled.GetPixels32();int minX=width,minY=height,maxX=-1,maxY=-1;
            for(int y=0;y<height;y++)for(int x=0;x<width;x++)
            {
                int pixelIndex=y*width+x;var color=pixels[pixelIndex];int min=Mathf.Min(color.r,Mathf.Min(color.g,color.b));int max=Mathf.Max(color.r,Mathf.Max(color.g,color.b));
                if(min>238&&max-min<18)color.a=0;
                else if(color.a>0&&color.a<245&&min>95&&max-min<55)
                {
                    // removebg-style sources often keep white matte RGB in translucent edge pixels.
                    // Pull the colour from a nearby opaque hair pixel while preserving antialias alpha.
                    bool replaced=false;
                    for(int radius=1;radius<=4&&!replaced;radius++)
                    for(int oy=-radius;oy<=radius&&!replaced;oy++)
                    for(int ox=-radius;ox<=radius;ox++)
                    {
                        int nx=x+ox,ny=y+oy;if(nx<0||ny<0||nx>=width||ny>=height)continue;
                        var neighbour=pixels[ny*width+nx];int neighbourMax=Mathf.Max(neighbour.r,Mathf.Max(neighbour.g,neighbour.b));
                        if(neighbour.a>235&&neighbourMax<190){color.r=neighbour.r;color.g=neighbour.g;color.b=neighbour.b;replaced=true;break;}
                    }
                    if(!replaced)color.a=0;
                }
                if(color.a>18){minX=Mathf.Min(minX,x);minY=Mathf.Min(minY,y);maxX=Mathf.Max(maxX,x);maxY=Mathf.Max(maxY,y);}pixels[pixelIndex]=color;
            }
            if(maxX<minX){sampled.SetPixels32(pixels);sampled.Apply();return Sprite.Create(sampled,new Rect(0,0,width,height),new Vector2(.5f,.5f),100f);}
            int contentWidth=maxX-minX+1,contentHeight=maxY-minY+1;const int padding=8;int side=Mathf.Max(contentWidth,contentHeight)+padding*2;
            var centered=new Texture2D(side,side,TextureFormat.RGBA32,false){name=name+" Centered Thumbnail"};var centeredPixels=new Color32[side*side];int destinationX=(side-contentWidth)/2,destinationY=(side-contentHeight)/2;
            for(int y=0;y<contentHeight;y++)for(int x=0;x<contentWidth;x++)centeredPixels[(destinationY+y)*side+destinationX+x]=pixels[(minY+y)*width+minX+x];
            centered.SetPixels32(centeredPixels);centered.Apply();centered.wrapMode=TextureWrapMode.Clamp;centered.filterMode=FilterMode.Bilinear;
            if(Application.isPlaying)Destroy(sampled);else DestroyImmediate(sampled);
            var sprite=Sprite.Create(centered,new Rect(0,0,side,side),new Vector2(.5f,.5f),100f);sprite.name=name+" Runtime Sprite";return sprite;
        }
        static Sprite CreateTransparentFaceThumbnail(Texture2D source,int index)
        {
            const int size=320;var previous=RenderTexture.active;
            var temporary=RenderTexture.GetTemporary(size,size,0,RenderTextureFormat.ARGB32,RenderTextureReadWrite.sRGB);
            var scale=new Vector2(.5f,.5f);var offset=index switch{0=>new Vector2(0,.5f),1=>new Vector2(.5f,.5f),2=>Vector2.zero,_=>new Vector2(.5f,0)};
            Graphics.Blit(source,temporary,scale,offset);RenderTexture.active=temporary;
            var texture=new Texture2D(size,size,TextureFormat.RGBA32,false){name=$"Face Shape {index+1:00} Runtime Thumbnail"};texture.ReadPixels(new Rect(0,0,size,size),0,0,false);texture.Apply();RenderTexture.active=previous;RenderTexture.ReleaseTemporary(temporary);
            var pixels=texture.GetPixels32();int minX=size,minY=size,maxX=-1,maxY=-1;
            for(int y=0;y<size;y++)for(int x=0;x<size;x++)
            {
                int pixelIndex=y*size+x;var color=pixels[pixelIndex];int min=Mathf.Min(color.r,Mathf.Min(color.g,color.b));int max=Mathf.Max(color.r,Mathf.Max(color.g,color.b));
                if(min>238&&max-min<18)color.a=0;
                if(color.a>18){minX=Mathf.Min(minX,x);minY=Mathf.Min(minY,y);maxX=Mathf.Max(maxX,x);maxY=Mathf.Max(maxY,y);}pixels[pixelIndex]=color;
            }
            texture.SetPixels32(pixels);texture.Apply();texture.wrapMode=TextureWrapMode.Clamp;texture.filterMode=FilterMode.Bilinear;
            var padding=4;minX=Mathf.Max(0,minX-padding);minY=Mathf.Max(0,minY-padding);maxX=Mathf.Min(size-1,maxX+padding);maxY=Mathf.Min(size-1,maxY+padding);
            var rect=maxX>=minX?new Rect(minX,minY,maxX-minX+1,maxY-minY+1):new Rect(0,0,size,size);
            var sprite=Sprite.Create(texture,rect,new Vector2(.5f,.5f),100f);sprite.name=$"Face Shape {index+1:00} Runtime Sprite";return sprite;
        }
        static Sprite CreateTransparentIcon(Texture2D source,string name)
        {
            const int size=256;
            var previous=RenderTexture.active;
            var temporary=RenderTexture.GetTemporary(size,size,0,RenderTextureFormat.ARGB32,RenderTextureReadWrite.sRGB);Graphics.Blit(source,temporary);RenderTexture.active=temporary;
            var texture=new Texture2D(size,size,TextureFormat.RGBA32,false){name=name+" Runtime Icon"};texture.ReadPixels(new Rect(0,0,size,size),0,0,false);texture.Apply();RenderTexture.active=previous;RenderTexture.ReleaseTemporary(temporary);
            var pixels=texture.GetPixels32();int minX=size,minY=size,maxX=-1,maxY=-1;
            for(int y=0;y<size;y++)for(int x=0;x<size;x++)
            {
                int index=y*size+x;var color=pixels[index];int min=Mathf.Min(color.r,Mathf.Min(color.g,color.b));int max=Mathf.Max(color.r,Mathf.Max(color.g,color.b));
                if(min>238&&max-min<18)color.a=0;
                if(color.a>18){minX=Mathf.Min(minX,x);minY=Mathf.Min(minY,y);maxX=Mathf.Max(maxX,x);maxY=Mathf.Max(maxY,y);}pixels[index]=color;
            }
            texture.SetPixels32(pixels);texture.Apply();texture.wrapMode=TextureWrapMode.Clamp;texture.filterMode=FilterMode.Bilinear;
            var rect=maxX>=minX?new Rect(minX,minY,maxX-minX+1,maxY-minY+1):new Rect(0,0,size,size);
            var sprite=Sprite.Create(texture,rect,new Vector2(.5f,.5f),100f);sprite.name=name+" Runtime Sprite";return sprite;
        }
        static string CategoryName(AvatarPartCategory c)=>c switch{AvatarPartCategory.Head=>"얼굴",AvatarPartCategory.Hair=>"헤어",AvatarPartCategory.Hat=>"모자",AvatarPartCategory.Glasses=>"액세서리",AvatarPartCategory.Top=>"상의",AvatarPartCategory.Bottom=>"하의",AvatarPartCategory.Outfit=>"한벌옷",AvatarPartCategory.Shoes=>"신발",_=>c.ToString()};
        static Sprite CategoryIcon(AvatarPartCategory category)
        {
            if(s_categoryIcons.TryGetValue(category,out var cached))return cached;
            string assetName=category switch
            {
                AvatarPartCategory.Head=>"category_head",
                AvatarPartCategory.Hair=>"category_hair",
                AvatarPartCategory.Hat=>"category_hat",
                AvatarPartCategory.Glasses=>"category_glasses",
                AvatarPartCategory.Top=>"category_top",
                AvatarPartCategory.Bottom=>"category_bottom",
                AvatarPartCategory.Outfit=>"category_outfit",
                AvatarPartCategory.Shoes=>"category_shoes",
                _=>string.Empty
            };
            var sprite=string.IsNullOrEmpty(assetName)?null:Resources.Load<Sprite>("Avatar/UI/CategoryIcons/"+assetName);
            s_categoryIcons[category]=sprite;
            return sprite;
        }
        static string ColorSlotName(AvatarColorSlot s)=>s switch{AvatarColorSlot.Skin=>"피부",AvatarColorSlot.Hair=>"헤어",AvatarColorSlot.Sclera=>"흰자위",AvatarColorSlot.Iris=>"홍채",AvatarColorSlot.Pupil=>"동공",AvatarColorSlot.Eyebrow=>"눈썹",AvatarColorSlot.Lips=>"입술",AvatarColorSlot.Top=>"상의",AvatarColorSlot.Bottom=>"하의",_=>s.ToString()};
        static string GarmentColorSlotName(AvatarGarmentColorSlot s)=>s switch{AvatarGarmentColorSlot.A1=>"영역 A · 어두운색",AvatarGarmentColorSlot.A2=>"영역 A · 밝은색",AvatarGarmentColorSlot.B1=>"영역 B · 어두운색",AvatarGarmentColorSlot.B2=>"영역 B · 밝은색",AvatarGarmentColorSlot.C1=>"영역 C · 어두운색",AvatarGarmentColorSlot.C2=>"영역 C · 밝은색",_=>s.ToString()};
        static string HatColorSlotName(AvatarGarmentColorSlot s)=>s switch{AvatarGarmentColorSlot.A1=>"본체 · 어두운색",AvatarGarmentColorSlot.A2=>"본체 · 밝은색",AvatarGarmentColorSlot.B1=>"챙·밴드/바이저 · 어두운색",AvatarGarmentColorSlot.B2=>"챙·밴드/바이저 · 밝은색",AvatarGarmentColorSlot.C1=>"장식 · 어두운색",AvatarGarmentColorSlot.C2=>"장식 · 밝은색",_=>s.ToString()};
        static string GlassesColorSlotName(AvatarGarmentColorSlot s)=>s switch{AvatarGarmentColorSlot.A1=>"프레임 · 어두운색",AvatarGarmentColorSlot.A2=>"프레임 · 밝은색",AvatarGarmentColorSlot.B1=>"브리지·다리 · 어두운색",AvatarGarmentColorSlot.B2=>"렌즈·영역 B · 밝은색",AvatarGarmentColorSlot.C1=>"장식 · 어두운색",AvatarGarmentColorSlot.C2=>"장식 · 밝은색",_=>s.ToString()};
        static string GarmentPropertyName(AvatarGarmentColorSlot s)=>s switch{AvatarGarmentColorSlot.A1=>"_Color_A_1",AvatarGarmentColorSlot.A2=>"_Color_A_2",AvatarGarmentColorSlot.B1=>"_Color_B_1",AvatarGarmentColorSlot.B2=>"_Color_B_2",AvatarGarmentColorSlot.C1=>"_Color_C_1",AvatarGarmentColorSlot.C2=>"_Color_C_2",_=>"_Color_A_1"};
        static string PrettyName(string value)=>value.Replace("Shared_","").Replace("Hairstyle.","헤어 ").Replace("Head.","얼굴 ").Replace("Top.","상의 ").Replace("Bot.","하의 ").Replace("Outfit.","한벌옷 ").Replace("Shoes.","신발 ").Replace("Hat.","모자 ").Replace("Glasses.","안경 ");
    }
}
