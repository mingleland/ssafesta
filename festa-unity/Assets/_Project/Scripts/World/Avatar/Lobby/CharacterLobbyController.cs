using System;
using System.Collections.Generic;
using System.Linq;
using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.SceneManagement;
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
        AvatarPartCategory _wardrobeCategory = AvatarPartCategory.Top;
        AvatarColorSlot _colorSlot = AvatarColorSlot.Hair;
        AvatarGarmentColorSlot _garmentColorSlot = AvatarGarmentColorSlot.A1;
        AvatarPartCategory _garmentColorCategory = AvatarPartCategory.Top;
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
        bool _restoredExistingAppearance;
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
        static Sprite s_roundedSprite;
        static Font s_uiFont;
        static readonly Color UiPanel = new(.055f,.058f,.075f,.985f);
        static readonly Color UiSurface = new(.105f,.115f,.14f,.99f);
        static readonly Color UiCard = new(.145f,.155f,.185f,.99f);
        static readonly Color UiCardSelected = new(.24f,.18f,.13f,1f);
        static readonly Color UiBorder = new(.34f,.35f,.39f,.94f);
        static readonly Color UiAccent = new(.94f,.62f,.29f,1f);
        static readonly Color UiText = new(.98f,.955f,.91f,1f);
        static readonly Color UiTextMuted = new(.74f,.72f,.69f,1f);
        static readonly Color UiPreview = new(.57f,.58f,.60f,1f);

        static readonly Color[] Palette = { new(1,.8f,.69f), new(.73f,.48f,.34f), new(.42f,.23f,.16f), new(.18f,.12f,.1f), new(.95f,.78f,.55f), new(.12f,.08f,.06f), new(.35f,.18f,.08f), new(.12f,.28f,.45f), new(.2f,.45f,.28f), new(.55f,.18f,.22f), new(.9f,.35f,.45f), new(.1f,.18f,.38f), new(.7f,.12f,.18f), new(.12f,.42f,.48f), new(.15f,.15f,.18f), Color.white };
        static readonly Color[] NaturalSkinColors = {new(.96f,.78f,.67f),new(.88f,.64f,.52f),new(.73f,.46f,.34f),new(.55f,.32f,.23f),new(.36f,.21f,.16f)};
        static readonly Color[] NaturalHairColors = {new(.08f,.065f,.06f),new(.16f,.105f,.08f),new(.28f,.17f,.11f),new(.42f,.25f,.15f),new(.34f,.17f,.12f),new(.62f,.49f,.33f)};
        static readonly Color[] NaturalIrisColors = {new(.20f,.12f,.08f),new(.34f,.23f,.12f),new(.17f,.29f,.39f),new(.24f,.35f,.29f),new(.29f,.31f,.33f)};
        static readonly Color[] NaturalLipColors = {new(.62f,.31f,.31f),new(.70f,.39f,.36f),new(.55f,.27f,.29f),new(.72f,.44f,.40f),new(.48f,.23f,.22f)};
        static readonly Color[][] OutfitColorThemes =
        {
            new[]{new Color(.10f,.18f,.34f),new Color(.88f,.84f,.73f),new Color(.45f,.30f,.20f)},
            new[]{new Color(.18f,.32f,.25f),new Color(.79f,.72f,.58f),new Color(.31f,.22f,.17f)},
            new[]{new Color(.43f,.14f,.18f),new Color(.91f,.86f,.74f),new Color(.18f,.19f,.22f)},
            new[]{new Color(.22f,.36f,.52f),new Color(.92f,.91f,.86f),new Color(.53f,.37f,.24f)},
            new[]{new Color(.17f,.17f,.20f),new Color(.48f,.50f,.53f),new Color(.52f,.20f,.24f)},
            new[]{new Color(.40f,.52f,.62f),new Color(.77f,.58f,.55f),new Color(.91f,.86f,.76f)}
        };

        public void Configure(AvatarCatalog catalog, AvatarAssembler assembler, Camera previewCamera)
        {
            _catalog = catalog; _assembler = assembler; _previewCamera = previewCamera;
        }

        void Awake()
        {
#if UNITY_SERVER
            // Dedicated Server builds share the project's scene list with the
            // WebGL client, whose first scene is the character lobby.  A server
            // must never construct the preview avatar/UI here: the world scene
            // owns NetworkBootstrap and starts Netcode.  Redirect before any
            // lobby renderer, material, or UI work can run.
            SceneManager.LoadScene(Festa.World.AvatarSceneHandoff.WorldSceneName);
            enabled = false;
            return;
#endif
            if (!_assembler) _assembler = GetComponentInChildren<AvatarAssembler>();
            if (!_previewCamera) _previewCamera = Camera.main;
            if (!_catalog || !_assembler || !_previewCamera) { Debug.LogError("[CharacterLobby] 필수 참조가 비어 있습니다.", this); enabled = false; return; }
            _assembler.Catalog = _catalog;
            _assembler.transform.localRotation = Quaternion.Euler(0, 180f, 0);
            _config = TryGetLiveAppearance(out var liveConfig)
                ? liveConfig
                : TryGetSceneHandoffAppearance(out var handoffConfig)
                    ? handoffConfig
                    : CreateRecommendedRandomConfig(AvatarGender.Female, false);
            _assembler.Apply(_config);
            BuildUi(); SetCamera(1); RefreshAll();
            if (!_restoredExistingAppearance) LoadPersistedAppearanceAsync();
        }

        bool TryGetLiveAppearance(out AvatarConfig config)
        {
            config = default;
            var network = Unity.Netcode.NetworkManager.Singleton;
            var player = network != null && network.IsClient ? network.LocalClient?.PlayerObject : null;
            var appearanceController = player ? player.GetComponent<Festa.World.PlayerAppearanceController>() : null;
            if (!appearanceController) return false;
            var appearance = appearanceController.Current;
            if (!appearance.IsModular || !IsUsableAppearance(appearance.ModularConfig)) return false;
            config = appearance.ModularConfig;
            _restoredExistingAppearance = true;
            return true;
        }

        bool TryGetSceneHandoffAppearance(out AvatarConfig config)
        {
            config = default;
            var encoded = Festa.World.AvatarSceneHandoff.GetEncodedOrFallback(string.Empty);
            var appearance = Festa.World.AvatarAppearance.Decode(encoded);
            if (!appearance.IsModular || !IsUsableAppearance(appearance.ModularConfig)) return false;

            config = appearance.ModularConfig;
            _restoredExistingAppearance = true;
            return true;
        }

        async void LoadPersistedAppearanceAsync()
        {
            try
            {
                Festa.Integration.ApiServices.EnsureInitialized();
                var profile = await Festa.Integration.ApiServices.User.GetMyProfileAsync();
                var appearance = Festa.World.AvatarAppearance.Decode(profile?.avatarCode);
                if (!appearance.IsModular || !IsUsableAppearance(appearance.ModularConfig)) return;
                _config = appearance.ModularConfig;
                _restoredExistingAppearance = true;
                _wardrobeCategory = _config.outfitId != 0 ? AvatarPartCategory.Outfit : AvatarPartCategory.Top;
                _garmentColorCategory = _wardrobeCategory;
                Apply(); RefreshAll();
            }
            catch (Exception exception)
            {
                Debug.LogWarning($"[CharacterLobby] 저장 외형을 불러오지 못해 최초 추천 외형을 유지합니다: {exception.Message}", this);
            }
        }

        static bool IsUsableAppearance(AvatarConfig config)
            => config.headId != 0 && config.hairId != 0 && config.shoesId != 0
               && (config.outfitId != 0 || (config.topId != 0 && config.bottomId != 0));

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
            canvas.pixelPerfect = true;
            _uiScaler=canvas.GetComponent<CanvasScaler>();_uiScaler.uiScaleMode=CanvasScaler.ScaleMode.ScaleWithScreenSize;_uiScaler.referenceResolution=new Vector2(1600,900);_uiScaler.screenMatchMode=CanvasScaler.ScreenMatchMode.MatchWidthOrHeight;
            _responsiveFrame=new GameObject("Responsive 16:9 Frame",typeof(RectTransform),typeof(AspectRatioFitter)).GetComponent<RectTransform>();
            _responsiveFrame.SetParent(canvas.transform,false);Anchor(_responsiveFrame,Vector2.zero,Vector2.one);
            var frameAspect=_responsiveFrame.GetComponent<AspectRatioFitter>();frameAspect.aspectMode=AspectRatioFitter.AspectMode.FitInParent;frameAspect.aspectRatio=16f/9f;
            UpdateResponsiveLayout(true);

            // Reference composition: wardrobe rail / focused portrait / appearance inspector.
            var left=Panel(_responsiveFrame,"Preset Rail",Vector2.zero,new Vector2(.245f,1),UiPanel);
            var centerShade=ImageLayer(_responsiveFrame,"Portrait Shade",new Vector2(.245f,0),new Vector2(.715f,1),new Color(.035f,.055f,.08f,.10f));centerShade.raycastTarget=false;_previewArea=centerShade.rectTransform;
            var wardrobeHeader=Label(left,"의상 선택",30,58,new Vector2(.06f,.93f),new Vector2(.94f,.99f));wardrobeHeader.fontStyle=FontStyle.Bold;wardrobeHeader.color=UiText;
            var wardrobeHeaderRect=wardrobeHeader.rectTransform;
            wardrobeHeaderRect.anchorMin=new Vector2(0,1);wardrobeHeaderRect.anchorMax=new Vector2(1,1);wardrobeHeaderRect.pivot=new Vector2(.5f,1);
            wardrobeHeaderRect.anchoredPosition=new Vector2(0,-16);wardrobeHeaderRect.sizeDelta=new Vector2(0,58);
            _wardrobeTabs=Horizontal(left,98,new Vector2(.035f,1),new Vector2(.965f,1));_wardrobeTabs.sizeDelta=new Vector2(0,120);_wardrobeTabs.GetComponent<HorizontalLayoutGroup>().spacing=11;
            _wardrobeTitle=Label(left,"한벌옷 설정",20,38,new Vector2(.06f,.78f),new Vector2(.94f,.83f));_wardrobeTitle.alignment=TextAnchor.MiddleLeft;_wardrobeTitle.color=UiText;
            _wardrobeGrid=ScrollGrid(left,new Vector2(.06f,.43f),new Vector2(.94f,.669f),2,new Vector2(158,148));_wardrobeItemScroll=(RectTransform)_wardrobeGrid.parent;
            _wardrobeColorTitle=Label(left,"의상 세부 색상",18,38,new Vector2(.06f,.45f),new Vector2(.94f,.50f));_wardrobeColorTitle.alignment=TextAnchor.MiddleLeft;_wardrobeColorTitle.color=UiTextMuted;
            _wardrobeColorSlots=ColorList(left);Anchor(_wardrobeColorSlots,new Vector2(.06f,.10f),new Vector2(.94f,.35f));
            var quickRow=Horizontal(left,740,new Vector2(.05f,1),new Vector2(.95f,1));quickRow.sizeDelta=new Vector2(0,52);
            Anchor(_wardrobeTitle.rectTransform,new Vector2(.06f,.684f),new Vector2(.94f,.739f));
            Anchor(_wardrobeColorTitle.rectTransform,new Vector2(.06f,.36f),new Vector2(.94f,.41f));
            Button(quickRow,"성별",()=>{_config=_catalog.CreateDefault(_config.gender==AvatarGender.Female?AvatarGender.Male:AvatarGender.Female);Apply();RefreshAll();},90,46,UiCardSelected);
            Button(quickRow,"무작위",Randomize,90,46);Button(quickRow,"초기화",()=>{_config=_catalog.CreateDefault(_config.gender);Apply();RefreshAll();},90,46);
            var enterWorld=Button(left,"월드 입장",EnterWorld,250,48,UiCardSelected);
            Anchor(enterWorld.GetComponent<RectTransform>(),new Vector2(.06f,.025f),new Vector2(.94f,.085f));


            var right=Panel(_responsiveFrame,"Detail Inspector",new Vector2(.715f,0),Vector2.one,UiPanel);
            var title=Label(right,"나만의 캐릭터",30,58);title.fontStyle=FontStyle.Bold;title.color=UiText;title.rectTransform.anchoredPosition=new Vector2(0,-16);
            _categoryTabs=Horizontal(right,98,new Vector2(.045f,1),new Vector2(.955f,1));_categoryTabs.sizeDelta=new Vector2(0,120);
            var tabLayout=_categoryTabs.GetComponent<HorizontalLayoutGroup>();tabLayout.spacing=8;tabLayout.childAlignment=TextAnchor.UpperCenter;
            _categoryTitle=Label(right,"얼굴형 선택",20,40,new Vector2(.055f,.684f),new Vector2(.945f,.739f));_categoryTitle.alignment=TextAnchor.MiddleLeft;_categoryTitle.fontStyle=FontStyle.Bold;_categoryTitle.color=UiText;
            _itemGrid=ScrollGrid(right,new Vector2(.055f,.49f),new Vector2(.945f,.669f),2,new Vector2(188,146));
            _itemScroll=(RectTransform)_itemGrid.parent;
            _colorTitle=Label(right,"얼굴 색상",20,38,new Vector2(.055f,.47f),new Vector2(.945f,.52f));_colorTitle.alignment=TextAnchor.MiddleLeft;_colorTitle.fontStyle=FontStyle.Bold;_colorTitle.color=UiText;
            _colorSlots=ColorList(right);
            Anchor(_categoryTitle.rectTransform,new Vector2(.055f,.684f),new Vector2(.945f,.739f));

            _colorPopup=Panel(_responsiveFrame,"Color Popup",new Vector2(.60f,.245f),new Vector2(.84f,.755f),UiPanel);
            _previewInputBlockers.Add(_colorPopup);
            _colorPopup.gameObject.AddComponent<AvatarDraggablePanel>();
            var popupShadow=_colorPopup.gameObject.AddComponent<Shadow>();popupShadow.effectColor=new Color(0,0,0,.58f);popupShadow.effectDistance=new Vector2(8,-8);
            var innerFrame=Panel(_colorPopup,"Inner Frame",new Vector2(.018f,.018f),new Vector2(.982f,.982f),UiPanel);innerFrame.GetComponent<Image>().raycastTarget=false;
            _colorPopupTitle=Label(_colorPopup,"색상 변경",23,50);_colorPopupTitle.color=UiText;_colorPopupTitle.fontStyle=FontStyle.Bold;_colorPopupTitle.rectTransform.anchoredPosition=new Vector2(0,-9);
            DecorativeDivider(_colorPopup,66);
            BuildColorPicker(_colorPopup,92);
            BuildHexColorInput(_colorPopup,242);
            var popupActions=Horizontal(_colorPopup,374,new Vector2(.09f,1),new Vector2(.91f,1));popupActions.sizeDelta=new Vector2(0,50);popupActions.GetComponent<HorizontalLayoutGroup>().spacing=12;
            var cancelColor=Button(popupActions,"취소",()=>_colorPopup.gameObject.SetActive(false),128,48,UiSurface);cancelColor.GetComponentInChildren<Text>().fontSize=17;
            var finishColor=Button(popupActions,"완료",()=>_colorPopup.gameObject.SetActive(false),128,48,new Color(.30f,.20f,.07f,1));finishColor.GetComponentInChildren<Text>().fontSize=17;
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
                WardrobeCategoryButton(_wardrobeTabs,CategoryName(category),CategoryIcon(category),()=>{_wardrobeCategory=captured;_editingGarmentColor=false;SetCamera(CategoryCameraPreset(captured));RefreshWardrobe();},83,120,_wardrobeCategory==category);
            }
            if(_wardrobeTitle)_wardrobeTitle.text=CategoryName(_wardrobeCategory)+" 선택";
            foreach(Transform child in _wardrobeGrid)Destroy(child.gameObject);
            if(_wardrobeItemScroll)_wardrobeItemScroll.GetComponent<ScrollRect>().verticalNormalizedPosition=1f;
            ImageButton(_wardrobeGrid,"없음",null,()=>SelectWardrobeItem(_wardrobeCategory,0),158,148,CurrentItemId(_wardrobeCategory)==0);
            var wardrobeItems=_catalog.GetItems(_wardrobeCategory,_config.gender).ToArray();
            for(int index=0;index<wardrobeItems.Length;index++)
            {
                var captured=wardrobeItems[index];
                var presentation=WardrobePresentation(_wardrobeCategory,captured);
                ImageButton(_wardrobeGrid,PrettyName(presentation.displayName),presentation.thumbnail,()=>SelectWardrobeItem(_wardrobeCategory,captured.itemId),158,148,IsSelected(_wardrobeCategory,captured));
            }
            RefreshWardrobeColors();
        }

        void RefreshWardrobeColors()
        {
            foreach(Transform child in _wardrobeColorSlots)Destroy(child.gameObject);
            bool hasItem=CurrentItemId(_wardrobeCategory)!=0;
            int areaMask=CurrentGarmentAreaMask(_wardrobeCategory);
            var selectedItem=WardrobePresentation(_wardrobeCategory,CurrentGarmentDefinition(_wardrobeCategory));
            if(_wardrobeColorTitle)_wardrobeColorTitle.text=hasItem&&selectedItem?PrettyName(selectedItem.displayName)+" 색상":"의상 색상 · 의상을 선택하세요";
            if(!hasItem)return;
            for(int area=0;area<3;area++)
            {
                if((areaMask&(1<<area))==0)continue;
                var capturedArea=area;
                GarmentAreaColorRow(_wardrobeColorSlots,GarmentAreaName(_wardrobeCategory,area),CurrentGarmentAreaColor(_wardrobeCategory,area),()=>OpenGarmentAreaColor(_wardrobeCategory,capturedArea),_editingGarmentColor&&_garmentColorCategory==_wardrobeCategory&&(int)_garmentColorSlot/2==area);
            }
        }
        void RefreshTabs()
        {
            foreach(Transform child in _categoryTabs)Destroy(child.gameObject);
            foreach(var category in new[]{AvatarPartCategory.Head,AvatarPartCategory.Hair,AvatarPartCategory.Hat,AvatarPartCategory.Glasses})
            {
                var captured=category;
                CategoryButton(_categoryTabs,CategoryName(category),CategoryIcon(category),()=>{_category=captured;_editingGarmentColor=false;SetCamera(CategoryCameraPreset(captured));RefreshAll();},96,120,_category==category);
            }
        }

        void RefreshColors()
        {
            foreach(Transform child in _colorSlots)Destroy(child.gameObject);
            if(_category==AvatarPartCategory.Hat||_category==AvatarPartCategory.Glasses)
            {
                bool hasItem=CurrentItemId(_category)!=0;
                int areaMask=CurrentGarmentAreaMask(_category);
                Anchor(_colorTitle.rectTransform,new Vector2(.055f,.24f),new Vector2(.945f,.29f));
                Anchor(_colorSlots,new Vector2(.055f,.03f),new Vector2(.945f,.23f));
                var selectedItem=CurrentGarmentDefinition(_category);
                if(_colorTitle)_colorTitle.text=hasItem&&selectedItem?PrettyName(selectedItem.displayName)+" 색상":CategoryName(_category)+" 색상 · 파츠를 선택해 주세요";
                if(hasItem)
                    for(int area=0;area<3;area++)
                    {
                        if((areaMask&(1<<area))==0)continue;
                        var capturedArea=area;
                        GarmentAreaColorRow(_colorSlots,GarmentAreaName(_category,area),CurrentGarmentAreaColor(_category,area),()=>OpenGarmentAreaColor(_category,capturedArea),_editingGarmentColor&&_garmentColorCategory==_category&&(int)_garmentColorSlot/2==area);
                    }
                return;
            }
            var slots=SlotsFor(_category).ToArray();
            bool face=_category==AvatarPartCategory.Head;
            Anchor(_colorTitle.rectTransform,face?new Vector2(.055f,.36f):new Vector2(.055f,.14f),face?new Vector2(.945f,.41f):new Vector2(.945f,.19f));
            Anchor(_colorSlots,face?new Vector2(.055f,.03f):new Vector2(.055f,.03f),face?new Vector2(.945f,.35f):new Vector2(.945f,.13f));
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

        void OpenGarmentAreaColor(AvatarPartCategory category,int area)
        {
            _editingGarmentColor=true;_garmentColorCategory=category;_garmentColorSlot=(AvatarGarmentColorSlot)(Mathf.Clamp(area,0,2)*2);var current=CurrentGarmentAreaColor(category,area);Color.RGBToHSV(current,out _pickerHue,out _pickerSaturation,out _pickerValue);
            if(_colorPopupTitle)_colorPopupTitle.text=GarmentAreaName(category,area)+" 색상";
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
            var itemLayout=_itemGrid.GetComponent<GridLayoutGroup>();
            bool garmentAccessory=_category==AvatarPartCategory.Hat||_category==AvatarPartCategory.Glasses;
            itemLayout.constraintCount=2;
            itemLayout.cellSize=new Vector2(188,face?142:_category==AvatarPartCategory.Hair?156:150);
            if(_itemScroll)
            {
                _itemScroll.gameObject.SetActive(true);
                Anchor(_itemScroll,
                    face?new Vector2(.055f,.43f):garmentAccessory?new Vector2(.055f,.31f):new Vector2(.055f,.21f),
                    face?new Vector2(.945f,.669f):new Vector2(.945f,.669f));
            }
            if(_categoryTitle)_categoryTitle.text=face?"얼굴형 선택":CategoryName(_category)+" 설정";
            foreach(Transform c in _itemGrid) Destroy(c.gameObject);
            if(_itemScroll)_itemScroll.GetComponent<ScrollRect>().verticalNormalizedPosition=1f;
            IEnumerable<AvatarItemDefinition> defs = _catalog.GetItems(_category,_config.gender);
            if(_category==AvatarPartCategory.Hat) defs=defs.GroupBy(x=>x.familyId).Select(x=>x.First());
            if(_category!=AvatarPartCategory.Head) ImageButton(_itemGrid,"없음",null,()=>{_config.SetItem(_category,0);Apply();RefreshItems();},188,150,CurrentItemId(_category)==0);
            var definitions=defs.ToArray();
            for(int index=0;index<definitions.Length;index++)
            {
                var captured=definitions[index];var select=new UnityEngine.Events.UnityAction(()=>{_config.SetItem(_category,_category==AvatarPartCategory.Hat?captured.familyId:captured.itemId);Apply();RefreshItems();RefreshColors();});
                if(face)FaceCardButton(_itemGrid,FaceDisplayName(index),FaceThumbnail(index)??captured.thumbnail,select,188,142,IsSelected(_category,captured));
                else if(_category==AvatarPartCategory.Hair)HairCardButton(_itemGrid,HairDisplayName(index),HairThumbnail(index)??captured.thumbnail,select,188,156,IsSelected(_category,captured));
                else ImageButton(_itemGrid,PrettyName(captured.displayName),captured.thumbnail,select,188,150,IsSelected(_category,captured));
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
            var row = Horizontal(parent, y,new Vector2(.07f,1),new Vector2(.93f,1)); row.sizeDelta = new Vector2(0, 142);
            var layout=row.GetComponent<HorizontalLayoutGroup>();layout.spacing=10;layout.childControlHeight=false;layout.childAlignment=TextAnchor.MiddleCenter;
            RawImage MakeRaw(string name, float width,float height=118)
            {
                var raw = new GameObject(name, typeof(RectTransform), typeof(RawImage), typeof(LayoutElement), typeof(AvatarColorPicker)).GetComponent<RawImage>();
                raw.transform.SetParent(row, false);raw.rectTransform.sizeDelta=new Vector2(width,height); var le = raw.GetComponent<LayoutElement>(); le.preferredWidth = width; le.preferredHeight = height; return raw;
            }
            var sv = MakeRaw("색상환", 142,142); _svPicker = sv.GetComponent<AvatarColorPicker>();
            _svPicker.Configure(AvatarColorPicker.PickerMode.HueSaturationWheel, (h,s) => { _pickerHue=h; _pickerSaturation=s;_valuePicker.Rebuild(_pickerHue,_pickerSaturation);ApplyPickerColor(); });
            _valuePicker = MakeRaw("밝기", 26,142).GetComponent<AvatarColorPicker>();
            _valuePicker.Configure(AvatarColorPicker.PickerMode.Value, (_,v) => { _pickerValue=v;ApplyPickerColor(); });
            var previewColumn=new GameObject("선택 색상",typeof(RectTransform),typeof(VerticalLayoutGroup),typeof(LayoutElement)).GetComponent<RectTransform>();
            previewColumn.SetParent(row,false);previewColumn.sizeDelta=new Vector2(78,78);var columnLayout=previewColumn.GetComponent<LayoutElement>();columnLayout.preferredWidth=78;columnLayout.preferredHeight=78;
            var vertical=previewColumn.GetComponent<VerticalLayoutGroup>();vertical.childAlignment=TextAnchor.MiddleCenter;vertical.childControlWidth=true;vertical.childForceExpandWidth=false;vertical.childControlHeight=true;vertical.childForceExpandHeight=false;
            _colorPreview = new GameObject("현재 색상", typeof(RectTransform), typeof(Image), typeof(LayoutElement),typeof(Outline)).GetComponent<Image>();
            _colorPreview.transform.SetParent(previewColumn, false); var previewLayout=_colorPreview.GetComponent<LayoutElement>(); previewLayout.preferredWidth=74; previewLayout.preferredHeight=74;
            Round(_colorPreview);var previewOutline=_colorPreview.GetComponent<Outline>();previewOutline.effectColor=UiText;previewOutline.effectDistance=new Vector2(1,-1);
            _colorPreview.color=_config.GetColor(_colorSlot,_catalog);
        }

        void BuildHexColorInput(Transform parent,float y)
        {
            var section=Label(parent,"색상 코드",20,36);section.alignment=TextAnchor.MiddleLeft;section.color=UiText;
            section.rectTransform.anchorMin=new Vector2(.09f,1);section.rectTransform.anchorMax=new Vector2(.91f,1);section.rectTransform.anchoredPosition=new Vector2(0,-(y-8));
            var row=Horizontal(parent,y+34,new Vector2(.09f,1),new Vector2(.91f,1));row.sizeDelta=new Vector2(0,50);row.GetComponent<HorizontalLayoutGroup>().spacing=8;
            _hexColorInput=HexInput(row,196,46);
            _hexColorInput.onValueChanged.AddListener(HandleHexColorChanged);
            var applyHex=Button(row,"적용",ApplyHexColor,82,46,UiCardSelected);applyHex.GetComponentInChildren<Text>().fontSize=16;
            _hexColorHint=Label(parent,"6자리 HEX · 입력 즉시 반영",15,26);_hexColorHint.alignment=TextAnchor.MiddleLeft;_hexColorHint.color=UiTextMuted;
            _hexColorHint.rectTransform.anchorMin=new Vector2(.09f,1);_hexColorHint.rectTransform.anchorMax=new Vector2(.91f,1);_hexColorHint.rectTransform.anchoredPosition=new Vector2(0,-(y+86));
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
            if(_editingGarmentColor){int area=(int)_garmentColorSlot/2;_config.SetGarmentColor(_garmentColorCategory,(AvatarGarmentColorSlot)(area*2),color);_config.SetGarmentColor(_garmentColorCategory,(AvatarGarmentColorSlot)(area*2+1),color);if(_garmentColorCategory==AvatarPartCategory.Hat||_garmentColorCategory==AvatarPartCategory.Glasses)RefreshColors();else RefreshWardrobeColors();}
            else{_config.SetColor(_colorSlot,color);RefreshColors();}
            if(_colorPreview)_colorPreview.color=color;
            SyncHexColor(color);
            _assembler.ApplyColorsOnly(_config);
        }
        void ApplyToWorld()
        {
            Festa.World.AvatarSceneHandoff.Save(Festa.World.AvatarAppearance.FromModularConfig(_config));
            var nm = Unity.Netcode.NetworkManager.Singleton;
            var player = nm != null && nm.IsClient ? nm.LocalClient?.PlayerObject : null;
            var controller = player ? player.GetComponent<Festa.World.PlayerAppearanceController>() : null;
            if (controller) { controller.RequestChange(Festa.World.AvatarAppearance.FromModularConfig(_config)); SetStatus("월드 아바타에 적용했습니다."); }
            else SetStatus("외형이 준비되었습니다. 월드 접속 후 자동 적용할 수 있습니다.");
        }
        void EnterWorld()
        {
            Festa.World.AvatarSceneHandoff.Save(Festa.World.AvatarAppearance.FromModularConfig(_config));
            Festa.World.AvatarSceneHandoff.RequestWorldConnection();
            SceneManager.LoadScene(Festa.World.AvatarSceneHandoff.WorldSceneName);
        }
        void Randomize()
        {
            _config=CreateRecommendedRandomConfig(_config.gender, true);
            _wardrobeCategory=_config.outfitId!=0?AvatarPartCategory.Outfit:AvatarPartCategory.Top;
            _garmentColorCategory=_wardrobeCategory;
            _editingGarmentColor=false;
            Apply();RefreshAll();
        }

        AvatarConfig CreateRecommendedRandomConfig(AvatarGender gender,bool allowOutfit)
        {
            var rng=new System.Random();
            var next=_catalog.CreateDefault(gender);
            SetRandomItem(ref next,AvatarPartCategory.Head,rng);
            SetRandomItem(ref next,AvatarPartCategory.Hair,rng);
            SetRandomItem(ref next,AvatarPartCategory.Shoes,rng);

            bool useOutfit=allowOutfit&&rng.NextDouble()<.30;
            if(useOutfit)SetRandomItem(ref next,AvatarPartCategory.Outfit,rng);
            else
            {
                next.SetItem(AvatarPartCategory.Outfit,0);
                SetRandomItem(ref next,AvatarPartCategory.Top,rng);
                SetRandomItem(ref next,AvatarPartCategory.Bottom,rng);
            }

            next.SetItem(AvatarPartCategory.Hat,0);
            next.SetItem(AvatarPartCategory.Glasses,0);
            if(rng.NextDouble()<.12)SetRandomItem(ref next,AvatarPartCategory.Hat,rng);
            else if(rng.NextDouble()<.18)SetRandomItem(ref next,AvatarPartCategory.Glasses,rng);

            Color skin=NaturalSkinColors[rng.Next(NaturalSkinColors.Length)];
            Color hair=NaturalHairColors[rng.Next(NaturalHairColors.Length)];
            Color iris=NaturalIrisColors[rng.Next(NaturalIrisColors.Length)];
            next.SetColor(AvatarColorSlot.Skin,skin);
            next.SetColor(AvatarColorSlot.Hair,hair);
            next.SetColor(AvatarColorSlot.Eyebrow,Color.Lerp(hair,Color.black,.22f));
            next.SetColor(AvatarColorSlot.Iris,iris);
            next.SetColor(AvatarColorSlot.Pupil,new Color(.055f,.045f,.04f));
            next.SetColor(AvatarColorSlot.Sclera,new Color(.96f,.95f,.91f));
            next.SetColor(AvatarColorSlot.Lips,NaturalLipColors[rng.Next(NaturalLipColors.Length)]);

            Color[] theme=OutfitColorThemes[rng.Next(OutfitColorThemes.Length)];
            next.SetColor(AvatarColorSlot.Top,theme[0]);
            next.SetColor(AvatarColorSlot.Bottom,theme[1]);
            SetGarmentTheme(ref next,AvatarPartCategory.Top,theme[0],theme[1],theme[2]);
            SetGarmentTheme(ref next,AvatarPartCategory.Bottom,theme[1],theme[2],theme[0]);
            SetGarmentTheme(ref next,AvatarPartCategory.Outfit,theme[0],theme[1],theme[2]);
            SetGarmentTheme(ref next,AvatarPartCategory.Shoes,theme[2],Color.Lerp(theme[2],Color.black,.32f),theme[1]);
            SetGarmentTheme(ref next,AvatarPartCategory.Hat,theme[0],theme[1],theme[2]);
            SetGarmentTheme(ref next,AvatarPartCategory.Glasses,Color.Lerp(theme[2],Color.black,.22f),theme[1],theme[2]);

            return next;
        }

        void SetRandomItem(ref AvatarConfig config,AvatarPartCategory category,System.Random rng)
        {
            var items=_catalog.GetItems(category,config.gender).ToArray();
            if(items.Length==0){config.SetItem(category,0);return;}
            var item=items[rng.Next(items.Length)];
            config.SetItem(category,category==AvatarPartCategory.Hat?item.familyId:item.itemId);
        }

        static void SetGarmentTheme(ref AvatarConfig config,AvatarPartCategory category,Color first,Color second,Color third)
        {
            var colors=new[]{first,second,third};
            for(int area=0;area<3;area++)
            {
                config.SetGarmentColor(category,(AvatarGarmentColorSlot)(area*2),colors[area]);
                config.SetGarmentColor(category,(AvatarGarmentColorSlot)(area*2+1),colors[area]);
            }
        }
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

        Color CurrentGarmentAreaColor(AvatarPartCategory category,int area)
        {
            var dark=CurrentGarmentColor(category,(AvatarGarmentColorSlot)(Mathf.Clamp(area,0,2)*2));
            var light=CurrentGarmentColor(category,(AvatarGarmentColorSlot)(Mathf.Clamp(area,0,2)*2+1));
            var combined=Color.Lerp(dark,light,.5f);combined.a=1f;return combined;
        }

        int CurrentItemId(AvatarPartCategory category)=>category switch{AvatarPartCategory.Head=>_config.headId,AvatarPartCategory.Hair=>_config.hairId,AvatarPartCategory.Hat=>_config.hatId,AvatarPartCategory.Glasses=>_config.glassesId,AvatarPartCategory.Top=>_config.topId,AvatarPartCategory.Bottom=>_config.bottomId,AvatarPartCategory.Outfit=>_config.outfitId,AvatarPartCategory.Shoes=>_config.shoesId,_=>0};
        AvatarItemDefinition CurrentGarmentDefinition(AvatarPartCategory category)=>category==AvatarPartCategory.Hat
            ?_catalog.ResolveHat(_config.hatId,_catalog.Get(_config.hairId)?.hairGroup??HairGroup.None)
            :_catalog.Get(CurrentItemId(category));
        AvatarItemDefinition WardrobePresentation(AvatarPartCategory category,AvatarItemDefinition actual)
        {
            if(!actual||category==AvatarPartCategory.Shoes)return actual;
            string styleKey=actual.name.StartsWith("M_")||actual.name.StartsWith("F_")?actual.name.Substring(2):actual.name;
            var canonical=_catalog.GetItems(category,AvatarGender.Female).FirstOrDefault(item=>
            {
                string candidateKey=item.name.StartsWith("M_")||item.name.StartsWith("F_")?item.name.Substring(2):item.name;
                return candidateKey==styleKey;
            });
            return canonical?canonical:actual;
        }
        int CurrentGarmentAreaMask(AvatarPartCategory category)
        {
            var item=CurrentGarmentDefinition(category);
            return item&&item.garmentColorAreaMask!=0?item.garmentColorAreaMask:0x7;
        }
        static int CountGarmentAreas(int mask)=>(mask&1)+((mask>>1)&1)+((mask>>2)&1);
        bool IsSelected(AvatarPartCategory category,AvatarItemDefinition item)=>CurrentItemId(category)==(category==AvatarPartCategory.Hat?item.familyId:item.itemId);

        static RectTransform Panel(Transform p,string n,Vector2 min,Vector2 max,Color c){var r=new GameObject(n,typeof(RectTransform),typeof(Image),typeof(Outline)).GetComponent<RectTransform>();r.SetParent(p,false);r.anchorMin=min;r.anchorMax=max;r.offsetMin=r.offsetMax=Vector2.zero;var image=r.GetComponent<Image>();image.color=c;Round(image);var o=r.GetComponent<Outline>();o.effectColor=UiBorder;o.effectDistance=new Vector2(1,-1);return r;}
        static Image ImageLayer(Transform p,string n,Vector2 min,Vector2 max,Color c){var image=new GameObject(n,typeof(RectTransform),typeof(Image)).GetComponent<Image>();image.transform.SetParent(p,false);Anchor(image.rectTransform,min,max);image.color=c;return image;}
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
        static RectTransform Vertical(Transform p,float top){var r=new GameObject("List",typeof(RectTransform),typeof(VerticalLayoutGroup),typeof(ContentSizeFitter)).GetComponent<RectTransform>();r.SetParent(p,false);r.anchorMin=new Vector2(.08f,0);r.anchorMax=new Vector2(.92f,1);r.offsetMin=new Vector2(0,18);r.offsetMax=new Vector2(0,-top);var l=r.GetComponent<VerticalLayoutGroup>();l.spacing=8;l.childAlignment=TextAnchor.UpperCenter;l.childControlWidth=true;l.childForceExpandWidth=true;l.childControlHeight=true;l.childForceExpandHeight=false;return r;}
        static RectTransform Horizontal(Transform p,float y,Vector2? min=null,Vector2? max=null){var r=new GameObject("Row",typeof(RectTransform),typeof(HorizontalLayoutGroup)).GetComponent<RectTransform>();r.SetParent(p,false);r.anchorMin=min??new Vector2(.03f,1);r.anchorMax=max??new Vector2(.97f,1);r.pivot=new Vector2(.5f,1);r.anchoredPosition=new Vector2(0,-y);r.sizeDelta=new Vector2(0,45);var l=r.GetComponent<HorizontalLayoutGroup>();l.spacing=10;l.childAlignment=TextAnchor.MiddleCenter;l.childControlWidth=true;l.childForceExpandWidth=false;l.childControlHeight=true;l.childForceExpandHeight=false;return r;}
        static RectTransform HorizontalScroll(Transform p,float y){var view=new GameObject("Wardrobe Scroll",typeof(RectTransform),typeof(Image),typeof(Mask),typeof(ScrollRect)).GetComponent<RectTransform>();view.SetParent(p,false);view.anchorMin=new Vector2(.01f,1);view.anchorMax=new Vector2(.99f,1);view.pivot=new Vector2(.5f,1);view.anchoredPosition=new Vector2(0,-y);view.sizeDelta=new Vector2(0,70);view.GetComponent<Image>().color=new Color(.01f,.025f,.045f,.78f);view.GetComponent<Mask>().showMaskGraphic=false;var content=new GameObject("Wardrobe Row",typeof(RectTransform),typeof(HorizontalLayoutGroup),typeof(ContentSizeFitter)).GetComponent<RectTransform>();content.SetParent(view,false);content.anchorMin=new Vector2(0,0);content.anchorMax=new Vector2(0,1);content.pivot=new Vector2(0,.5f);content.anchoredPosition=Vector2.zero;content.sizeDelta=Vector2.zero;var layout=content.GetComponent<HorizontalLayoutGroup>();layout.padding=new RectOffset(3,3,1,1);layout.spacing=4;layout.childAlignment=TextAnchor.MiddleLeft;layout.childControlWidth=true;layout.childForceExpandWidth=false;layout.childControlHeight=true;layout.childForceExpandHeight=false;content.GetComponent<ContentSizeFitter>().horizontalFit=ContentSizeFitter.FitMode.PreferredSize;var scroll=view.GetComponent<ScrollRect>();scroll.viewport=view;scroll.content=content;scroll.horizontal=true;scroll.vertical=false;scroll.scrollSensitivity=24;return content;}
        static RectTransform ColorList(Transform p){var r=new GameObject("Color List",typeof(RectTransform),typeof(VerticalLayoutGroup)).GetComponent<RectTransform>();r.SetParent(p,false);Anchor(r,new Vector2(.06f,.20f),new Vector2(.94f,.52f));var layout=r.GetComponent<VerticalLayoutGroup>();layout.spacing=5;layout.padding=new RectOffset(0,0,3,3);layout.childAlignment=TextAnchor.UpperCenter;layout.childControlWidth=true;layout.childForceExpandWidth=true;layout.childControlHeight=true;layout.childForceExpandHeight=false;return r;}
        static RectTransform ScrollGrid(Transform p)=>ScrollGrid(p,new Vector2(.06f,.37f),new Vector2(.94f,.71f),4,new Vector2(70,82));
        static RectTransform ScrollGrid(Transform p,Vector2 min,Vector2 max,int columns,Vector2 cellSize){var view=new GameObject("Item Scroll",typeof(RectTransform),typeof(Image),typeof(Mask),typeof(ScrollRect)).GetComponent<RectTransform>();view.SetParent(p,false);view.anchorMin=min;view.anchorMax=max;view.offsetMin=view.offsetMax=Vector2.zero;var viewImage=view.GetComponent<Image>();viewImage.color=UiSurface;Round(viewImage);view.GetComponent<Mask>().showMaskGraphic=true;var content=new GameObject("Grid",typeof(RectTransform),typeof(GridLayoutGroup),typeof(ContentSizeFitter)).GetComponent<RectTransform>();content.SetParent(view,false);content.anchorMin=new Vector2(0,1);content.anchorMax=new Vector2(1,1);content.pivot=new Vector2(.5f,1);content.anchoredPosition=Vector2.zero;content.sizeDelta=Vector2.zero;var grid=content.GetComponent<GridLayoutGroup>();grid.cellSize=cellSize;grid.spacing=new Vector2(12,12);grid.padding=new RectOffset(8,8,10,10);grid.constraint=GridLayoutGroup.Constraint.FixedColumnCount;grid.constraintCount=columns;content.GetComponent<ContentSizeFitter>().verticalFit=ContentSizeFitter.FitMode.PreferredSize;var scroll=view.GetComponent<ScrollRect>();scroll.viewport=view;scroll.content=content;scroll.horizontal=false;scroll.vertical=true;scroll.scrollSensitivity=30;return content;}
        static Text Label(Transform p,string s,int size,float h,Vector2? min=null,Vector2? max=null){var t=new GameObject("Label",typeof(RectTransform),typeof(Text)).GetComponent<Text>();t.transform.SetParent(p,false);t.text=s;s_uiFont??=Resources.Load<Font>("Fonts/MalgunGothicLight");t.font=s_uiFont?s_uiFont:Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");t.fontSize=size;t.fontStyle=FontStyle.Bold;t.color=UiText;t.alignment=TextAnchor.MiddleCenter;t.alignByGeometry=true;t.supportRichText=false;t.horizontalOverflow=HorizontalWrapMode.Wrap;t.verticalOverflow=VerticalWrapMode.Truncate;var r=t.rectTransform;r.anchorMin=min??new Vector2(0,1);r.anchorMax=max??new Vector2(1,1);r.pivot=new Vector2(.5f,1);r.sizeDelta=new Vector2(0,h);return t;}
        static InputField HexInput(Transform parent,float width,float height)
        {
            var input=new GameObject("HEX 색상 코드",typeof(RectTransform),typeof(Image),typeof(InputField),typeof(LayoutElement),typeof(Outline)).GetComponent<InputField>();input.transform.SetParent(parent,false);
            input.image.color=UiSurface;Round(input.image);var le=input.GetComponent<LayoutElement>();le.preferredWidth=width;le.preferredHeight=height;
            var outline=input.GetComponent<Outline>();outline.effectColor=UiBorder;outline.effectDistance=new Vector2(1,-1);
            var text=Label(input.transform,"#FFFFFF",23,height);Anchor(text.rectTransform,new Vector2(.08f,0),new Vector2(.95f,1));text.alignment=TextAnchor.MiddleLeft;
            input.textComponent=text;input.characterLimit=7;input.lineType=InputField.LineType.SingleLine;input.contentType=InputField.ContentType.Standard;
            return input;
        }
        static Button Button(Transform p,string s,UnityEngine.Events.UnityAction click,float w=250,float h=44,Color? color=null){var b=new GameObject(s,typeof(RectTransform),typeof(Image),typeof(Button),typeof(LayoutElement),typeof(Outline)).GetComponent<Button>();b.transform.SetParent(p,false);b.image.color=color??UiCard;Round(b.image);var le=b.GetComponent<LayoutElement>();le.preferredWidth=w;le.preferredHeight=h;var colors=b.colors;colors.normalColor=Color.white;colors.highlightedColor=new Color(1.08f,1.08f,1.08f,1);colors.pressedColor=new Color(.82f,.86f,.92f,1);colors.selectedColor=Color.white;colors.fadeDuration=.10f;b.colors=colors;var o=b.GetComponent<Outline>();o.effectColor=UiBorder;o.effectDistance=new Vector2(1,-1);var t=Label(b.transform,s,17,h);t.rectTransform.anchorMin=Vector2.zero;t.rectTransform.anchorMax=Vector2.one;t.rectTransform.offsetMin=t.rectTransform.offsetMax=Vector2.zero;b.onClick.AddListener(click);return b;}
        static Button CategoryButton(Transform parent,string label,Sprite sprite,UnityEngine.Events.UnityAction click,float width,float height,bool selected)
        {
            var root=new GameObject(label,typeof(RectTransform),typeof(Image),typeof(Button),typeof(LayoutElement)).GetComponent<Button>();root.transform.SetParent(parent,false);root.image.color=Color.clear;
            var layout=root.GetComponent<LayoutElement>();layout.preferredWidth=width;layout.preferredHeight=height;
            var circle=new GameObject("Circular Icon",typeof(RectTransform),typeof(Image),typeof(Outline),typeof(Mask)).GetComponent<Image>();circle.transform.SetParent(root.transform,false);circle.rectTransform.anchorMin=circle.rectTransform.anchorMax=new Vector2(.5f,1);circle.rectTransform.pivot=new Vector2(.5f,1);circle.rectTransform.anchoredPosition=new Vector2(0,-2);circle.rectTransform.sizeDelta=new Vector2(82,82);circle.sprite=CircleSprite();circle.color=selected?UiCardSelected:UiSurface;circle.GetComponent<Mask>().showMaskGraphic=true;
            var outline=circle.GetComponent<Outline>();outline.effectColor=selected?UiAccent:UiBorder;outline.effectDistance=selected?new Vector2(2,-2):new Vector2(1,-1);
            if(sprite){var icon=new GameObject("Icon",typeof(RectTransform),typeof(Image)).GetComponent<Image>();icon.transform.SetParent(circle.transform,false);Anchor(icon.rectTransform,new Vector2(.03f,.03f),new Vector2(.97f,.97f));icon.sprite=sprite;icon.preserveAspect=true;icon.color=selected?new Color(1f,.83f,.48f):UiText;icon.raycastTarget=false;}
            var text=Label(root.transform,label,18,30);Anchor(text.rectTransform,new Vector2(0,0),new Vector2(1,.255f));text.color=selected?new Color(1f,.82f,.45f):UiTextMuted;
            root.onClick.AddListener(click);return root;
        }
        static Button WardrobeCategoryButton(Transform parent,string label,Sprite sprite,UnityEngine.Events.UnityAction click,float width,float height,bool selected)
        {
            var root=new GameObject(label,typeof(RectTransform),typeof(Image),typeof(Button),typeof(LayoutElement)).GetComponent<Button>();root.transform.SetParent(parent,false);root.image.color=Color.clear;
            var layout=root.GetComponent<LayoutElement>();layout.preferredWidth=width;layout.preferredHeight=height;
            var card=new GameObject("Category Preview",typeof(RectTransform),typeof(Image),typeof(Outline),typeof(Mask)).GetComponent<Image>();card.transform.SetParent(root.transform,false);card.rectTransform.anchorMin=card.rectTransform.anchorMax=new Vector2(.5f,1);card.rectTransform.pivot=new Vector2(.5f,1);card.rectTransform.anchoredPosition=new Vector2(0,-2);card.rectTransform.sizeDelta=new Vector2(80,80);card.color=selected?UiCardSelected:UiCard;Round(card);card.GetComponent<Mask>().showMaskGraphic=true;
            var outline=card.GetComponent<Outline>();outline.effectColor=selected?UiAccent:UiBorder;outline.effectDistance=selected?new Vector2(2,-2):new Vector2(1,-1);
            if(sprite){var icon=new GameObject("Icon",typeof(RectTransform),typeof(Image)).GetComponent<Image>();icon.transform.SetParent(card.transform,false);Anchor(icon.rectTransform,new Vector2(.08f,.08f),new Vector2(.92f,.92f));icon.rectTransform.localScale=Vector3.one*1.05f;icon.sprite=sprite;icon.preserveAspect=true;icon.color=Color.white;icon.raycastTarget=false;}
            var text=Label(root.transform,label,18,32);Anchor(text.rectTransform,new Vector2(0,0),new Vector2(1,.255f));text.color=selected?new Color(1f,.82f,.45f):UiTextMuted;
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
        static Sprite RoundedSprite()
        {
            if(s_roundedSprite)return s_roundedSprite;
            const int size=64;const float radius=12f;float half=(size-1)*.5f;var texture=new Texture2D(size,size,TextureFormat.RGBA32,false){name="Runtime Rounded UI"};var pixels=new Color32[size*size];
            for(int y=0;y<size;y++)for(int x=0;x<size;x++)
            {
                float qx=Mathf.Abs(x-half)-(half-radius),qy=Mathf.Abs(y-half)-(half-radius);
                float outside=Mathf.Sqrt(Mathf.Max(qx,0)*Mathf.Max(qx,0)+Mathf.Max(qy,0)*Mathf.Max(qy,0))+Mathf.Min(Mathf.Max(qx,qy),0)-radius;
                byte alpha=(byte)Mathf.RoundToInt(Mathf.Clamp01(.75f-outside)*255f);pixels[y*size+x]=new Color32(255,255,255,alpha);
            }
            texture.SetPixels32(pixels);texture.Apply();texture.wrapMode=TextureWrapMode.Clamp;texture.filterMode=FilterMode.Bilinear;
            s_roundedSprite=Sprite.Create(texture,new Rect(0,0,size,size),new Vector2(.5f,.5f),100f,0,SpriteMeshType.FullRect,new Vector4(16,16,16,16));s_roundedSprite.name="Runtime Rounded UI Sprite";return s_roundedSprite;
        }
        static void Round(Image image){if(!image)return;image.sprite=RoundedSprite();image.type=Image.Type.Sliced;}
        static Button ImageButton(Transform p,string label,Sprite sprite,UnityEngine.Events.UnityAction click,float w,float h,bool selected=false,Color? swatch=null)
        {
            var b=new GameObject(string.IsNullOrEmpty(label)?"Preview":label,typeof(RectTransform),typeof(Image),typeof(Button),typeof(LayoutElement),typeof(Outline)).GetComponent<Button>();b.transform.SetParent(p,false);
            b.image.color=selected?UiCardSelected:UiCard;Round(b.image);var le=b.GetComponent<LayoutElement>();le.preferredWidth=w;le.preferredHeight=h;
            var o=b.GetComponent<Outline>();o.effectColor=selected?UiAccent:UiBorder;o.effectDistance=selected?new Vector2(2,-2):new Vector2(1,-1);
            if(sprite)
            {
                var surface=ImageLayer(b.transform,"Preview Surface",new Vector2(.05f,.30f),new Vector2(.95f,.95f),UiPreview);Round(surface);surface.raycastTarget=false;
                var preview=new GameObject("Preview",typeof(RectTransform),typeof(Image)).GetComponent<Image>();preview.transform.SetParent(surface.transform,false);preview.sprite=sprite;preview.preserveAspect=true;preview.color=Color.white;Anchor(preview.rectTransform,new Vector2(.025f,.025f),new Vector2(.975f,.975f));preview.raycastTarget=false;
            }
            else if(swatch.HasValue)ColorSwatch(b.transform,swatch.Value,38);
            else
            {
                var emptySurface=ImageLayer(b.transform,"Preview Surface",new Vector2(.05f,.30f),new Vector2(.95f,.95f),UiSurface);Round(emptySurface);emptySurface.raycastTarget=false;
                var empty=Label(emptySurface.transform,"—",24,h*.6f);Anchor(empty.rectTransform,Vector2.zero,Vector2.one);empty.color=UiTextMuted;
            }
            if(!string.IsNullOrEmpty(label))
            {
                var labelSurface=ImageLayer(b.transform,"Label Surface",new Vector2(.05f,.035f),new Vector2(.95f,.255f),new Color(.065f,.06f,.07f,.92f));Round(labelSurface);labelSurface.raycastTarget=false;
                var text=Label(labelSurface.transform,label,16,h*.22f);Anchor(text.rectTransform,new Vector2(.04f,0),new Vector2(.96f,1));text.color=selected?new Color(1f,.84f,.52f):UiText;text.alignment=TextAnchor.MiddleCenter;
            }
            b.onClick.AddListener(click);return b;
        }
        static Button FaceCardButton(Transform p,string label,Sprite sprite,UnityEngine.Events.UnityAction click,float w,float h,bool selected)
        {
            var button=ImageButton(p,label,sprite,click,w,h,selected);
            var surface=button.transform.Find("Preview Surface")?.GetComponent<Image>();if(surface)surface.color=UiPreview;
            var text=button.transform.Find("Label Surface")?.GetComponentInChildren<Text>();if(text){text.fontSize=17;text.color=selected?new Color(1f,.84f,.52f):UiText;}
            return button;
        }
        static Button HairCardButton(Transform p,string label,Sprite sprite,UnityEngine.Events.UnityAction click,float w,float h,bool selected)
        {
            var button=ImageButton(p,label,sprite,click,w,h,selected);
            var surface=button.transform.Find("Preview Surface")?.GetComponent<Image>();if(surface)surface.color=new Color(.68f,.71f,.75f,1);
            var text=button.transform.Find("Label Surface")?.GetComponentInChildren<Text>();if(text){text.fontSize=16;text.resizeTextForBestFit=false;text.color=selected?new Color(1f,.84f,.52f):UiText;}
            return button;
        }
        static Image ColorSwatch(Transform parent,Color value,float size)
        {
            var swatch=new GameObject("Color Swatch",typeof(RectTransform),typeof(Image),typeof(Outline)).GetComponent<Image>();swatch.transform.SetParent(parent,false);swatch.rectTransform.anchorMin=swatch.rectTransform.anchorMax=new Vector2(.91f,.5f);swatch.rectTransform.pivot=new Vector2(.5f,.5f);swatch.rectTransform.anchoredPosition=Vector2.zero;swatch.rectTransform.sizeDelta=new Vector2(size,size);swatch.color=value;Round(swatch);var outline=swatch.GetComponent<Outline>();outline.effectColor=new Color(.48f,.49f,.54f,.95f);outline.effectDistance=new Vector2(1,-1);swatch.raycastTarget=false;return swatch;
        }
        static Button ColorRow(Transform p,string label,Color swatch,UnityEngine.Events.UnityAction click,bool selected)
        {
            var b=new GameObject(label,typeof(RectTransform),typeof(Image),typeof(Button),typeof(LayoutElement),typeof(Outline)).GetComponent<Button>();b.transform.SetParent(p,false);b.image.color=selected?new Color(.24f,.18f,.07f,.98f):new Color(.025f,.045f,.07f,.98f);var le=b.GetComponent<LayoutElement>();le.preferredHeight=35;le.minHeight=33;
            b.image.color=selected?UiCardSelected:UiCard;Round(b.image);var outline=b.GetComponent<Outline>();outline.effectColor=selected?UiAccent:UiBorder;outline.effectDistance=selected?new Vector2(2,-2):new Vector2(1,-1);
            le.preferredHeight=42;le.minHeight=42;
            var marker=new GameObject(label+" Marker",typeof(RectTransform),typeof(Image),typeof(Outline)).GetComponent<Image>();marker.transform.SetParent(b.transform,false);marker.rectTransform.anchorMin=marker.rectTransform.anchorMax=new Vector2(.075f,.5f);marker.rectTransform.pivot=new Vector2(.5f,.5f);marker.rectTransform.sizeDelta=new Vector2(18,18);marker.sprite=CircleSprite();marker.color=GlyphColor(label);marker.raycastTarget=false;var markerOutline=marker.GetComponent<Outline>();markerOutline.effectColor=new Color(.03f,.035f,.045f,.9f);markerOutline.effectDistance=new Vector2(1,-1);
            var text=Label(b.transform,label,17,42);Anchor(text.rectTransform,new Vector2(.125f,0),new Vector2(.76f,1));text.alignment=TextAnchor.MiddleLeft;text.color=UiText;
            ColorSwatch(b.transform,swatch,30);
            b.onClick.AddListener(click);return b;
        }
        static Button GarmentAreaColorRow(Transform parent,string areaName,Color swatch,UnityEngine.Events.UnityAction click,bool selected)
        {
            var button=new GameObject(areaName,typeof(RectTransform),typeof(Image),typeof(Button),typeof(LayoutElement),typeof(Outline)).GetComponent<Button>();button.transform.SetParent(parent,false);
            button.image.color=selected?UiCardSelected:UiCard;Round(button.image);var layout=button.GetComponent<LayoutElement>();layout.preferredHeight=52;layout.minHeight=50;
            var outline=button.GetComponent<Outline>();outline.effectColor=selected?UiAccent:UiBorder;outline.effectDistance=selected?new Vector2(2,-2):new Vector2(1,-1);
            var text=Label(button.transform,areaName,17,50);Anchor(text.rectTransform,new Vector2(.045f,0),new Vector2(.80f,1));text.alignment=TextAnchor.MiddleLeft;text.color=UiText;text.resizeTextForBestFit=false;
            ColorSwatch(button.transform,swatch,32);
            button.onClick.AddListener(click);return button;
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
                AvatarPartCategory.Top=>"category_top_cutout",
                AvatarPartCategory.Bottom=>"category_bottom_cutout",
                AvatarPartCategory.Outfit=>"category_outfit_cutout",
                AvatarPartCategory.Shoes=>"category_shoes_cutout",
                _=>string.Empty
            };
            var sprite=string.IsNullOrEmpty(assetName)?null:Resources.Load<Sprite>("Avatar/UI/CategoryIcons/"+assetName);
            s_categoryIcons[category]=sprite;
            return sprite;
        }
        static string ColorSlotName(AvatarColorSlot s)=>s switch{AvatarColorSlot.Skin=>"피부",AvatarColorSlot.Hair=>"헤어",AvatarColorSlot.Sclera=>"흰자위",AvatarColorSlot.Iris=>"홍채",AvatarColorSlot.Pupil=>"동공",AvatarColorSlot.Eyebrow=>"눈썹",AvatarColorSlot.Lips=>"입술",AvatarColorSlot.Top=>"상의",AvatarColorSlot.Bottom=>"하의",_=>s.ToString()};
        string GarmentAreaName(AvatarPartCategory category,int area)
        {
            var item=CurrentGarmentDefinition(category);
            string name=item?item.displayName:string.Empty;
            if(category==AvatarPartCategory.Top)
            {
                if(IsItem(name,"링거","Top.01"))return area==0?"티셔츠 몸판":"목·소매 배색";
                if(IsItem(name,"후드집업","Top.02"))return area switch{0=>"후드집업 몸판",1=>"후드·소매 배색",_=>"지퍼·끈·포켓"};
                if(IsItem(name,"배색 맨투맨","Top.03"))return area==0?"맨투맨 몸판":"소매·시보리 배색";
                if(IsItem(name,"포켓 셔츠","Top.04"))return "셔츠 원단";
                if(IsItem(name,"니트 조끼","Top.05"))return area==0?"니트 몸판":"목·소매 시보리";
                if(IsItem(name,"오픈 셔츠","Top.06"))return area==0?"겉셔츠 원단":"이너·소매단";
                if(IsItem(name,"크롭 재킷","Top.07-A"))return area==0?"재킷 몸판":"소매·밑단";
                if(IsItem(name,"크롭 볼레로","Top.07-B"))return area==0?"볼레로 몸판":"소매·테두리";
                if(IsItem(name,"스트라이프","Top.08-A"))return area==0?"티셔츠 몸판":"줄무늬·소매단";
                if(IsItem(name,"머플러","Top.08-B"))return area==0?"티셔츠 몸판":"머플러·소매단";
                if(IsItem(name,"기본 후드티","Top.09"))return "후드티 원단";
                if(IsItem(name,"타이 셔츠","Top.10"))return area==0?"셔츠 원단":"타이·단추";
                if(IsItem(name,"정장 재킷","Top.11"))return "재킷 원단";
                return area switch{0=>"상의 몸판",1=>"소매·배색",_=>"단추·장식"};
            }
            if(category==AvatarPartCategory.Bottom)
            {
                if(IsItem(name,"일자 팬츠","Bot.01"))return "팬츠 원단";
                if(IsItem(name,"롤업 숏팬츠","Bot.02"))return area==0?"숏팬츠 원단":"롤업·허리선";
                if(IsItem(name,"플레어 스커트","Bot.03"))return "스커트 원단";
                if(IsItem(name,"찢청","Bot.04"))return "데님 원단";
                if(IsItem(name,"롱 반바지","Bot.05"))return "반바지 원단";
                if(IsItem(name,"카고 조거팬츠","Bot.06"))return area==0?"조거팬츠 원단":"카고 포켓·허리선";
                return area==0?"하의 원단":"허리선·포켓";
            }
            if(category==AvatarPartCategory.Outfit)
            {
                if(IsItem(name,"멜빵바지","Outfit.01"))return area==0?"바지 원단":"멜빵·단추";
                if(IsItem(name,"서스펜더 반바지","Outfit.02"))return area switch{0=>"상의 원단",1=>"반바지 원단",_=>"멜빵·단추"};
                if(IsItem(name,"카라 원피스","Outfit.03"))return area==0?"원피스 원단":"칼라·소매단";
                if(IsItem(name,"유니폼 점프수트","Outfit.04"))return area==0?"점프수트 원단":"칼라·허리선";
                return area switch{0=>"옷 본체",1=>"배색 부분",_=>"끈·단추"};
            }
            if(category==AvatarPartCategory.Shoes)
            {
                if(name.Contains("쪼리"))return area==0?"스트랩":"밑창";
                if(name.Contains("샌들"))return area==0?"샌들 스트랩":"밑창";
                if(name.Contains("스니커즈"))return area switch{0=>"신발 몸체",1=>"밑창",_=>"끈·장식"};
                if(name.Contains("부츠"))return area switch{0=>"부츠 몸체",1=>"밑창",_=>"끈·장식"};
                return area switch{0=>"신발 몸체",1=>"밑창",_=>"끈·장식"};
            }
            if(category==AvatarPartCategory.Hat)
            {
                if(name.Contains("볼캡"))return area switch{0=>"모자 본체",1=>"챙·밴드",_=>"로고·단추"};
                if(name.Contains("비니"))return "비니 원단";
                if(name.Contains("헬멧"))return area==0?"헬멧 외피":"바이저·테두리";
                return area switch{0=>"모자 본체",1=>"챙·밴드",_=>"장식"};
            }
            if(category==AvatarPartCategory.Glasses)return area==0?"안경테":"브리지·다리";
            return "색상";
        }
        static bool IsItem(string value,string localizedName,string sourceCode)=>value.Contains(localizedName)||string.Equals(value,sourceCode,StringComparison.OrdinalIgnoreCase);
        static string GarmentPropertyName(AvatarGarmentColorSlot s)=>s switch{AvatarGarmentColorSlot.A1=>"_Color_A_1",AvatarGarmentColorSlot.A2=>"_Color_A_2",AvatarGarmentColorSlot.B1=>"_Color_B_1",AvatarGarmentColorSlot.B2=>"_Color_B_2",AvatarGarmentColorSlot.C1=>"_Color_C_1",AvatarGarmentColorSlot.C2=>"_Color_C_2",_=>"_Color_A_1"};
        static string PrettyName(string value)=>value.Replace("Shared_","").Replace("Hairstyle.","헤어 ").Replace("Head.","얼굴 ").Replace("Top.","상의 ").Replace("Bot.","하의 ").Replace("Outfit.","한벌옷 ").Replace("Shoes.","신발 ").Replace("Hat.","모자 ").Replace("Glasses.","안경 ");
    }

    sealed class AvatarDraggablePanel : MonoBehaviour, IBeginDragHandler, IDragHandler
    {
        RectTransform _rect;
        RectTransform _parent;
        Vector2 _pointerStart;
        Vector2 _positionStart;
        bool _dragging;

        void Awake()
        {
            _rect=transform as RectTransform;
            _parent=_rect&&_rect.parent?_rect.parent as RectTransform:null;
        }

        public void OnBeginDrag(PointerEventData eventData)
        {
            _dragging=false;
            if(!_rect||!_parent)return;
            if(!RectTransformUtility.ScreenPointToLocalPointInRectangle(_rect,eventData.position,eventData.pressEventCamera,out var local))return;
            if(local.y<_rect.rect.yMax-64f)return;
            if(!RectTransformUtility.ScreenPointToLocalPointInRectangle(_parent,eventData.position,eventData.pressEventCamera,out _pointerStart))return;
            _positionStart=_rect.anchoredPosition;
            _dragging=true;
        }

        public void OnDrag(PointerEventData eventData)
        {
            if(!_dragging||!_rect||!_parent)return;
            if(!RectTransformUtility.ScreenPointToLocalPointInRectangle(_parent,eventData.position,eventData.pressEventCamera,out var pointer))return;
            var desired=_positionStart+pointer-_pointerStart;
            Vector2 anchorRatio=(_rect.anchorMin+_rect.anchorMax)*.5f;
            Vector2 anchorCenter=new Vector2(Mathf.Lerp(_parent.rect.xMin,_parent.rect.xMax,anchorRatio.x),Mathf.Lerp(_parent.rect.yMin,_parent.rect.yMax,anchorRatio.y));
            Vector2 halfSize=_rect.rect.size*.5f;
            desired.x=Mathf.Clamp(desired.x,_parent.rect.xMin+halfSize.x-anchorCenter.x,_parent.rect.xMax-halfSize.x-anchorCenter.x);
            desired.y=Mathf.Clamp(desired.y,_parent.rect.yMin+halfSize.y-anchorCenter.y,_parent.rect.yMax-halfSize.y-anchorCenter.y);
            _rect.anchoredPosition=desired;
        }
    }
}
