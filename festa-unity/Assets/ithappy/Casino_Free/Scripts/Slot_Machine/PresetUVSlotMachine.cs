using System;
using System.Collections;
using System.Collections.Generic;
using UnityEngine;

namespace ithappy.Casino
{
    [DisallowMultipleComponent]
    public sealed class PresetUVSlotMachine : MonoBehaviour
    {
        private const int ReelCount = 4;

        public enum ScrollDirection
        {
            PositiveY,
            NegativeY
        }

        [Serializable]
        public sealed class ReelSettings
        {
            public Renderer renderer;
            [Min(0)] public int materialIndex;
            public int rowShift;
            public float fineAlignmentY;
        }

        [Serializable]
        public sealed class ResultPreset
        {
            public string presetName = "Result";
            public bool isWin;
            [Min(0f)] public float weight = 1f;

            [Header("Rows 1–12")]
            [Min(1)] public int reel1Row = 1;
            [Min(1)] public int reel2Row = 1;
            [Min(1)] public int reel3Row = 1;
            [Min(1)] public int reel4Row = 1;

            public int GetRow(int index)
            {
                switch (index)
                {
                    case 0: return reel1Row;
                    case 1: return reel2Row;
                    case 2: return reel3Row;
                    case 3: return reel4Row;
                    default: return 1;
                }
            }
        }

        [Header("Reels — left to right")]
        [SerializeField] private ReelSettings[] reels =
        {
            new ReelSettings(), new ReelSettings(),
            new ReelSettings(), new ReelSettings()
        };

        [Header("Texture")]
        [SerializeField, Min(1)] private int rowsCount = 12;
        [SerializeField] private bool reverseRowOrder;
        [SerializeField] private int globalRowShift;
        [SerializeField] private float globalFineAlignmentY;
        [SerializeField] private string preferredTextureSTProperty = "_BaseMap_ST";

        [Header("Spin")]
        [Tooltip("PositiveY — противоположное направление относительно первого варианта скрипта.")]
        [SerializeField] private ScrollDirection scrollDirection = ScrollDirection.PositiveY;
        [SerializeField, Min(0f)] private float delayBetweenReelStarts = 0.12f;
        [SerializeField, Min(0.1f)] private float firstReelDuration = 1.8f;
        [SerializeField, Min(0f)] private float extraDurationPerReel = 0.28f;
        [SerializeField, Min(1)] private int firstReelFullTurns = 5;
        [SerializeField, Min(0)] private int extraTurnsPerReel = 1;
        [SerializeField] private AnimationCurve spinCurve = new AnimationCurve(
            new Keyframe(0f, 0f, 0f, 2.8f),
            new Keyframe(0.12f, 0.22f, 2.2f, 2.2f),
            new Keyframe(0.62f, 0.82f, 0.75f, 0.75f),
            new Keyframe(0.88f, 0.975f, 0.18f, 0.18f),
            new Keyframe(1f, 1f, 0f, 0f)
        );

        [Header("Automatic Spins")]
        [SerializeField] private bool autoPlay = true;
        [SerializeField] private Vector2 firstSpinDelayRange = new Vector2(0.5f, 2.5f);
        [SerializeField] private Vector2 delayBetweenSpinsRange = new Vector2(3f, 7f);

        [Header("Results")]
        [SerializeField, Range(0f, 100f)] private float winChancePercent = 15f;

        [Header("LOSE ANIMATION CHANCE")]
        [Tooltip("Шанс запуска анимации Lose при проигрыше. 0 = всегда остаётся Sitting/Idle, 100 = Lose после каждого проигрыша.")]
        [SerializeField, Range(0f, 100f)]
        private float loseAnimationChancePercent = 35f;

        [SerializeField] private List<ResultPreset> presets = new List<ResultPreset>();

        [Header("Character")]
        [SerializeField] private string characterPointName = "CharacterPoint";
        [SerializeField] private RuntimeAnimatorController characterController;
        [SerializeField] private string sittingStatePath = "Base Layer.Adult_Slot_Sitting";
        [SerializeField] private string winTriggerName = "Win";
        [SerializeField] private string loseTriggerName = "Lose";

        [Header("Feedback")]
        [SerializeField] private ParticleSystem[] winParticles;
        [SerializeField] private AudioSource winAudioSource;
        [SerializeField] private AudioSource spinAudioSource;
        [SerializeField] private AudioSource reelStopAudioSource;

        [Header("Preview")]
        [SerializeField, Min(1)] private int previewRow = 1;
        [SerializeField, Min(0)] private int previewPresetIndex;

        private MaterialPropertyBlock[] blocks;
        private Vector4[] originalST;
        private int[] stPropertyIds;
        private bool[] reelReady;
        private float[] currentOffsets;

        private Animator characterAnimator;
        private Coroutine autoRoutine;
        private bool isSpinning;
        private int sittingHash;
        private int winHash;
        private int loseHash;

        public bool IsSpinning => isSpinning;

        private void Awake()
        {
            sittingHash = Animator.StringToHash(sittingStatePath);
            winHash = Animator.StringToHash(winTriggerName);
            loseHash = Animator.StringToHash(loseTriggerName);

            InitializeReels();
            StopFeedback();
        }

        private IEnumerator Start()
        {
            yield return null; // ждём, пока общий спавнер посадит персонажа
            FindAndConfigureCharacter();

            if (autoPlay)
                autoRoutine = StartCoroutine(AutoPlayRoutine());
        }

        private void OnDisable()
        {
            if (autoRoutine != null)
                StopCoroutine(autoRoutine);

            StopAllCoroutines();
            autoRoutine = null;
            isSpinning = false;
            StopFeedback();
        }

        private void OnValidate()
        {
            EnsureFourReels();
            rowsCount = Mathf.Max(1, rowsCount);
            firstReelDuration = Mathf.Max(0.1f, firstReelDuration);
            firstReelFullTurns = Mathf.Max(1, firstReelFullTurns);
            delayBetweenReelStarts = Mathf.Max(0f, delayBetweenReelStarts);
            extraDurationPerReel = Mathf.Max(0f, extraDurationPerReel);
            extraTurnsPerReel = Mathf.Max(0, extraTurnsPerReel);
        }

        public void SpinRandom()
        {
            if (isActiveAndEnabled && !isSpinning)
                StartCoroutine(SpinRoutine(null));
        }

        public void SpinPresetByIndex(int index)
        {
            if (!isActiveAndEnabled || isSpinning)
                return;

            if (index < 0 || index >= presets.Count)
            {
                Debug.LogError($"Preset index {index} does not exist on {name}.", this);
                return;
            }

            StartCoroutine(SpinRoutine(presets[index]));
        }

        public void RefreshCharacter()
        {
            characterAnimator = null;
            FindAndConfigureCharacter();
        }

        private IEnumerator AutoPlayRoutine()
        {
            float firstDelay = RandomRange(firstSpinDelayRange);
            if (firstDelay > 0f)
                yield return new WaitForSeconds(firstDelay);

            while (enabled && gameObject.activeInHierarchy)
            {
                yield return SpinRoutine(null);

                float delay = RandomRange(delayBetweenSpinsRange);
                if (delay > 0f)
                    yield return new WaitForSeconds(delay);
            }
        }

        private IEnumerator SpinRoutine(ResultPreset forcedPreset)
        {
            if (isSpinning)
                yield break;

            EnsureInitialized();
            ResultPreset preset = forcedPreset ?? SelectRandomPreset();

            if (preset == null)
            {
                Debug.LogError($"No Result Presets configured on {name}.", this);
                yield break;
            }

            isSpinning = true;
            ResetCharacterTriggers();
            StopWinFeedback();

            if (spinAudioSource != null)
            {
                spinAudioSource.Stop();
                spinAudioSource.Play();
            }

            int finished = 0;

            for (int i = 0; i < ReelCount; i++)
            {
                int reelIndex = i;
                StartCoroutine(AnimateReel(
                    reelIndex,
                    preset.GetRow(reelIndex),
                    () => finished++
                ));
            }

            while (finished < ReelCount)
                yield return null;

            if (spinAudioSource != null)
                spinAudioSource.Stop();

            PlayResult(preset.isWin);
            isSpinning = false;
        }

        private IEnumerator AnimateReel(int index, int targetRow, Action finished)
        {
            ReelSettings reel = reels[index];

            if (reel == null || reel.renderer == null || !reelReady[index])
            {
                finished?.Invoke();
                yield break;
            }

            float startDelay = index * delayBetweenReelStarts;
            if (startDelay > 0f)
                yield return new WaitForSeconds(startDelay);

            float duration = firstReelDuration + index * extraDurationPerReel;
            int turns = firstReelFullTurns + index * extraTurnsPerReel;
            float startOffset = currentOffsets[index];
            float targetOffset = GetRowOffset(targetRow, reel.rowShift);
            float destination = GetDestination(startOffset, targetOffset, turns);
            float elapsed = 0f;

            while (elapsed < duration)
            {
                elapsed += Time.deltaTime;
                float t = Mathf.Clamp01(elapsed / duration);
                float offset = Mathf.LerpUnclamped(
                    startOffset,
                    destination,
                    spinCurve.Evaluate(t)
                );

                ApplyOffset(index, offset);
                yield return null;
            }

            currentOffsets[index] = targetOffset;
            ApplyOffset(index, targetOffset);

            if (reelStopAudioSource != null && reelStopAudioSource.clip != null)
                reelStopAudioSource.PlayOneShot(reelStopAudioSource.clip);

            finished?.Invoke();
        }

        private float GetDestination(float start, float target, int turns)
        {
            float start01 = Mathf.Repeat(start, 1f);
            float target01 = Mathf.Repeat(target, 1f);
            turns = Mathf.Max(1, turns);

            if (scrollDirection == ScrollDirection.PositiveY)
                return start + turns + Mathf.Repeat(target01 - start01, 1f);

            return start - turns - Mathf.Repeat(start01 - target01, 1f);
        }

        private float GetRowOffset(int row, int localRowShift)
        {
            int index = PositiveModulo(row - 1 + globalRowShift + localRowShift, rowsCount);

            if (reverseRowOrder)
                index = rowsCount - 1 - index;

            // Твоя схема: 1 = 1/12, 2 = 2/12 ... 12 = 12/12 (= 0 после Repeat).
            return Mathf.Repeat((index + 1f) / rowsCount, 1f);
        }

        private void ApplyOffset(int index, float logicalOffset)
        {
            ReelSettings reel = reels[index];
            if (reel == null || reel.renderer == null || !reelReady[index])
                return;

            MaterialPropertyBlock block = blocks[index];
            reel.renderer.GetPropertyBlock(block, reel.materialIndex);

            Vector4 st = originalST[index];
            st.w += logicalOffset + globalFineAlignmentY + reel.fineAlignmentY;

            block.SetVector(stPropertyIds[index], st);
            reel.renderer.SetPropertyBlock(block, reel.materialIndex);
        }

        private ResultPreset SelectRandomPreset()
        {
            if (presets == null || presets.Count == 0)
                return null;

            bool wantWin = UnityEngine.Random.Range(0f, 100f) < winChancePercent;
            return SelectWeighted(wantWin) ?? SelectWeighted(!wantWin);
        }

        private ResultPreset SelectWeighted(bool win)
        {
            float total = 0f;
            int count = 0;

            foreach (ResultPreset preset in presets)
            {
                if (preset == null || preset.isWin != win)
                    continue;

                count++;
                total += Mathf.Max(0f, preset.weight);
            }

            if (count == 0)
                return null;

            if (total <= 0f)
            {
                int randomIndex = UnityEngine.Random.Range(0, count);
                foreach (ResultPreset preset in presets)
                {
                    if (preset == null || preset.isWin != win)
                        continue;

                    if (randomIndex-- == 0)
                        return preset;
                }
            }

            float value = UnityEngine.Random.Range(0f, total);
            foreach (ResultPreset preset in presets)
            {
                if (preset == null || preset.isWin != win)
                    continue;

                value -= Mathf.Max(0f, preset.weight);
                if (value <= 0f)
                    return preset;
            }

            return null;
        }

        private void InitializeReels()
        {
            EnsureFourReels();

            blocks = new MaterialPropertyBlock[ReelCount];
            originalST = new Vector4[ReelCount];
            stPropertyIds = new int[ReelCount];
            reelReady = new bool[ReelCount];
            currentOffsets = new float[ReelCount];

            for (int i = 0; i < ReelCount; i++)
            {
                blocks[i] = new MaterialPropertyBlock();
                originalST[i] = new Vector4(1f, 1f, 0f, 0f);

                ReelSettings reel = reels[i];
                if (reel == null || reel.renderer == null)
                    continue;

                Material[] materials = reel.renderer.sharedMaterials;
                if (materials.Length == 0)
                    continue;

                reel.materialIndex = Mathf.Clamp(reel.materialIndex, 0, materials.Length - 1);
                Material material = materials[reel.materialIndex];
                if (material == null)
                    continue;

                string propertyName = ResolveSTProperty(material);
                if (string.IsNullOrEmpty(propertyName))
                {
                    Debug.LogError(
                        $"No texture ST property found on material {material.name}.",
                        material
                    );
                    continue;
                }

                stPropertyIds[i] = Shader.PropertyToID(propertyName);
                originalST[i] = material.GetVector(stPropertyIds[i]);
                reelReady[i] = true;
            }
        }

        private void EnsureInitialized()
        {
            if (blocks == null || blocks.Length != ReelCount)
                InitializeReels();
        }

        private string ResolveSTProperty(Material material)
        {
            if (!string.IsNullOrWhiteSpace(preferredTextureSTProperty) &&
                material.HasProperty(preferredTextureSTProperty))
                return preferredTextureSTProperty;

            if (material.HasProperty("_BaseMap_ST")) return "_BaseMap_ST";
            if (material.HasProperty("_MainTex_ST")) return "_MainTex_ST";
            return null;
        }

        private void FindAndConfigureCharacter()
        {
            Transform point = FindChildRecursive(transform, characterPointName);
            if (point == null)
                return;

            characterAnimator = point.GetComponentInChildren<Animator>(true);
            if (characterAnimator == null)
                return;

            if (characterController != null)
                characterAnimator.runtimeAnimatorController = characterController;

            characterAnimator.applyRootMotion = false;
            characterAnimator.cullingMode = AnimatorCullingMode.CullCompletely;

            foreach (SkinnedMeshRenderer smr in
                     characterAnimator.GetComponentsInChildren<SkinnedMeshRenderer>(true))
                smr.updateWhenOffscreen = false;

            characterAnimator.Rebind();
            characterAnimator.Update(0f);
            PlaySitting();
        }

        private void EnsureCharacter()
        {
            if (characterAnimator == null)
                FindAndConfigureCharacter();
        }

        private void PlayResult(bool win)
        {
            EnsureCharacter();

            if (characterAnimator != null)
            {
                characterAnimator.ResetTrigger(winHash);
                characterAnimator.ResetTrigger(loseHash);

                if (win)
                {
                    characterAnimator.SetTrigger(winHash);
                }
                else if (UnityEngine.Random.Range(0f, 100f) < loseAnimationChancePercent)
                {
                    characterAnimator.SetTrigger(loseHash);
                }
                // Если шанс Lose не сработал, персонаж продолжает Sitting/Idle.
            }

            if (win)
                PlayWinFeedback();
        }

        private void PlaySitting()
        {
            if (characterAnimator == null)
                return;

            if (!characterAnimator.HasState(0, sittingHash))
            {
                Debug.LogWarning($"State {sittingStatePath} not found on {name}.", characterAnimator);
                return;
            }

            characterAnimator.Play(sittingHash, 0, UnityEngine.Random.Range(0f, 1f));
            characterAnimator.Update(0f);
        }

        private void ResetCharacterTriggers()
        {
            EnsureCharacter();
            if (characterAnimator == null)
                return;

            characterAnimator.ResetTrigger(winHash);
            characterAnimator.ResetTrigger(loseHash);
        }

        private void PlayWinFeedback()
        {
            if (winParticles != null)
            {
                foreach (ParticleSystem particle in winParticles)
                {
                    if (particle == null) continue;
                    particle.Stop(true, ParticleSystemStopBehavior.StopEmittingAndClear);
                    particle.Play(true);
                }
            }

            if (winAudioSource != null)
            {
                winAudioSource.Stop();
                winAudioSource.Play();
            }
        }

        private void StopWinFeedback()
        {
            if (winParticles != null)
            {
                foreach (ParticleSystem particle in winParticles)
                {
                    if (particle != null)
                        particle.Stop(true, ParticleSystemStopBehavior.StopEmittingAndClear);
                }
            }

            if (winAudioSource != null)
                winAudioSource.Stop();
        }

        private void StopFeedback()
        {
            StopWinFeedback();
            if (spinAudioSource != null) spinAudioSource.Stop();
            if (reelStopAudioSource != null) reelStopAudioSource.Stop();
        }

        private void EnsureFourReels()
        {
            if (reels == null)
                reels = new ReelSettings[ReelCount];
            else if (reels.Length != ReelCount)
                Array.Resize(ref reels, ReelCount);

            for (int i = 0; i < ReelCount; i++)
                if (reels[i] == null)
                    reels[i] = new ReelSettings();
        }

        private static int PositiveModulo(int value, int modulo)
        {
            int result = value % modulo;
            return result < 0 ? result + modulo : result;
        }

        private static float RandomRange(Vector2 range)
        {
            return UnityEngine.Random.Range(
                Mathf.Min(range.x, range.y),
                Mathf.Max(range.x, range.y)
            );
        }

        private static Transform FindChildRecursive(Transform parent, string targetName)
        {
            foreach (Transform child in parent)
            {
                if (string.Equals(child.name, targetName, StringComparison.Ordinal))
                    return child;

                Transform result = FindChildRecursive(child, targetName);
                if (result != null)
                    return result;
            }

            return null;
        }

        [ContextMenu("TEST/Random Spin")]
        private void TestRandomSpin()
        {
            if (Application.isPlaying) SpinRandom();
        }

        [ContextMenu("TEST/Win")]
        private void TestWin()
        {
            if (Application.isPlaying) PlayResult(true);
        }

        [ContextMenu("TEST/Lose")]
        private void TestLose()
        {
            if (Application.isPlaying) PlayResult(false);
        }

        [ContextMenu("TEST/Sitting")]
        private void TestSitting()
        {
            if (!Application.isPlaying) return;
            EnsureCharacter();
            PlaySitting();
        }

        [ContextMenu("PREVIEW/Apply Row To All Reels")]
        private void PreviewAllReels()
        {
            EnsureInitialized();

            for (int i = 0; i < ReelCount; i++)
            {
                float offset = GetRowOffset(previewRow, reels[i].rowShift);
                currentOffsets[i] = offset;
                ApplyOffset(i, offset);
            }
        }

        [ContextMenu("PREVIEW/Apply Preset")]
        private void PreviewPreset()
        {
            EnsureInitialized();

            if (previewPresetIndex < 0 || previewPresetIndex >= presets.Count)
                return;

            ResultPreset preset = presets[previewPresetIndex];
            for (int i = 0; i < ReelCount; i++)
            {
                float offset = GetRowOffset(preset.GetRow(i), reels[i].rowShift);
                currentOffsets[i] = offset;
                ApplyOffset(i, offset);
            }
        }

        [ContextMenu("PREVIEW/Reset Added UV Offset")]
        private void ResetPreview()
        {
            EnsureInitialized();

            for (int i = 0; i < ReelCount; i++)
            {
                currentOffsets[i] = 0f;
                ApplyOffset(i, 0f);
            }
        }
    }
}
