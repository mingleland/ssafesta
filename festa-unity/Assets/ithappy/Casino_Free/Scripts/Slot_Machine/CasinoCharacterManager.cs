using System;
using System.Collections.Generic;
using UnityEngine;

namespace ithappy.Casino
{
    [DefaultExecutionOrder(-100)]
    public sealed class CasinoCharacterDistributor : MonoBehaviour
    {
        [Header("Slots")]
        [Tooltip(
            "Корневой объект со всеми слотами. " +
            "Если оставить пустым, скрипт найдёт слоты во всей сцене."
        )]
        [SerializeField] private Transform slotsRoot;

        [Header("Characters")]
        [SerializeField] private List<GameObject> characterPrefabs =
            new List<GameObject>();

        [Header("Random")]
        [SerializeField] private bool randomSeedEachPlay = true;
        [SerializeField] private int fixedSeed = 12345;

        private void Awake()
        {
            DistributeCharacters();
        }

        [ContextMenu("Distribute Characters")]
        public void DistributeCharacters()
        {
            SlotMachineFeedback[] slots = FindSlots();

            if (slots == null || slots.Length == 0)
            {
                Debug.LogWarning("No slot machines found.", this);
                return;
            }

            List<GameObject> uniqueCharacters =
                GetUniqueCharacterPrefabs();

            if (uniqueCharacters.Count < slots.Length)
            {
                Debug.LogError(
                    $"Not enough unique characters. " +
                    $"Slots: {slots.Length}, " +
                    $"Characters: {uniqueCharacters.Count}. " +
                    $"Add at least {slots.Length} different prefabs.",
                    this
                );

                return;
            }

            int seed = randomSeedEachPlay
                ? Environment.TickCount
                : fixedSeed;

            System.Random random = new System.Random(seed);

            Shuffle(uniqueCharacters, random);
            Shuffle(slots, random);

            for (int i = 0; i < slots.Length; i++)
            {
                slots[i].AssignCharacter(uniqueCharacters[i]);
            }

            Debug.Log(
                $"Assigned {slots.Length} unique characters to slots.",
                this
            );
        }

        private SlotMachineFeedback[] FindSlots()
        {
            if (slotsRoot != null)
            {
                return slotsRoot.GetComponentsInChildren
                    <SlotMachineFeedback>(true);
            }

            return FindObjectsOfType<SlotMachineFeedback>(true);
        }

        private List<GameObject> GetUniqueCharacterPrefabs()
        {
            List<GameObject> result = new List<GameObject>();
            HashSet<GameObject> addedPrefabs =
                new HashSet<GameObject>();

            foreach (GameObject prefab in characterPrefabs)
            {
                if (prefab == null)
                {
                    continue;
                }

                if (addedPrefabs.Add(prefab))
                {
                    result.Add(prefab);
                }
            }

            return result;
        }

        private static void Shuffle<T>(
            IList<T> collection,
            System.Random random
        )
        {
            for (int i = collection.Count - 1; i > 0; i--)
            {
                int randomIndex = random.Next(i + 1);

                T temporary = collection[i];
                collection[i] = collection[randomIndex];
                collection[randomIndex] = temporary;
            }
        }
    }
}