using UnityEngine;

namespace ithappy.Casino
{
    [DisallowMultipleComponent]
    public sealed class SlotMachineFeedback : MonoBehaviour
    {
        [Header("Character")]
        [SerializeField] private Transform characterPoint;

        [Header("Win Effects")]
        [SerializeField] private ParticleSystem[] winFireworks;
        [SerializeField] private AudioSource winAudioSource;

        [Tooltip("Можно оставить пустым, если клип уже назначен в AudioSource.")]
        [SerializeField] private AudioClip winClip;

        [SerializeField, Range(0f, 1f)]
        private float winVolume = 1f;

        [Header("Animator Parameters")]
        [SerializeField] private string winTriggerName = "Win";
        [SerializeField] private string loseTriggerName = "Lose";

        private GameObject characterInstance;
        private Animator characterAnimator;

        private int winTriggerHash;
        private int loseTriggerHash;

        private void Awake()
        {
            winTriggerHash = Animator.StringToHash(winTriggerName);
            loseTriggerHash = Animator.StringToHash(loseTriggerName);
        }

        /// <summary>
        /// Создаёт персонажа возле этого слота.
        /// </summary>
        public void AssignCharacter(GameObject characterPrefab)
        {
            if (characterPrefab == null)
            {
                Debug.LogError(
                    $"Character prefab is missing for slot {name}.",
                    this
                );

                return;
            }

            if (characterPoint == null)
            {
                Debug.LogError(
                    $"CharacterPoint is not assigned on slot {name}.",
                    this
                );

                return;
            }

            if (characterInstance != null)
            {
                Destroy(characterInstance);
            }

            characterInstance = Instantiate(
                characterPrefab,
                characterPoint,
                false
            );

            characterInstance.name = characterPrefab.name;

            characterAnimator =
                characterInstance.GetComponentInChildren<Animator>(true);

            if (characterAnimator == null)
            {
                Debug.LogError(
                    $"Character {characterPrefab.name} has no Animator.",
                    characterInstance
                );

                return;
            }

            // Персонажи за пределами камеры не будут зря обновляться.
            characterAnimator.cullingMode =
                AnimatorCullingMode.CullCompletely;

            ResetAnimationTriggers();
        }

        /// <summary>
        /// Вызывается в момент начала вращения.
        /// </summary>
        public void SpinStarted()
        {
            ResetAnimationTriggers();
            StopWinEffects();
        }

        /// <summary>
        /// Вызывается после остановки последнего барабана.
        /// </summary>
        public void SpinFinished(bool isWin)
        {
            if (characterAnimator == null)
            {
                Debug.LogWarning(
                    $"Slot {name} has no assigned character.",
                    this
                );

                return;
            }

            ResetAnimationTriggers();

            if (isWin)
            {
                characterAnimator.SetTrigger(winTriggerHash);
                PlayWinEffects();
            }
            else
            {
                characterAnimator.SetTrigger(loseTriggerHash);
            }
        }

        private void ResetAnimationTriggers()
        {
            if (characterAnimator == null)
            {
                return;
            }

            characterAnimator.ResetTrigger(winTriggerHash);
            characterAnimator.ResetTrigger(loseTriggerHash);
        }

        private void PlayWinEffects()
        {
            if (winFireworks != null)
            {
                foreach (ParticleSystem particle in winFireworks)
                {
                    if (particle == null)
                    {
                        continue;
                    }

                    particle.Stop(
                        true,
                        ParticleSystemStopBehavior.StopEmittingAndClear
                    );

                    particle.Play(true);
                }
            }

            if (winAudioSource == null)
            {
                return;
            }

            winAudioSource.Stop();

            if (winClip != null)
            {
                winAudioSource.PlayOneShot(winClip, winVolume);
            }
            else if (winAudioSource.clip != null)
            {
                winAudioSource.volume = winVolume;
                winAudioSource.Play();
            }
        }

        private void StopWinEffects()
        {
            if (winFireworks != null)
            {
                foreach (ParticleSystem particle in winFireworks)
                {
                    if (particle == null)
                    {
                        continue;
                    }

                    particle.Stop(
                        true,
                        ParticleSystemStopBehavior.StopEmittingAndClear
                    );
                }
            }

            if (winAudioSource != null)
            {
                winAudioSource.Stop();
            }
        }
    }
}