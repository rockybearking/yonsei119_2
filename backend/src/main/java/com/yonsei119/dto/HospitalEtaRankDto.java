package com.yonsei119.dto;

/**
 * ============================================================================
 * [레코드 역할 — DTO]
 * 현재 출발지 기준 주변 후보 병원들에 대해 네이버 Directions 5 API를 병렬 호출하여 산출한
 * **실제 도로 주행 최단 도착시간(ETA) 추천 순위** 단일 항목 DTO입니다.
 * (직선거리상으로는 가까워 보여도 강·산·고속도로 나들목 때문에 실제 주행 시간이 더 걸리는 역전 현상을 해결합니다!)
 * <p>
 * [파이썬 장고(Django) 비유]
 * - DRF에서 {@code GET /api/directions/rank} 응답 리스트의 각 원소를 직렬화하는 {@code HospitalEtaRankSerializer}와 같습니다.
 * <p>
 * [코드의 경로성 (어디서 오고 어디로 가는가?)]
 * - [생성 (Incoming Producer)] : {@code service/NaverDirectionsService.java}의 {@code rankHospitalsByDrivingTime(...)}
 * - [전달 (Via)] : {@code controller/EmergencyApiController.java}의 {@code GET /api/directions/rank} ({@code rankDirections()})
 * - [소비 (Outgoing Consumer)] : {@code frontend/app.js}의 {@code loadEtaRanking()}이 받아서
 *   좌측 패널의 {@code #eta-rank-box} 안에 1위~5위 추천 병원 카드(소요시간 {@code durationText}, 실도로 거리 {@code drivingDistanceKm},
 *   응급실 병상 {@code erBeds} 등)를 그려주고, 클릭 시 해당 병원의 상세 시트를 즉시 엽니다!
 * <p>
 * [수정 시 영향 및 예시]
 * - 순위 카드에 유류비({@code fuelPrice})도 함께 표시하고 싶다면 이 레코드에 {@code int fuelPrice}를 추가하고,
 *   {@code NaverDirectionsService.rankHospitalsByDrivingTime()}에서 {@code route.fuelPrice()}를 넣어주면 됩니다.
 * ============================================================================
 *
 * @param rank               실도로 소요시간 오름차순 순위 ({@code 1}, {@code 2}, {@code 3}, ...)
 * @param id                 병원 고유 기관코드 ({@code HPID})
 * @param name               병원 이름
 * @param typeGroup          종별 그룹 코드 ({@code "tertiary"}, {@code "medical"}, {@code "other"})
 * @param typeLabel          종별 한글 라벨 ({@code "상급종합"}, {@code "의료원"}, {@code "일반·기타"})
 * @param regional           권역응급의료센터 여부 ({@code true}/{@code false})
 * @param erBeds             응급실 일반병상 원본 문자열 ({@code hvec} — 음수/빈문자열 규칙 보존)
 * @param straightDistanceKm 출발지에서 병원까지의 직선 거리 (km)
 * @param drivingDistanceKm  실제 자동차 도로 주행 거리 (km)
 * @param durationMinutes    예상 주행 소요 시간 (분)
 * @param durationText       예상 주행 소요 시간 한글 문자열 (예: {@code "약 9분"})
 * @param tollFare           예상 통행료 (원)
 * @param taxiFare           예상 택시비 (원)
 * @param lat                병원 위도
 * @param lng                병원 경도
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
