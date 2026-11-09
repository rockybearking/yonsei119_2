package com.yonsei119.dto;

import java.util.List;

/**
 * ============================================================================
 * [레코드 역할]
 * 네이버 클라우드 플랫폼(NCP) Geocoding API (`map-geocode/v2/geocode`) 및 장소/주소 검색 결과를
 * 프론트엔드가 출발지 이동 및 주변 응급실 탐색에 사용할 수 있도록 정제한 DTO입니다.
 * ============================================================================
 */
public record GeocodeResponseDto(
        int code,
        String query,
        List<GeocodeItemDto> items
) {
    public record GeocodeItemDto(
            String title,
            String roadAddress,
            String jibunAddress,
            double lat,
            double lng
    ) {
    }
}
