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
        // 변환된 런타임 재질은 (원본 재질 + 카테고리)의 순수 함수다 — 아바타별 색은
        // MaterialPropertyBlock 으로 나가므로 재질 자체에 개인 상태가 없다. 그래서
        // **모든 아바타가 공유**한다. 인스턴스마다 만들면 40명 기준 480개가 생기고
        // SRP Batcher 가 배칭할 수 없어 SetPass 가 인원수에 비례해 늘어난다 (실측 18.2/기).
        static readonly Dictionary<(int source, int category), Material> SharedMaterials = new();
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
            CombineSameMaterialParts();   // 가시성 확정 뒤에 합쳐야 옷에 가려지는 신체가 반영된다
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


        // ── 같은 재질 파츠 결합 (드로우콜 감축) ──────────────────────────
        // WebGL 40기 실측에서 드로우콜 990 · 25.7 FPS 였고, 캔버스 픽셀을 9분의 1로 줄여도
        // 12% 개선뿐이라 **CPU/드로우콜 병목**이 확정됐다(docs/KHS/28 §10).
        // 아바타 1기가 SkinnedMeshRenderer 11개를 쓰는데, 그중 신체 파츠 5개
        // (팔 상/하·손·다리·몸통)는 **같은 재질·같은 골격**이라 손실 없이 하나로 합칠 수 있다.
        //
        // 합칠 수 있는 조건 — 하나라도 어긋나면 건너뛴다:
        //   · 재질 동일 · rootBone 동일 · 뼈 배열 길이·순서 동일 · bindpose 동일
        //   · 서브메시 1개 · 블렌드셰이프 없음
        // 정점은 이미 같은 스킨 공간에 있으므로 좌표 변환 없이 이어 붙이면 된다.
        readonly List<GameObject> _merged = new();

        void CombineSameMaterialParts()
        {
            foreach (var go in _merged) if (go) DestroySafe(go);
            _merged.Clear();

            var actives = GetComponentsInChildren<SkinnedMeshRenderer>(false)
                .Where(r => r && r.enabled && r.sharedMesh &&
                            r.sharedMaterials.Length == 1 && r.sharedMaterials[0] &&
                            r.sharedMesh.subMeshCount == 1 && r.sharedMesh.blendShapeCount == 0 &&
                            r.bones != null && r.bones.Length > 0)
                .ToList();

            foreach (var group in actives.GroupBy(r => (r.sharedMaterials[0], r.rootBone, r.bones.Length)))
            {
                var parts = group.ToList();
                if (parts.Count < 2) continue;
                if (!BonesAndBindposesMatch(parts)) continue;
                var mergedGo = BuildMergedRenderer(parts);
                if (mergedGo == null) continue;
                foreach (var p in parts) p.enabled = false;   // 원본은 끄기만 한다 (파괴 금지 — 가시성 로직이 참조)
                _merged.Add(mergedGo);
            }
        }

        static bool BonesAndBindposesMatch(List<SkinnedMeshRenderer> parts)
        {
            var first = parts[0];
            var bp0 = first.sharedMesh.bindposes;
            for (int k = 1; k < parts.Count; k++)
            {
                var s = parts[k];
                for (int i = 0; i < s.bones.Length; i++)
                    if (s.bones[i] != first.bones[i]) return false;
                var bp = s.sharedMesh.bindposes;
                if (bp.Length != bp0.Length) return false;
                for (int i = 0; i < bp.Length; i++)
                    if (bp[i] != bp0[i]) return false;
            }
            return true;
        }

        GameObject BuildMergedRenderer(List<SkinnedMeshRenderer> parts)
        {
            var first = parts[0];
            var verts = new List<Vector3>();
            var norms = new List<Vector3>();
            var tans = new List<Vector4>();
            var uvs = new List<Vector2>();
            var cols = new List<Color32>();
            var weights = new List<BoneWeight>();
            var tris = new List<int>();

            bool hasNormals = true, hasTangents = true, hasUv = true, hasColors = true;
            foreach (var p in parts)
            {
                var m = p.sharedMesh;
                if (m.normals.Length != m.vertexCount) hasNormals = false;
                if (m.tangents.Length != m.vertexCount) hasTangents = false;
                if (m.uv.Length != m.vertexCount) hasUv = false;
                if (m.colors32.Length != m.vertexCount) hasColors = false;
            }

            foreach (var p in parts)
            {
                var m = p.sharedMesh;
                int offset = verts.Count;
                verts.AddRange(m.vertices);
                if (hasNormals) norms.AddRange(m.normals);
                if (hasTangents) tans.AddRange(m.tangents);
                if (hasUv) uvs.AddRange(m.uv);
                if (hasColors) cols.AddRange(m.colors32);
                // 뼈 인덱스는 같은 bones 배열을 가리키므로 재매핑이 필요 없다.
                weights.AddRange(m.boneWeights);
                foreach (var t in m.triangles) tris.Add(t + offset);
            }

            var mesh = new Mesh { name = first.sharedMaterials[0].name + "_Merged" };
            mesh.indexFormat = verts.Count > 65000
                ? UnityEngine.Rendering.IndexFormat.UInt32
                : UnityEngine.Rendering.IndexFormat.UInt16;
            mesh.SetVertices(verts);
            if (hasNormals) mesh.SetNormals(norms);
            if (hasTangents) mesh.SetTangents(tans);
            if (hasUv) mesh.SetUVs(0, uvs);
            if (hasColors) mesh.SetColors(cols);
            mesh.SetTriangles(tris, 0);
            mesh.boneWeights = weights.ToArray();
            mesh.bindposes = first.sharedMesh.bindposes;
            if (!hasNormals) mesh.RecalculateNormals();
            mesh.RecalculateBounds();

            var go = new GameObject("Merged_" + first.sharedMaterials[0].name);
            go.transform.SetParent(first.transform.parent, false);
            var smr = go.AddComponent<SkinnedMeshRenderer>();
            smr.sharedMesh = mesh;
            smr.bones = first.bones;
            smr.rootBone = first.rootBone;
            smr.sharedMaterial = first.sharedMaterials[0];
            smr.localBounds = first.localBounds;
            smr.shadowCastingMode = first.shadowCastingMode;
            smr.updateWhenOffscreen = first.updateWhenOffscreen;
            smr.quality = first.quality;
            return go;
        }

        static void DestroySafe(UnityEngine.Object o)
        {
            if (Application.isPlaying) Destroy(o); else DestroyImmediate(o);
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
                // 캐시 키에 카테고리를 넣는다 — 같은 원본이 의상/모자 헤어/바이저로 다르게
                // 변환되기 때문이다. Play 종료 시 파괴된 재질이 남을 수 있어 유효성도 본다.
                var cacheKey = (source.GetInstanceID(), category.HasValue ? (int)category.Value : -1);
                if (!SharedMaterials.TryGetValue(cacheKey, out var material) || !material)
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
                        Texture garmentMask = source.HasProperty("_BaseMap") ? source.GetTexture("_BaseMap")
                            : source.HasProperty("_BaseColorMap") ? source.GetTexture("_BaseColorMap")
                            : source.mainTexture;
                        if (!garmentMask)
                            foreach (var property in source.GetTexturePropertyNames())
                                if (source.GetTexture(property)) { garmentMask = source.GetTexture(property); break; }
                        if (garmentMask && material.HasProperty("_BaseMap"))
                            material.SetTexture("_BaseMap", garmentMask);
                        if (source.HasProperty("_BaseMap") && material.HasProperty("_BaseMap"))
                        {
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
                        SharedMaterials[cacheKey] = material;
                        converted[i] = material;
                        continue;
                    }
                    var targetShader = isEye ? Shader.Find("Festa/Avatar/IrisTint") : isFace ? Shader.Find("Festa/Avatar/FaceTint") : isBody ? Shader.Find("Festa/Avatar/SkinTint") : isMouth ? Shader.Find("Festa/Avatar/MouthTint") : isEyeHighlight ? Shader.Find("Universal Render Pipeline/Unlit") : shader;
                    material = new Material(targetShader) { name = source.name + "_RuntimeURP" };
                    // Body의 BaseMap은 단순 Albedo가 아니라 피부/속옷 영역을
                    // 구분하는 RGB 마스크이므로 SkinTint에도 반드시 전달한다.
                    bool preserveAlbedo = isFace || isBody || embeddedHatHair || hatVisor || lowerName.Contains("eye") || lowerName.Contains("mouth") || lowerName.Contains("eyebrow") || lowerName.Contains("lash") || lowerName.Contains("glasses");
                    Texture texture = preserveAlbedo ? (source.HasProperty("_BaseMap") ? source.GetTexture("_BaseMap") : source.HasProperty("_BaseColorMap") ? source.GetTexture("_BaseColorMap") : source.mainTexture) : null;
                    // The vendor materials do not all expose their visible map
                    // as _BaseMap.  WebGL then used a newly-created material
                    // with no source map, which made some assembled parts look
                    // uniformly black.  Fall back to the first texture on every
                    // material, including generic garment materials.
                    if (!texture)
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
                    if(material.HasProperty("_Smoothness"))material.SetFloat("_Smoothness", smoothness); SharedMaterials[cacheKey] = material;
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

        // 공유 재질은 아바타 하나가 사라졌다고 파괴하면 안 된다 — 다른 아바타가 쓰고 있다.
        // 프로세스 수명 동안 유지하고 도메인 리로드 때 함께 정리된다.

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
