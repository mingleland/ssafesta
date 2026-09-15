using System.Collections;
using System.Collections.Generic;
using UnityEngine;

namespace ithappy
{
    public class SlotMachineCharacterSpawner : MonoBehaviour
    {
        [Header("Player Spawn Points")]
        [SerializeField] private List<Transform> playerSpawnPoints = new List<Transform>();

        [Header("Player Characters")]
        [SerializeField] private List<GameObject> playerPrefabs = new List<GameObject>();

        [Header("Animator Controller")]
        [SerializeField] private RuntimeAnimatorController playerAnimatorController;

        [Header("Animation")]
        [SerializeField] private string animationStateName = "Idle";
        [SerializeField] private bool playAnimationAfterSpawn = true;
        [SerializeField] private bool randomAnimationOffset = true;
        [SerializeField] private bool disableRootMotion = true;
        [SerializeField] private bool alwaysAnimate = true;

        [Header("Spawn Settings")]
        [SerializeField] private bool spawnOnStart = true;
        [SerializeField] private bool spawnOnlyOnce = true;
        [SerializeField] private bool randomizePlayerPrefabs = true;
        [SerializeField] private bool allowDuplicatePlayers = true;

        [Range(0f, 1f)]
        [SerializeField] private float playerSpawnChance = 0.8f;

        [SerializeField] private int minPlayersToSpawn = 1;
        [SerializeField] private int maxPlayersToSpawn = -1;

        private readonly List<GameObject> spawnedPlayers = new List<GameObject>();
        private readonly List<Animator> playerAnimators = new List<Animator>();

        private bool hasSpawned;
        private bool isRespawning;

        private void Start()
        {
            if (spawnOnStart)
            {
                SpawnPlayersOnce();
            }
        }

        [ContextMenu("Spawn Players Once")]
        public void SpawnPlayersOnce()
        {
            if (spawnOnlyOnce && hasSpawned)
            {
                Debug.Log($"{name}: Characters already spawned. Use Respawn Characters if you want to recreate them.");
                return;
            }

            SpawnPlayersInternal();
        }

        [ContextMenu("Respawn Characters")]
        public void RespawnCharacters()
        {
            if (isRespawning)
                return;

            if (Application.isPlaying)
            {
                StartCoroutine(RespawnRoutine());
            }
            else
            {
                ClearSpawnedPlayersImmediate();
                SpawnPlayersInternal();
            }
        }

        private IEnumerator RespawnRoutine()
        {
            isRespawning = true;

            ClearSpawnedPlayers();

            
            yield return null;

            SpawnPlayersInternal();

            isRespawning = false;
        }

        private void SpawnPlayersInternal()
        {
            RemoveNullReferences();

            if (playerSpawnPoints == null || playerSpawnPoints.Count == 0)
            {
                Debug.LogWarning($"{name}: Player Spawn Points list is empty.");
                return;
            }

            if (playerPrefabs == null || playerPrefabs.Count == 0)
            {
                Debug.LogWarning($"{name}: Player Prefabs list is empty.");
                return;
            }

            List<Transform> selectedSpawnPoints = GetRandomPlayerSpawnPoints();
            List<GameObject> availablePlayerPrefabs = new List<GameObject>(playerPrefabs);

            foreach (Transform spawnPoint in selectedSpawnPoints)
            {
                if (spawnPoint == null)
                    continue;

                if (HasSpawnedCharacterOnPoint(spawnPoint))
                    continue;

                GameObject playerPrefab = GetPlayerPrefab(availablePlayerPrefabs);

                if (playerPrefab == null)
                    continue;

                GameObject spawnedPlayer = Instantiate(
                    playerPrefab,
                    spawnPoint.position,
                    spawnPoint.rotation,
                    spawnPoint
                );

                spawnedPlayer.transform.localPosition = Vector3.zero;
                spawnedPlayer.transform.localRotation = Quaternion.identity;

                spawnedPlayers.Add(spawnedPlayer);

                Animator animator = SetupAnimator(spawnedPlayer);

                if (animator != null)
                {
                    playerAnimators.Add(animator);

                    if (playAnimationAfterSpawn)
                    {
                        PlayAnimationOnce(animator);
                    }
                }
            }

            hasSpawned = spawnedPlayers.Count > 0;
        }

        private Animator SetupAnimator(GameObject spawnedPlayer)
        {
            Animator animator = spawnedPlayer.GetComponentInChildren<Animator>(true);

            if (animator == null)
            {
                Debug.LogWarning($"{name}: Spawned player has no Animator: {spawnedPlayer.name}");
                return null;
            }

            if (playerAnimatorController != null)
            {
                animator.runtimeAnimatorController = playerAnimatorController;
            }

            if (disableRootMotion)
            {
                animator.applyRootMotion = false;
            }

            if (alwaysAnimate)
            {
                animator.cullingMode = AnimatorCullingMode.AlwaysAnimate;
            }

            animator.updateMode = AnimatorUpdateMode.Normal;
            animator.enabled = true;

            return animator;
        }

        private void PlayAnimationOnce(Animator animator)
        {
            if (animator == null)
                return;

            if (string.IsNullOrEmpty(animationStateName))
                return;

            int stateHash = Animator.StringToHash(animationStateName);

            if (!animator.HasState(0, stateHash))
            {
                Debug.LogWarning($"{name}: Animation State not found in Animator Controller: {animationStateName}", animator);
                return;
            }

            float offset = randomAnimationOffset ? Random.Range(0f, 1f) : 0f;

            
            animator.Play(stateHash, 0, offset);
            animator.Update(0f);
        }

        private bool HasSpawnedCharacterOnPoint(Transform spawnPoint)
        {
            foreach (GameObject player in spawnedPlayers)
            {
                if (player == null)
                    continue;

                if (player.transform.parent == spawnPoint)
                    return true;
            }

            return false;
        }

        private List<Transform> GetRandomPlayerSpawnPoints()
        {
            List<Transform> selectedPoints = new List<Transform>();

            foreach (Transform point in playerSpawnPoints)
            {
                if (point == null)
                    continue;

                if (Random.value <= playerSpawnChance)
                {
                    selectedPoints.Add(point);
                }
            }

            if (minPlayersToSpawn > 0)
            {
                List<Transform> availablePoints = new List<Transform>();

                foreach (Transform point in playerSpawnPoints)
                {
                    if (point != null && !selectedPoints.Contains(point))
                    {
                        availablePoints.Add(point);
                    }
                }

                while (selectedPoints.Count < minPlayersToSpawn && availablePoints.Count > 0)
                {
                    int randomIndex = Random.Range(0, availablePoints.Count);
                    selectedPoints.Add(availablePoints[randomIndex]);
                    availablePoints.RemoveAt(randomIndex);
                }
            }

            int maxAllowedPlayers = maxPlayersToSpawn;

            if (maxAllowedPlayers < 0 || maxAllowedPlayers > playerSpawnPoints.Count)
            {
                maxAllowedPlayers = playerSpawnPoints.Count;
            }

            while (selectedPoints.Count > maxAllowedPlayers)
            {
                int randomIndex = Random.Range(0, selectedPoints.Count);
                selectedPoints.RemoveAt(randomIndex);
            }

            ShuffleList(selectedPoints);

            return selectedPoints;
        }

        private GameObject GetPlayerPrefab(List<GameObject> availablePrefabs)
        {
            if (playerPrefabs.Count == 0)
                return null;

            if (allowDuplicatePlayers)
            {
                if (randomizePlayerPrefabs)
                {
                    return playerPrefabs[Random.Range(0, playerPrefabs.Count)];
                }

                return playerPrefabs[0];
            }

            if (availablePrefabs.Count == 0)
            {
                Debug.LogWarning($"{name}: Not enough unique player prefabs for all spawn points.");
                return null;
            }

            int randomIndex = randomizePlayerPrefabs
                ? Random.Range(0, availablePrefabs.Count)
                : 0;

            GameObject prefab = availablePrefabs[randomIndex];
            availablePrefabs.RemoveAt(randomIndex);

            return prefab;
        }

        [ContextMenu("Clear Spawned Players")]
        public void ClearSpawnedPlayers()
        {
            foreach (GameObject player in spawnedPlayers)
            {
                if (player != null)
                {
                    DestroySafe(player);
                }
            }

            spawnedPlayers.Clear();
            playerAnimators.Clear();
            hasSpawned = false;
        }

        [ContextMenu("Clear Spawned Players Immediate")]
        public void ClearSpawnedPlayersImmediate()
        {
            foreach (GameObject player in spawnedPlayers)
            {
                if (player != null)
                {
                    DestroyImmediateSafe(player);
                }
            }

            spawnedPlayers.Clear();
            playerAnimators.Clear();
            hasSpawned = false;
        }

        private void RemoveNullReferences()
        {
            spawnedPlayers.RemoveAll(player => player == null);
            playerAnimators.RemoveAll(animator => animator == null);
        }

        private void ShuffleList<T>(List<T> list)
        {
            for (int i = 0; i < list.Count; i++)
            {
                int randomIndex = Random.Range(i, list.Count);

                T temp = list[i];
                list[i] = list[randomIndex];
                list[randomIndex] = temp;
            }
        }

        private void DestroySafe(GameObject target)
        {
            if (target == null)
                return;

            if (Application.isPlaying)
            {
                Destroy(target);
            }
            else
            {
                DestroyImmediate(target);
            }
        }

        private void DestroyImmediateSafe(GameObject target)
        {
            if (target == null)
                return;

            DestroyImmediate(target);
        }
    }
}
