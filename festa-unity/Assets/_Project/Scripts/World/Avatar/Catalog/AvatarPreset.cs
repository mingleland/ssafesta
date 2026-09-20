using UnityEngine;
namespace Festa.Avatar
{
    [CreateAssetMenu(menuName = "Festa/Avatar/Preset")]
    public sealed class AvatarPreset : ScriptableObject { public string presetId; public AvatarConfig config; }
}
