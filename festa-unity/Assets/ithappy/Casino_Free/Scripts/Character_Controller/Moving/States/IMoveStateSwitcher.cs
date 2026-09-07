namespace Controller
{
    public interface IMoveStateSwitcher
    {
        public void SwitchState<T>() where T : MovementState;
    }
}
