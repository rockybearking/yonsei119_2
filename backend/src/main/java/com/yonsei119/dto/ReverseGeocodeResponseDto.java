package com.yonsei119.dto;

/**
 * ============================================================================
 * [레코드 역할 — DTO]
 * 위경도 좌표를 한국어 도로명·지번 주소로 변환한 역지오코딩({@code GET /api/reverse-geocode}) 응답 DTO입니다.
 * <p>
 * [파이썬 장고(Django) 비유]
 * - DRF의 {@code ReverseGeocodeSerializer}와 동일하며, 네이버 Reverse Geocoding 원본 JSON의 깊숙한 중첩 구조를
 *   프론트엔드가 쓰기 편하게 1차원 평면 필드로 펼쳐 담은 결과 객체입니다.
 * <p>
 * [코드의 경로성 (어디서 오고 어디로 가는가?)]
 * - [생성 (Incoming Producer)] : {@code service/NaverDirectionsService.java}의 {@code reverseGeocode(double lat, double lng)}
 * - [라우팅 (Via)] : {@code controller/EmergencyApiController.java}의 {@code GET /api/reverse-geocode} ({@code reverseGeocode()})
 * - [소비 (Outgoing Consumer)] : {@code frontend/app.js}의 {@code updateOriginAddress()} 함수가 호출하여,
 *   좌측 패널의 현재 출발 기준 주소({@code #origin-address-text})에 {@code roadAddress} 또는 {@code fullAddress}를 표시합니다!
 * <p>
 * [수정 시 영향 및 예시]
 * - 우편번호({@code zipCode}) 정보도 함께 내려주고 싶다면:
 *   예시(변경 전): {@code ..., String roadAddress, String jibunAddress)}
 *   예시(변경 후): {@code ..., String roadAddress, String jibunAddress, String zipCode)}
 *   로 필드를 추가한 뒤 {@code NaverDirectionsService.reverseGeocode()}에서 파싱해 넘겨주면 됩니다.
 * ============================================================================
 *
 * @param code         응답 상태 코드 ({@code 0}: 변환 성공, 그 외: 좌표 영역 외/실패)
 * @param message      상태 설명 메시지
 * @param lat          요청한 위도
 * @param lng          요청한 경도
 * @param shortAddress 축약 주소 (예: {@code "강원특별자치도 원주시 흥업면"})
 * @param fullAddress  표시용 전체 주소 (도로명 우선, 없으면 지번 주소)
 * @param roadAddress  도로명 주소 (예: {@code "강원특별자치도 원주시 연세대길 1"})
 * @param jibunAddress 지번(법정동/행정동) 주소
 */
public record ReverseGeocodeResponseDto(
        int code,
        String message,
        double lat,
        double lng,
        String shortAddress,
        String fullAddress,
        String roadAddress,
        String jibunAddress
) {
}
