package com.yonsei119.service;

import com.yonsei119.dto.HospitalTypeDto;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ============================================================================
 * [클래스 역할]
 * 보건복지부 지정 제5기(2024~2026) 상급종합병원(47개소) 공식 고시 명단 및
 * 국립중앙의료원(NEMC) 응급의료기관 분류(`dutyDivNam`, `dutyEmclsName` 등)를 기반으로
 * 각 병원을 **상급종합병원(`tertiary`) / 의료원(`medical`) / 일반·기타(`other`)** 및
 * **권역응급의료센터(`regional`)** 여부로 자동 판별하는 분류기입니다.
 *
 * [담당 기능]
 * 1. 기존 프론트엔드(`app.js`)에 하드코딩되어 있던 `TERTIARY_2024_2026`, `REGION_PREFIX`,
 *    `normalizeName()`, `isRegional()`, `classify()` 로직을 백엔드 서비스로 완전히 이관했습니다.
 * 2. 주소의 광역 지자체 접두어(`REGION_PREFIX`)와 병원 약칭(`aliases`) 교차 대조를 통해
 *    동명이원 오분류를 차단하고 상급종합병원을 정확하게 판별합니다.
 *
 * [수정 시 영향 및 예시]
 * - 보건복지부 제6기(2027~2029) 상급종합병원 명단이 발표되면 {@link #TERTIARY_2024_2026}
 *   리스트에 신규 병원의 권역과 약칭을 추가하면 됩니다.
 *   예시(변경 전):
 *   {@code new TertiaryRule("강원", List.of("연세대학교원주세브란스기독병원", "원주세브란스기독병원"))}
 *   예시(변경 후):
 *   {@code new TertiaryRule("강원", List.of("연세대학교원주세브란스기독병원", "원주세브란스기독병원", "원주세브란스"))}
 * ============================================================================
 */
@Component
public class HospitalClassifier {

    /** 상급종합병원 분류 메타데이터 */
    public static final HospitalTypeDto TYPE_TERTIARY =
            new HospitalTypeDto("tertiary", "상급종합병원", "#d34458", 1);

    /** 의료원 분류 메타데이터 */
    public static final HospitalTypeDto TYPE_MEDICAL =
            new HospitalTypeDto("medical", "의료원", "#2879ce", 2);

    /** 일반·기타 응급의료기관 분류 메타데이터 */
    public static final HospitalTypeDto TYPE_OTHER =
            new HospitalTypeDto("other", "일반·기타 응급의료기관", "#738295", 3);

    /** 병원명 비교 시 제거할 공백·가운뎃점·괄호·하이픈 정규식 */
    private static final Pattern NORMALIZE_STRIP_PATTERN = Pattern.compile("[\\s·()（）-]");

    /** 권역별 주소 시작 정규식 사전 (`REGION_PREFIX`) */
    private static final Map<String, Pattern> REGION_PREFIX = Map.ofEntries(
            Map.entry("서울", Pattern.compile("^서울")),
            Map.entry("인천", Pattern.compile("^인천")),
            Map.entry("경기", Pattern.compile("^경기")),
            Map.entry("강원", Pattern.compile("^강원")),
            Map.entry("충북", Pattern.compile("^(충북|충청북도)")),
            Map.entry("충남", Pattern.compile("^(충남|충청남도)")),
            Map.entry("대전", Pattern.compile("^대전")),
            Map.entry("전북", Pattern.compile("^(전북|전라북도)")),
            Map.entry("광주", Pattern.compile("^광주")),
            Map.entry("전남", Pattern.compile("^(전남|전라남도)")),
            Map.entry("대구", Pattern.compile("^대구")),
            Map.entry("경북", Pattern.compile("^(경북|경상북도)")),
            Map.entry("부산", Pattern.compile("^부산")),
            Map.entry("경남", Pattern.compile("^(경남|경상남도)")),
            Map.entry("울산", Pattern.compile("^울산"))
    );

    /**
     * 보건복지부 지정 제5기(2024~2026년) 상급종합병원 47개소 권역·약칭 매핑 목록.
     */
    private static final List<TertiaryRule> TERTIARY_2024_2026 = List.of(
            new TertiaryRule("서울", List.of("강북삼성병원")),
            new TertiaryRule("서울", List.of("건국대학교병원")),
            new TertiaryRule("서울", List.of("경희대학교병원")),
            new TertiaryRule("서울", List.of("고려대학교의과대학부속구로병원", "고려대학교구로병원")),
            new TertiaryRule("서울", List.of("삼성서울병원")),
            new TertiaryRule("서울", List.of("서울대학교병원")),
            new TertiaryRule("서울", List.of("강남세브란스병원")),
            new TertiaryRule("서울", List.of("연세대학교의과대학세브란스병원", "신촌세브란스병원")),
            new TertiaryRule("서울", List.of("이화여자대학교의과대학부속목동병원", "이화여자대학교목동병원", "이대목동병원")),
            new TertiaryRule("서울", List.of("서울아산병원")),
            new TertiaryRule("서울", List.of("중앙대학교병원")),
            new TertiaryRule("서울", List.of("고려대학교의과대학부속병원", "고려대학교안암병원", "고대안암병원")),
            new TertiaryRule("서울", List.of("가톨릭대학교서울성모병원", "서울성모병원")),
            new TertiaryRule("서울", List.of("한양대학교병원")),
            new TertiaryRule("인천", List.of("가톨릭대학교인천성모병원", "인천성모병원")),
            new TertiaryRule("경기", List.of("순천향대학교부속부천병원", "순천향대학교부천병원")),
            new TertiaryRule("인천", List.of("길병원")),
            new TertiaryRule("인천", List.of("인하대학교의과대학부속병원", "인하대학교병원")),
            new TertiaryRule("경기", List.of("가톨릭대학교성빈센트병원", "성빈센트병원")),
            new TertiaryRule("경기", List.of("고려대학교의과대학부속안산병원", "고려대학교안산병원")),
            new TertiaryRule("경기", List.of("분당서울대학교병원")),
            new TertiaryRule("경기", List.of("아주대학교병원")),
            new TertiaryRule("경기", List.of("한림대학교성심병원")),
            new TertiaryRule("강원", List.of("강릉아산병원")),
            new TertiaryRule("강원", List.of("연세대학교원주세브란스기독병원", "원주세브란스기독병원")),
            new TertiaryRule("충북", List.of("충북대학교병원")),
            new TertiaryRule("충남", List.of("단국대학교의과대학부속병원", "단국대학교병원")),
            new TertiaryRule("대전", List.of("충남대학교병원")),
            new TertiaryRule("대전", List.of("건양대학교병원")),
            new TertiaryRule("전북", List.of("원광대학교병원")),
            new TertiaryRule("전북", List.of("전북대학교병원")),
            new TertiaryRule("광주", List.of("전남대학교병원")),
            new TertiaryRule("광주", List.of("조선대학교병원")),
            new TertiaryRule("전남", List.of("화순전남대학교병원")),
            new TertiaryRule("대구", List.of("경북대학교병원")),
            new TertiaryRule("대구", List.of("계명대학교동산병원")),
            new TertiaryRule("대구", List.of("대구가톨릭대학교병원")),
            new TertiaryRule("대구", List.of("영남대학교병원")),
            new TertiaryRule("대구", List.of("칠곡경북대학교병원")),
            new TertiaryRule("부산", List.of("고신대학교복음병원")),
            new TertiaryRule("부산", List.of("동아대학교병원")),
            new TertiaryRule("부산", List.of("부산대학교병원")),
            new TertiaryRule("경남", List.of("양산부산대학교병원")),
            new TertiaryRule("부산", List.of("인제대학교부산백병원", "부산백병원")),
            new TertiaryRule("울산", List.of("울산대학교병원")),
            new TertiaryRule("경남", List.of("경상국립대학교병원")),
            new TertiaryRule("경남", List.of("삼성창원병원"))
    );

    /**
     * [메서드 역할]
     * 병원명을 NFKC 유니코드 정규화하고 공백·괄호·가운뎃점·하이픈을 제거한 소문자로 변환합니다.
     *
     * [수정 시 영향 및 예시]
     * - 입력: `"연세대학교 원주세브란스기독병원(권역)"`
     * - 출력: `"연세대학교원주세브란스기독병원권역"`
     *
     * @param value 원본 병원명
     * @return 정규화된 문자열
     */
    public static String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String nfkc = Normalizer.normalize(value, Normalizer.Form.NFKC);
        return NORMALIZE_STRIP_PATTERN.matcher(nfkc).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /**
     * [메서드 역할]
     * 해당 기관이 권역응급의료센터(`dutyEmclsName`에 `"권역응급의료센터"` 포함)인지 판별합니다.
     *
     * @param fields 병합된 기관 기본정보 맵
     * @return 권역응급의료센터 해당 여부
     */
    public boolean isRegional(Map<String, String> fields) {
        if (fields == null) {
            return false;
        }
        String emclsName = fields.getOrDefault("dutyemclsname", "");
        return emclsName.contains("권역응급의료센터");
    }

    /**
     * [메서드 역할]
     * 기관 기본정보(`fields`)와 병원명(`rawName`)을 분석하여
     * **상급종합병원(`tertiary`)**, **의료원(`medical`)**, **일반·기타 응급의료기관(`other`)** 중 하나로 분류합니다.
     *
     * @param fields  병합된 기관 기본정보 맵
     * @param rawName 병원명
     * @return 분류된 {@link HospitalTypeDto}
     */
    public HospitalTypeDto classify(Map<String, String> fields, String rawName) {
        Map<String, String> safeFields = fields != null ? fields : Map.of();
        String name = normalizeName(rawName != null ? rawName : safeFields.getOrDefault("dutyname", ""));
        String address = safeFields.getOrDefault("dutyaddr", "").trim();
        String divNam = safeFields.getOrDefault("dutydivnam", "");

        boolean explicitlyDesignated = divNam.contains("상급종합");

        boolean inOfficialList = TERTIARY_2024_2026.stream().anyMatch(rule -> {
            Pattern regionPattern = REGION_PREFIX.get(rule.region());
            boolean regionMatches = address.isEmpty()
                    || (regionPattern != null && regionPattern.matcher(address).find());
            return regionMatches && rule.aliases().stream()
                    .anyMatch(alias -> name.contains(normalizeName(alias)));
        });

        if (explicitlyDesignated || inOfficialList) {
            return TYPE_TERTIARY;
        }

        if (name.contains("의료원")) {
            return TYPE_MEDICAL;
        }

        return TYPE_OTHER;
    }

    private record TertiaryRule(
            String region,
            List<String> aliases
    ) {
    }
}
