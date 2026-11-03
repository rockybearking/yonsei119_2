package com.yonsei119;

import com.yonsei119.dto.DirectionsResponseDto;
import com.yonsei119.dto.EmergencySnapshotDto;
import com.yonsei119.dto.HospitalDto;
import com.yonsei119.dto.HospitalTypeDto;
import com.yonsei119.service.EmergencyDataService;
import com.yonsei119.service.HospitalClassifier;
import com.yonsei119.service.NaverDirectionsService;
import com.yonsei119.service.NmcOpenApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ============================================================================
 * [클래스 역할]
 * 연세 119 응급실 정보 시스템 백엔드의 핵심 도메인 로직을 검증하는 통합 테스트 클래스입니다.
 *
 * [검증 항목]
 * 1. 제5기 상급종합병원 / 권역응급의료센터 / 의료원 / 일반·기타 자동 분류 정확도 검증
 * 2. 현장 실사 도메인 규칙(음수 병상 = 초과수용 `-4` 보존, 빈 값 `""` 보존) XML 파싱 검증
 * 3. 공공데이터포털 인증키 이중 인코딩 방지 로직 검증
 * 4. 7대 OpenAPI 데이터셋의 `HPID` 기준 교차 병합 및 대한민국 좌표 유효성 필터링 검증
 * 5. 네이버 Directions 5 및 Directions 15 JSON 응답 파싱(소요시간, 거리, 요금, 폴리라인 경로) 검증
 * ============================================================================
 */
@SpringBootTest
class Yonsei119BackendTests {

    @Autowired
    private HospitalClassifier hospitalClassifier;

    @Autowired
    private NmcOpenApiClient nmcOpenApiClient;

    @Autowired
    private EmergencyDataService emergencyDataService;

    @Autowired
    private NaverDirectionsService naverDirectionsService;

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

    @Test
    @DisplayName("3. 공공데이터포털 인증키가 이미 인코딩된 경우 이중 인코딩하지 않아야 한다")
    void testServiceKeyEncoding() {
        String encoded = "s%2FvWNiKH1YndVRu2mPOq7OXAIj%2Byk0M3JTgvN%2B8UVdSFPq8SBR7zuUdG3mxklTrj6WYIiUidSIUwgE8bz09fzQ%3D%3D";
        String decoded = "s/vWNiKH1YndVRu2mPOq7OXAIj+yk0M3JTgvN+8UVdSFPq8SBR7zuUdG3mxklTrj6WYIiUidSIUwgE8bz09fzQ==";

        assertEquals(encoded, NmcOpenApiClient.ensureUrlEncodedServiceKey(encoded));
        assertEquals(encoded, NmcOpenApiClient.ensureUrlEncodedServiceKey(decoded));
    }

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
        assertFalse(dto.guidePrompts().isEmpty());
    }
}
