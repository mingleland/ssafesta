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
            UpdateBodyVisibility();
            ApplyColors();
            ValidateAnimator();
        }

        void BuildBody()
        {
            foreach (Transform child in transform) Destroy(child.gameObject);
            _spawned.Clear(); _bodyParts.Clear();
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
            if (_spawned.TryGetValue(category, out var old)) foreach (var go in old) if (go) Destroy(go);
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
                PrepareMaterials(r);
                spawned.Add(go);
            }
            for (int i = 0; i < def.objectPrefabs.Length; i++)
            {
                var targetBone = i < def.targetBones.Length ? def.targetBones[i] : HumanBodyBones.Head;
                var bone = _animator.GetBoneTransform(targetBone);
                if (!bone) { Fail($"{def.displayName}: 부착 본 {targetBone}을 찾지 못했습니다."); continue; }
                var attached = Instantiate(def.objectPrefabs[i], bone); spawned.Add(attached);
                foreach (var renderer in attached.GetComponentsInChildren<Renderer>(true)) PrepareMaterials(renderer);
            }
        }

        void UpdateBodyVisibility()
        {
            var hidden = new HashSet<int>();
            foreach (AvatarPartCategory c in Enum.GetValues(typeof(AvatarPartCategory)))
            {
                var d = c == AvatarPartCategory.Hat ? null : _catalog.Get(_config.GetItem(c));
                if (d?.hiddenBodyParts == null) continue;
                foreach (var p in d.hiddenBodyParts) hidden.Add(p);
            }
            foreach (var pair in _bodyParts) pair.Value.SetActive(!hidden.Contains(pair.Key));
        }

        void ApplyColors()
        {
            _block ??= new MaterialPropertyBlock();
            foreach (var renderer in GetComponentsInChildren<Renderer>(true))
            for (int i = 0; i < renderer.sharedMaterials.Length; i++)
            {
                var mat = renderer.sharedMaterials[i]; if (!mat) continue;
                string n = mat.name.ToLowerInvariant(); renderer.GetPropertyBlock(_block, i);
                if (n.Contains("eye") && !n.Contains("brow")) Set("_BaseColor", AvatarColorSlot.Iris);
                else if (n.Contains("eyebrow")) Set("_BaseColor", AvatarColorSlot.Eyebrow);
                else if (n.Contains("mouth")) Set("_BaseColor", AvatarColorSlot.Lips);
                else if (n.Contains("hair")) Set("_BaseColor", AvatarColorSlot.Hair);
                else if (n.Contains("face") || n.Contains("body")) Set("_BaseColor", AvatarColorSlot.Skin);
                else if (n.Contains("top") || n.Contains("outfit")) Set("_BaseColor", AvatarColorSlot.Top);
                else if (n.Contains("bot")) Set("_BaseColor", AvatarColorSlot.Bottom);
                renderer.SetPropertyBlock(_block, i);
                void Set(string property, AvatarColorSlot slot) { if (mat.HasProperty(property)) _block.SetColor(property, _catalog.GetColor(_config.GetColor(slot))); }
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

        void PrepareMaterials(Renderer renderer)
        {
            var originals = renderer.sharedMaterials; var converted = new Material[originals.Length];
            var shader = Shader.Find("Universal Render Pipeline/Lit");
            for (int i = 0; i < originals.Length; i++)
            {
                var source = originals[i]; if (!source) continue;
                if (!_compatibleMaterials.TryGetValue(source, out var material))
                {
                    material = new Material(shader) { name = source.name + "_RuntimeURP" };
                    string lowerName = source.name.ToLowerInvariant();
                    bool usesRgbMask = lowerName.Contains("body") || lowerName.Contains("face") || lowerName.Contains("hair") || lowerName.Contains("top") || lowerName.Contains("bot") || lowerName.Contains("outfit") || lowerName.Contains("eye") || lowerName.Contains("mouth") || lowerName.Contains("eyebrow");
                    Texture texture = usesRgbMask ? null : source.HasProperty("_BaseMap") ? source.GetTexture("_BaseMap") : source.HasProperty("_BaseColorMap") ? source.GetTexture("_BaseColorMap") : source.mainTexture;
                    if (texture) material.SetTexture("_BaseMap", texture);
                    material.SetFloat("_Smoothness", .25f); _compatibleMaterials[source] = material;
                }
                converted[i] = material;
            }
            renderer.sharedMaterials = converted;
        }

        void OnDestroy() { foreach (var m in _compatibleMaterials.Values) if (m) Destroy(m); }

        static int BodyPartCode(string name)
        {
            name = name.ToLowerInvariant();
            if (name.Contains("body_hips")) return 0; if (name.Contains("body_torso") && name.EndsWith(".001")) return 1;
            if (name.Contains("body_torso") && name.EndsWith(".002")) return 2; if (name.Contains("body_shoulders")) return 3;
            if (name.Contains("headslot")) return 4; if (name == "m_body_torso" || name == "f_body_torso") return 5;
            if (name.Contains("arms_lower")) return 20; if (name.Contains("arms_upper")) return 21; if (name.Contains("hands")) return 22;
            if (name.Contains("legs_upper")) return 30; if (name.Contains("legs_knee")) return 31; if (name.Contains("legs_lower")) return 32; if (name.Contains("feet")) return 33;
            return -1;
        }
    }
}
