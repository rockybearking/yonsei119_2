package com.yonsei119;

import com.yonsei119.controller.EmergencyApiController;
import com.yonsei119.dto.DirectionsResponseDto;
import com.yonsei119.dto.EmergencySnapshotDto;
import com.yonsei119.dto.FrontendConfigDto;
import com.yonsei119.dto.GeocodeResponseDto;
import com.yonsei119.dto.HospitalDto;
import com.yonsei119.dto.HospitalTypeDto;
import com.yonsei119.dto.ReverseGeocodeResponseDto;
import com.yonsei119.service.EmergencyDataService;
import com.yonsei119.service.HospitalClassifier;
import com.yonsei119.service.NaverDirectionsService;
import com.yonsei119.service.NmcOpenApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ============================================================================
 * [클래스 역할]
 * 연세 119 응급실 정보 시스템 백엔드의 핵심 도메인 로직이 코드 수정 후에도 깨지지 않았는지
 * 자동으로 검증하는 **JUnit 5 + Spring Boot 통합 테스트** 클래스입니다.
 * <p>
 * [파이썬 장고(Django) 비유]
 * - 장고 앱의 **{@code tests.py} ({@code from django.test import TestCase})** 또는 **{@code pytest}** 테스트 파일과 완전히 같습니다!
 * - 장고에서 {@code python manage.py test}를 실행하듯, 터미널에서 {@code ./gradlew test}를 실행하면 이 파일 안의
 *   {@code @Test} 메서드 7개가 순서대로 실행되며 초록불({@code BUILD SUCCESSFUL}) 여부를 판별합니다.
 * <p>
 * [코드의 경로성 (어디서 실행되고 어떤 파일들을 검증하는가?)]
 * - [실행 주체 (Incoming)] : {@code backend/build.gradle}의 {@code tasks.named('test') { useJUnitPlatform() }}
 * - [검증 대상 파일들 (Outgoing Targets)] :
 *   1) {@link #testHospitalClassification()} → {@code service/HospitalClassifier.java} 검증
 *   2) {@link #testXmlParsingPreservesOverflowAndBlankValues()} → {@code service/NmcOpenApiClient.java} (음수 병상·빈 값 보존) 검증
 *   3) {@link #testServiceKeyEncoding()} → {@code service/NmcOpenApiClient.java} (인증키 이중 인코딩 방지) 검증
 *   4) {@link #testDatasetCrossMerge()} → {@code service/EmergencyDataService.java} ({@code HPID} 7종 병합 및 오류 좌표 제외) 검증
 *   5) {@link #testNaverDirectionsJsonParsing()} → {@code service/NaverDirectionsService.java} (Directions 5/15 파싱) 검증
 *   6) {@link #testXmlParsingDetectsFailureResponses()} → {@code service/NmcOpenApiClient.java} (장애 응답 감지) 검증
 *   7) {@link #testMultiOptionAndGeocodingParsing()} → {@code service/NaverDirectionsService.java} (다중 옵션 및 지오코딩) 검증
 * <p>
 * [참고: 왜 {@code @SpringBootTest(properties = "yonsei119.nemc.auto-refresh-enabled=false")}가 붙어 있나요?]
 * - 테스트를 돌릴 때마다 실제 공공데이터포털 서버에 7개 대용량 API 요청을 날리면 테스트가 느려지고 트래픽을 소모하므로,
 *   테스트 환경에서만 {@code application.yml}의 {@code auto-refresh-enabled} 값을 임시로 {@code false}로 덮어쓴 것입니다!
 *   (장고 테스트의 {@code @override_settings(NEMC_AUTO_REFRESH_ENABLED=False)} 데코레이터와 똑같은 기능입니다.)
 * ============================================================================
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "yonsei119.nemc.auto-refresh-enabled=false"
)
class Yonsei119BackendTests {

    @LocalServerPort
    private int port;

    /** 테스트 대상 빈(Bean)들을 Spring 컨테이너로부터 자동 주입받습니다 ({@code @Autowired}). */
    @Autowired
    private HospitalClassifier hospitalClassifier;

    @Autowired
    private NmcOpenApiClient nmcOpenApiClient;

    @Autowired
    private EmergencyDataService emergencyDataService;

    @Autowired
    private NaverDirectionsService naverDirectionsService;

    @Autowired
    private EmergencyApiController emergencyApiController;

    /**
     * [테스트 1] {@code service/HospitalClassifier.java} 검증
     * - 원주세브란스기독병원이 상급종합병원({@code tertiary})이자 동시에 권역응급의료센터({@code regional=true})로 분류되는지,
     *   원주의료원이 의료원({@code medical})으로, 일반 병원이 기타({@code other})로 정확히 나뉘는지 확인합니다.
     */
    @Test
    @DisplayName("1. 상급종합병원 / 권역응급의료센터 / 의료원 / 일반·기타 종별 분류가 정확히 동작해야 한다")
    void testHospitalClassification() {
        // 원주세브란스기독병원: 제5기 상급종합병원이자 권역응급의료센터
        Map<String, String> wonjuInfo = Map.of(
                "dutyname", "연세대학교원주세브란스기독병원",
                "dutyaddr", "강원특별자치도 원주시 일산로 20",
                "dutyemclsname", "권역응급의료센터"
        );
        HospitalTypeDto wonjuType = hospitalClassifier.classify(wonjuInfo, "연세대학교원주세브란스기독병원");
        assertEquals("tertiary", wonjuType.group());
        assertEquals("상급종합병원", wonjuType.label());
        assertTrue(hospitalClassifier.isRegional(wonjuInfo));

        // 의료원 분류 테스트
        Map<String, String> medicalInfo = Map.of(
                "dutyname", "강원특별자치도원주의료원",
                "dutyaddr", "강원특별자치도 원주시 서원대로 387",
                "dutyemclsname", "지역응급의료센터"
        );
        HospitalTypeDto medicalType = hospitalClassifier.classify(medicalInfo, "강원특별자치도원주의료원");
        assertEquals("medical", medicalType.group());
        assertEquals("의료원", medicalType.label());

        // 일반·기타 응급의료기관 분류 테스트
        Map<String, String> otherInfo = Map.of(
                "dutyname", "원주성지병원",
                "dutyaddr", "강원특별자치도 원주시 원일로 67",
                "dutyemclsname", "지역응급의료기관"
        );
        HospitalTypeDto otherType = hospitalClassifier.classify(otherInfo, "원주성지병원");
        assertEquals("other", otherType.group());
        assertEquals("일반·기타 응급의료기관", otherType.label());
    }

    /**
     * [테스트 2] {@code service/NmcOpenApiClient.java}의 {@code parseXmlResponse()} 도메인 규칙 검증
     * - 응급실 포화 상태를 뜻하는 음수 병상({@code -4})과 미보고 빈 태그(`{@code )가 }0`으로 왜곡되지 않는지 확인합니다.
     */
    @Test
    @DisplayName("2. XML 파싱 시 음수 병상 값(초과수용)과 미보고 빈 값을 0으로 왜곡하지 않고 그대로 보존해야 한다")
    void testXmlParsingPreservesOverflowAndBlankValues() {
        String sampleXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <response>
                  <header>
                    <resultCode>00</resultCode>
                    <resultMsg>NORMAL SERVICE.</resultMsg>
                  </header>
                  <body>
                    <items>
                      <item>
                        <hpid>A2200001</hpid>
                        <dutyName>연세대학교원주세브란스기독병원</dutyName>
                        <hvec>-4</hvec>
                        <hvs01>30</hvs01>
                        <hv28></hv28>
                        <hvctayn>Y</hvctayn>
                        <hvoxyayn>N1</hvoxyayn>
                      </item>
                    </items>
                    <totalCount>1</totalCount>
                  </body>
                </response>
                """;

        NmcOpenApiClient.ParsedXmlPage parsed = nmcOpenApiClient.parseXmlResponse(
                sampleXml, "getEmrrmRltmUsefulSckbdInfoInqire"
        );
        assertEquals(1, parsed.totalCount());
        assertEquals(1, parsed.items().size());

        Map<String, String> item = parsed.items().get(0);
        assertEquals("A2200001", item.get("hpid"));
        assertEquals("-4", item.get("hvec"), "음수 병상 값(-4, 초과수용)은 원본 문자열 그대로 보존되어야 합니다.");
        assertEquals("30", item.get("hvs01"));
        assertEquals("", item.get("hv28"), "빈 태그는 0으로 바뀌지 않고 빈 문자열로 보존되어야 합니다.");
        assertEquals("N1", item.get("hvoxyayn"));
    }

    /**
     * [테스트 3] 공공데이터포털 인증키(Encoded / Decoded) 정규화 검증
     */
    @Test
    @DisplayName("3. 공공데이터포털 인증키가 이미 인코딩된 경우 이중 인코딩하지 않아야 한다")
    void testServiceKeyEncoding() {
        String encoded = "dummy%2Ftest%2BserviceKey1234567890abcdef%3D%3D";
        String decoded = "dummy/test+serviceKey1234567890abcdef==";

        assertEquals(encoded, NmcOpenApiClient.ensureUrlEncodedServiceKey(encoded));
        assertEquals(encoded, NmcOpenApiClient.ensureUrlEncodedServiceKey(decoded));
    }

    /**
     * [테스트 4] {@code service/EmergencyDataService.java}의 {@code mergeDatasetsIntoSnapshot()} 7종 병합 검증
     */
    @Test
    @DisplayName("4. 7개 데이터셋이 HPID 기준으로 정확히 병합되고 유효하지 않은 좌표는 제외되어야 한다")
    void testDatasetCrossMerge() {
        Map<String, List<Map<String, String>>> rawDatasets = Map.of(
                "list", List.of(
                        Map.of("hpid", "A2200001", "dutyname", "연세대학교원주세브란스기독병원", "wgs84lat", "37.31912", "wgs84lon", "127.94561", "dutyaddr", "강원특별자치도 원주시 일산로 20"),
                        Map.of("hpid", "INVALID01", "dutyname", "좌표오류병원", "wgs84lat", "0.0", "wgs84lon", "0.0")
                ),
                "basic", List.of(
                        Map.of("hpid", "A2200001", "dutyemclsname", "권역응급의료센터", "dutytel1", "033-741-0114", "dutytel3", "033-741-1119")
                ),
                "beds", List.of(
                        Map.of("hpid", "A2200001", "hvec", "-2", "hvs01", "30", "hv13", "1", "hvs46", "4")
                ),
                "messages", List.of(
                        Map.of("hpid", "A2200001", "symblkmsg", "소아응급 진료 가능", "symblkmsgtyp", "응급")
                ),
                "severe", List.of(
                        Map.of("hpid", "A2200001", "mkioskty1", "Y", "mkioskty28", "Y")
                ),
                "traumaList", List.of(
                        Map.of("hpid", "A2200001", "dutyname", "연세대학교원주세브란스기독병원")
                ),
                "traumaBasic", List.of(
                        Map.of("hpid", "A2200001", "dutyemclsname", "권역외상센터")
                )
        );

        EmergencySnapshotDto snapshot = emergencyDataService.mergeDatasetsIntoSnapshot(rawDatasets);
        assertEquals(1, snapshot.totalCount(), "좌표가 (0,0)인 기관은 제외되고 1개 기관만 병합되어야 합니다.");
        assertEquals(1, snapshot.tertiaryCount());
        assertEquals(1, snapshot.traumaCount());

        HospitalDto hospital = snapshot.hospitals().get(0);
        assertEquals("A2200001", hospital.id());
        assertEquals("연세대학교원주세브란스기독병원", hospital.name());
        assertTrue(hospital.regional());
        assertNotNull(hospital.trauma());
        assertEquals("-2", hospital.beds().get("hvec"));
        assertEquals("1", hospital.beds().get("hv13"));
        assertEquals("Y", hospital.severe().get("mkioskty28"));
        assertEquals(1, hospital.messages().size());
    }

    /**
     * [테스트 5] {@code service/NaverDirectionsService.java}의 {@code parseDirectionsJson()} 경로/혼잡도 파싱 검증
     */
    @Test
    @DisplayName("5. 네이버 Directions 5 / 15 JSON 응답 파싱이 정확히 수행되어야 한다")
    void testNaverDirectionsJsonParsing() {
        String sampleDirectionsJson = """
                {
                  "code": 0,
                  "message": "길찾기를 성공하였습니다.",
                  "currentDateTime": "2026-11-03T17:00:00",
                  "route": {
                    "trafast": [
                      {
                        "summary": {
                          "distance": 8450,
                          "duration": 960000,
                          "departureTime": "2026-11-03T17:00:00",
                          "tollFare": 0,
                          "taxiFare": 11800,
                          "fuelPrice": 1120
                        },
                        "path": [
                          [127.901228, 37.319040],
                          [127.920100, 37.319500],
                          [127.945610, 37.319120]
                        ],
                        "section": [
                          {
                            "pointIndex": 0,
                            "pointCount": 2,
                            "distance": 5200,
                            "name": "서원대로",
                            "congestion": 2,
                            "speed": 32
                          },
                          {
                            "pointIndex": 1,
                            "pointCount": 2,
                            "distance": 3250,
                            "name": "일산로",
                            "congestion": 3,
                            "speed": 14
                          }
                        ],
                        "guide": [
                          {"instructions": "연세대길 방면 우회전"},
                          {"instructions": "일산로 방면 직진"},
                          {"instructions": "목적지 도착"}
                        ]
                      }
                    ]
                  }
                }
                """;

        DirectionsResponseDto dto = naverDirectionsService.parseDirectionsJson(
                sampleDirectionsJson,
                "directions15",
                "Directions 15",
                "trafast",
                "실시간 빠른길"
        );

        assertNotNull(dto);
        assertEquals(0, dto.code());
        assertEquals("directions15", dto.apiType());
        assertEquals(8450, dto.distanceMeters());
        assertEquals(8.5, dto.distanceKm());
        assertEquals(16, dto.durationMinutes());
        assertEquals("약 16분", dto.durationText());
        assertEquals(11800, dto.taxiFare());
        assertEquals(3, dto.path().size());
        assertEquals(2, dto.sections().size());
        assertEquals("서원대로", dto.sections().get(0).name());
        assertEquals(2, dto.sections().get(0).congestion());
        assertEquals("일산로", dto.sections().get(1).name());
        assertEquals(3, dto.sections().get(1).congestion());
        assertFalse(dto.guidePrompts().isEmpty());
    }

    /**
     * [테스트 6] 공공데이터포털 에러 XML({@code resultCode != 00}) 수신 시 폴백 보호 플래그 검증
     */
    @Test
    @DisplayName("6. XML 비정상 응답(에러 코드 또는 빈 문자열) 시 success=false가 반환되어야 한다")
    void testXmlParsingDetectsFailureResponses() {
        NmcOpenApiClient.ParsedXmlPage blankParsed = nmcOpenApiClient.parseXmlResponse(
                "", "getEmrrmSrsillDissMsgInqire"
        );
        assertFalse(blankParsed.success());

        String errorXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <response>
                  <header>
                    <resultCode>99</resultCode>
                    <resultMsg>DEADLINE_EXPIRED_ERROR</resultMsg>
                  </header>
                </response>
                """;
        NmcOpenApiClient.ParsedXmlPage errorParsed = nmcOpenApiClient.parseXmlResponse(
                errorXml, "getSrsillDissAceptncPosblInfoInqire"
        );
        assertFalse(errorParsed.success());
        assertTrue(errorParsed.items().isEmpty());
    }

    /**
     * [테스트 7] 다중 탐색 옵션({@code alternatives}) 및 역지오코딩·지오코딩 파서 검증
     */
    @Test
    @DisplayName("7. 네이버 Directions 다중 옵션(trafast:traoptimal:traavoidtoll) 파싱 및 Reverse/Geocode 파싱이 정확히 동작해야 한다")
    void testMultiOptionAndGeocodingParsing() {
        String multiOptionJson = """
                {
                  "code": 0,
                  "message": "길찾기를 성공하였습니다.",
                  "route": {
                    "trafast": [
                      {
                        "summary": { "distance": 8450, "duration": 840000, "tollFare": 1200, "taxiFare": 11800, "fuelPrice": 1120 },
                        "path": [[127.9012, 37.3190], [127.9456, 37.3191]]
                      }
                    ],
                    "traavoidtoll": [
                      {
                        "summary": { "distance": 9800, "duration": 1140000, "tollFare": 0, "taxiFare": 13200, "fuelPrice": 1290 },
                        "path": [[127.9012, 37.3190], [127.9456, 37.3191]]
                      }
                    ]
                  }
                }
                """;

        DirectionsResponseDto dto = naverDirectionsService.parseDirectionsJson(
                multiOptionJson, "directions5", "Directions 5", "trafast", "실시간 빠른길"
        );
        assertEquals(0, dto.code());
        assertEquals(2, dto.alternatives().size(), "다중 옵션 요약이 2건 추출되어야 합니다.");
        assertEquals("trafast", dto.alternatives().get(0).option());
        assertEquals(1200, dto.alternatives().get(0).tollFare());
        assertEquals("traavoidtoll", dto.alternatives().get(1).option());
        assertEquals(0, dto.alternatives().get(1).tollFare());

        String sampleRevGeo = """
                {
                  "status": { "code": 0, "name": "ok", "message": "done" },
                  "results": [
                    {
                      "name": "roadaddr",
                      "region": {
                        "area1": { "name": "강원특별자치도", "alias": "강원" },
                        "area2": { "name": "원주시" },
                        "area3": { "name": "흥업면" }
                      },
                      "land": {
                        "name": "연세대길",
                        "number1": "1",
                        "number2": "",
                        "addition0": { "value": "연세대학교 미래캠퍼스" }
                      }
                    }
                  ]
                }
                """;
        ReverseGeocodeResponseDto revDto = naverDirectionsService.parseReverseGeocodeJson(sampleRevGeo, 37.3190, 127.9012);
        assertEquals(0, revDto.code());
        assertEquals("강원 원주시 흥업면", revDto.shortAddress());
        assertTrue(revDto.roadAddress().contains("연세대길 1"));

        String sampleGeo = """
                {
                  "status": "OK",
                  "addresses": [
                    {
                      "roadAddress": "강원특별자치도 원주시 흥업면 연세대길 1",
                      "jibunAddress": "강원특별자치도 원주시 흥업면 매지리 1496",
                      "x": "127.9009998",
                      "y": "37.2808750"
                    }
                  ]
                }
                """;
        List<GeocodeResponseDto.GeocodeItemDto> geoItems = naverDirectionsService.parseGeocodeJson(sampleGeo);
        assertEquals(1, geoItems.size());
        assertEquals(37.2808750, geoItems.get(0).lat(), 0.0001);
        assertEquals(127.9009998, geoItems.get(0).lng(), 0.0001);
    }

    /**
     * [테스트 8] {@code GET /api/config} 공개 설정 반환 및 시크릿 비노출 검증
     */
    @Test
    @DisplayName("8. /api/config 엔드포인트는 공개 Client ID와 기본 출발 좌표를 반환하고 시크릿은 노출하지 않아야 한다")
    void configEndpointExposesPublicClientIdOnly() {
        var response = emergencyApiController.getConfig();
        FrontendConfigDto body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.naverClientId());
        assertNotNull(body.naverDarkStyleId());
        assertTrue(body.defaultLat() > 33.0 && body.defaultLat() < 39.0);
        assertTrue(body.defaultLng() > 124.0 && body.defaultLng() < 132.0);
        assertFalse(body.defaultLabel().isBlank());
    }

    /**
     * [테스트 9] 길찾기 세부 혼잡 구간({@code buildDetailedSections}) 중복 추가 방지 검증
     */
    @Test
    @DisplayName("9. guide 기반 세부 구간 생성 시 이미 분해된 영역 위에 원본 section이 중복 추가되지 않아야 한다")
    void testDetailedSectionsDoNotDuplicateCongestionSegments() {
        String detailedJson = """
                {
                  "code": 0,
                  "message": "ok",
                  "route": {
                    "trafast": [
                      {
                        "summary": { "distance": 2000, "duration": 240000, "tollFare": 0, "taxiFare": 4800, "fuelPrice": 300 },
                        "path": [
                          [127.90, 37.31],
                          [127.91, 37.31],
                          [127.92, 37.31],
                          [127.93, 37.31],
                          [127.94, 37.31]
                        ],
                        "section": [
                          { "pointIndex": 0, "pointCount": 4, "distance": 2000, "name": "서원대로", "congestion": 2, "speed": 22 }
                        ],
                        "guide": [
                          { "pointIndex": 2, "distance": 1200, "duration": 90000, "type": 1, "instructions": "서원대로 직진" },
                          { "pointIndex": 4, "distance": 800, "duration": 240000, "type": 88, "instructions": "목적지 도착" }
                        ]
                      }
                    ]
                  }
                }
                """;

        DirectionsResponseDto dto = naverDirectionsService.parseDirectionsJson(
                detailedJson, "directions5", "Directions 5", "trafast", "실시간 빠른길"
        );
        assertEquals(2, dto.sections().size(), "원본 section이 끝에 중복 추가되지 않고 세밀한 2개 구간만 유지되어야 합니다.");
        assertEquals(1, dto.sections().get(0).congestion(), "48km/h 원활 구간은 서행(2)으로 덮어씌워지지 않고 원활(1)을 유지해야 합니다.");
        assertEquals(3, dto.sections().get(1).congestion(), "12km/h 정체 구간은 정체(3)로 분류되어야 합니다.");
    }

    /**
     * [테스트 10] {@code mergedBasic} 필드 보존 및 전체 장애 폴백 시 종별 등급 유지 검증
     */
    @Test
    @DisplayName("10. list 전용 필드가 basic에 병합 보존되고, list·basic 동시 장애 폴백 시에도 권역/상급종합 분류가 유지되어야 한다")
    void testMergedBasicAndTotalOutageClassificationFallback() {
        try {
            Map<String, List<Map<String, String>>> initialData = Map.of(
                    "list", List.of(Map.of(
                            "hpid", "A2200001",
                            "dutyname", "연세대학교원주세브란스기독병원",
                            "dutyaddr", "강원특별자치도 원주시 일산로 20",
                            "wgs84lat", "37.31912",
                            "wgs84lon", "127.94561",
                            "phpid", "LEGACY_CODE_01"
                    )),
                    "basic", List.of(Map.of(
                            "hpid", "A2200001",
                            "dutyemclsname", "권역응급의료센터",
                            "dutytel3", "033-741-1119"
                    )),
                    "beds", List.of(Map.of("hpid", "A2200001", "hvec", "5"))
            );

            EmergencySnapshotDto initialSnapshot = emergencyDataService.mergeDatasetsIntoSnapshot(initialData);
            HospitalDto first = initialSnapshot.hospitals().get(0);
            assertEquals("LEGACY_CODE_01", first.basic().get("phpid"), "list 전용 필드가 mergedBasic에 누락 없이 보존되어야 합니다.");
            assertTrue(first.regional());
            assertEquals("tertiary", first.type().group());

            emergencyDataService.setCachedSnapshotForTest(initialSnapshot);

            // list·basic이 모두 빈 응답이고 beds만 수신된 부분/전체 장애 상황 시뮬레이션
            EmergencySnapshotDto fallbackSnapshot = emergencyDataService.mergeDatasetsIntoSnapshot(Map.of(
                    "beds", List.of(Map.of("hpid", "A2200001", "hvec", "2"))
            ));
            assertEquals(1, fallbackSnapshot.totalCount());
            HospitalDto recovered = fallbackSnapshot.hospitals().get(0);
            assertTrue(recovered.regional(), "장애 폴백 시 권역응급의료센터 여부가 초기화되지 않아야 합니다.");
            assertEquals("tertiary", recovered.type().group(), "장애 폴백 시 상급종합병원 등급이 유지되어야 합니다.");
            assertEquals("2", recovered.beds().get("hvec"));
        } finally {
            emergencyDataService.setCachedSnapshotForTest(null);
        }
    }

    /**
     * [테스트 11] {@code BoundedTtlCache} LRU 용량 상한 검증
     */
    @Test
    @DisplayName("11. 유한 크기 LRU 캐시는 최대 엔트리 상한을 초과하지 않고 가장 오래된 항목을 자동 퇴거해야 한다")
    void testBoundedCacheEvictsLeastRecentlyUsedEntries() {
        NaverDirectionsService.BoundedTtlCache<String, String> cache =
                new NaverDirectionsService.BoundedTtlCache<>(2, 60L);
        cache.put("k1", "v1");
        cache.put("k2", "v2");
        assertEquals("v1", cache.get("k1")); // k1을 최근 접근으로 갱신 → k2가 LRU 대상
        cache.put("k3", "v3");

        assertEquals(2, cache.size());
        assertEquals("v1", cache.get("k1"));
        assertEquals("v3", cache.get("k3"));
        assertNull(cache.get("k2"), "가장 오래 참조되지 않은 k2가 퇴거되어야 합니다.");
    }

    /**
     * [테스트 12] 실제 내장 Tomcat HTTP 서버의 루트({@code /}) 포워딩 및 정적 리소스(GZIP/ETag 포함) 응답 무결성 검증
     */
    @Test
    @DisplayName("12. 실제 내장 웹 서버에서 GET / 및 정적 리소스가 빈 본문(0바이트) 없이 정상 반환되어야 한다")
    void testRootAndStaticResourcesServeNonEmptyContent() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        for (String path : List.of("/", "/index.html", "/style.css", "/app.js", "/api/config")) {
            HttpRequest plainReq = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .GET()
                    .build();
            HttpResponse<String> plainRes = client.send(plainReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, plainRes.statusCode(), path + " 요청이 HTTP 200을 반환해야 합니다.");
            assertFalse(plainRes.body().isBlank(), path + " 응답 본문이 비어 있지 않아야 합니다.");

            HttpRequest gzipReq = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Accept-Encoding", "gzip, deflate, br")
                    .GET()
                    .build();
            HttpResponse<byte[]> gzipRes = client.send(gzipReq, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, gzipRes.statusCode(), path + " (gzip) 요청이 HTTP 200을 반환해야 합니다.");
            assertTrue(gzipRes.body().length > 0, path + " (gzip) 응답 바이트 길이가 0보다 커야 합니다.");

            String decodedBody;
            if ("gzip".equalsIgnoreCase(gzipRes.headers().firstValue("Content-Encoding").orElse(""))) {
                try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(gzipRes.body()))) {
                    decodedBody = new String(gis.readAllBytes(), StandardCharsets.UTF_8);
                }
            } else {
                decodedBody = new String(gzipRes.body(), StandardCharsets.UTF_8);
            }

            if ("/".equals(path) || "/index.html".equals(path)) {
                assertTrue(decodedBody.contains("<!DOCTYPE html>"), path + " 응답에 HTML 문서가 포함되어야 합니다.");
                assertTrue(decodedBody.contains("id=\"map\""), path + " 응답에 #map 요소가 포함되어야 합니다.");
            }
        }

        // 정적 리소스 Weak ETag 조건부 재검증(304 Not Modified) 동작 확인
        HttpResponse<String> appJsRes = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/app.js")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        String etag = appJsRes.headers().firstValue("ETag").orElse("");
        assertFalse(etag.isBlank(), "/app.js 응답에 ETag 헤더가 포함되어야 합니다.");

        HttpResponse<String> notModifiedRes = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/app.js"))
                        .header("If-None-Match", etag)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertEquals(304, notModifiedRes.statusCode(), "동일 ETag 재요청 시 304 Not Modified가 반환되어야 합니다.");
    }
}
