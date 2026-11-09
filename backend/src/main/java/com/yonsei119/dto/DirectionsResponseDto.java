package com.yonsei119.dto;

import java.util.List;

/**
 * ============================================================================
 * [레코드 역할]
 * 네이버 클라우드 플랫폼(NCP) **Directions 5** 및 **Directions 15** 자동차 경로 탐색 API의
 * 응답 결과를 프론트엔드가 즉시 렌더링할 수 있도록 정제한 DTO입니다.
 * <p>
 * [담당 기능]
 * 1. 사용자가 선택한 API 종류(`directions5` 또는 `directions15`)와 탐색 옵션(`trafast`, `traoptimal` 등)을 명시합니다.
 * 2. 예상 소요 시간(밀리초, 분, 한글 포맷 문자열 `"18분"` / `"1시간 05분"`), 총 주행 거리(m, km),
 *    예상 통행료(`tollFare`), 예상 택시 요금(`taxiFare`), 예상 유류비(`fuelPrice`)를 제공합니다.
 * 3. 네이버 지도(`naver.maps.Polyline`) 위에 실제 도로 주행 경로 및 구간별 실시간 교통 혼잡도(원활·서행·정체)를
 *    그릴 수 있도록 경로 좌표 배열(`path`), 구간 혼잡도 리스트(`sections`), 주요 분기점 안내(`guidePrompts`)를 제공합니다.
 * 4. 다중 옵션 동시 조회(`trafast:traoptimal:traavoidtoll`) 결과를 `alternatives` 리스트로 함께 제공하여
 *    프론트엔드에서 추가 API 호출 없이 옵션별 소요시간·통행료를 즉시 비교할 수 있습니다.
 * ============================================================================
 */
public record DirectionsResponseDto(
        String apiType,
        String apiLabel,
        String option,
        String optionLabel,
        int code,
        String message,
        int distanceMeters,
        double distanceKm,
        long durationMs,
        int durationMinutes,
        String durationText,
        String departureTime,
        int tollFare,
        int taxiFare,
        int fuelPrice,
        List<List<Double>> path,
        List<RouteSectionDto> sections,
        List<String> guidePrompts,
        List<RouteOptionSummaryDto> alternatives
) {

    /**
     * [레코드 역할]
     * 네이버 Directions 5 / 15 응답의 `section` 배열 원소(주요 도로 구간별 실시간 혼잡도 정보)를 담는 DTO입니다.
     */
    public record RouteSectionDto(
            int pointIndex,
            int pointCount,
            int distance,
            String name,
            int congestion,
            int speed
    ) {
    }

    /**
     * [레코드 역할]
     * 다중 경로 옵션(`trafast:traoptimal:traavoidtoll`) 동시 조회 시 각 옵션별 요약 비교 정보를 담는 DTO입니다.
     */
    public record RouteOptionSummaryDto(
            String option,
            String optionLabel,
            int distanceMeters,
            double distanceKm,
            long durationMs,
            int durationMinutes,
            String durationText,
            int tollFare,
            int taxiFare,
            int fuelPrice
    ) {
    }
}
