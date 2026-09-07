using UnityEngine;

namespace Controller
{
    public abstract class PlayerCamera : MonoBehaviour
    {
        [SerializeField] private PlayerInput m_input; 

        [Header("Distance limits from the player")]
        [SerializeField] private float m_MinDistance = 1f;
        [SerializeField] private float m_MaxDistance = 10f;

        [SerializeField] protected Transform m_Player;

        [Header("Camera rotation sensitivities")]
        [SerializeField, Range(0f, 1f)]
        private float m_SensitivityX = 0.1f;
        [SerializeField, Range(0f, 1f)]
        private float m_SensitivityY = 0.1f;

        [Header("Camera distance from the player (the higher the value, the closer the camera is)")]
        [SerializeField, Range(0f, 1f)]
        private float m_Zoom = 0.5f;
        [Header("Zoom sensitivity")]
        [SerializeField, Range(0f, 1f)]
        private float m_SensetivityZoom = 0.1f;

        [Header("Camera rotation limits")]
        [SerializeField, Range(-90f, 90f)]
        private float m_MinAngle = 0f;
        [SerializeField, Range(-90f, 90f)]
        private float m_MaxAngle = 50f;

        protected Transform m_Target;
        protected Transform m_Transform;

        [Header("When true, moving the mouse up rotates the camera downward." +
            "\nWhen false, moving the mouse up rotates the camera upward.")]
        [SerializeField] protected bool m_DoInvertY = false;

        protected Vector2 m_Angles;
        protected float m_Distance;

        public Transform Player => m_Player;
        public Vector3 Target => m_Target.position;
        public float TargetDistance => m_MaxDistance * 2f;

        protected virtual void Awake()
        {
            m_Transform = transform;

            m_Target = new GameObject($"Target_{gameObject.name}").transform;
            if(m_Transform.parent != null)
            {
                m_Target.transform.parent = m_Transform.parent;
            }
            
            if(m_Player == null)
            {
                Debug.Log($"Please set the player transform to the camera. GameObject name ({gameObject.name})");
            }

            if (m_input == null)
            {
                m_input = FindFirstObjectByType<PlayerInput>();
                if (m_input != null)
                {
                    m_input.AddCameraAction(SetInput);
                    Debug.Log($"Player input been found automatically from {m_input.gameObject.name}");
                }
                else
                {
                    Debug.LogWarning($"No player input could be found!");
                }
            }
        }

        public void BindPlayer(Transform player)
        {
            m_Player = player;
        }

        protected virtual void SetInput(CameraInputData cameraInputData)
        {
            float direction = m_DoInvertY ? 1f : -1f;

            m_Angles.x += cameraInputData.MouseDelta.y * m_SensitivityY * direction * 360f;
            m_Angles.y += cameraInputData.MouseDelta.x * m_SensitivityX * 360f;

            m_Angles.x = Mathf.Clamp(m_Angles.x, m_MinAngle, m_MaxAngle);

            m_Zoom += cameraInputData.Scroll * m_SensetivityZoom;
            m_Zoom = Mathf.Clamp01(m_Zoom);

            m_Distance = (1f - m_Zoom) * (m_MaxDistance - m_MinDistance) + m_MinDistance;
        }

        protected virtual void OnDestroy()
        {
            if (m_input != null)
                m_input.RemoveCameraAction(SetInput);
        }
    }
}