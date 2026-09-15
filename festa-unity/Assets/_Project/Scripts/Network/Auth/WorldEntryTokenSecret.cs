using System;
using UnityEngine;

namespace Festa.Network
{
    /// <summary>
    /// 월드 입장 grant 를 검증할 HS256 키를 읽는다 (spec infra-003 `world-entry-token.md`).
    ///
    /// **기본키를 만들지 않는다.** 계약이 "local/demo 기본키, 자동 생성 fallback 또는 커밋된
    /// 샘플키를 만들지 않는다" 로 못박고 있다. 키가 없으면 배포 환경에서는 **기동을 실패시킨다** —
    /// 조용히 검증을 끄면 아무도 모르는 채로 무인증 서버가 뜬다 (T-24 와 같은 부류의 사고다).
    ///
    /// 읽는 순서:
    /// 1. `CONNECTION_TOKEN_SECRET_FILE` — 파일 경로. 배포는 read-only Secret 파일로 준다.
    /// 2. `CONNECTION_TOKEN_SECRET` — Base64 문자열. 로컬·CI 편의용.
    ///
    /// 키 값·길이 외의 어떤 것도 로그에 남기지 않는다 (계약: "토큰이나 Secret 원문을 수집하지 않는다").
    /// </summary>
    public static class WorldEntryTokenSecret
    {
        const string FileEnv = "CONNECTION_TOKEN_SECRET_FILE";
        const string ValueEnv = "CONNECTION_TOKEN_SECRET";

        /// <summary>Backend `WorldProperties.MIN_SECRET_BYTES` 와 같은 값이어야 한다.</summary>
        const int MinSecretBytes = 32;

        static byte[] s_key;
        static string s_failure;
        static bool s_loaded;

        /// <summary>키가 준비돼 있으면 true. 실패 사유는 <paramref name="failure"/> 에 담긴다.</summary>
        public static bool TryGet(out byte[] key, out string failure)
        {
            if (!s_loaded) Load();
            key = s_key;
            failure = s_failure;
            return s_key != null;
        }

        /// <summary>
        /// 배포 환경에서 키가 없으면 기동을 멈춘다.
        ///
        /// 에디터는 예외로 둔다 — Mock 경로로 혼자 플레이하는 흐름까지 막으면 개발이 불가능하다.
        /// 대신 검증이 꺼져 있다는 사실을 **매 승인마다 에러로 드러낸다**
        /// (<see cref="WorldEntryTokenVerifier"/> 참조). 조용한 폴백은 만들지 않는다.
        /// </summary>
        public static void EnforceOrQuit()
        {
            if (TryGet(out _, out var failure)) return;

            var message = $"[WorldEntryToken] 검증 키가 없어 서버를 기동할 수 없다 — {failure}. " +
                          $"{FileEnv} 또는 {ValueEnv} 를 주입하라 (기본키는 만들지 않는다).";

            if (Application.isEditor)
            {
                Debug.LogError(message + " 에디터라 기동은 계속하지만 **입장 검증이 꺼진 상태**다.");
                return;
            }

            Debug.LogError(message);
            Application.Quit(78);   // EX_CONFIG
        }

        static void Load()
        {
            s_loaded = true;

            var path = Environment.GetEnvironmentVariable(FileEnv);
            if (!string.IsNullOrEmpty(path))
            {
                try
                {
                    // 파일에는 Base64 문자열이 들어 있다. 편집기가 붙이는 개행은 제거한다.
                    var text = System.IO.File.ReadAllText(path).Trim();
                    if (Decode(text, out var fromFile, out var why)) { s_key = fromFile; return; }
                    s_failure = $"{FileEnv} 의 내용이 유효하지 않다 ({why})";
                    return;
                }
                catch (Exception e)
                {
                    // 예외 메시지에 경로는 남기되 내용은 남기지 않는다.
                    s_failure = $"{FileEnv} 를 읽지 못했다 ({e.GetType().Name})";
                    return;
                }
            }

            var raw = Environment.GetEnvironmentVariable(ValueEnv);
            if (string.IsNullOrEmpty(raw))
            {
                s_failure = $"{FileEnv}·{ValueEnv} 가 모두 비어 있다";
                return;
            }

            if (Decode(raw.Trim(), out var fromEnv, out var reason)) { s_key = fromEnv; return; }
            s_failure = $"{ValueEnv} 가 유효하지 않다 ({reason})";
        }

        static bool Decode(string base64, out byte[] key, out string reason)
        {
            key = null;
            try
            {
                key = Convert.FromBase64String(base64);
            }
            catch (FormatException)
            {
                reason = "Base64 디코딩 실패";
                return false;
            }

            if (key.Length < MinSecretBytes)
            {
                // 길이만 말한다. 값은 말하지 않는다.
                reason = $"디코딩 결과가 {key.Length}바이트로 최소 {MinSecretBytes}바이트 미만";
                key = null;
                return false;
            }

            reason = null;
            return true;
        }
    }
}
