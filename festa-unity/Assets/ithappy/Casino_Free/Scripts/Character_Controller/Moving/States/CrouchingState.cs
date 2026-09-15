using UnityEngine;

namespace Controller
{
    public class CrouchingState : MovementState
    {
        private readonly float m_CrouchHeight = 1.2f;
        private readonly float m_NormalHeight = 1.8f;

        private readonly float m_crouchSpeed;

        public CrouchingState(CharacterMover context, float crouchSpeed) : base(context) 
        {
            m_crouchSpeed = crouchSpeed;
        }

        public override void Enter()
        {
            base.Enter();

            m_Context.Controller.height = m_CrouchHeight;
            m_Context.Controller.center = new Vector3(0, m_CrouchHeight / 2f, 0);

            Animation.SetCrouching(true);
            Movement.SetMoveSpeed(m_crouchSpeed);
        }

        public override void Exit()
        {
            base.Exit(); 

            m_Context.Controller.height = m_NormalHeight;
            m_Context.Controller.center = new Vector3(0, m_NormalHeight / 2f, 0);

            m_Context.Animation.SetCrouching(false);
        }

        public override void Update(MoveInputData data, float deltaTime)
        {
            if (!data.IsCrouch)
            {
                if (data.Axis.sqrMagnitude > Mathf.Epsilon)
                    m_Context.SwitchState<WalkState>();
                else
                    m_Context.SwitchState<IdleState>();

                return;
            }

            if (data.IsJump || m_Context.Controller.isGrounded == false) 
            { 
                m_Context.SwitchState<JumpState>(); 
                return; 
            }

            Movement.SetMoving(data.Axis.sqrMagnitude > Mathf.Epsilon);
            Movement.Move(deltaTime, data.Axis, data.Target, out var animAxis);

            Animation.Animate(in animAxis, deltaTime);
            Animation.SetTargetState(0f, deltaTime);
        }
    }
}