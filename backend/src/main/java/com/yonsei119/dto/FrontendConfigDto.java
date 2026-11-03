package com.yonsei119.dto;

/**
 * ============================================================================
 * [레코드 역할]
 * 프론트엔드(`app.js`)가 초기 구동 시 `/api/config` 엔드포인트로부터 전달받는
 * 공개 환경 설정 DTO(Data Transfer Object)입니다.
 *
 * [담당 기능]
 * 1. 네이버 지도 JS v3 SDK 로드에 필요한 공개 Client ID(`ncpKeyId`)를 전달합니다.
 *    (주의: 보안상 Client Secret 및 공공데이터포털 인증키는 절대 포함하지 않습니다.)
 * 2. 초기 기준 위치(연세대학교 미래캠퍼스)의 위도, 경도, 표시 라벨을 전달합니다.
 *
 * [수정 시 영향 및 예시]
 * - 프론트엔드에 초기 줌 레벨(`defaultZoom`)도 백엔드 설정으로 내려주고 싶다면:
 *   예시(변경 전):
 *   {@code
 *   public record FrontendConfigDto(String naverClientId, double defaultLat, double defaultLng, String defaultLabel)
 *   }
 *   예시(변경 후):
 *   {@code
 *   public record FrontendConfigDto(String naverClientId, double defaultLat, double defaultLng, String defaultLabel, int defaultZoom)
 *   }
 * ============================================================================
 *
 * @param naverClientId 네이버 클라우드 플랫폼 Application Client ID (`ncpKeyId`)
 * @param defaultLat    기본 출발지 WGS84 위도
 * @param defaultLng    기본 출발지 WGS84 경도
 * @param defaultLabel  기본 출발지 표시 이름
 */
public record FrontendConfigDto(
        String naverClientId,
        double defaultLat,
        double defaultLng,
        String defaultLabel
) {
}
