package com.yonsei119.dto;

/**
 * ============================================================================
 * [레코드 역할]
 * 네이버 클라우드 플랫폼(NCP) Reverse Geocoding API (`map-reversegeocode/v2/gc`) 호출 결과를
 * 프론트엔드가 사용하기 쉽게 정제한 DTO입니다.
 * ============================================================================
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
