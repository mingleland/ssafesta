using UnityEngine;

namespace Controller
{
    public abstract class MovementState
    {
        protected readonly CharacterMover m_Context;

        protected MovementHandler Movement => m_Context.Movement;
        protected AnimationHandler Animation => m_Context.Animation;
        protected PlayerInput Input => m_Context.InputProvider;

        protected MovementState(CharacterMover context)
        {
            m_Context = context;
        }

        public virtual void Enter() 
        {
            Input.AddMoveAction(InternalUpdate);
        }
        public virtual void Exit() 
        {
            Input.RemoveMoveAction(InternalUpdate);
        }

        private void InternalUpdate(MoveInputData moveInputData)
        {
            Update(moveInputData, Time.deltaTime);
        }

        public abstract void Update(MoveInputData moveInputData, float deltaTime);
    }
}
