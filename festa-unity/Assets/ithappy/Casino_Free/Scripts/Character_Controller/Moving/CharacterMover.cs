using System;
using System.Collections.Generic;
using UnityEngine;

namespace Controller
{
    [RequireComponent(typeof(CharacterController))]
    [RequireComponent(typeof(Animator))]
    [DisallowMultipleComponent]
    public class CharacterMover : MonoBehaviour
    {
        [Header("Movement")] 
        [SerializeField] private float m_WalkSpeed = 1.3f;
        [SerializeField] private float m_RunSpeed = 2.2f;
        [SerializeField] private float m_CrouchSpeed = 0.9f;
        [SerializeField] private Space m_Space = Space.Self;
        [SerializeField] private float m_JumpHeight = 1f;

        [Header("Animator")] 
        [SerializeField] private string m_HorizontalID = "Hor";
        [SerializeField] private string m_VerticalID = "Vert";
        [SerializeField] private string m_StateID = "State";
        [SerializeField] private string m_JumpID = "IsJump";
        [SerializeField] private string m_CrouchID = "IsCrouching";

        [SerializeField] private AnimationCurve m_StateCurve = AnimationCurve.EaseInOut(0, 0, 1, 1);
        [SerializeField] private float m_stateSpeed = 4.5f;

        private MovementState m_CurrentState;
        private List<MovementState> m_States;

        private float m_RotateSpeed = 360f;
        private float m_Luft = 0f;
        
        private Animator m_Animator;
        private Transform m_Transform;

        public PlayerInput InputProvider { get; private set; }
        public MovementHandler Movement { get; private set; }
        public AnimationHandler Animation { get; private set; }
        public CharacterController Controller { get; private set; }

        private void OnValidate()
        {
            m_WalkSpeed = Mathf.Max(m_WalkSpeed, 0f);
            m_RunSpeed = Mathf.Max(m_RunSpeed, m_WalkSpeed);
        }

        private void Awake()
        {
            m_Transform = transform;
            Controller = GetComponent<CharacterController>();
            InputProvider = GetComponent<PlayerInput>();
            m_Animator = GetComponent<Animator>();

            Movement = new MovementHandler(Controller, m_Transform, m_RotateSpeed, m_Luft, m_JumpHeight, m_Space);
            Animation = new AnimationHandler(m_Animator, 
                m_HorizontalID, m_VerticalID, m_StateID, m_JumpID, m_CrouchID, 
                m_StateCurve, m_stateSpeed);

            m_States = new List<MovementState>
            {
                new IdleState(this),
                new WalkState(this, m_WalkSpeed),
                new RunState(this, m_RunSpeed),
                new JumpState(this),
                new CrouchingState(this, m_CrouchSpeed)
            };

            SwitchState<IdleState>();
        }

        public void SwitchState<T>() where T : MovementState
        {
            var nextState = m_States.Find(s => s is T);
            if (nextState != null && nextState != m_CurrentState)
            {
                if(m_CurrentState != null)
                    m_CurrentState.Exit();

                m_CurrentState = nextState;
                m_CurrentState.Enter();
            }
        }

        private void OnControllerColliderHit(ControllerColliderHit hit)
        {
            if (hit.normal.y > Controller.stepOffset)
            {
                Movement.SetSurface(hit.normal);
            }
        }
    }
}
