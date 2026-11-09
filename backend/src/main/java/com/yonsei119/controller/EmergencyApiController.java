package com.yonsei119.controller;

import com.yonsei119.config.Yonsei119Properties;
import com.yonsei119.dto.DirectionsResponseDto;
import com.yonsei119.dto.EmergencySnapshotDto;
import com.yonsei119.dto.FrontendConfigDto;
import com.yonsei119.dto.GeocodeResponseDto;
import com.yonsei119.dto.HospitalEtaRankDto;
import com.yonsei119.dto.ReverseGeocodeResponseDto;
import com.yonsei119.service.EmergencyDataService;
import com.yonsei119.service.NaverDirectionsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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
 * 4. `GET /api/directions` : 네이버 Directions 5 및 Directions 15 기반 자동차 경로 및 다중 옵션 비교 조회
 * 5. `GET /api/directions/rank` : 주변 후보 병원들의 실제 도로 주행 소요시간(ETA) 병렬 비교 및 최단시간 추천
 * 6. `GET /api/reverse-geocode` : 위경도 좌표를 한국어 도로명/행정동 주소로 변환 (NCP Reverse Geocoding)
 * 7. `GET /api/geocode` : 주소/지역명을 위경도 좌표로 변환 (NCP Geocoding + 병원/장소 폴백)
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

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "yonsei119-backend");
        body.put("timestamp", Instant.now().toString());
        body.put("javaVersion", Runtime.version().toString());
        return ResponseEntity.ok(body);
    }

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

    @GetMapping("/hospitals")
    public ResponseEntity<EmergencySnapshotDto> getHospitals(
            @RequestParam(name = "refresh", defaultValue = "false") boolean refresh
    ) {
        EmergencySnapshotDto snapshot = emergencyDataService.getSnapshot(refresh);
        return ResponseEntity.ok(snapshot);
    }

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

    /**
     * [메서드 역할]
     * 현재 출발지(`startLat`, `startLng`) 기준 반경 내 후보 병원들에 대해
     * 네이버 Directions 5 API를 병렬 호출하여 **실제 도로 주행 소요시간(ETA)이 가장 짧은 순서대로** 추천 목록을 반환합니다.
     */
    @GetMapping("/directions/rank")
    public ResponseEntity<List<HospitalEtaRankDto>> rankDirections(
            @RequestParam(name = "startLat") double startLat,
            @RequestParam(name = "startLng") double startLng,
            @RequestParam(name = "radiusKm", defaultValue = "20") double radiusKm,
            @RequestParam(name = "limit", defaultValue = "5") int limit,
            @RequestParam(name = "includeOther", defaultValue = "false") boolean includeOther
    ) {
        EmergencySnapshotDto snapshot = emergencyDataService.getSnapshot(false);
        List<HospitalEtaRankDto> ranked = naverDirectionsService.rankHospitalsByDrivingTime(
                snapshot.hospitals(), startLat, startLng, radiusKm, limit, includeOther
        );
        return ResponseEntity.ok(ranked);
    }

    /**
     * [메서드 역할]
     * 네이버 클라우드 플랫폼 Reverse Geocoding API를 호출하여 좌표를 한국어 주소로 변환합니다.
     */
    @GetMapping("/reverse-geocode")
    public ResponseEntity<ReverseGeocodeResponseDto> reverseGeocode(
            @RequestParam(name = "lat") double lat,
            @RequestParam(name = "lng") double lng
    ) {
        return ResponseEntity.ok(naverDirectionsService.reverseGeocode(lat, lng));
    }

    /**
     * [메서드 역할]
     * 네이버 클라우드 플랫폼 Geocoding API 및 병원/장소 데이터를 활용하여 주소/지역명 검색어를 좌표로 변환합니다.
     */
    @GetMapping("/geocode")
    public ResponseEntity<GeocodeResponseDto> geocode(
            @RequestParam(name = "query") String query
    ) {
        EmergencySnapshotDto snapshot = emergencyDataService.getSnapshot(false);
        return ResponseEntity.ok(naverDirectionsService.geocode(query, snapshot.hospitals()));
    }
}
