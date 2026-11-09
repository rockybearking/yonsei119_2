package com.yonsei119.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ============================================================================
 * [클래스 역할]
 * {@code application.yml}의 {@code yonsei119.*} 설정 계층을 타입 안전(Type-Safe)하고
 * 불변(Immutable)인 Java 17 {@code record} 객체로 바인딩하는 구성 프로퍼티 클래스입니다.
 * <p>
 * [파이썬 장고(Django) 비유]
 * - 장고에서는 {@code from django.conf import settings} 후 {@code settings.NAVER_CLIENT_ID}처럼 딕셔너리/속성으로 꺼내 쓰다가
 *   오타가 나면 실행 중에야 {@code AttributeError}가 발생하는 경우가 많습니다.
 * - Spring Boot에서는 이 {@code Yonsei119Properties} 파일이 **{@code settings.py}에 타입 검증(Pydantic {@code BaseSettings}와 유사)을 입힌 역할**을 합니다!
 *   덕분에 오타가 있으면 컴파일 시점에 즉시 잡아주고, 실행 중 설정값이 실수로 덮어써지는(Mutation) 일도 원천 차단됩니다.
 * <p>
 * [코드의 경로성 (어디서 오고 어디로 가는가?)]
 * - [어디서 왔는가 (Incoming)] :
 *   1) 값의 원본: {@code backend/src/main/resources/application.yml} 파일의 {@code yonsei119:} 블록 (및 OS 환경변수)
 *   2) 스캔 주체: {@code Yonsei119Application.java}의 {@code @ConfigurationPropertiesScan} 어노테이션이 서버 부팅 시 자동 생성
 * - [어디로 가는가 (Outgoing — 이 객체를 주입받아 쓰는 파일들)] :
 *   1) {@code controller/EmergencyApiController.java} : {@code naver().clientId()}, {@code naver().darkStyleId()}, {@code defaultOrigin()}을 읽어 {@code /api/config}로 반환
 *   2) {@code service/NmcOpenApiClient.java} : {@code nemc().baseUrl()}, {@code nemc().serviceKey()}, {@code nemc().requestTimeoutMs()}를 읽어 공공데이터포털 호출
 *   3) {@code service/EmergencyDataService.java} : {@code nemc().cacheTtlSeconds()}, {@code nemc().autoRefreshEnabled()}를 읽어 캐시 수명 관리
 *   4) {@code service/NaverDirectionsService.java} : {@code naver().clientId()}, {@code naver().clientSecret()}, {@code directions5Url()}, {@code directions15Url()}로 네이버 API 호출
 * <p>
 * [수정 시 영향 및 예시]
 * - 만약 {@code application.yml}의 {@code yonsei119:} 아래에 {@code max-search-results: 20}이라는 새 옵션을 추가하고 싶다면:
 *   1단계 ({@code application.yml}에 추가):
 *     {@code max-search-results: 20}
 *   2단계 (이 파일의 레코드 파라미터에 {@code int maxSearchResults} 추가):
 *     예시(변경 전):
 *     {@code public record Yonsei119Properties(Nemc nemc, Naver naver, DefaultOrigin defaultOrigin)}
 *     예시(변경 후):
 *     {@code public record Yonsei119Properties(Nemc nemc, Naver naver, DefaultOrigin defaultOrigin, int maxSearchResults)}
 *   그러면 다른 서비스 파일에서 {@code properties.maxSearchResults()}로 즉시 꺼내 쓸 수 있습니다!
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
     * {@code application.yml}의 {@code yonsei119.nemc.*} 항목과 1:1 매핑되는 국립중앙의료원(NEMC) OpenAPI 설정 묶음입니다.
     * <p>
     * [참조하는 파일 (Outgoing)]
     * - {@code service/NmcOpenApiClient.java} ({@code baseUrl}, {@code serviceKey}, {@code requestTimeoutMs} 사용)
     * - {@code service/EmergencyDataService.java} ({@code cacheTtlSeconds}, {@code autoRefreshEnabled}, {@code refreshIntervalMs} 사용)
     *
     * @param baseUrl            OpenAPI 기본 엔드포인트 URL ({@code yonsei119.nemc.base-url})
     * @param serviceKey         공공데이터포털 인증키 ({@code yonsei119.nemc.service-key})
     * @param cacheTtlSeconds    전국 병원 스냅샷 인메모리 캐시 유지 시간(초) ({@code yonsei119.nemc.cache-ttl-seconds})
     * @param requestTimeoutMs   개별 OpenAPI HTTP 요청 타임아웃(밀리초) ({@code yonsei119.nemc.request-timeout-ms})
     * @param autoRefreshEnabled 서버 시작 즉시 사전 로딩 및 주기적 갱신 활성화 여부 ({@code yonsei119.nemc.auto-refresh-enabled})
     * @param refreshIntervalMs  백그라운드 스케줄러 자동 갱신 주기(밀리초) ({@code yonsei119.nemc.refresh-interval-ms})
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
     * {@code application.yml}의 {@code yonsei119.naver.*} 항목과 1:1 매핑되는 네이버 클라우드 플랫폼(NCP) 설정 묶음입니다.
     * <p>
     * [참조하는 파일 (Outgoing)]
     * - {@code controller/EmergencyApiController.java} : 공개 가능한 {@code clientId}, {@code darkStyleId}만 추려서 {@code FrontendConfigDto}에 담아 프론트엔드로 전달
     * - {@code service/NaverDirectionsService.java} : 비공개 {@code clientSecret}까지 포함하여 서버 간 통신으로 길찾기/주소변환 수행
     *
     * @param clientId        NCP Application Client ID ({@code X-NCP-APIGW-API-KEY-ID} / {@code ncpKeyId})
     * @param darkStyleId     네이버 지도 Style Editor 다크 모드(야간 관제) Custom Style ID
     * @param clientSecret    NCP Application Client Secret ({@code X-NCP-APIGW-API-KEY}, 서버 전용 비공개 키)
     * @param directions5Url  네이버 Directions 5 REST API 엔드포인트 URL
     * @param directions15Url 네이버 Directions 15 REST API 엔드포인트 URL
     */
    public record Naver(
            String clientId,
            String darkStyleId,
            String clientSecret,
            String directions5Url,
            String directions15Url
    ) {
    }

    /**
     * [레코드 역할]
     * {@code application.yml}의 {@code yonsei119.default-origin.*} 항목과 1:1 매핑되는 초기 기준 출발지 좌표 설정입니다.
     * <p>
     * [참조하는 파일 (Outgoing)]
     * - {@code controller/EmergencyApiController.java} ({@code getConfig()}) → {@code dto/FrontendConfigDto.java} →
     *   프론트엔드 {@code /api/config} 응답으로 내려가 초기 출발점 정보로 활용됩니다.
     *
     * @param lat   WGS84 위도(Latitude, 예: 37.319040...)
     * @param lng   WGS84 경도(Longitude, 예: 127.901228...)
     * @param label 화면에 표시할 기준 위치 명칭 (예: "연세대학교 미래캠퍼스 (기본 위치)")
     */
    public record DefaultOrigin(
            double lat,
            double lng,
            String label
    ) {
    }
}
