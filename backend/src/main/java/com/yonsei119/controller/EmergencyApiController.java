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
 * 프론트엔드 웹 애플리케이션({@code frontend/app.js})이 {@code fetch('/api/...')}로 보내는 모든 HTTP 요청을
 * 가장 먼저 받아서 알맞은 서비스({@code service/*.java})로 전달하고, 결과를 JSON으로 응답하는
 * 백엔드의 **관문(REST API Controller)** 클래스입니다.
 * <p>
 * [파이썬 장고(Django) 비유 — 백엔드 입문자 핵심 포인트!]
 * - 장고에서는 URL 주소를 연결하는 **{@code urls.py}** 파일과, 요청을 받아 처리하는 **{@code views.py}** 파일이 나뉘어 있습니다.
 *   - 장고 {@code urls.py} 예시: {@code path("api/hospitals", views.get_hospitals)}
 *   - 장고 {@code views.py} 예시: {@code def get_hospitals(request): refresh = request.GET.get("refresh", "false") ... return JsonResponse(...)}
 * - Spring Boot에서는 클래스 위의 {@code @RequestMapping("/api")}와 메서드 위의 {@code @GetMapping("/hospitals")} 어노테이션이
 *   **장고의 {@code urls.py} + {@code views.py} 역할을 한 곳에서 직관적으로 수행**합니다!
 * - 또한 컨트롤러 안에 복잡한 계산 로직을 직접 적지 않고 {@code EmergencyDataService}, {@code NaverDirectionsService}를 호출하는데,
 *   이는 장고 실무에서 {@code views.py}를 얇게 유지하고 비즈니스 로직을 {@code services.py}로 분리하는 설계 패턴과 정확히 같습니다.
 * <p>
 * [전체 API 경로성 요약표 (프론트엔드 ↔ 컨트롤러 ↔ 서비스 ↔ 반환 DTO)]
 * 1. {@code GET /api/health}          : Render 배포 헬스체크 / 모니터링 → 자체 {@code Map} JSON 반환
 * 2. {@code GET /api/config}          : 초기 지도 인증키·기본위치 조회 → {@code Yonsei119Properties} → {@link FrontendConfigDto}
 * 3. {@code GET /api/hospitals}       : {@code app.js:fetchData()} → {@code EmergencyDataService.getSnapshot()} → {@link EmergencySnapshotDto}
 * 4. {@code GET /api/directions}      : {@code app.js:requestDirections()} / {@code refreshActiveRoute()} → {@code NaverDirectionsService.getDrivingRoute()} → {@link DirectionsResponseDto}
 * 5. {@code GET /api/directions/rank} : {@code app.js:loadEtaRanking()} → {@code NaverDirectionsService.rankHospitalsByDrivingTime()} → {@code List<HospitalEtaRankDto>}
 * 6. {@code GET /api/reverse-geocode} : {@code app.js:updateOriginAddress()} → {@code NaverDirectionsService.reverseGeocode()} → {@link ReverseGeocodeResponseDto}
 * 7. {@code GET /api/geocode}         : {@code app.js:geocodeAndMoveOrigin()} → {@code NaverDirectionsService.geocode()} → {@link GeocodeResponseDto}
 * <p>
 * [수정 시 영향 및 예시]
 * - 만약 모든 API 주소 앞에 버전({@code /api/v1/...})을 붙이고 싶다면:
 *   예시(변경 전): {@code @RequestMapping("/api")}
 *   예시(변경 후): {@code @RequestMapping("/api/v1")}
 *   (주의: 이렇게 바꾸면 프론트엔드 {@code frontend/app.js}의 {@code fetch('/api/...')} 경로들도 모두 {@code /api/v1/...}로 함께 맞춰주어야 합니다!)
 * ============================================================================
 */
@RestController
@RequestMapping("/api")
public class EmergencyApiController {

    /** {@code application.yml} 설정값을 담고 있는 불변 프로퍼티 객체 ({@code config/Yonsei119Properties.java}에서 주입됨) */
    private final Yonsei119Properties properties;

    /** 전국 응급실 공공데이터 수집·병합·캐싱 담당 서비스 ({@code service/EmergencyDataService.java}에서 주입됨) */
    private final EmergencyDataService emergencyDataService;

    /** 네이버 자동차 경로탐색(Directions 5/15) 및 지오코딩 담당 서비스 ({@code service/NaverDirectionsService.java}에서 주입됨) */
    private final NaverDirectionsService naverDirectionsService;

    /**
     * [생성자 역할 — 의존성 주입(Dependency Injection, DI)]
     * Spring이 서버 시작 시 미리 만들어 둔 싱글톤(Singleton) 객체 3개를 이 컨트롤러에 자동으로 꽂아줍니다.
     * (파이썬에서 직접 {@code EmergencyDataService()}를 매번 {@code new}로 생성하지 않고 공용 인스턴스를 주입받는 구조입니다.)
     */
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
     * [API 1] {@code GET /api/health} — 서버 생존 여부 확인(Health Check) 엔드포인트
     * <p>
     * [코드의 경로성]
     * - [어디서 호출하는가 (Incoming)] : Render 클라우드 헬스체커, 브라우저 직접 접속, {@code Yonsei119BackendTests.healthEndpointReturnsUp()}
     * - [어디로 가는가 (Outgoing)] : 별도 서비스 호출 없이 서버 현재 시각과 Java 버전을 담아 즉시 HTTP 200 JSON으로 반환
     * <p>
     * [수정 시 영향 및 예시]
     * - 응답 JSON에 현재 캐시된 병원 수({@code cachedHospitalCount})를 추가로 보여주고 싶다면:
     *   {@code body.put("cachedHospitalCount", emergencyDataService.getSnapshot(false).totalCount());} 한 줄을 추가하면 됩니다.
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
     * [API 2] {@code GET /api/config} — 프론트엔드 공개 환경설정 조회 엔드포인트
     * <p>
     * [코드의 경로성]
     * - [어디서 호출하는가 (Incoming)] : 프론트엔드 초기화/점검 또는 {@code Yonsei119BackendTests.configEndpointExposesPublicClientIdOnly()}
     * - [어디를 참조하는가 (Outgoing)] :
     *   {@code application.yml} → {@code config/Yonsei119Properties.java} → {@code dto/FrontendConfigDto.java}로 변환 후 JSON 반환
     * - [보안 핵심] : {@code properties.naver().clientSecret()}은 절대 담지 않고 공개용 {@code clientId}와 {@code darkStyleId}만 내려보냅니다.
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
     * [API 3] {@code GET /api/hospitals?refresh=false|true} — 전국 응급의료기관 실시간 통합 스냅샷 조회
     * <p>
     * [코드의 경로성]
     * - [어디서 왔는가 (Incoming)] :
     *   프론트엔드 {@code frontend/app.js}의 {@code fetchData(forceRefresh)} ({@code loadData(false)} 최초 로딩 및 상단 {@code '새로고침'} 버튼 {@code loadData(true)})
     * - [어디로 가는가 (Outgoing)] :
     *   {@code service/EmergencyDataService.java}의 {@link EmergencyDataService#getSnapshot(boolean)} 호출
     *   → 캐시가 유효하면 메모리에서 즉시 반환, 만료되었거나 {@code refresh=true}이면 {@code service/NmcOpenApiClient.java}로 7개 OpenAPI 병렬 수집 후
     *   → {@code dto/EmergencySnapshotDto.java} (내부에 530여 개 {@code dto/HospitalDto.java} 리스트 포함) JSON을 프론트엔드로 응답!
     * <p>
     * [수정 시 영향 및 예시]
     * - 쿼리 파라미터 이름을 {@code ?refresh=true} 대신 {@code ?force=true}로 바꾸고 싶다면:
     *   예시(변경 전): {@code @RequestParam(name = "refresh", defaultValue = "false") boolean refresh}
     *   예시(변경 후): {@code @RequestParam(name = "force", defaultValue = "false") boolean refresh}
     *   (단, {@code frontend/app.js}의 {@code fetchData()} 안의 {@code ?refresh=true} 문자열도 함께 수정해야 합니다.)
     */
    @GetMapping("/hospitals")
    public ResponseEntity<EmergencySnapshotDto> getHospitals(
            @RequestParam(name = "refresh", defaultValue = "false") boolean refresh
    ) {
        EmergencySnapshotDto snapshot = emergencyDataService.getSnapshot(refresh);
        return ResponseEntity.ok(snapshot);
    }

    /**
     * [API 4] {@code GET /api/directions} — 네이버 자동차 경로 탐색 (Directions 5 / Directions 15 프록시)
     * <p>
     * [코드의 경로성]
     * - [어디서 왔는가 (Incoming)] :
     *   1) 병원 상세 시트의 {@code '소요시간·요금 조회'} 및 {@code '지도에 경로 표시'} 버튼 → {@code frontend/app.js}의 {@code requestDirections(drawOnMap)}
     *   2) 지도 우클릭(모바일 롱탭)으로 출발지를 옮겼을 때 실시간 경로 자동 재계산 → {@code frontend/app.js}의 {@code refreshActiveRoute()}
     * - [어디로 가는가 (Outgoing)] :
     *   {@code service/NaverDirectionsService.java}의 {@link NaverDirectionsService#getDrivingRoute} 호출
     *   → 네이버 클라우드 Directions 5 또는 15 API 서버 통신 (혼잡도 구간 {@code congestion} 및 대체 경로 비교 포함)
     *   → {@code dto/DirectionsResponseDto.java} JSON 응답 → {@code app.js}의 {@code renderDirectionsResult()} 및 {@code drawRouteOnMap()}이 지도에 렌더링!
     * <p>
     * [수정 시 영향 및 예시]
     * - 기본 탐색 옵션을 실시간 빠른길({@code trafast}) 대신 실시간 최적({@code traoptimal})으로 바꾸고 싶다면:
     *   예시(변경 전): {@code @RequestParam(name = "option", defaultValue = "trafast") String option}
     *   예시(변경 후): {@code @RequestParam(name = "option", defaultValue = "traoptimal") String option}
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

    /**
     * [API 5] {@code GET /api/directions/rank} — 주변 응급실 실도로 최단 도착시간(ETA) 병렬 순위 추천
     * <p>
     * [코드의 경로성]
     * - [어디서 왔는가 (Incoming)] :
     *   좌측 검색 패널의 {@code '실도로 최단시간 순위'} 버튼 클릭(또는 우클릭 출발지 변경 시 자동 갱신) → {@code frontend/app.js}의 {@code loadEtaRanking()}
     * - [어디로 가는가 (Outgoing)] :
     *   1) 먼저 {@code EmergencyDataService.getSnapshot(false)}로 현재 메모리에 캐싱된 전국 병원 목록을 가져옵니다.
     *   2) 그 병원 목록을 {@code NaverDirectionsService.rankHospitalsByDrivingTime(...)}에 넘겨 반경 내 후보 병원들의 실제 도로 주행 시간을 병렬 계산합니다.
     *   3) 소요시간 오름차순으로 정렬된 {@code List<HospitalEtaRankDto>}({@code dto/HospitalEtaRankDto.java})를 반환하면 {@code app.js}가 순위 카드를 그립니다.
     * <p>
     * [수정 시 영향 및 예시]
     * - 추천 순위 기본 표시 개수를 5개에서 8개로 늘리고 싶다면:
     *   예시(변경 전): {@code @RequestParam(name = "limit", defaultValue = "5") int limit}
     *   예시(변경 후): {@code @RequestParam(name = "limit", defaultValue = "8") int limit}
     *   (그리고 {@code frontend/app.js}의 {@code loadEtaRanking()}에서 보내는 {@code limit: '5'} 파라미터도 {@code '8'}로 바꾸면 8개가 표시됩니다.)
     */
    @GetMapping("/directions/rank")
    public ResponseEntity<List<HospitalEtaRankDto>> rankDirections(
            @RequestParam(name = "startLat") double startLat,
            @RequestParam(name = "startLng") double startLng,
            @RequestParam(name = "radiusKm", defaultValue = "20") double radiusKm,
            @RequestParam(name = "limit", defaultValue = "5") int limit,
            @RequestParam(name = "includeOther", defaultValue = "false") boolean includeOther,
            @RequestParam(name = "option", defaultValue = "trafast") String option
    ) {
        EmergencySnapshotDto snapshot = emergencyDataService.getSnapshot(false);
        List<HospitalEtaRankDto> ranked = naverDirectionsService.rankHospitalsByDrivingTime(
                snapshot.hospitals(), startLat, startLng, radiusKm, limit, includeOther, option
        );
        return ResponseEntity.ok(ranked);
    }

    /**
     * [API 6] {@code GET /api/reverse-geocode} — 위경도 좌표를 한국어 도로명/지번 주소로 변환 (역지오코딩)
     * <p>
     * [코드의 경로성]
     * - [어디서 왔는가 (Incoming)] :
     *   프론트엔드 {@code frontend/app.js}의 {@code updateOriginAddress()} (GPS 현재 위치 확인 시, 지도 우클릭/롱탭 시, 출발지 초기화 시 호출)
     * - [어디로 가는가 (Outgoing)] :
     *   {@code service/NaverDirectionsService.java}의 {@link NaverDirectionsService#reverseGeocode(double, double)} 호출
     *   → NCP Reverse Geocoding API 조회 → {@code dto/ReverseGeocodeResponseDto.java} JSON 반환 → {@code #origin-address-text}에 주소 표시!
     */
    @GetMapping("/reverse-geocode")
    public ResponseEntity<ReverseGeocodeResponseDto> reverseGeocode(
            @RequestParam(name = "lat") double lat,
            @RequestParam(name = "lng") double lng
    ) {
        return ResponseEntity.ok(naverDirectionsService.reverseGeocode(lat, lng));
    }

    /**
     * [API 7] {@code GET /api/geocode} — 주소·지역·장소 검색어를 위경도 좌표로 변환 (지오코딩)
     * <p>
     * [코드의 경로성]
     * - [어디서 왔는가 (Incoming)] :
     *   좌측 상단 검색창({@code #keyword})에 지역명/주소를 입력 후 엔터 또는 항목 클릭 시 → {@code frontend/app.js}의 {@code geocodeAndMoveOrigin(query)}
     * - [어디로 가는가 (Outgoing)] :
     *   {@code EmergencyDataService.getSnapshot(false)}의 병원 주소록과 함께 {@code NaverDirectionsService.geocode(query, hospitals)}를 호출
     *   → NCP Geocoding API + 내장 지명/병원 매칭 폴백 → {@code dto/GeocodeResponseDto.java} JSON 반환 → 지도가 해당 좌표로 이동!
     */
    @GetMapping("/geocode")
    public ResponseEntity<GeocodeResponseDto> geocode(
            @RequestParam(name = "query") String query
    ) {
        EmergencySnapshotDto snapshot = emergencyDataService.getSnapshot(false);
        return ResponseEntity.ok(naverDirectionsService.geocode(query, snapshot.hospitals()));
    }
}
