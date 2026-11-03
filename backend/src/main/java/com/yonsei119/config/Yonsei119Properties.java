package com.yonsei119.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ============================================================================
 * [클래스 역할]
 * `application.yml`의 `yonsei119.*` 설정 계층을 타입 안전(Type-Safe)하고
 * 불변(Immutable)인 Java 17 Record로 바인딩하는 구성 프로퍼티 클래스입니다.
 *
 * [담당 기능]
 * 1. 국립중앙의료원(NEMC) OpenAPI 주소, 인증키, 캐시 TTL, 타임아웃 값을 보유합니다.
 * 2. 네이버 클라우드 플랫폼(NCP) Client ID, Client Secret, Directions 5 / 15 URL을 보유합니다.
 * 3. 프론트엔드 초기 기준 좌표(연세대학교 미래캠퍼스) 정보를 보유합니다.
 *
 * [수정 시 영향 및 예시]
 * - `application.yml`의 `yonsei119` 하위에 새 설정(예: `max-radius-km: 150`)을 추가할 경우,
 *   이 레코드에 필드를 하나 추가하면 스프링이 자동으로 값을 주입해 줍니다.
 *   예시(변경 전):
 *   {@code
 *   public record Yonsei119Properties(Nemc nemc, Naver naver, DefaultOrigin defaultOrigin)
 *   }
 *   예시(변경 후):
 *   {@code
 *   public record Yonsei119Properties(Nemc nemc, Naver naver, DefaultOrigin defaultOrigin, int maxRadiusKm)
 *   }
 * ============================================================================
 */
@ConfigurationProperties(prefix = "yonsei119")
public record Yonsei119Properties(
        Nemc nemc,
        Naver naver,
        DefaultOrigin defaultOrigin
) {

    /**
     * [레코드 역할]
     * 국립중앙의료원(NEMC) 응급의료정보조회서비스 OpenAPI 연동 설정값 묶음입니다.
     *
     * @param baseUrl            OpenAPI 기본 엔드포인트 URL
     * @param serviceKey         공공데이터포털 서비스 인증키 (Encoded 또는 Decoded 모두 허용)
     * @param cacheTtlSeconds    전국 병원 스냅샷 인메모리 캐시 유지 시간(초)
     * @param requestTimeoutMs   개별 OpenAPI HTTP 요청 타임아웃(밀리초)
     * @param autoRefreshEnabled 서버 시작 즉시 사전 로딩(Pre-warming) 및 백그라운드 주기적 자동 갱신 활성화 여부
     * @param refreshIntervalMs  백그라운드 스케줄러 자동 갱신 주기(밀리초, 기본 45000ms = 45초)
     */
    public record Nemc(
            String baseUrl,
            String serviceKey,
            long cacheTtlSeconds,
            long requestTimeoutMs,
            boolean autoRefreshEnabled,
            long refreshIntervalMs
    ) {
    }

    /**
     * [레코드 역할]
     * 네이버 클라우드 플랫폼(NCP) Maps JS v3 및 Directions 5 / Directions 15 설정값 묶음입니다.
     *
     * @param clientId        NCP Application Client ID (`X-NCP-APIGW-API-KEY-ID` / `ncpKeyId`)
     * @param clientSecret    NCP Application Client Secret (`X-NCP-APIGW-API-KEY`)
     * @param directions5Url  네이버 Directions 5 REST API 엔드포인트 URL
     * @param directions15Url 네이버 Directions 15 REST API 엔드포인트 URL
     */
    public record Naver(
            String clientId,
            String clientSecret,
            String directions5Url,
            String directions15Url
    ) {
    }

    /**
     * [레코드 역할]
     * 앱 최초 구동 시 기준점이 되는 기본 출발지(연세대학교 미래캠퍼스) 좌표 설정입니다.
     *
     * @param lat   WGS84 위도(Latitude)
     * @param lng   WGS84 경도(Longitude)
     * @param label 화면에 표시할 기준 위치 명칭
     */
    public record DefaultOrigin(
            double lat,
            double lng,
            String label
    ) {
    }
}
