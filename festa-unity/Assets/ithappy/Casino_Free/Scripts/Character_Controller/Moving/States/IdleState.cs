using UnityEngine;

namespace Controller
{
    public class IdleState : MovementState
    {
        public IdleState(CharacterMover context) : base(context) { }

        public override void Enter()
        {
            base.Enter();

            Movement.SetMoving(false);
        }

        public override void Update(MoveInputData data, float deltaTime)
        {
            if (data.IsJump || m_Context.Controller.isGrounded == false) { m_Context.SwitchState<JumpState>(); return; }
            if (data.IsCrouch) { m_Context.SwitchState<CrouchingState>(); return; }
            if (data.Axis.sqrMagnitude > Mathf.Epsilon) { m_Context.SwitchState<WalkState>(); return; }

            Movement.Move(deltaTime, data.Axis, data.Target, out var animAxis);

            Animation.Animate(in animAxis, deltaTime);
            Animation.SetTargetState(0f, deltaTime);
        }
    }
}