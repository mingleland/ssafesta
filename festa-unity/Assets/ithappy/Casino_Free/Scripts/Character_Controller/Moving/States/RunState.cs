using UnityEngine;

namespace Controller
{
    public class RunState : MovementState
    {
        private readonly float m_runSpeed;

        public RunState(CharacterMover context, float runSpeed) : base(context) 
        {
            m_runSpeed = runSpeed;
        }

        public override void Enter()
        {
            base.Enter();
            Movement.SetMoving(true);
            Movement.SetMoveSpeed(m_runSpeed);
        }

        public override void Update(MoveInputData data, float deltaTime)
        {
            if (data.IsJump || m_Context.Controller.isGrounded == false) { m_Context.SwitchState<JumpState>(); return; }
            if (data.IsCrouch) { m_Context.SwitchState<CrouchingState>(); return; }
            if (!data.IsRun) { m_Context.SwitchState<WalkState>(); return; }
            if (data.Axis.sqrMagnitude <= Mathf.Epsilon) { m_Context.SwitchState<IdleState>(); return; }

            Movement.Move(deltaTime, data.Axis, data.Target, out var animAxis);

            Animation.Animate(in animAxis, deltaTime);
            Animation.SetTargetState(1f, deltaTime);
        }
    }
}