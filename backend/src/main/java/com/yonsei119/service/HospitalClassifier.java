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
 * 국립중앙의료원(NEMC) 응급의료기관 분류({@code dutyDivNam}, {@code dutyEmclsName})를 기반으로
 * 각 병원을 **상급종합병원({@code tertiary}) / 의료원({@code medical}) / 일반·기타({@code other})** 및
 * **권역응급의료센터({@code regional})** 여부로 자동 판별하는 도메인 분류기입니다.
 * <p>
 * [파이썬 장고(Django) 비유]
 * - 장고 앱 내부의 {@code classifiers.py} 또는 {@code services/hospital_classifier.py} 유틸리티 모듈과 같습니다!
 * - {@code @Component} 어노테이션이 붙어 있어서 서버가 켜질 때 Spring이 딱 1개 인스턴스만 만들어 두고,
 *   {@code EmergencyDataService.java} 생성자에 자동으로 꽂아줍니다(의존성 주입).
 * <p>
 * [코드의 경로성 (어디서 오고 어디로 가는가?)]
 * - [누가 호출하는가 (Incoming)] :
 *   1) {@code service/EmergencyDataService.java}의 {@code mergeDatasetsIntoSnapshot()} 루프 안에서 전국 530여 개 병원을 하나씩 순회하며
 *      {@link #isRegional(Map)}과 {@link #classify(Map, String)}을 호출합니다.
 *   2) {@code src/test/java/com/yonsei119/Yonsei119BackendTests.java}의 {@code testHospitalClassification()}에서 정확도를 검증합니다.
 * - [어디로 전달되는가 (Outgoing)] :
 *   분류 결과인 {@link HospitalTypeDto}({@code dto/HospitalTypeDto.java})와 {@code boolean regional} 값이
 *   {@code dto/HospitalDto.java}에 담겨 {@code /api/hospitals} JSON으로 나가고,
 *   프론트엔드 {@code frontend/app.js}의 체크박스 필터({@code #filter-regional}, {@code #filter-tertiary}, {@code #filter-medical}, {@code #filter-other})와
 *   지도 위 마커 배지 색상을 결정합니다!
 * <p>
 * [수정 시 영향 및 예시]
 * - 보건복지부 제6기(2027~2029) 상급종합병원 명단이 새로 발표되어 신규 병원을 추가하고 싶다면
 *   아래 {@link #TERTIARY_2024_2026} 리스트에 {@code new TertiaryRule("지역명", List.of("병원명", "약칭"))} 한 줄만 추가하면 됩니다!
 *   예시(변경 전):
 *   {@code new TertiaryRule("강원", List.of("연세대학교원주세브란스기독병원", "원주세브란스기독병원"))}
 *   예시(변경 후 — 약칭 추가):
 *   {@code new TertiaryRule("강원", List.of("연세대학교원주세브란스기독병원", "원주세브란스기독병원", "원주세브란스"))}
 * ============================================================================
 */
@Component
public class HospitalClassifier {

    /**
     * [상수 역할] 상급종합병원 분류 결과 DTO 상수 ({@code group="tertiary"}, 우선순위 {@code 1})
     * - [수정 시 영향] {@code accent} 색상 코드나 {@code label} 문자열을 바꾸면 API 응답({@code hospital.type})의 라벨/색상 메타데이터가 바뀝니다.
     *   (주의: {@code Yonsei119BackendTests.java}에서 {@code label}이 {@code "상급종합병원"}인지 검증하므로 라벨 변경 시 테스트 코드도 함께 맞춰주세요.)
     */
    public static final HospitalTypeDto TYPE_TERTIARY =
            new HospitalTypeDto("tertiary", "상급종합병원", "#d34458", 1);

    /**
     * [상수 역할] 지방의료원·국립중앙의료원 등 의료원 분류 결과 DTO 상수 ({@code group="medical"}, 우선순위 {@code 2})
     */
    public static final HospitalTypeDto TYPE_MEDICAL =
            new HospitalTypeDto("medical", "의료원", "#2879ce", 2);

    /**
     * [상수 역할] 일반·기타 응급의료기관 분류 결과 DTO 상수 ({@code group="other"}, 우선순위 {@code 3})
     * - 프론트엔드({@code frontend/index.html})에서 {@code #filter-other} 체크박스는 초기 로딩 시 기본적으로 해제({@code unchecked})되어 있습니다.
     */
    public static final HospitalTypeDto TYPE_OTHER =
            new HospitalTypeDto("other", "일반·기타 응급의료기관", "#738295", 3);

    /** 병원명 비교 시 공백·가운뎃점(·)·괄호·하이픈(-) 차이 때문에 매칭이 실패하지 않도록 제거하는 정규표현식 */
    private static final Pattern NORMALIZE_STRIP_PATTERN = Pattern.compile("[\\s·()（）-]");

    /**
     * [상수 역할] 광역 시·도 주소 시작 정규표현식 사전 ({@code REGION_PREFIX})
     * - 왜 필요한가요? 다른 지역에 이름이 비슷한 중소병원이 있을 때 상급종합병원으로 오분류되는 사고를 막기 위해,
     *   주소가 해당 시·도로 시작하는지 함께 대조합니다. (강원특별자치도/전북특별자치도 모두 {@code ^강원}, {@code ^전북}으로 매칭됩니다!)
     * - [수정 시 영향 및 예시] 제주특별자치도에 상급종합병원이 신설된다면 아래 엔트리를 한 줄 추가합니다:
     *   {@code Map.entry("제주", Pattern.compile("^제주"))}
     */
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
     * 보건복지부 지정 제5기(2024~2026년) 상급종합병원 47개소 공식 권역·약칭 매핑 목록입니다.
     * 공공데이터 XML에서 {@code dutyDivNam} 태그가 간혹 {@code "종합병원"}으로 내려오는 상급종합병원까지 누락 없이 잡아냅니다.
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
     * 병원명 비교 전에 전각 문자(（, ）)를 반각으로 통일(NFKC 정규화)하고,
     * 공백·괄호·가운뎃점·하이픈을 모두 지운 뒤 소문자로 변환합니다.
     * <p>
     * [코드의 경로성]
     * - [호출하는 곳 (Incoming)] : 아래 {@link #classify(Map, String)} 내부에서 원본 병원명과 별칭(alias) 양쪽에 호출
     * <p>
     * [동작 및 수정 예시]
     * - 입력: {@code "연세대학교 원주세브란스기독병원(권역)"} → 출력: {@code "연세대학교원주세브란스기독병원권역"}
     * - 만약 대괄호({@code [}, {@code ]})도 제거 대상에 포함하고 싶다면 위 {@link #NORMALIZE_STRIP_PATTERN}을 수정합니다:
     *   변경 전: {@code Pattern.compile("[\\s·()（）-]")}
     *   변경 후: {@code Pattern.compile("[\\s·()（）\\[\\]-]")}
     *
     * @param value 원본 병원명 문자열
     * @return 비교용 정규화 문자열
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
     * 해당 병원이 보건복지부 지정 **권역응급의료센터**인지 판별합니다.
     * (참고: 원주세브란스기독병원처럼 '상급종합병원'이면서 동시에 '권역응급의료센터'인 병원이 많으므로,
     * {@code type}과 별도로 {@code boolean regional} 플래그를 독립 분리해 두 종류 필터 모두에 걸리게 설계했습니다!)
     * <p>
     * [코드의 경로성]
     * - [호출하는 곳 (Incoming)] : {@code EmergencyDataService.mergeDatasetsIntoSnapshot()} → {@code HospitalDto.regional} 필드에 저장
     * - [프론트엔드 반영 (Outgoing)] : {@code frontend/app.js}에서 {@code hospital.regional === true}이면 빨간 {@code '권역응급'} 배지를 최우선 표시!
     * <p>
     * [수정 시 영향 및 예시]
     * - 만약 {@code '전문응급의료센터'}(소아/화상 등)도 권역응급 그룹에 함께 묶고 싶다면:
     *   변경 전: {@code return emclsName.contains("권역응급의료센터");}
     *   변경 후: {@code return emclsName.contains("권역응급의료센터") || emclsName.contains("전문응급의료센터");}
     *
     * @param fields NEMC 기본정보 XML을 소문자 키로 병합한 맵
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
     * 기관 기본정보({@code fields})와 병원명({@code rawName})을 분석하여
     * **상급종합병원({@code TYPE_TERTIARY})**, **의료원({@code TYPE_MEDICAL})**, **일반·기타({@code TYPE_OTHER})** 중 하나로 분류합니다.
     * <p>
     * [판별 순서 (위에서 아래로 우선 적용)]
     * 1순위: 공공데이터 {@code dutydivnam}에 {@code "상급종합"}이 적혀 있거나, 제5기 47개 공식 고시 명단({@code TERTIARY_2024_2026})과 권역+이름이 일치하면 → {@code TYPE_TERTIARY}
     * 2순위: 병원명에 {@code "의료원"}이 포함되어 있으면 (예: 서울의료원, 원주의료원, 국립중앙의료원) → {@code TYPE_MEDICAL}
     * 3순위: 그 외 모든 지역응급의료센터/기관 → {@code TYPE_OTHER}
     * <p>
     * [수정 시 영향 및 예시]
     * - 만약 {@code "적십자병원"}도 {@code "의료원"}(공공의료기관) 그룹에 함께 넣고 싶다면 2순위 조건문을 아래처럼 고치면 됩니다:
     *   예시(변경 전): {@code if (name.contains("의료원")) { return TYPE_MEDICAL; }}
     *   예시(변경 후): {@code if (name.contains("의료원") || name.contains("적십자병원")) { return TYPE_MEDICAL; }}
     *
     * @param fields  병합된 기관 기본정보 맵
     * @param rawName 병원명
     * @return 분류된 {@link HospitalTypeDto} ({@code dto/HospitalTypeDto.java})
     */
    public HospitalTypeDto classify(Map<String, String> fields, String rawName) {
        Map<String, String> safeFields = fields != null ? fields : Map.of();
        String name = normalizeName(rawName != null ? rawName : safeFields.getOrDefault("dutyname", ""));
        String address = safeFields.getOrDefault("dutyaddr", "").trim();
        String divNam = safeFields.getOrDefault("dutydivnam", "");

        // 1-A. 공공데이터 종별코드명에 '상급종합'이 직접 명시된 경우
        boolean explicitlyDesignated = divNam.contains("상급종합");

        // 1-B. 보건복지부 제5기 47개 상급종합병원 고시 명단(지역 접두어 + 병원 약칭)에 매칭되는 경우
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

        // 2. 공공 의료원 판별
        if (name.contains("의료원")) {
            return TYPE_MEDICAL;
        }

        // 3. 나머지 일반·기타 응급의료기관
        return TYPE_OTHER;
    }

    /** 상급종합병원 고시 매칭 규칙을 담는 파일 내부 전용 레코드 (파이썬의 {@code NamedTuple}과 동일) */
    private record TertiaryRule(
            String region,
            List<String> aliases
    ) {
    }
}
