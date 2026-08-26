using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// Stores the local player's selected appearance while moving between the
    /// character lobby and the networked world. This is a local handoff cache;
    /// the authoritative profile remains the backend when that API is enabled.
    /// </summary>
    public static class AvatarSceneHandoff
    {
        public const string LobbySceneName = "CharacterLobby";
        public const string WorldSceneName = "main";

        const string AppearanceKey = "festa.avatar.scene-handoff";
        const string PendingConnectionKey = "festa.avatar.pending-world-connection";

        // Scene transitions happen within the same running client. Keep an in-memory
        // copy as the primary handoff so WebGL storage timing cannot replace the
        // newly selected modular appearance with an older fallback value.
        static string s_runtimeAppearance;

        public static void Save(AvatarAppearance appearance)
        {
            var encoded = appearance.Encode();
            if (string.IsNullOrEmpty(encoded) || encoded.Length > AvatarAppearance.MaxEncodedLength) return;

            s_runtimeAppearance = encoded;
            PlayerPrefs.SetString(AppearanceKey, encoded);
            PlayerPrefs.Save();
        }

        public static string GetEncodedOrFallback(string fallback)
        {
            if (!string.IsNullOrEmpty(s_runtimeAppearance) &&
                s_runtimeAppearance.Length <= AvatarAppearance.MaxEncodedLength)
            {
                return s_runtimeAppearance;
            }

            var encoded = PlayerPrefs.GetString(AppearanceKey, string.Empty);
            return !string.IsNullOrEmpty(encoded) && encoded.Length <= AvatarAppearance.MaxEncodedLength
                ? encoded
                : fallback;
        }

        public static void RequestWorldConnection()
        {
            PlayerPrefs.SetInt(PendingConnectionKey, 1);
            PlayerPrefs.Save();
        }

        public static bool ConsumeWorldConnectionRequest()
        {
            if (PlayerPrefs.GetInt(PendingConnectionKey, 0) == 0) return false;
            PlayerPrefs.DeleteKey(PendingConnectionKey);
            PlayerPrefs.Save();
            return true;
        }
    }
}
