package com.yonsei119.dto;

import java.util.List;
import java.util.Map;

/**
 * ============================================================================
 * [레코드 역할 — 핵심 도메인 DTO]
 * 국립중앙의료원(NEMC) 7개 OpenAPI 엔드포인트의 XML 데이터를 기관 고유코드({@code HPID}) 기준으로
 * 교차 병합(Cross-Merge)하고 종별 분류까지 마친 **단일 응급의료기관 통합 정보 그릇**입니다.
 * <p>
 * [파이썬 장고(Django) 비유]
 * - 장고에서 DB 모델 없이 외부 공공데이터 API 여러 개를 합쳐서 내려줄 때 쓰는
 *   **핵심 도메인 {@code @dataclass} 겸 DRF {@code HospitalSerializer}** 역할입니다!
 * <p>
 * [코드의 경로성 (어디서 오고 어디로 가는가?)]
 * - [원본 수집 (Step 1)] : {@code service/NmcOpenApiClient.java}가 공공데이터포털 XML 7종을 파싱해 {@code Map<String, String>}으로 전달
 * - [병합 및 생성 (Step 2)] : {@code service/EmergencyDataService.java}의 {@code fetchFreshSnapshot()}이 {@code HPID}로 묶고,
 *   {@code service/HospitalClassifier.java}를 호출해 {@code regional} 여부 및 {@code type}({@code HospitalTypeDto})을 판정한 뒤 이 {@code HospitalDto}를 생성!
 * - [전달 (Step 3)] : {@code EmergencySnapshotDto.hospitals} 리스트에 담겨 {@code EmergencyApiController} ({@code GET /api/hospitals})를 통해 전송
 * - [프론트엔드 사용처 (Step 4 — {@code frontend/app.js})] :
 *   - {@code createMarkerContent()} / {@code updateMarkerContent()} : {@code name}, {@code regional}, {@code type}, {@code beds.hvec}으로 지도 위 알약형 병원 카드 표시
 *   - {@code openHospital()} : 우측(모바일은 하단) 상세 시트({@code #sheet})에 응급실 병상({@code beds}), 장비({@code basic}), 중증질환({@code severe}),
 *     응급 메시지({@code messages}), 권역외상센터({@code trauma}), 직통 전화번호({@code erTel}, {@code tel})를 렌더링!
 * <p>
 * [절대 지켜야 할 응급의료 도메인 규칙 (수정 시 주의!)]
 * - {@code beds} 맵의 값(예: {@code hvec} 응급실 일반병상)은 숫자가 아니라 **원본 문자열({@code "-3"}, {@code "5"}, {@code ""})** 그대로 유지합니다!
 *   - 음수({@code "-3"})는 에러가 아니라 현장에서 정원보다 3명을 더 받고 있다는 **'초과수용 3석(포화)'** 핵심 지표입니다.
 *   - 빈 문자열({@code ""})이나 키 누락은 병상이 {@code 0}개인 것이 아니라 **'미보고/미운영({@code -})'**이므로 절대 {@code "0"}으로 바꾸면 안 됩니다.
 * <p>
 * [수정 시 영향 및 예시]
 * - 병원 홈페이지 URL 같은 새 최상위 필드를 추가하고 싶다면:
 *   1) 이 레코드에 {@code String homepageUrl}을 추가하고,
 *   2) {@code EmergencyDataService.java}에서 {@code new HospitalDto(...)} 호출 시 값을 넣어주면 프론트엔드 {@code hospital.homepageUrl}로 즉시 전달됩니다.
 * ============================================================================
 *
 * @param id       국립중앙의료원 기관 고유코드 ({@code HPID}, 예: {@code "A2200001"})
 * @param name     응급의료기관 기관명 ({@code dutyName}, 예: {@code "연세대학교원주세브란스기독병원"})
 * @param address  기관 도로명/지번 주소 ({@code dutyAddr})
 * @param tel      대표 전화번호 ({@code dutyTel1})
 * @param erTel    응급실 직통 전화번호 ({@code dutyTel3})
 * @param lat      WGS84 위도 ({@code wgs84Lat})
 * @param lng      WGS84 경도 ({@code wgs84Lon})
 * @param regional 권역응급의료센터 지정 여부 ({@code true}: 권역응급 배지 부여)
 * @param type     기관 종별 분류 메타데이터 ({@link HospitalTypeDto} — 상급종합 / 의료원 / 일반·기타)
 * @param basic    응급의료기관 목록 + 기본정보 XML 태그 전체 병합 맵 (키가 모두 소문자, 예: {@code "dgididname"}, {@code "dutyeryn"})
 * @param trauma   권역외상센터 목록 + 기본정보 병합 맵 (권역외상센터가 아닌 병원은 {@code null})
 * @param beds     실시간 가용병상 정보 맵 ({@code "hvec"} 응급실, {@code "hvs01"} 기준병상, {@code "hvctayn"} CT 가용여부 등 원본 문자열)
 * @param messages 응급실 및 중증질환 실시간 전달 메시지 리스트 ({@code "symblkmsg"}, {@code "symblkmsgtyp"} 등)
 * @param severe   28대 중증질환 실시간 수용 가능 여부 맵 ({@code "mkioskty1"} ~ {@code "mkioskty28"} — {@code "Y"}/{@code "N"}/{@code "정보없음"})
 */
public record HospitalDto(
        String id,
        String name,
        String address,
        String tel,
        String erTel,
        double lat,
        double lng,
        boolean regional,
        HospitalTypeDto type,
        Map<String, String> basic,
        Map<String, String> trauma,
        Map<String, String> beds,
        List<Map<String, String>> messages,
        Map<String, String> severe
) {
}
