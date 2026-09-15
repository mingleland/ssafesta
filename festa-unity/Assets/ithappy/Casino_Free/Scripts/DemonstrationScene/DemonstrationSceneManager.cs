using Controller;
using UnityEngine;

namespace ithappy
{
    public class DemonstrationSceneManager : MonoBehaviour
    {
        private void Start()
        {
            PlayerInput[] playerInputs = FindObjectsOfType<PlayerInput>();
            PlayerCamera[] playerCameras = FindObjectsOfType<PlayerCamera>();
            
            if (playerInputs.Length == 0)
            {
                Debug.Log("No player found. Please add one to the scene.");
                return;
            }
            
            if (playerCameras.Length == 0)
            {
                Debug.Log("No player camera found. Please add one");
                return;
            }
            
            if (playerCameras.Length > 1)
            {
                Debug.Log("You should have no more then one PlayerCamera");
                return;
            }
            
            if (playerInputs.Length > 1)
            {
                Debug.Log($"If you have 2 or more characters in the scene, they will all be controlled. " +
                          $"If you want to control only one character using the animation controller, " +
                          $"remove the scripts (MovePlayerInput and CharacterMover) from the others in the inspector.");
            }
            
            if (playerCameras[0].Player == null)
            {
                playerCameras[0].BindPlayer(playerInputs[0].transform);
                Debug.Log($"Player {playerInputs[0].gameObject.name} set automatically to the Camera by DemonstrationSceneManager");
            }
        }
    }
}