using System;
using System.Collections.Generic;
using System.Linq;
using UnityEngine;

namespace Festa.Avatar
{
    public sealed class AvatarAssembler : MonoBehaviour
    {
        [SerializeField] AvatarCatalog _catalog;
        readonly Dictionary<AvatarPartCategory, List<GameObject>> _spawned = new();
        readonly Dictionary<int, GameObject> _bodyParts = new();
        readonly Dictionary<Material, Material> _compatibleMaterials = new();
        readonly Dictionary<Renderer, AvatarPartCategory> _rendererCategories = new();
        MaterialPropertyBlock _block;
        Animator _animator;
        SkinnedMeshRenderer _reference;
        AvatarConfig _config;
        public AvatarConfig Config => _config;
        public string LastError { get; private set; }
        public AvatarCatalog Catalog { get => _catalog; set => _catalog = value; }

        void Awake() => _block = new MaterialPropertyBlock();

        public void Apply(AvatarConfig config)
        {
            LastError = null;
            if (!_catalog) { Fail("AvatarCatalog이 지정되지 않았습니다."); return; }
            bool rebuild = !_animator || _config.gender != config.gender;
            _config = config;
            if (rebuild) BuildBody();
            if (!_animator) return;
            foreach (AvatarPartCategory c in Enum.GetValues(typeof(AvatarPartCategory))) Equip(c);
            UpdateHairVisibilityForHat();
            UpdateBodyVisibility();
            ApplyColors();
            ValidateAnimator();
        }

        public void ApplyColorsOnly(AvatarConfig config)
        {
            _config = config;
            if (_animator) ApplyColors();
        }

        void BuildBody()
        {
            foreach (Transform child in transform) Destroy(child.gameObject);
            _spawned.Clear(); _bodyParts.Clear(); _rendererCategories.Clear();
            var prefab = _config.gender == AvatarGender.Female ? _catalog.femaleBody : _catalog.maleBody;
            if (!prefab) { Fail($"{_config.gender} 기본 신체 프리팹이 없습니다."); return; }
            var body = Instantiate(prefab, transform);
            body.name = $"Preview_{_config.gender}";
            _animator = body.GetComponent<Animator>();
            _reference = body.GetComponentsInChildren<SkinnedMeshRenderer>(true).FirstOrDefault();
            foreach (var node in body.GetComponentsInChildren<Transform>(true))
            {
                int tag = BodyPartCode(node.name);
                if (tag >= 0) _bodyParts[tag] = node.gameObject;
            }
            foreach (var renderer in body.GetComponentsInChildren<Renderer>(true)) PrepareMaterials(renderer);
        }

        void Equip(AvatarPartCategory category)
        {
            if (_spawned.TryGetValue(category, out var old)) foreach (var go in old) if (go)
            {
                foreach (var renderer in go.GetComponentsInChildren<Renderer>(true)) _rendererCategories.Remove(renderer);
                Destroy(go);
            }
            var spawned = new List<GameObject>(); _spawned[category] = spawned;
            AvatarItemDefinition def;
            if (category == AvatarPartCategory.Hat)
            {
                var hair = _catalog.Get(_config.hairId);
                def = _catalog.ResolveHat(_config.hatId, hair ? hair.hairGroup : HairGroup.None);
                if (_config.hatId != 0 && !def) { Fail("선택한 모자는 현재 헤어 그룹과 호환되지 않습니다."); return; }
            }
            else def = _catalog.Get(_config.GetItem(category));
            if (!def) return;
            foreach (var source in def.meshes)
            {
                var go = new GameObject(source.name);
                go.transform.SetParent(_animator.transform, false);
                var r = go.AddComponent<SkinnedMeshRenderer>();
                r.rootBone = _reference.rootBone; r.bones = _reference.bones; r.localBounds = _reference.localBounds;
                r.sharedMesh = source.sharedMesh; r.sharedMaterials = source.sharedMaterials;
                _rendererCategories[r] = category;
                PrepareMaterials(r, category);
                spawned.Add(go);
            }
            for (int i = 0; i < def.objectPrefabs.Length; i++)
            {
                var targetBone = i < def.targetBones.Length ? def.targetBones[i] : HumanBodyBones.Head;
                var bone = _animator.GetBoneTransform(targetBone);
                if (!bone) { Fail($"{def.displayName}: 부착 본 {targetBone}을 찾지 못했습니다."); continue; }
                var attached = Instantiate(def.objectPrefabs[i], bone); spawned.Add(attached);
                foreach (var renderer in attached.GetComponentsInChildren<Renderer>(true))
                {
                    _rendererCategories[renderer] = category;
                    PrepareMaterials(renderer, category);
                }
            }
        }

        void UpdateBodyVisibility()
        {
            var hidden = new HashSet<int>();
            var forcedVisible = new HashSet<int>();
            if (_config.headId != 0 && _spawned.TryGetValue(AvatarPartCategory.Head, out var heads) && heads.Any(x => x)) hidden.Add(4);
            foreach (AvatarPartCategory c in Enum.GetValues(typeof(AvatarPartCategory)))
            {
                var d = c == AvatarPartCategory.Hat ? null : _catalog.Get(_config.GetItem(c));
                if (!_spawned.TryGetValue(c, out var visibleParts) || !visibleParts.Any(x => x)) continue;
                if (d == null) continue;
                if (d.hiddenBodyParts != null)
                    foreach (var p in d.hiddenBodyParts) hidden.Add(p);
                if (d.forcedVisibleBodyParts != null)
                    foreach (var p in d.forcedVisibleBodyParts) forcedVisible.Add(p);
            }
            hidden.ExceptWith(forcedVisible);
            foreach (var pair in _bodyParts) pair.Value.SetActive(!hidden.Contains(pair.Key));
        }

        void UpdateHairVisibilityForHat()
        {
            // Cap and beanie prefabs already contain a fitted hair mesh for each
            // hair group. Keeping the regular hair active at the same time makes
            // it poke through and visually bury the hat.
            bool wearingHat = _config.hatId != 0
                && _spawned.TryGetValue(AvatarPartCategory.Hat, out var hats)
                && hats.Any(x => x);
            if (_spawned.TryGetValue(AvatarPartCategory.Hair, out var hairParts))
                foreach (var hair in hairParts) if (hair) hair.SetActive(!wearingHat);

            // When Hair is set to None, ResolveHat deliberately falls back to one
            // fitted variant so the cap/beanie body can still be instantiated.
            // Hide only that variant's embedded hair renderers, never the hat root.
            bool showFittedHair = _config.hairId != 0;
            if (!_spawned.TryGetValue(AvatarPartCategory.Hat, out var hatParts)) return;
            foreach (var hat in hatParts)
            {
                if (!hat) continue;
                foreach (var renderer in hat.GetComponentsInChildren<Renderer>(true))
                    if (IsEmbeddedHatHair(renderer)) renderer.enabled = showFittedHair;
            }
        }

        static bool IsEmbeddedHatHair(Renderer renderer)
        {
            if (!renderer) return false;
            if (renderer.name.ToLowerInvariant().Contains("hair")) return true;
            return renderer.sharedMaterials.Any(x => x && x.name.ToLowerInvariant().Contains("hair"));
        }

        void ApplyColors()
        {
            _block ??= new MaterialPropertyBlock();
            foreach (var renderer in GetComponentsInChildren<Renderer>(true))
            for (int i = 0; i < renderer.sharedMaterials.Length; i++)
            {
                var mat = renderer.sharedMaterials[i]; if (!mat) continue;
                string n = mat.name.ToLowerInvariant(); renderer.GetPropertyBlock(_block, i);
                bool hasCategory = _rendererCategories.TryGetValue(renderer, out var garmentCategory);
                bool embeddedHatHair = hasCategory && garmentCategory == AvatarPartCategory.Hat && n.Contains("hair");
                bool hatVisor = hasCategory && garmentCategory == AvatarPartCategory.Hat && n.Contains("visor");
                bool glassesLens = hasCategory && garmentCategory == AvatarPartCategory.Glasses && IsGlassesLens(mat);
                if (embeddedHatHair)
                {
                    Set("_BaseColor", AvatarColorSlot.Hair);
                }
                else if (hatVisor)
                {
                    var visorColor = _config.GetGarmentColor(AvatarPartCategory.Hat, AvatarGarmentColorSlot.B2);
                    if (visorColor.a == 0) visorColor = new Color32(105, 125, 145, 210);
                    if (mat.HasProperty("_BaseColor")) _block.SetColor("_BaseColor", visorColor);
                    if (mat.HasProperty("_Color")) _block.SetColor("_Color", visorColor);
                }
                else if (glassesLens)
                {
                    var lensColor = _config.GetGarmentColor(AvatarPartCategory.Glasses, AvatarGarmentColorSlot.B2);
                    if (lensColor.a > 0)
                    {
                        var sourceColor = mat.HasProperty("_BaseColor") ? mat.GetColor("_BaseColor") : mat.HasProperty("_Color") ? mat.GetColor("_Color") : new Color(1f, 1f, 1f, .32f);
                        var tinted = (Color)lensColor;
                        tinted.a = sourceColor.a > .02f ? sourceColor.a : .32f;
                        if (mat.HasProperty("_BaseColor")) _block.SetColor("_BaseColor", tinted);
                        if (mat.HasProperty("_Color")) _block.SetColor("_Color", tinted);
                    }
                }
                else if (hasCategory && IsGarment(garmentCategory))
                {
                    ApplyGarmentColors(mat, garmentCategory);
                }
                // The vendor eye texture already contains sclera, pupil and iris detail.
                // Tinting its whole base color made the sclera black and erased the iris.
                else if (n.Contains("eye") && !n.Contains("brow") && !n.Contains("lash") && !n.Contains("highlight"))
                {
                    _block.SetColor("_IrisColor", _config.GetColor(AvatarColorSlot.Iris, _catalog));
                    _block.SetColor("_ScleraColor", _config.GetColor(AvatarColorSlot.Sclera, _catalog));
                    _block.SetColor("_PupilColor", _config.GetColor(AvatarColorSlot.Pupil, _catalog));
                }
                else if (n.Contains("eyebrow")) Set("_BaseColor", AvatarColorSlot.Eyebrow);
                else if (n.Contains("hair")) Set("_BaseColor", AvatarColorSlot.Hair);
                else if (n.Contains("face"))
                {
                    _block.SetColor("_SkinColor", _config.GetColor(AvatarColorSlot.Skin, _catalog));
                    _block.SetColor("_LipColor", _config.GetColor(AvatarColorSlot.Lips, _catalog));
                    _block.SetColor("_EyebrowColor", _config.GetColor(AvatarColorSlot.Eyebrow, _catalog));
                }
                else if (n.Contains("body"))
                {
                    _block.SetColor("_SkinColor", _config.GetColor(AvatarColorSlot.Skin, _catalog));
                    // 속옷은 Body RGB 마스크의 별도 영역이다. 피부색 변경과
                    // 독립된 고정색으로 유지해 전신이 피부색으로 덮이지 않게 한다.
                    if (mat.HasProperty("_UnderwearColor"))
                        _block.SetColor("_UnderwearColor", new Color(.015f, .015f, .02f, 1f));
                }
                renderer.SetPropertyBlock(_block, i);
                void Set(string property, AvatarColorSlot slot)
                {
                    var color = _config.GetColor(slot, _catalog);
                    foreach (var candidate in new[] { property, "_Color", "_Color_A", "_Color_B", "_Color_C" })
                        if (mat.HasProperty(candidate)) _block.SetColor(candidate, color);
                }
            }
        }

        void ApplyGarmentColors(Material material, AvatarPartCategory category)
        {
            string[] properties = { "_Color_A_1", "_Color_A_2", "_Color_B_1", "_Color_B_2", "_Color_C_1", "_Color_C_2" };
            for (int i = 0; i < properties.Length; i++)
            {
                if (!material.HasProperty(properties[i])) continue;
                var custom = _config.GetGarmentColor(category, (AvatarGarmentColorSlot)i);
                if (custom.a > 0)
                {
                    _block.SetColor(properties[i], custom);
                    continue;
                }

                // v0 외형 문자열은 상·하의 전체색만 저장했다. 해당 데이터만 여섯
                // 영역에 같은 색을 적용해 이전 저장 외형을 유지한다.
                if (_config.garmentColorVersion == 0 && category != AvatarPartCategory.Shoes && category != AvatarPartCategory.Glasses)
                {
                    var legacySlot = category == AvatarPartCategory.Bottom ? AvatarColorSlot.Bottom : AvatarColorSlot.Top;
                    _block.SetColor(properties[i], _config.GetColor(legacySlot, _catalog));
                }
            }
        }

        void ValidateAnimator()
        {
            if (!_animator) return;
            if (!_animator.avatar) Fail("조립된 캐릭터 Animator의 Avatar가 비어 있습니다.");
            if (!_animator.runtimeAnimatorController && _catalog.animatorController) _animator.runtimeAnimatorController = _catalog.animatorController;
            if (!_animator.runtimeAnimatorController) Fail("조립된 캐릭터 Animator Controller가 비어 있습니다 (T-27).");
            else _animator.Rebind();
        }

        void Fail(string message) { LastError = message; Debug.LogError($"[AvatarAssembler] {message}", this); }

        void PrepareMaterials(Renderer renderer, AvatarPartCategory? category = null)
        {
            var originals = renderer.sharedMaterials; var converted = new Material[originals.Length];
            var shader = Shader.Find("Universal Render Pipeline/Lit");
            for (int i = 0; i < originals.Length; i++)
            {
                var source = originals[i]; if (!source) continue;
                if (!_compatibleMaterials.TryGetValue(source, out var material))
                {
                    string lowerName = source.name.ToLowerInvariant();
                    bool isEyeHighlight = lowerName.Contains("eye") && lowerName.Contains("highlight");
                    bool isEye = lowerName.Contains("eye") && !lowerName.Contains("eyebrow") && !lowerName.Contains("eyelash") && !isEyeHighlight;
                    bool isMouth = lowerName.Contains("mouth");
                    bool isFace = lowerName.Contains("face");
                    bool isBody = lowerName.Contains("body");
                    bool embeddedHatHair = category == AvatarPartCategory.Hat && lowerName.Contains("hair");
                    bool hatVisor = category == AvatarPartCategory.Hat && lowerName.Contains("visor");
                    bool glassesLens = category == AvatarPartCategory.Glasses && IsGlassesLens(source);
                    bool isGarment = category.HasValue && IsGarment(category.Value) && !embeddedHatHair && !hatVisor && !glassesLens;
                    if (isGarment)
                    {
                        var garmentShader = Shader.Find("Festa/Avatar/GarmentTint");
                        material = new Material(garmentShader ? garmentShader : shader) { name = source.name + "_RuntimeGarment" };
                        if (source.HasProperty("_BaseMap") && material.HasProperty("_BaseMap"))
                        {
                            material.SetTexture("_BaseMap", source.GetTexture("_BaseMap"));
                            material.SetTextureScale("_BaseMap", source.GetTextureScale("_BaseMap"));
                            material.SetTextureOffset("_BaseMap", source.GetTextureOffset("_BaseMap"));
                        }
                        if (source.HasProperty("_BumpMap") && material.HasProperty("_BumpMap")) material.SetTexture("_BumpMap", source.GetTexture("_BumpMap"));
                        foreach (var property in new[] { "_Color_A_1", "_Color_A_2", "_Color_B_1", "_Color_B_2", "_Color_C_1", "_Color_C_2" })
                            if (source.HasProperty(property) && material.HasProperty(property))
                            {
                                var sourceColor = source.GetColor(property); sourceColor.a = 1f;
                                material.SetColor(property, sourceColor);
                            }
                        if (source.HasProperty("_MaskRemap") && material.HasProperty("_MaskRemap")) material.SetVector("_MaskRemap", source.GetVector("_MaskRemap"));
                        if (source.HasProperty("_Mask_Factor") && material.HasProperty("_Mask_Factor")) material.SetFloat("_Mask_Factor", source.GetFloat("_Mask_Factor"));
                        _compatibleMaterials[source] = material;
                        converted[i] = material;
                        continue;
                    }
                    var targetShader = isEye ? Shader.Find("Festa/Avatar/IrisTint") : isFace ? Shader.Find("Festa/Avatar/FaceTint") : isBody ? Shader.Find("Festa/Avatar/SkinTint") : isMouth ? Shader.Find("Festa/Avatar/MouthTint") : isEyeHighlight ? Shader.Find("Universal Render Pipeline/Unlit") : shader;
                    material = new Material(targetShader) { name = source.name + "_RuntimeURP" };
                    // Body의 BaseMap은 단순 Albedo가 아니라 피부/속옷 영역을
                    // 구분하는 RGB 마스크이므로 SkinTint에도 반드시 전달한다.
                    bool preserveAlbedo = isFace || isBody || embeddedHatHair || hatVisor || lowerName.Contains("eye") || lowerName.Contains("mouth") || lowerName.Contains("eyebrow") || lowerName.Contains("lash") || lowerName.Contains("glasses");
                    Texture texture = preserveAlbedo ? (source.HasProperty("_BaseMap") ? source.GetTexture("_BaseMap") : source.HasProperty("_BaseColorMap") ? source.GetTexture("_BaseColorMap") : source.mainTexture) : null;
                    if(preserveAlbedo && !texture)
                        foreach(var property in source.GetTexturePropertyNames())
                            if(source.GetTexture(property)){texture=source.GetTexture(property);break;}
                    if(isEye && !texture)texture=Resources.Load<Texture2D>("Avatar/EyeTexture");
                    if (texture)
                    {
                        material.SetTexture("_BaseMap", texture);
                        if (source.HasProperty("_BaseMap"))
                        {
                            material.SetTextureScale("_BaseMap", source.GetTextureScale("_BaseMap"));
                            material.SetTextureOffset("_BaseMap", source.GetTextureOffset("_BaseMap"));
                        }
                    }
                    // Eyelashes and other vendor materials carry their visible tint
                    // in the material base color. A newly-created URP material defaults
                    // to white, which made the upper eyelid/eyelash mesh look white.
                    // Preserve the source tint whenever the replacement shader exposes it.
                    Color sourceTint = Color.white;
                    bool hasSourceTint = false;
                    if (source.HasProperty("_BaseColor")) { sourceTint = source.GetColor("_BaseColor"); hasSourceTint = true; }
                    else if (source.HasProperty("_Color")) { sourceTint = source.GetColor("_Color"); hasSourceTint = true; }
                    if (hasSourceTint)
                    {
                        if (material.HasProperty("_BaseColor")) material.SetColor("_BaseColor", sourceTint);
                        if (material.HasProperty("_Color")) material.SetColor("_Color", sourceTint);
                    }
                    if (isEyeHighlight)
                    {
                        if (material.HasProperty("_BaseColor")) material.SetColor("_BaseColor", Color.clear);
                        if (material.HasProperty("_Color")) material.SetColor("_Color", Color.clear);
                        if (material.HasProperty("_Surface")) material.SetFloat("_Surface", 1f);
                        if (material.HasProperty("_SrcBlend")) material.SetFloat("_SrcBlend", (float)UnityEngine.Rendering.BlendMode.SrcAlpha);
                        if (material.HasProperty("_DstBlend")) material.SetFloat("_DstBlend", (float)UnityEngine.Rendering.BlendMode.OneMinusSrcAlpha);
                        if (material.HasProperty("_ZWrite")) material.SetFloat("_ZWrite", 0f);
                        material.EnableKeyword("_SURFACE_TYPE_TRANSPARENT");
                        material.renderQueue = (int)UnityEngine.Rendering.RenderQueue.Transparent;
                    }
                    if (hatVisor || glassesLens)
                    {
                        if (material.HasProperty("_Surface")) material.SetFloat("_Surface", 1f);
                        if (material.HasProperty("_SrcBlend")) material.SetFloat("_SrcBlend", (float)UnityEngine.Rendering.BlendMode.SrcAlpha);
                        if (material.HasProperty("_DstBlend")) material.SetFloat("_DstBlend", (float)UnityEngine.Rendering.BlendMode.OneMinusSrcAlpha);
                        if (material.HasProperty("_ZWrite")) material.SetFloat("_ZWrite", 0f);
                        material.EnableKeyword("_SURFACE_TYPE_TRANSPARENT");
                        material.renderQueue = (int)UnityEngine.Rendering.RenderQueue.Transparent;
                    }
                    Texture normal = source.HasProperty("_Normal") ? source.GetTexture("_Normal") : source.HasProperty("_BumpMap") ? source.GetTexture("_BumpMap") : null;
                    if (normal) { material.SetTexture("_BumpMap", normal); material.EnableKeyword("_NORMALMAP"); }
                    float smoothness = lowerName.Contains("eye") ? .72f : lowerName.Contains("face") || lowerName.Contains("body") ? .42f : .3f;
                    if(material.HasProperty("_Smoothness"))material.SetFloat("_Smoothness", smoothness); _compatibleMaterials[source] = material;
                }
                converted[i] = material;
            }
            renderer.sharedMaterials = converted;
        }

        static bool IsGarment(AvatarPartCategory category) => category == AvatarPartCategory.Top || category == AvatarPartCategory.Bottom || category == AvatarPartCategory.Outfit || category == AvatarPartCategory.Shoes || category == AvatarPartCategory.Hat || category == AvatarPartCategory.Glasses;

        static bool IsGlassesLens(Material material)
        {
            if (!material) return false;
            string name = material.name.ToLowerInvariant();
            // PrepareMaterials에서 생성한 런타임 복제본은
            // `glasses_RuntimeURP` 이름을 가지므로 원본과 복제본을 모두 판별한다.
            // 프레임 머티리얼은 `mat_glasses...`로 시작해 이 조건에 포함되지 않는다.
            return name == "glasses" || name.StartsWith("glasses_") || name.Contains("lens") || name.Contains("glass_lens");
        }

        void OnDestroy() { foreach (var m in _compatibleMaterials.Values) if (m) { if(Application.isPlaying) Destroy(m); else DestroyImmediate(m); } }

        static int BodyPartCode(string name)
        {
            name = name.ToLowerInvariant();
            if (name.Contains("body_hips")) return 0;
            if (name.Contains("body_torso") && name.EndsWith(".001")) return 1;
            // Vendor BodyPartType maps the unnumbered torso to Spine02 (chest)
            // and torso.002 to Spine03 (upper neck bridge). Reversing these leaves
            // chest skin visible through tops while hiding the bridge creates a neck gap.
            if (name == "m_body_torso" || name == "f_body_torso") return 2;
            if (name.Contains("body_shoulders")) return 3;
            if (name.Contains("headslot")) return 4;
            if (name.Contains("body_torso") && name.EndsWith(".002")) return 5;
            if (name.Contains("arms_lower")) return 20; if (name.Contains("arms_upper")) return 21; if (name.Contains("hands")) return 22;
            if (name.Contains("legs_upper")) return 30; if (name.Contains("legs_knee")) return 31; if (name.Contains("legs_lower")) return 32; if (name.Contains("feet")) return 33;
            return -1;
        }
    }
}
