package com.example.ssafesta.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Keeps the Spring seed aligned with the Unity ScriptableObject catalog measured from source. */
class AvatarCatalogSeedContractTest {

    private static final Pattern FIELD = Pattern.compile("(?m)^  %s: (.+)$");
    private static final Pattern SQL_ASSET_KEY = Pattern.compile(
            "(?m)^\\s*\\('[^']+', '[^']+', 'AVATAR_PART', '[A-Z]+', '([0-9]+)',");

    @Test
    void seedAssetKeysEqualUnitySalesUnits() throws IOException {
        Path avatarDirectory = repoRoot().resolve(
                "festa-unity/Assets/_Project/ScriptableObjects/Avatar");
        Set<String> unityKeys = new HashSet<>();
        int assetCount = 0;
        try (var files = Files.list(avatarDirectory)) {
            for (Path asset : files.filter(path -> path.getFileName().toString().endsWith(".asset"))
                    .filter(path -> !path.getFileName().toString().equals("AvatarCatalog.asset")).toList()) {
                String yaml = Files.readString(asset, StandardCharsets.UTF_8);
                String category = field(yaml, "category");
                unityKeys.add(category.equals("2") ? field(yaml, "familyId") : field(yaml, "itemId"));
                assetCount++;
            }
        }

        String migration;
        try (var stream = getClass().getResourceAsStream("/db/migration/V16__avatar_catalog_seed.sql")) {
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        Set<String> seededKeys = new HashSet<>();
        Matcher matcher = SQL_ASSET_KEY.matcher(migration);
        while (matcher.find()) {
            seededKeys.add(matcher.group(1));
        }

        assertEquals(105, assetCount, "Unity 정의 자산 수가 바뀌면 판매 단위 산출을 다시 검토해야 합니다");
        assertEquals(97, unityKeys.size(), "모자 11개가 family 3개로 접힌 판매 단위 수");
        assertEquals(unityKeys, seededKeys,
                "V16 asset_key는 비모자 itemId + 모자 familyId의 정확한 집합이어야 합니다");
    }

    private static String field(String yaml, String name) {
        Matcher matcher = Pattern.compile(FIELD.pattern().formatted(Pattern.quote(name))).matcher(yaml);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Unity asset field missing: " + name);
        }
        return matcher.group(1).trim();
    }

    private static Path repoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isDirectory(cwd.resolve("festa-unity"))) {
            return cwd;
        }
        if (Files.isDirectory(cwd.resolve("../festa-unity"))) {
            return cwd.getParent();
        }
        throw new IllegalStateException("저장소 루트에서 festa-unity를 찾을 수 없습니다: " + cwd);
    }
}
