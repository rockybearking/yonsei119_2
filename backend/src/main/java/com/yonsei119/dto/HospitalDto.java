package com.yonsei119.dto;

import java.util.List;
import java.util.Map;

/**
 * ============================================================================
 * [레코드 역할]
 * 국립중앙의료원(NEMC) 7개 OpenAPI 엔드포인트의 데이터를 기관코드(`HPID`) 기준으로
 * 교차 병합(Cross-Merge)하고 종별 분류를 마친 단일 응급의료기관 통합 DTO입니다.
 *
 * [담당 기능]
 * 1. 기존 VanillaJS(`app.js`)에서 브라우저가 직접 XML 파싱 및 병합하던 작업을
 *    백엔드에서 완료하여 프론트엔드에 정제된 JSON 구조로 전달합니다.
 * 2. 현장 실사 기반 도메인 규칙을 보존하기 위해 병상 수치(`beds`)는 임의로 `0`으로
 *    치환하지 않고 원본 문자열(음수 `-3` = 초과수용, 빈 문자열 `""` = 미보고 `-`)을
 *    그대로 보존하여 전달합니다.
 *
 * [수정 시 영향 및 예시]
 * - 병원별 응급실 직통 전화번호 외에 대표 홈페이지 주소 등을 최상위 필드로
 *   노출하고 싶다면 이 레코드에 `String homepageUrl` 필드를 추가하면 됩니다.
 * ============================================================================
 *
 * @param id       국립중앙의료원 기관 고유코드 (`HPID`, 예: `"A2200001"`)
 * @param name     응급의료기관 기관명 (`dutyName`, 예: `"연세대학교원주세브란스기독병원"`)
 * @param address  기관 도로명/지번 주소 (`dutyAddr`)
 * @param tel      대표 전화번호 (`dutyTel1`)
 * @param erTel    응급실 직통 전화번호 (`dutyTel3`)
 * @param lat      WGS84 위도 (`wgs84Lat`)
 * @param lng      WGS84 경도 (`wgs84Lon`)
 * @param regional 권역응급의료센터 해당 여부 (`true`/`false`)
 * @param type     기관 종별 분류 정보 (`상급종합병원`, `의료원`, `일반·기타 응급의료기관`)
 * @param basic    응급의료기관 목록 + 기본정보 병합 맵 (소문자 태그 키 -> 문자열 값)
 * @param trauma   권역외상센터 목록 + 기본정보 병합 맵 (외상센터가 아닌 경우 null)
 * @param beds     실시간 가용병상 정보 맵 (`hvec`, `hvs01`, `hv28` 등 원본 문자열 보존)
 * @param messages 응급실 및 중증질환 실시간 전달 메시지 목록 (`symBlkMsg`, `symBlkMsgTyp` 등)
 * @param severe   28대 중증질환 실시간 수용 가능 정보 맵 (`mkioskty1` ~ `mkioskty28` 등)
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
