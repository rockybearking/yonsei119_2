package com.yonsei119.dto;

/**
 * ============================================================================
 * [레코드 역할 — DTO]
 * {@code /api/config} 엔드포인트가 브라우저에 전달하는 **프론트엔드 공개용 환경 설정** DTO입니다.
 * <p>
 * [파이썬 장고(Django) 비유]
 * - 장고 {@code settings.py}에는 DB 비밀번호나 {@code SECRET_KEY}처럼 절대 브라우저에 노출하면 안 되는 비밀값과,
 *   프론트엔드 지도 SDK 로딩에 꼭 필요한 공개 키({@code NAVER_MAP_CLIENT_ID})가 섞여 있습니다.
 * - 이 DTO는 그중 **브라우저에 안전하게 내려보내도 되는 5가지 공개 설정값만 걸러서 내보내는 DRF Serializer**입니다.
 * <p>
 * [코드의 경로성 (어디서 오고 어디로 가는가?)]
 * - [원본 데이터 (Origin)] : {@code backend/src/main/resources/application.yml} → {@code config/Yonsei119Properties.java}
 * - [생성 및 반환 (Via)] : {@code controller/EmergencyApiController.java}의 {@code GET /api/config} ({@code getConfig()})
 * - [검증 테스트 (Tested By)] : {@code Yonsei119BackendTests.java}의 {@code configEndpointExposesPublicClientIdOnly()}가
 *   이 응답에 {@code clientSecret}이 절대 포함되지 않는지 자동 검증합니다!
 * <p>
 * [수정 시 영향 및 예시]
 * - 초기 지도 줌 레벨({@code defaultZoom})도 백엔드 설정으로 내려주고 싶다면:
 *   예시(변경 전):
 *   {@code public record FrontendConfigDto(String naverClientId, String naverDarkStyleId, double defaultLat, double defaultLng, String defaultLabel)}
 *   예시(변경 후):
 *   {@code public record FrontendConfigDto(String naverClientId, String naverDarkStyleId, double defaultLat, double defaultLng, String defaultLabel, int defaultZoom)}
 * ============================================================================
 *
 * @param naverClientId    네이버 클라우드 플랫폼 공개 Client ID ({@code ncpKeyId})
 * @param naverDarkStyleId 네이버 지도 Style Editor 다크 모드(야간 관제) Custom Style UUID
 * @param defaultLat       기본 출발지 WGS84 위도 ({@code 37.319040...})
 * @param defaultLng       기본 출발지 WGS84 경도 ({@code 127.901228...})
 * @param defaultLabel     기본 출발지 화면 표시 명칭 ({@code "연세대학교 미래캠퍼스 (기본 위치)"})
 */
public record FrontendConfigDto(
        String naverClientId,
        String naverDarkStyleId,
        double defaultLat,
        double defaultLng,
        String defaultLabel
) {
}
