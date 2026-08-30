using System.Collections.Generic;

namespace Festa.Booth
{
    public enum BoothObjectType
    {
        Unknown = 0,
        AiAgent,
        VideoScreen,
        ProjectPanel,
        SurveyKiosk,
        RecruitmentBoard,
        ConsultationDesk,
        Laptop,
        LikeVote,
        Furniture,
        Decoration,
    }

    public static class BoothObjectTypes
    {
        // JSON 계약의 canonical type 10종과 이전 POC 문자열의 읽기 호환 매핑.
        static readonly Dictionary<string, BoothObjectType> s_FromString = new()
        {
            { "AI_AGENT", BoothObjectType.AiAgent },
            { "VIDEO_SCREEN", BoothObjectType.VideoScreen },
            { "PROJECT_PANEL", BoothObjectType.ProjectPanel },
            { "SURVEY_KIOSK", BoothObjectType.SurveyKiosk },
            { "RECRUITMENT_BOARD", BoothObjectType.RecruitmentBoard },
            { "CONSULTATION_DESK", BoothObjectType.ConsultationDesk },
            { "LAPTOP", BoothObjectType.Laptop },
            { "LIKE_VOTE", BoothObjectType.LikeVote },
            { "FURNITURE", BoothObjectType.Furniture },
            { "DECORATION", BoothObjectType.Decoration },
            { "SURVEY", BoothObjectType.SurveyKiosk },
            { "CONSULT_DESK", BoothObjectType.ConsultationDesk },
        };

        public static BoothObjectType Parse(string raw) =>
            raw != null && s_FromString.TryGetValue(raw, out var type) ? type : BoothObjectType.Unknown;
    }
}
