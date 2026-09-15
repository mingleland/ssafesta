using UnityEngine;

namespace Controller
{
    public class WalkState : MovementState
    {
        private readonly float m_walkSpeed;

        public WalkState(CharacterMover context, float walkSpeed) : base(context) 
        {
            m_walkSpeed = walkSpeed;
        }

        public override void Enter()
        {
            base.Enter();

            Movement.SetMoving(false);
            Movement.SetMoveSpeed(m_walkSpeed);
        }

        public override void Update(MoveInputData data, float deltaTime)
        {
            if (data.IsJump || m_Context.Controller.isGrounded == false) { m_Context.SwitchState<JumpState>(); return; }
            if (data.IsCrouch) { m_Context.SwitchState<CrouchingState>(); return; }
            if (data.Axis.sqrMagnitude <= Mathf.Epsilon) { m_Context.SwitchState<IdleState>(); return; }
            if (data.IsRun) { m_Context.SwitchState<RunState>(); return; }

            Movement.Move(deltaTime, data.Axis, data.Target, out var animAxis);

            Animation.Animate(in animAxis, deltaTime);
            Animation.SetTargetState(0f, deltaTime);
        }
    }
}