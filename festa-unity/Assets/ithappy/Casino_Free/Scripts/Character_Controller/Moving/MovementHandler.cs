using UnityEngine;

namespace Controller
{
    public class MovementHandler
    {
        private readonly CharacterController m_Controller;
        private readonly Transform m_Transform;

        private float m_RotateSpeed;
        private float m_JumpHeight;

        private Space m_Space;

        private readonly float m_Luft = 35f;
        private readonly float m_JumpReload = 1f;

        private float m_TargetAngle;
        private bool m_IsRotating = false;

        private Vector3 m_Normal;
        private Vector3 m_GravityAcelleration = Physics.gravity;

        private float m_jumpTimer;
        private bool m_isJump;

        private float m_moveSpeed;
        private bool m_isMoving;

        public MovementHandler(CharacterController controller, Transform transform,
            float rotateSpeed, float rotationLuft, float jumpHeight, Space space)
        {
            m_Controller = controller;
            m_Transform = transform;

            m_RotateSpeed = rotateSpeed;
            m_Luft = rotationLuft;

            m_JumpHeight = jumpHeight;

            m_Space = space;
        }

        public void SetSurface(in Vector3 normal)
        {
            m_Normal = normal;
        }

        public void SetMoveSpeed(float moveSpeed)
        {
            m_moveSpeed = moveSpeed;
        }

        public void SetMoving(bool isMoving)
        {
            m_isMoving = isMoving;
        }

        public void SetJump(bool isJump)
        {
            m_isJump = isJump;
        }

        public void Move(float deltaTime, in Vector2 axis, in Vector3 target, out Vector2 animAxis)
        {
            Vector3 targetForward = Vector3.Normalize(target - m_Transform.position);

            ConvertMovement(in axis, in targetForward, out var movement);
            CaculateGravity(deltaTime);

            Displace(deltaTime, in movement);
            Turn(in targetForward);
            UpdateRotation(deltaTime);

            GenAnimationAxis(in movement, out animAxis);
        }

        private void ConvertMovement(in Vector2 axis, in Vector3 targetForward, out Vector3 movement)
        {
            Vector3 forward;
            Vector3 right;

            if (m_Space == Space.Self)
            {
                forward = new Vector3(targetForward.x, 0f, targetForward.z).normalized;
                right = Vector3.Cross(Vector3.up, forward).normalized;
            }
            else
            {
                forward = Vector3.forward;
                right = Vector3.right;
            }

            movement = axis.x * right + axis.y * forward;
            movement = Vector3.ProjectOnPlane(movement, m_Normal);
        }

        private void Displace(float deltaTime, in Vector3 movement)
        {
            Vector3 displacement = m_moveSpeed * movement;
            displacement += m_GravityAcelleration;
            displacement *= deltaTime;

            m_Controller.Move(displacement);
        }

        private void CaculateGravity(float deltaTime)
        {
            m_jumpTimer = Mathf.Max(m_jumpTimer - deltaTime, 0f);

            if (m_Controller.isGrounded)
            {
                if (!m_isJump)
                {
                    m_GravityAcelleration = Physics.gravity * 0.1f;
                }

                if (m_isJump && m_jumpTimer <= 0)
                {
                    var gravity = Physics.gravity;
                    var length = gravity.magnitude;

                    m_GravityAcelleration = Vector3.up * Mathf.Sqrt(m_JumpHeight * 2f * length);

                    m_jumpTimer = m_JumpReload;
                    m_isJump = false; 
                    return;
                }
                return;
            }

            m_GravityAcelleration += Physics.gravity * deltaTime;
        }

        private void GenAnimationAxis(in Vector3 movement, out Vector2 animAxis)
        {
            if (m_Space == Space.Self)
            {
                animAxis = new Vector2(Vector3.Dot(movement, m_Transform.right),
                    Vector3.Dot(movement, m_Transform.forward));
            }
            else
            {
                animAxis = new Vector2(Vector3.Dot(movement, Vector3.right),
                    Vector3.Dot(movement, Vector3.forward));
            }
        }

        private void Turn(in Vector3 targetForward)
        {
            float angle = Vector3.SignedAngle(m_Transform.forward, Vector3.ProjectOnPlane(targetForward, Vector3.up),
                Vector3.up);

            if (m_IsRotating == false)
            {
                if ((m_isMoving == false) && Mathf.Abs(angle) < m_Luft)
                {
                    m_IsRotating = false;
                    return;
                }

                m_IsRotating = true;
            }

            m_TargetAngle = angle;
        }

        private void UpdateRotation(float deltaTime)
        {
            if (m_IsRotating == false)
                return;

            float rotDelta = m_RotateSpeed * deltaTime;
            if (rotDelta + Mathf.PI * 2f + Mathf.Epsilon >= Mathf.Abs(m_TargetAngle))
            {
                rotDelta = m_TargetAngle;
                m_IsRotating = false;
            }
            else
            {
                rotDelta *= Mathf.Sign(m_TargetAngle);
            }

            m_Transform.Rotate(Vector3.up, rotDelta);
        }
    }
}