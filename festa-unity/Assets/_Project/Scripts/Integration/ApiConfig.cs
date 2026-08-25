using System;
using System.Collections.Generic;
using UnityEngine;

namespace Festa.Integration
{
    public enum ApiEnvironment
    {
        Local,
        Dev,
        Prod,
    }

    /// <summary>
    /// 환경별 API 설정 (단순 ScriptableObject — Config Framework 금지 원칙).
    /// 에디터에서 Create > FESTA > Api Config 로 생성 후 GameBootstrap에 연결한다.
    /// Base URL을 코드에 하드코딩하지 않는다.
    /// </summary>
    [CreateAssetMenu(menuName = "FESTA/Api Config", fileName = "ApiConfig")]
    public class ApiConfig : ScriptableObject
    {
        [Serializable]
        public class Entry
        {
            public ApiEnvironment environment;
            public string springBaseUrl;
            public string aiBaseUrl;
        }

        [Tooltip("체크 시 모든 클라이언트가 Mock으로 동작 (네트워크 불필요)")]
        public bool useMockApi = true;

        public ApiEnvironment activeEnvironment = ApiEnvironment.Local;

        public List<Entry> entries = new()
        {
            new Entry { environment = ApiEnvironment.Local, springBaseUrl = "http://localhost:8000", aiBaseUrl = "http://localhost:8000" },
            new Entry { environment = ApiEnvironment.Dev,   springBaseUrl = "https://dev-api.example.com", aiBaseUrl = "https://dev-ai.example.com" },
            new Entry { environment = ApiEnvironment.Prod,  springBaseUrl = "https://api.example.com", aiBaseUrl = "https://ai.example.com" },
        };

        public Entry Active
        {
            get
            {
                foreach (var e in entries)
                    if (e.environment == activeEnvironment) return e;
                Debug.LogError($"[ApiConfig] No entry for {activeEnvironment} — falling back to first");
                return entries.Count > 0 ? entries[0] : null;
            }
        }
    }
}
