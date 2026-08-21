using System.Collections.Generic;

namespace Festa.Booth
{
    public enum BoothObjectType
    {
        Unknown = 0,
        AiAgent,
        VideoScreen,
        ProjectPanel,
        Survey,
        RecruitmentBoard,
        ConsultDesk,
        LikeVote,
        Furniture,
        Decoration,
    }

    public static class BoothObjectTypes
    {
        // JSON 계약의 type 문자열 ↔ enum 매핑 (Draft — 계약 확정 시 함께 확정)
        static readonly Dictionary<string, BoothObjectType> s_FromString = new()
        {
            { "AI_AGENT", BoothObjectType.AiAgent },
            { "VIDEO_SCREEN", BoothObjectType.VideoScreen },
            { "PROJECT_PANEL", BoothObjectType.ProjectPanel },
            { "SURVEY", BoothObjectType.Survey },
            { "RECRUITMENT_BOARD", BoothObjectType.RecruitmentBoard },
            { "CONSULT_DESK", BoothObjectType.ConsultDesk },
            { "LIKE_VOTE", BoothObjectType.LikeVote },
            { "FURNITURE", BoothObjectType.Furniture },
            { "DECORATION", BoothObjectType.Decoration },
        };

        public static BoothObjectType Parse(string raw) =>
            raw != null && s_FromString.TryGetValue(raw, out var type) ? type : BoothObjectType.Unknown;
    }
}
