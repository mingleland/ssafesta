using UnityEngine;

namespace Controller
{
    public class JumpState : MovementState
    {
        public JumpState(CharacterMover context) : base(context) { }

        public override void Enter()
        {
            base.Enter();

            Animation.SetJump(true);
            Movement.SetJump(true);
        }

        public override void Exit()
        {
            base.Exit();
            Animation.SetJump(false);
            Movement.SetJump(false);
        }

        public override void Update(MoveInputData data, float deltaTime)
        {
            Movement.SetMoving(data.Axis.sqrMagnitude > Mathf.Epsilon);
            Movement.Move(deltaTime, data.Axis, data.Target, out var animAxis);

            Animation.Animate(in animAxis, deltaTime);
            Animation.SetTargetState(data.IsRun ? 1f : 0f, deltaTime);

            if (m_Context.Controller.isGrounded)
            {
                if (data.IsCrouch) 
                    m_Context.SwitchState<CrouchingState>();
                else if 
                    (data.Axis.sqrMagnitude > Mathf.Epsilon) 
                    m_Context.SwitchState<WalkState>();
                else 
                    m_Context.SwitchState<IdleState>();
            }
        }
    }
}