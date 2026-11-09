package com.yonsei119.dto;

/**
 * ============================================================================
 * [레코드 역할]
 * 주변 후보 응급의료기관들에 대해 네이버 Directions 5 API를 병렬 호출하여 산출한
 * **실제 도로 주행 소요시간(ETA) 기반 추천 순위** 항목 DTO입니다.
 * ============================================================================
 */
public record HospitalEtaRankDto(
        int rank,
        String id,
        String name,
        String typeGroup,
        String typeLabel,
        boolean regional,
        String erBeds,
        double straightDistanceKm,
        double drivingDistanceKm,
        int durationMinutes,
        String durationText,
        int tollFare,
        int taxiFare,
        double lat,
        double lng
) {
}
