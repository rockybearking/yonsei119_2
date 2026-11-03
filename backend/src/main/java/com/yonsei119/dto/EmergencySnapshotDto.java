package com.yonsei119.dto;

import java.util.List;
import java.util.Map;

/**
 * ============================================================================
 * [레코드 역할]
 * `/api/hospitals` 엔드포인트가 반환하는 전국 응급의료기관 실시간 스냅샷 응답 DTO입니다.
 *
 * [담당 기능]
 * 1. 데이터 갱신 시각(`updatedAt`)과 인메모리 캐시 히트 여부(`cached`)를 제공합니다.
 * 2. 종별 기관 통계(`totalCount`, `tertiaryCount`, `regionalCount`, `medicalCount`, `traumaCount`, `otherCount`)와
 *    각 OpenAPI 오퍼레이션별 수신 성공 여부(`sources`) 및 건수(`endpointCounts`)를 함께 제공합니다.
 * 3. 전국 응급의료기관 리스트(`hospitals`)를 제공합니다.
 *
 * [수정 시 영향 및 예시]
 * - 만약 백엔드 응답 소요 시간(`elapsedMs`)을 프론트엔드 개발자 모드에 표시하고 싶다면:
 *   필드에 `long elapsedMs`를 추가하여 `EmergencyDataService`에서 측정값을 담아 반환하면 됩니다.
 * ============================================================================
 *
 * @param updatedAt      스냅샷 생성 시각 (ISO-8601 문자열)
 * @param cached         캐시된 스냅샷 반환 여부 (`true`: 캐시 응답, `false`: 신규 OpenAPI 수집)
 * @param totalCount     유효 좌표를 가진 전체 응급의료기관 수
 * @param tertiaryCount  상급종합병원 수
 * @param regionalCount  권역응급의료센터 수
 * @param medicalCount   의료원 수
 * @param traumaCount    권역외상센터 수
 * @param otherCount     일반·기타 응급의료기관 수
 * @param sources        NEMC OpenAPI 엔드포인트별 정상 수신 여부 (`list`, `basic`, `beds`, `messages`, `severe`, `traumaList`, `traumaBasic`)
 * @param endpointCounts NEMC OpenAPI 엔드포인트별 원본 수신 아이템 건수
 * @param hospitals      기관코드(`HPID`) 기준으로 병합된 전체 응급의료기관 목록
 */
public record EmergencySnapshotDto(
        String updatedAt,
        boolean cached,
        int totalCount,
        int tertiaryCount,
        int regionalCount,
        int medicalCount,
        int traumaCount,
        int otherCount,
        Map<String, Boolean> sources,
        Map<String, Integer> endpointCounts,
        List<HospitalDto> hospitals
) {
}
