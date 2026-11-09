package com.yonsei119.dto;

import java.util.List;

/**
 * ============================================================================
 * [레코드 역할 — DTO(Data Transfer Object)]
 * 네이버 클라우드 플랫폼(NCP) **Directions 5** 및 **Directions 15** 자동차 경로 탐색 API의
 * 복잡한 원본 JSON 응답을 프론트엔드가 곧바로 화면·지도에 그릴 수 있도록 깔끔하게 가공한 데이터 그릇(DTO)입니다.
 * <p>
 * [파이썬 장고(Django) 비유]
 * - Django REST Framework(DRF)의 **{@code serializers.py} ({@code DirectionsResponseSerializer})** 또는
 *   Python의 **{@code @dataclass(frozen=True)} / {@code TypedDict}**와 동일한 역할입니다!
 * - Java {@code record}는 필드만 선언하면 Spring Boot 내장 Jackson 라이브러리가 자동으로 필드명을 Key로 삼아
 *   {@code {"apiType": "directions5", "durationText": "약 11분", ...}} 형태의 JSON으로 변환해 프론트엔드에 내려보냅니다.
 * <p>
 * [코드의 경로성 (어디서 오고 어디로 가는가?)]
 * - [누가 이 객체를 만드는가 (Incoming Producer)] :
 *   {@code service/NaverDirectionsService.java}의 {@code getDrivingRoute(...)} 및 {@code parseDirectionsResponse(...)} 메서드
 * - [어느 컨트롤러를 거치는가 (Via)] :
 *   {@code controller/EmergencyApiController.java}의 {@code GET /api/directions} ({@code getDirections()})
 * - [프론트엔드 어디서 받아 쓰는가 (Outgoing Consumer)] :
 *   {@code frontend/app.js}의 아래 함수들이 이 JSON 필드를 그대로 읽습니다:
 *   1) {@code requestDirections()} → {@code renderDirectionsResult()} : 상세 시트에 소요시간({@code durationText}), 거리({@code distanceKm}),
 *      통행료({@code tollFare}), 택시비({@code taxiFare}), 분기점 안내({@code guidePrompts}), 다중 경로 비교({@code alternatives}) 렌더링
 *   2) {@code drawRouteOnMap()} : {@code path} 좌표 배열과 {@code sections} 혼잡도 구간으로 지도 위 경로선(원활/서행/정체 색상) 렌더링
 *   3) {@code showActiveRouteBanner()} / {@code refreshActiveRoute()} : 상단 알약 배너({@code #active-route-banner})에 실시간 요약 표시
 * <p>
 * [수정 시 영향 및 예시]
 * - 주의: 여기에 선언된 필드 이름({@code durationText}, {@code path}, {@code sections} 등)이 곧 프론트엔드 {@code app.js}가 읽는 JSON 키 이름입니다!
 * - 만약 도착 예정 시각({@code arrivalTime}) 필드를 새로 추가해 화면에 보여주고 싶다면:
 *   1) 이 레코드 맨 끝에 {@code String arrivalTime} 파라미터를 추가합니다:
 *      예시(변경 전): {@code ..., List<RouteOptionSummaryDto> alternatives)}
 *      예시(변경 후): {@code ..., List<RouteOptionSummaryDto> alternatives, String arrivalTime)}
 *   2) {@code NaverDirectionsService.java}에서 {@code new DirectionsResponseDto(..., arrivalTime)} 값을 채워 넣고,
 *   3) {@code frontend/app.js}의 {@code renderDirectionsResult(data)}에서 {@code data.arrivalTime}을 화면에 출력하면 됩니다.
 * ============================================================================
 *
 * @param apiType         사용된 API 식별자 ({@code "directions5"} 또는 {@code "directions15"})
 * @param apiLabel        화면 배지용 API 이름 ({@code "Directions 5"} 또는 {@code "Directions 15"})
 * @param option          적용된 탐색 옵션 코드 ({@code "trafast"}, {@code "traoptimal"}, {@code "tracomfort"}, {@code "traavoidtoll"}, {@code "traavoidcaronly"})
 * @param optionLabel     탐색 옵션 한글 명칭 ({@code "실시간 빠른길"}, {@code "실시간 최적"} 등)
 * @param code            응답 상태 코드 ({@code 0}이면 정상 탐색 성공, 그 외에는 오류/폴백 코드)
 * @param message         상태 메시지 또는 폴백 안내 문구
 * @param distanceMeters  총 주행 거리 (미터 단위 정수)
 * @param distanceKm      총 주행 거리 (킬로미터 소수점 첫째 자리, 예: {@code 6.7})
 * @param durationMs      총 예상 소요 시간 (밀리초 단위 원본값)
 * @param durationMinutes 총 예상 소요 시간 (분 단위 반올림값)
 * @param durationText    사람이 읽기 쉬운 한글 소요 시간 문자열 (예: {@code "약 11분 3초"}, {@code "1시간 05분"})
 * @param departureTime   출발 기준 시각 문자열
 * @param tollFare        예상 톨게이트 통행료 (원)
 * @param taxiFare        예상 택시 요금 (원)
 * @param fuelPrice       예상 유류비 (원)
 * @param path            경로 폴리라인 좌표 배열 ({@code [[lng, lat], [lng, lat], ...]} — 네이버/GeoJSON 표준 {@code [경도, 위도]} 순서!)
 * @param sections        구간별 실시간 교통 혼잡도 정보 리스트 ({@link RouteSectionDto})
 * @param guidePrompts    주요 교차로·IC 주행 안내 문구 리스트 (최대 상위 안내 추출)
 * @param alternatives    동시 조회된 다른 탐색 옵션({@code 실시간 빠른길} vs {@code 실시간 최적} vs {@code 무료 우선}) 요약 비교 리스트
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
     * [하위 레코드 역할]
     * 전체 경로({@code path}) 중 특정 도로 구간의 실시간 교통 혼잡도({@code congestion})를 담는 DTO입니다.
     * <p>
     * [코드의 경로성]
     * - [생성] {@code NaverDirectionsService.java}가 네이버 응답 {@code section} 배열에서 파싱
     * - [소비] {@code frontend/app.js}의 {@code drawRouteOnMap()}이 {@code pointIndex} ~ {@code pointIndex + pointCount} 구간을 잘라
     *   혼잡도 값({@code 1}: 원활 초록 {@code #03c75a}, {@code 2}: 서행 주황 {@code #f59e0b}, {@code 3}: 정체 빨강 {@code #ef4444})으로 색칠합니다!
     *
     * @param pointIndex 전체 {@code path} 배열에서 이 구간이 시작되는 인덱스 번호
     * @param pointCount 이 구간에 포함된 좌표 점 개수
     * @param distance   구간 거리 (미터)
     * @param name       도로명 (예: {@code "북원로"}, {@code "영동고속도로"})
     * @param congestion 혼잡도 코드 ({@code 0}: 정보없음, {@code 1}: 원활, {@code 2}: 서행, {@code 3}: 정체)
     * @param speed      구간 평균 주행 속도 (km/h)
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
     * [하위 레코드 역할]
     * 한 번의 API 호출({@code trafast:traoptimal:traavoidtoll})로 함께 받은 대체 경로들의 요약 카드 데이터입니다.
     * <p>
     * [코드의 경로성]
     * - [생성] {@code NaverDirectionsService.java}
     * - [소비] {@code frontend/app.js}의 {@code renderDirectionsResult()} → 병원 상세 시트의 대체 경로 비교 버튼({@code .directions-alt-card}) 렌더링
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
