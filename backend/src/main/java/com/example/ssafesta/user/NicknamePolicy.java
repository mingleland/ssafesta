package com.example.ssafesta.user;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class NicknamePolicy {

    private static final int MIN_LENGTH = 2;
    private static final int MAX_LENGTH = 30;
    private static final Map<Character, Character> LEET = Map.of(
            '0', 'o', '1', 'i', '3', 'e', '4', 'a', '5', 's', '7', 't', '8', '팔');
    private static final List<String> BLOCKED_TERMS = List.of(
            "씨발", "시발", "씨i발", "시i발", "씹발", "씨팔", "시팔", "씨벌", "시벌", "씹", "씹새끼", "개새끼", "개새",
            "개년", "개놈", "개같", "개좆", "좆", "좃", "존나", "졸라", "병신", "븅신", "빙신",
            "등신", "미친놈", "미친년", "또라이", "지랄", "염병", "느금마", "꺼져", "닥쳐", "뒤져", "죽어",
            "ㅅㅂ", "ㅆㅂ", "ㅂㅅ", "ㅄ", "ㅈㄹ", "ㅈㄴ", "ㄱㅅㄲ", "ㄲㅈ", "ㄷㅊ", "ㄴㄱㅁ", "ㄴㅇㅁ",
            "섹스", "야동", "야설", "야짤", "포르노", "자위", "보지", "자지", "꼬추", "고추", "성기", "강간", "성폭행", "몰카", "누드",
            "한남", "한녀", "김치녀", "김치남", "맘충", "급식충", "틀딱", "잼민", "메갈", "창녀", "창남", "호모", "정박아",
            "마약", "필로폰", "대마", "코카인", "헤로인", "펜타닐", "엑스터시",
            "fuck", "fucking", "fucker", "fck", "fuk", "fucc", "fuxk", "shit", "bullshit", "bitch", "bastard", "asshole", "cunt",
            "dick", "cock", "pussy", "whore", "slut", "retard", "stfu", "gtfo", "sex", "sexy", "porn", "xxx", "hentai", "nude", "nudes",
            "naked", "penis", "vagina", "cum", "semen", "anal", "blowjob", "handjob", "rape", "rapist", "molest");
    private static final List<String> RESERVED_TERMS = List.of(
            "관리자", "운영자", "운영진", "어드민", "관리팀", "운영팀", "개발자", "개발팀", "공식계정",
            "admin", "administrator", "manager", "moderator", "mod", "staff", "operator", "official", "system", "root", "superuser",
            "support", "developer", "devteam", "management", "ssafyadmin", "ssafystaff", "ssafestaadmin", "ssafestastaff");
    private static final Pattern CONTACT_OR_URL = Pattern.compile(
            "(01[016789]\\d{7,8}|https?|www|tme|openkakao|[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,})");
    private static final Pattern PHONE_NUMBER = Pattern.compile("01[016789]\\d{7,8}");

    public void validate(String nickname) {
        if (nickname == null || nickname.isBlank() || nickname.codePointCount(0, nickname.length()) < MIN_LENGTH
                || nickname.codePointCount(0, nickname.length()) > MAX_LENGTH) {
            throw new InvalidNicknameException();
        }
        String normalized = normalize(nickname);
        String digitsOnly = nickname.replaceAll("\\D", "");
        if (normalized.isBlank() || PHONE_NUMBER.matcher(digitsOnly).matches() || CONTACT_OR_URL.matcher(normalized).find()
                || BLOCKED_TERMS.stream().anyMatch(normalized::contains)
                || RESERVED_TERMS.stream().anyMatch(normalized::contains)
                || isReservedBrandOnly(normalized)) {
            throw new InvalidNicknameException();
        }
    }

    String normalize(String nickname) {
        String normalized = Normalizer.normalize(nickname, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        for (char character : normalized.toCharArray()) {
            if (Character.isLetterOrDigit(character) || Character.UnicodeScript.of(character) == Character.UnicodeScript.HANGUL) {
                result.append(LEET.getOrDefault(character, character));
            }
        }
        return result.toString();
    }

    private boolean isReservedBrandOnly(String normalized) {
        return normalized.equals("ssafy") || normalized.equals("ssafesta") || normalized.equals("싸피");
    }
}
