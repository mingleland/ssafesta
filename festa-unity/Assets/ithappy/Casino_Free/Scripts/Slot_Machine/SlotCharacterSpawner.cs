using System;
using System.Collections.Generic;
using UnityEngine;

namespace ithappy
{
    [DefaultExecutionOrder(-100)]
    [DisallowMultipleComponent]
    public sealed class SlotCharacterSpawner : MonoBehaviour
    {
        [Header("Character Prefabs")]
        [SerializeField]
        private List<GameObject> characterPrefabs = new List<GameObject>();

        [Header("Character Point")]
        [SerializeField]
        private string characterPointName = "CharacterPoint";

        [Header("Spawn Settings")]
        [SerializeField]
        private bool spawnOnAwake = true;
        [SerializeField]
        private bool clearPointBeforeSpawn = true;
        [SerializeField]
        private bool randomSeedEachPlay = true;
        [SerializeField]
        private int fixedSeed = 12345;

        private void Awake()
        {
            if (spawnOnAwake)
            {
                SpawnCharacters();
            }
        }

        [ContextMenu("Spawn Characters")]
        public void SpawnCharacters()
        {
            List<Transform> characterPoints = FindCharacterPoints();
            List<GameObject> validCharacters = GetValidCharacterPrefabs();

            if (characterPoints.Count == 0)
            {
                Debug.LogError(
                    $"Внутри {name} не найдено ни одной точки " +
                    $"с именем \"{characterPointName}\".",
                    this
                );

                return;
            }

            if (validCharacters.Count == 0)
            {
                Debug.LogError(
                    "Список Character Prefabs пуст. " +
                    "Добавь хотя бы один префаб персонажа.",
                    this
                );

                return;
            }

            int seed = randomSeedEachPlay
                ? Guid.NewGuid().GetHashCode()
                : fixedSeed;

            System.Random random = new System.Random(seed);

            
            
            Shuffle(characterPoints, random);

            List<GameObject> assignments = CreateRandomAssignments(
                characterPoints.Count,
                validCharacters,
                random
            );

            for (int i = 0; i < characterPoints.Count; i++)
            {
                SpawnCharacter(
                    assignments[i],
                    characterPoints[i]
                );
            }

            Debug.Log(
                $"Персонажи расставлены.\n" +
                $"Слотов: {characterPoints.Count}\n" +
                $"Префабов персонажей: {validCharacters.Count}",
                this
            );
        }

        private List<GameObject> CreateRandomAssignments(
            int requiredCount,
            List<GameObject> availableCharacters,
            System.Random random
        )
        {
            List<GameObject> result =
                new List<GameObject>(requiredCount);

            GameObject previousCharacter = null;

            while (result.Count < requiredCount)
            {
                
                List<GameObject> randomBag =
                    new List<GameObject>(availableCharacters);

                Shuffle(randomBag, random);

                
                
                if (randomBag.Count > 1 &&
                    previousCharacter != null &&
                    randomBag[0] == previousCharacter)
                {
                    int swapIndex = FindDifferentCharacterIndex(
                        randomBag,
                        previousCharacter
                    );

                    if (swapIndex > 0)
                    {
                        Swap(randomBag, 0, swapIndex);
                    }
                }

                foreach (GameObject character in randomBag)
                {
                    if (result.Count >= requiredCount)
                    {
                        break;
                    }

                    result.Add(character);
                    previousCharacter = character;
                }
            }

            return result;
        }

        private static int FindDifferentCharacterIndex(
            List<GameObject> characters,
            GameObject previousCharacter
        )
        {
            for (int i = 1; i < characters.Count; i++)
            {
                if (characters[i] != previousCharacter)
                {
                    return i;
                }
            }

            return -1;
        }

        private List<Transform> FindCharacterPoints()
        {
            List<Transform> result = new List<Transform>();

            Transform[] allChildren =
                GetComponentsInChildren<Transform>(true);

            foreach (Transform child in allChildren)
            {
                if (child == transform)
                {
                    continue;
                }

                if (string.Equals(
                    child.name,
                    characterPointName,
                    StringComparison.Ordinal))
                {
                    result.Add(child);
                }
            }

            return result;
        }

        private List<GameObject> GetValidCharacterPrefabs()
        {
            List<GameObject> result = new List<GameObject>();
            HashSet<GameObject> addedPrefabs =
                new HashSet<GameObject>();

            foreach (GameObject characterPrefab in characterPrefabs)
            {
                if (characterPrefab == null)
                {
                    continue;
                }

                
                
                if (addedPrefabs.Add(characterPrefab))
                {
                    result.Add(characterPrefab);
                }
            }

            return result;
        }

        private void SpawnCharacter(
            GameObject characterPrefab,
            Transform characterPoint
        )
        {
            if (clearPointBeforeSpawn)
            {
                ClearCharacterPoint(characterPoint);
            }

            GameObject characterInstance = Instantiate(
                characterPrefab,
                characterPoint,
                false
            );

            characterInstance.name = characterPrefab.name;

            characterInstance.transform.localPosition = Vector3.zero;
            characterInstance.transform.localRotation =
                Quaternion.identity;

            
        }

        private static void ClearCharacterPoint(
            Transform characterPoint
        )
        {
            for (int i = characterPoint.childCount - 1; i >= 0; i--)
            {
                GameObject child =
                    characterPoint.GetChild(i).gameObject;

                if (Application.isPlaying)
                {
                    child.SetActive(false);
                    Destroy(child);
                }
                else
                {
                    DestroyImmediate(child);
                }
            }
        }

        private static void Shuffle<T>(
            IList<T> list,
            System.Random random
        )
        {
            for (int i = list.Count - 1; i > 0; i--)
            {
                int randomIndex = random.Next(i + 1);
                Swap(list, i, randomIndex);
            }
        }

        private static void Swap<T>(
            IList<T> list,
            int firstIndex,
            int secondIndex
        )
        {
            T temporary = list[firstIndex];
            list[firstIndex] = list[secondIndex];
            list[secondIndex] = temporary;
        }
    }
}
