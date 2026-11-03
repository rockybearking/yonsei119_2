package com.yonsei119.dto;

import java.util.List;

/**
 * ============================================================================
 * [레코드 역할]
 * 네이버 클라우드 플랫폼(NCP) **Directions 5** 및 **Directions 15** 자동차 경로 탐색 API의
 * 응답 결과를 프론트엔드가 즉시 렌더링할 수 있도록 정제한 DTO입니다.
 *
 * [담당 기능]
 * 1. 사용자가 선택한 API 종류(`directions5` 또는 `directions15`)와 탐색 옵션(`trafast`, `traoptimal` 등)을 명시합니다.
 * 2. 예상 소요 시간(밀리초, 분, 한글 포맷 문자열 `"18분"` / `"1시간 05분"`), 총 주행 거리(m, km),
 *    예상 통행료(`tollFare`), 예상 택시 요금(`taxiFare`), 예상 유류비(`fuelPrice`)를 제공합니다.
 * 3. 네이버 지도(`naver.maps.Polyline`) 위에 실제 도로 주행 경로를 그릴 수 있도록
 *    경로 좌표 배열(`path`, 각 원소는 `[경도(lng), 위도(lat)]`)과 주요 분기점 안내(`guidePrompts`)를 제공합니다.
 *
 * [수정 시 영향 및 예시]
 * - 만약 도착 예정 시각(`etaText`)을 추가로 내려주고 싶다면 필드에 `String etaText`를 추가하고
 *   `NaverDirectionsService`에서 현재 시각 + `durationMs`를 계산해 넣으면 됩니다.
 * ============================================================================
 *
 * @param apiType         호출된 네이버 경로 API 구분 (`"directions5"` 또는 `"directions15"`)
 * @param apiLabel        화면 표시용 API 명칭 (`"Directions 5"` 또는 `"Directions 15"`)
 * @param option          경로 탐색 옵션 코드 (`"trafast"`, `"traoptimal"`, `"tracomfort"`, `"traavoidtoll"`)
 * @param optionLabel     경로 탐색 옵션 한글 라벨 (`"실시간 빠른길"`, `"실시간 최적"`, `"실시간 편한길"`, `"무료 우선"`)
 * @param code            네이버 Directions API 응답 코드 (`0`이면 정상 탐색 성공)
 * @param message         네이버 Directions API 응답 메시지
 * @param distanceMeters  총 주행 거리 (미터 단위)
 * @param distanceKm      총 주행 거리 (킬로미터 단위, 소수점 1자리 반올림)
 * @param durationMs      예상 소요 시간 (밀리초 단위)
 * @param durationMinutes 예상 소요 시간 (분 단위, 최소 1분 보장)
 * @param durationText    화면 표시용 예상 소요 시간 문자열 (예: `"12분"`, `"1시간 8분"`)
 * @param departureTime   네이버 API 기준 출발 시각 문자열
 * @param tollFare        예상 톨게이트 통행료 (원)
 * @param taxiFare        예상 택시 요금 (원)
 * @param fuelPrice       예상 유류비 (원)
 * @param path            폴리라인 렌더링용 경로 좌표 리스트 (각 항목은 `[lng, lat]` 2원소 리스트)
 * @param guidePrompts    주요 회전/진입 구간 내비게이션 안내 문구 리스트
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
        List<String> guidePrompts
) {
}
