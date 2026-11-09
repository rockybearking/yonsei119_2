package com.yonsei119.dto;

import java.util.List;

/**
 * ============================================================================
 * [레코드 역할 — DTO]
 * 사용자가 검색창에 입력한 주소·지역명·장소명을 위경도 좌표로 변환한 지오코딩({@code GET /api/geocode}) 응답 DTO입니다.
 * <p>
 * [파이썬 장고(Django) 비유]
 * - DRF의 중첩 Serializer ({@code GeocodeResponseSerializer} 내부에 {@code GeocodeItemSerializer(many=True)} 포함)와 같습니다.
 * <p>
 * [코드의 경로성 (어디서 오고 어디로 가는가?)]
 * - [생성 (Incoming Producer)] : {@code service/NaverDirectionsService.java}의 {@code geocode(String query, List<HospitalDto> hospitals)}
 * - [라우팅 (Via)] : {@code controller/EmergencyApiController.java}의 {@code GET /api/geocode} ({@code geocode()})
 * - [소비 (Outgoing Consumer)] : {@code frontend/app.js}의 {@code searchAddressAndMove()} 및 검색 자동완성 목록({@code #search-results})에서
 *   {@code items[0].lat}, {@code items[0].lng} 좌표로 지도를 이동시키고 출발지를 설정하는 데 사용됩니다.
 * <p>
 * [수정 시 영향 및 예시]
 * - 검색 결과 항목에 행정구역 시도명({@code sido})을 추가로 내려주고 싶다면 내부 {@code GeocodeItemDto}에 {@code String sido} 필드를 추가합니다:
 *   예시(변경 전): {@code public record GeocodeItemDto(String title, String roadAddress, String jibunAddress, double lat, double lng)}
 *   예시(변경 후): {@code public record GeocodeItemDto(String title, String roadAddress, String jibunAddress, double lat, double lng, String sido)}
 * ============================================================================
 *
 * @param code  응답 상태 코드 ({@code 0}: 성공, 그 외: 검색 결과 없음 또는 실패)
 * @param query 사용자가 요청한 원본 검색어 문자열
 * @param items 검색된 주소/장소 좌표 후보 목록 ({@link GeocodeItemDto})
 */
public record GeocodeResponseDto(
        int code,
        String query,
        List<GeocodeItemDto> items
) {
    /**
     * [하위 레코드 역할] 지오코딩으로 찾은 개별 주소·장소 좌표 항목입니다.
     *
     * @param title        화면 표시 제목 (도로명 주소, 장소명, 또는 병원명)
     * @param roadAddress  도로명 주소
     * @param jibunAddress 지번 주소
     * @param lat          위도 (WGS84 Latitude)
     * @param lng          경도 (WGS84 Longitude)
     */
    public record GeocodeItemDto(
            String title,
            String roadAddress,
            String jibunAddress,
            double lat,
            double lng
    ) {
    }
}
