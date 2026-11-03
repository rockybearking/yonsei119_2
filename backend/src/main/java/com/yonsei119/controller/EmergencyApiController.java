package com.yonsei119.controller;

import com.yonsei119.config.Yonsei119Properties;
import com.yonsei119.dto.DirectionsResponseDto;
import com.yonsei119.dto.EmergencySnapshotDto;
import com.yonsei119.dto.FrontendConfigDto;
import com.yonsei119.service.EmergencyDataService;
import com.yonsei119.service.NaverDirectionsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ============================================================================
 * [클래스 역할]
 * 프론트엔드 웹 애플리케이션(`frontend/app.js`)과 통신하는 핵심 REST API 컨트롤러입니다.
 * <p>
 * [담당 기능]
 * 1. `GET /api/health` : Render 클라우드 배포 헬스체크 및 서버 상태 확인 엔드포인트
 * 2. `GET /api/config` : 네이버 지도 JS v3 공개 Client ID(`ncpKeyId`) 및 초기 기준 위치 반환
 * 3. `GET /api/hospitals` : 전국 응급의료기관 통합 실시간 데이터(7개 OpenAPI 병합본) 반환
 * 4. `GET /api/directions` : 네이버 Directions 5 및 Directions 15 기반 자동차 경로 및 예상 소요 시간 조회
 * <p>
 * [수정 시 영향 및 예시]
 * - API 버저닝을 도입하여 모든 엔드포인트 경로를 `/api/v1/...`으로 변경하고 싶다면
 *   클래스 상단의 `@RequestMapping("/api")`를 `@RequestMapping("/api/v1")`로 변경하고
 *   `frontend/app.js`의 호출 경로를 맞추면 됩니다.
 * ============================================================================
 */
@RestController
@RequestMapping("/api")
public class EmergencyApiController {

    private final Yonsei119Properties properties;
    private final EmergencyDataService emergencyDataService;
    private final NaverDirectionsService naverDirectionsService;

    public EmergencyApiController(
            Yonsei119Properties properties,
            EmergencyDataService emergencyDataService,
            NaverDirectionsService naverDirectionsService
    ) {
        this.properties = properties;
        this.emergencyDataService = emergencyDataService;
        this.naverDirectionsService = naverDirectionsService;
    }

    /**
     * [메서드 역할]
     * Render 클라우드 플랫폼의 Zero-Downtime 배포 헬스체크(`healthCheckPath: /api/health`) 및
     * 운영 상태 점검을 위한 엔드포인트입니다.
     *
     * @return 서버 상태(`UP`), 현재 시각, Java 런타임 버전 정보를 담은 JSON 응답
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "yonsei119-backend");
        body.put("timestamp", Instant.now().toString());
        body.put("javaVersion", Runtime.version().toString());
        return ResponseEntity.ok(body);
    }

    /**
     * [메서드 역할]
     * 프론트엔드가 초기 구동될 때 필요한 공개 설정(네이버 지도 Client ID 및 기본 중심 좌표)을 반환합니다.
     *
     * @return {@link FrontendConfigDto} JSON 응답
     */
    @GetMapping("/config")
    public ResponseEntity<FrontendConfigDto> getConfig() {
        FrontendConfigDto config = new FrontendConfigDto(
                properties.naver().clientId(),
                properties.naver().darkStyleId(),
                properties.defaultOrigin().lat(),
                properties.defaultOrigin().lng(),
                properties.defaultOrigin().label()
        );
        return ResponseEntity.ok(config);
    }

    /**
     * [메서드 역할]
     * 국립중앙의료원(NEMC) 7개 OpenAPI가 통합·분류된 전국 응급의료기관 실시간 스냅샷을 반환합니다.
     * <p>
     * [파라미터 설명]
     * - `refresh=false` (기본값): 캐시 유효 시간(TTL) 이내이면 인메모리 캐시 스냅샷을 즉시 반환합니다.
     * - `refresh=true` : 사용자가 우측 하단 '새로고침' 버튼을 눌렀을 때 캐시를 강제 갱신합니다.
     *
     * @param refresh 강제 새로고침 여부
     * @return {@link EmergencySnapshotDto} JSON 응답
     */
    @GetMapping("/hospitals")
    public ResponseEntity<EmergencySnapshotDto> getHospitals(
            @RequestParam(name = "refresh", defaultValue = "false") boolean refresh
    ) {
        EmergencySnapshotDto snapshot = emergencyDataService.getSnapshot(refresh);
        return ResponseEntity.ok(snapshot);
    }

    /**
     * [메서드 역할]
     * 네이버 클라우드 플랫폼 **Directions 5** 또는 **Directions 15** API를 호출하여
     * 현재 출발 기준점에서 선택한 응급의료기관까지의 자동차 주행 경로와 예상 소요 시간(ETA)을 반환합니다.
     * <p>
     * [호출 예시]
     * {@code GET /api/directions?startLat=37.31904&startLng=127.90123&goalLat=37.31912&goalLng=127.94561&apiType=directions5&option=trafast}
     *
     * @param startLat  출발지 위도 (WGS84)
     * @param startLng  출발지 경도 (WGS84)
     * @param goalLat   도착 병원 위도 (WGS84)
     * @param goalLng   도착 병원 경도 (WGS84)
     * @param apiType   사용할 네이버 API (`"directions5"` 또는 `"directions15"`, 기본값 `"directions5"`)
     * @param option    탐색 옵션 (`"trafast"`, `"traoptimal"`, `"tracomfort"`, `"traavoidtoll"`, 기본값 `"trafast"`)
     * @param waypoints 선택적 경유지 목록 (`"경도1,위도1|경도2,위도2"`)
     * @return {@link DirectionsResponseDto} JSON 응답
     */
    @GetMapping("/directions")
    public ResponseEntity<DirectionsResponseDto> getDirections(
            @RequestParam(name = "startLat") double startLat,
            @RequestParam(name = "startLng") double startLng,
            @RequestParam(name = "goalLat") double goalLat,
            @RequestParam(name = "goalLng") double goalLng,
            @RequestParam(name = "apiType", defaultValue = "directions5") String apiType,
            @RequestParam(name = "option", defaultValue = "trafast") String option,
            @RequestParam(name = "waypoints", defaultValue = "") String waypoints
    ) {
        DirectionsResponseDto result = naverDirectionsService.getDrivingRoute(
                startLat, startLng, goalLat, goalLng, apiType, option, waypoints
        );
        return ResponseEntity.ok(result);
    }
}
