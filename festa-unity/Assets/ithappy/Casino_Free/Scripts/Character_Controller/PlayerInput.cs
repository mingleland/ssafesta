using System;
using UnityEngine;

namespace Controller
{
    public class MoveInputData
    {
        public Vector2 Axis;
        public Vector3 Target;

        public bool IsRun;
        public bool IsJump;
        public bool IsCrouch;
    }

    public class CameraInputData
    {
        public Vector2 MouseDelta;
        public float Scroll;
    }

    public class PlayerInput : MonoBehaviour
    {
        [Header("Character Axes")]
        [SerializeField] private string m_HorizontalAxis = "Horizontal";
        [SerializeField] private string m_VerticalAxis = "Vertical";
        [SerializeField] private string m_JumpButton = "Jump";
        [SerializeField] private KeyCode m_RunKey = KeyCode.LeftShift;
        [SerializeField] private KeyCode m_CrouchKey = KeyCode.LeftControl;

        [Header("Camera Axes")]
        [SerializeField] private PlayerCamera m_Camera;
        [SerializeField] private string m_MouseX = "Mouse X";
        [SerializeField] private string m_MouseY = "Mouse Y";
        [SerializeField] private string m_MouseScroll = "Mouse ScrollWheel";

        private float m_inputDurationTimer = 0f;

        private readonly float m_inputPressedTheshold = 0.15f;

        private bool m_CrouchToggle = false;

        private event Action<MoveInputData> OnMoveInput;
        private event Action<CameraInputData> OnCameraInput;

        public MoveInputData CurrentMoveData { get; private set; } = new();
        public CameraInputData CurrentCameraData { get; private set; } = new();

        public void AddMoveAction(Action<MoveInputData> action) => OnMoveInput += action;
        public void RemoveMoveAction(Action<MoveInputData> action) => OnMoveInput -= action;

        public void AddCameraAction(Action<CameraInputData> action) => OnCameraInput += action;
        public void RemoveCameraAction(Action<CameraInputData> action) => OnCameraInput -= action;

        private void Awake()
        {
            if (m_Camera == null)
            {
                m_Camera = FindFirstObjectByType<PlayerCamera>();
            }
        }

        private void Update()
        {
            GatherAndFireMoveInput();
            GatherAndFireCameraInput();
        }

        private void GatherAndFireMoveInput()
        {
            if (Input.GetKeyDown(m_CrouchKey))
            {
                m_CrouchToggle = !m_CrouchToggle;
            }

            CalculateCurrentMoveData();

            OnMoveInput?.Invoke(CurrentMoveData);
        }
        private void GatherAndFireCameraInput()
        {
            CalculateCurrentCameraData();

            OnCameraInput?.Invoke(CurrentCameraData);
        }

        private void CalculateCurrentMoveData()
        {
            Vector2 axis = new Vector2(Input.GetAxis(m_HorizontalAxis), Input.GetAxis(m_VerticalAxis)).normalized;
            CurrentMoveData = new MoveInputData
            {
                Axis = axis,
                IsRun = Input.GetKey(m_RunKey),
                IsJump = Input.GetButton(m_JumpButton),
                IsCrouch = m_CrouchToggle,
                Target = (m_Camera == null) ? Vector3.zero : m_Camera.Target,
            };
        }
        private void CalculateCurrentCameraData()
        {
            CurrentCameraData = new CameraInputData
            {
                MouseDelta = new Vector2(Input.GetAxis(m_MouseX), Input.GetAxis(m_MouseY)),
                Scroll = Input.GetAxis(m_MouseScroll)
            };
        }
    }
}