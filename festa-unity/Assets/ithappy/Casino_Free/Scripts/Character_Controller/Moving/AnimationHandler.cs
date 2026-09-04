using UnityEngine;

namespace Controller
{
    public class AnimationHandler
    {
        private const float ANIMATION_DAMP_TIME = 5f;

        private readonly Animator m_Animator;

        private readonly string m_HorizontalID;
        private readonly string m_VerticalID;
        private readonly string m_StateID;
        private readonly string m_JumpID;
        private readonly string m_CrouchID;

        private readonly AnimationCurve m_StateCurve;
        private readonly float m_StateSpeed = 3f;

        private float m_StateTime;

        private Vector2 m_FlowAxis;

        public AnimationHandler(Animator animator, string horizontalID, string verticalID, string stateID,
            string jumpID, string crouchID, AnimationCurve stateCurve = null, float stateSpeed = 4.5f)
        {
            m_Animator = animator;

            m_HorizontalID = horizontalID;
            m_VerticalID = verticalID;
            m_StateID = stateID;
            m_JumpID = jumpID;
            m_CrouchID = crouchID;

            m_StateCurve = stateCurve ?? AnimationCurve.EaseInOut(0, 0, 1, 1);
            m_StateSpeed = stateSpeed;
        }

        public void SetCrouching(bool isCrouching) => m_Animator.SetBool(m_CrouchID, isCrouching);
        public void SetJump(bool isJump) => m_Animator.SetBool(m_JumpID, isJump);

        public void SetTargetState(float targetState, float deltaTime)
        {
            float targetTime = targetState > 0.5f ? 1f : 0f;
            m_StateTime = Mathf.MoveTowards(m_StateTime, targetTime, m_StateSpeed * deltaTime);

            float finalState = m_StateCurve.Evaluate(m_StateTime);
            m_Animator.SetFloat(m_StateID, finalState);
        }

        public void Animate(in Vector2 animAxis, float deltaTime)
        {
            m_FlowAxis.x = Mathf.Lerp(m_FlowAxis.x, animAxis.x, ANIMATION_DAMP_TIME * deltaTime);
            m_FlowAxis.y = Mathf.Lerp(m_FlowAxis.y, animAxis.y, ANIMATION_DAMP_TIME * deltaTime);

            m_FlowAxis.x = Mathf.Round(m_FlowAxis.x * 1000f) / 1000f;
            m_FlowAxis.y = Mathf.Round(m_FlowAxis.y * 1000f) / 1000f;

            if (Mathf.Abs(m_FlowAxis.x) < 0.001f)
                m_FlowAxis.x = 0f;
            if (Mathf.Abs(m_FlowAxis.y) < 0.001f)
                m_FlowAxis.y = 0f;

            float speedForAnimator = m_FlowAxis.magnitude;
            m_Animator.SetFloat("Speed", speedForAnimator);

            m_Animator.SetFloat(m_HorizontalID, m_FlowAxis.x);
            m_Animator.SetFloat(m_VerticalID, m_FlowAxis.y);
        }
    }
}