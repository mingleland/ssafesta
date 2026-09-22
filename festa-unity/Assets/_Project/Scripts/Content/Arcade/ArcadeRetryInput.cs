// 모든 내장 오락기가 레거시 입력과 새 Input System 양쪽에서 재시작 키를 동일하게 감지하게 한다.
using UnityEngine;
using UnityEngine.InputSystem;

namespace Festa.Content.Arcade
{
    static class ArcadeRetryInput
    {
        public static bool WasPressed()
        {
            var keyboard = Keyboard.current;
            return Input.GetKeyDown(KeyCode.Space) || Input.GetKeyDown(KeyCode.R)
                || keyboard != null && (keyboard.spaceKey.wasPressedThisFrame || keyboard.rKey.wasPressedThisFrame);
        }
    }
}
