package com.yonsei119.service;

import com.yonsei119.config.Yonsei119Properties;
import com.yonsei119.dto.DirectionsResponseDto;
import com.yonsei119.dto.GeocodeResponseDto;
import com.yonsei119.dto.HospitalDto;
import com.yonsei119.dto.HospitalEtaRankDto;
import com.yonsei119.dto.ReverseGeocodeResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * ============================================================================
 * [클래스 역할]
 * 네이버 클라우드 플랫폼(NCP)의 **Directions 5**, **Directions 15**, **Reverse Geocoding**,
 * **Geocoding** API를 백엔드에서 안전하게 대신 호출(프록시)하고 인메모리 캐싱하는 **통합 위치·경로 서비스**입니다.
 * <p>
 * [파이썬 장고(Django) 비유]
 * - 장고 프로젝트의 **{@code services/naver_maps_service.py}** 파일과 동일한 위치의 비즈니스 서비스입니다!
 * - 왜 브라우저({@code app.js})에서 네이버 길찾기 API를 직접 안 부르고 백엔드({@code NaverDirectionsService})를 거치나요?
 *   1) **보안**: 네이버 Directions/Geocoding REST API는 비공개 비밀키({@code X-NCP-APIGW-API-KEY} = {@code clientSecret})가 필수인데,
 *      이를 프론트엔드 JS에 적으면 누구나 F12 개발자 도구로 키를 훔쳐 쓸 수 있기 때문입니다.
 *   2) **CORS 차단 우회**: 네이버 Directions REST API는 브라우저 직접 호출(CORS)을 막아두었으므로 반드시 백엔드 서버가 호출해야 합니다.
 *   3) **공용 캐싱**: 여러 사용자가 비슷한 경로/주소를 조회할 때 {@code ConcurrentHashMap} 캐시({@code routeCache}, {@code reverseGeocodeCache})로 즉시 응답합니다.
 * <p>
 * [코드의 경로성 (어디서 오고 어디로 가는가?)]
 * - [설정 주입 (Config Source)] : {@code application.yml} ({@code yonsei119.naver.*}) → {@code config/Yonsei119Properties.java}
 * - [호출 출발지 (Incoming — {@code controller/EmergencyApiController.java})] :
 *   1) {@code GET /api/directions}      → {@link #getDrivingRoute} 호출 → {@code dto/DirectionsResponseDto.java} 반환 → {@code app.js:requestDirections()} / {@code refreshActiveRoute()}
 *   2) {@code GET /api/directions/rank} → {@link #rankHospitalsByDrivingTime} 호출 → {@code List<HospitalEtaRankDto>} 반환 → {@code app.js:loadEtaRanking()}
 *   3) {@code GET /api/reverse-geocode} → {@link #reverseGeocode} 호출 → {@code dto/ReverseGeocodeResponseDto.java} 반환 → {@code app.js:updateOriginAddress()}
 *   4) {@code GET /api/geocode}         → {@link #geocode} 호출 → {@code dto/GeocodeResponseDto.java} 반환 → {@code app.js:searchAddressAndMove()}
 * <p>
 * [수정 시 영향 및 예시]
 * - 실시간 교통상황이 반영되는 길찾기 캐시 유지 시간({@code ROUTE_CACHE_TTL_SECONDS}, 기본 60초)을 30초로 줄이고 싶다면:
 *   예시(변경 전): {@code private static final long ROUTE_CACHE_TTL_SECONDS = 60L;}
 *   예시(변경 후): {@code private static final long ROUTE_CACHE_TTL_SECONDS = 30L;}
 * ============================================================================
 */
@Service
public class NaverDirectionsService {

    private static final Logger log = LoggerFactory.getLogger(NaverDirectionsService.class);

    /** 동일 출발·도착·옵션 경로 재조회 시 캐시 유지 시간(초, 기본 60초 — 실시간 교통 반영과 API 비용 절감의 균형점) */
    private static final long ROUTE_CACHE_TTL_SECONDS = 60L;

    /** 지오코딩/역지오코딩 캐시 유지 시간(초, 기본 600초 = 10분 — 도로명 주소는 자주 변하지 않으므로 길게 유지) */
    private static final long GEOCODE_CACHE_TTL_SECONDS = 600L;

    /** 네이버 클라우드 Reverse Geocoding(좌표 -> 주소) 공식 엔드포인트 */
    private static final String REVERSE_GEOCODE_URL = "https://maps.apigw.ntruss.com/map-reversegeocode/v2/gc";
    /** 네이버 클라우드 Geocoding(주소 -> 좌표) 공식 엔드포인트 */
    private static final String GEOCODE_URL = "https://maps.apigw.ntruss.com/map-geocode/v2/geocode";

    /**
     * 네이버 Directions API 탐색 옵션 코드 → 화면 표시 한글 라벨 매핑 사전.
     * - [수정 예시] {@code "무료 우선"}이라는 문구를 {@code "톨게이트 무료"}로 바꾸고 싶다면 {@code "traavoidtoll", "톨게이트 무료"}로 수정하세요.
     */
    public static final Map<String, String> OPTION_LABELS = Map.of(
            "trafast", "실시간 빠른길",
            "traoptimal", "실시간 최적",
            "tracomfort", "실시간 편한길",
            "traavoidtoll", "무료 우선",
            "traavoidcaronly", "자동차전용 회피"
    );

    /** 네이버 Directions API 4대 핵심 탐색 옵션 목록 (실시간 빠른길·실시간 최적·실시간 편한길·무료 우선) */
    private static final List<String> ALL_FOUR_OPTIONS = List.of(
            "trafast", "traoptimal", "tracomfort", "traavoidtoll"
    );

    private final Yonsei119Properties properties;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final ConcurrentHashMap<String, CachedRoute> routeCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedReverseGeocode> reverseGeocodeCache = new ConcurrentHashMap<>();

    public NaverDirectionsService(Yonsei119Properties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.jsonMapper = JsonMapper.builder().build();
    }

    /**
     * [메서드 역할 — 자동차 경로 탐색 핵심 진입점]
     * 출발지({@code startLat}, {@code startLng})에서 목표 병원({@code goalLat}, {@code goalLng})까지의 실시간 자동차 주행 경로를 조회합니다.
     * <p>
     * [코드의 경로성]
     * - [어디서 왔는가 (Incoming)] :
     *   1) {@code EmergencyApiController.getDirections()} ({@code GET /api/directions}) ← {@code frontend/app.js}의 {@code requestDirections()} 및 {@code refreshActiveRoute()}
     *   2) 같은 파일 아래의 {@link #rankHospitalsByDrivingTime} 내부 병렬 ETA 계산 루프
     * - [어디로 가는가 (Outgoing)] :
     *   {@link #sendDirectionsHttpRequest}로 네이버 API 서버 통신 → {@link #parseDirectionsJson}으로 파싱 →
     *   {@code dto/DirectionsResponseDto.java} 반환!
     * <p>
     * [중요 좌표 순서 주의사항!]
     * - 네이버 Directions REST API 쿼리 파라미터({@code start=경도,위도})는 일반적인 {@code (위도, 경도)}와 반대인 **{@code 경도(lng),위도(lat)} 순서**입니다!
     *   ({@code line 141}: {@code String.format(Locale.US, "%.6f,%.6f", startLng, startLat)} — 순서를 절대 바꾸지 마세요!)
     */
    public DirectionsResponseDto getDrivingRoute(
            double startLat,
            double startLng,
            double goalLat,
            double goalLng,
            String apiType,
            String option,
            String waypoints
    ) {
        String normalizedApi = normalizeApiType(apiType);
        String normalizedOption = normalizeOption(option);
        String safeWaypoints = waypoints != null ? waypoints.trim() : "";

        String cacheKey = buildRouteCacheKey(
                normalizedApi, normalizedOption, startLat, startLng, goalLat, goalLng, safeWaypoints
        );

        CachedRoute cached = routeCache.get(cacheKey);
        if (cached != null && Instant.now().isBefore(cached.createdAt().plusSeconds(ROUTE_CACHE_TTL_SECONDS))) {
            return cached.response();
        }

        String baseEndpoint = "directions15".equals(normalizedApi)
                ? properties.naver().directions15Url()
                : properties.naver().directions5Url();
        String apiLabel = "directions15".equals(normalizedApi) ? "Directions 15" : "Directions 5";
        String optionLabel = OPTION_LABELS.getOrDefault(normalizedOption, "실시간 빠른길");

        // 네이버 Directions API는 1회 요청당 최대 3개 옵션까지 콜론(:) 구분자로 동시 조회를 지원하므로,
        // 4대 핵심 옵션(실시간 빠른길·실시간 최적·실시간 편한길·무료 우선) 전체를 한 번에 비교할 수 있도록
        // [배치 1: trafast:traoptimal:tracomfort] + [배치 2: traavoidtoll] 2건을 비동기 병렬 호출하여 병합합니다.
        Set<String> allRequestedOptions = new LinkedHashSet<>(ALL_FOUR_OPTIONS);
        allRequestedOptions.add(normalizedOption);

        String batch1Options = "trafast:traoptimal:tracomfort";
        String batch2Options = "traavoidcaronly".equals(normalizedOption)
                ? "traavoidtoll:traavoidcaronly"
                : "traavoidtoll";

        String startParam = String.format(Locale.US, "%.6f,%.6f", startLng, startLat);
        String goalParam = String.format(Locale.US, "%.6f,%.6f", goalLng, goalLat);

        try {
            CompletableFuture<HttpResponse<String>> future1 = CompletableFuture.supplyAsync(
                    () -> sendDirectionsHttpRequest(baseEndpoint, startParam, goalParam, batch1Options, safeWaypoints)
            );
            CompletableFuture<HttpResponse<String>> future2 = CompletableFuture.supplyAsync(
                    () -> sendDirectionsHttpRequest(baseEndpoint, startParam, goalParam, batch2Options, safeWaypoints)
            );

            HttpResponse<String> response1 = future1.join();
            HttpResponse<String> response2 = future2.join();

            HttpResponse<String> primaryResponse = (response1 != null && response1.statusCode() >= 200 && response1.statusCode() < 300)
                    ? response1
                    : response2;

            if (primaryResponse == null) {
                return createErrorResponse(
                        normalizedApi, apiLabel, normalizedOption, optionLabel,
                        -1,
                        "경로 탐색 요청 중 네트워크 오류가 발생했습니다."
                );
            }

            if (primaryResponse.statusCode() < 200 || primaryResponse.statusCode() >= 300) {
                log.warn("네이버 {} HTTP 오류: status={}, body={}",
                        apiLabel, primaryResponse.statusCode(), primaryResponse.body());
                return createErrorResponse(
                        normalizedApi, apiLabel, normalizedOption, optionLabel,
                        primaryResponse.statusCode(),
                        "네이버 경로 탐색 서버 응답 오류 (HTTP " + primaryResponse.statusCode() + ")"
                );
            }

            String mergedBody = mergeDirectionsJsonBodies(
                    response1 != null && response1.statusCode() >= 200 && response1.statusCode() < 300 ? response1.body() : null,
                    response2 != null && response2.statusCode() >= 200 && response2.statusCode() < 300 ? response2.body() : null
            );
            Instant now = Instant.now();

            // 병합된 응답에서 4대 옵션 전체를 각각 파싱하여 캐시에 선제 저장
            for (String optKey : allRequestedOptions) {
                String label = OPTION_LABELS.getOrDefault(optKey, "실시간 빠른길");
                DirectionsResponseDto optDto = parseDirectionsJson(
                        mergedBody, normalizedApi, apiLabel, optKey, label
                );
                if (optDto.code() == 0 && !optDto.path().isEmpty()) {
                    String optCacheKey = buildRouteCacheKey(
                            normalizedApi, optKey, startLat, startLng, goalLat, goalLng, safeWaypoints
                    );
                    routeCache.put(optCacheKey, new CachedRoute(optDto, now));
                }
            }

            CachedRoute primaryCached = routeCache.get(cacheKey);
            if (primaryCached != null) {
                return primaryCached.response();
            }

            return parseDirectionsJson(
                    mergedBody, normalizedApi, apiLabel, normalizedOption, optionLabel
            );
        } catch (Exception ex) {
            log.error("네이버 {} 호출 중 예외 발생: {}", apiLabel, ex.getMessage(), ex);
            return createErrorResponse(
                    normalizedApi, apiLabel, normalizedOption, optionLabel,
                    -1,
                    "경로 탐색 요청 중 네트워크 오류가 발생했습니다: " + ex.getMessage()
            );
        }
    }

    private HttpResponse<String> sendDirectionsHttpRequest(
            String baseEndpoint,
            String startParam,
            String goalParam,
            String optionParam,
            String safeWaypoints
    ) {
        try {
            StringBuilder urlBuilder = new StringBuilder(baseEndpoint)
                    .append("?start=").append(URLEncoder.encode(startParam, StandardCharsets.UTF_8))
                    .append("&goal=").append(URLEncoder.encode(goalParam, StandardCharsets.UTF_8))
                    .append("&option=").append(URLEncoder.encode(optionParam, StandardCharsets.UTF_8));

            if (!safeWaypoints.isEmpty()) {
                urlBuilder.append("&waypoints=").append(URLEncoder.encode(safeWaypoints, StandardCharsets.UTF_8));
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlBuilder.toString()))
                    .timeout(Duration.ofSeconds(10))
                    .header("X-NCP-APIGW-API-KEY-ID", properties.naver().clientId())
                    .header("X-NCP-APIGW-API-KEY", properties.naver().clientSecret())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            return httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
        } catch (Exception ex) {
            log.warn("네이버 Directions 배치 호출 실패 (option={}): {}", optionParam, ex.getMessage());
            return null;
        }
    }

    private String mergeDirectionsJsonBodies(String body1, String body2) {
        if (body1 == null || body1.isBlank()) {
            return body2 != null ? body2 : "{}";
        }
        if (body2 == null || body2.isBlank()) {
            return body1;
        }
        try {
            JsonNode root1 = jsonMapper.readTree(body1);
            JsonNode root2 = jsonMapper.readTree(body2);
            if (root1.path("route") instanceof tools.jackson.databind.node.ObjectNode routeObj1
                    && root2.path("route") instanceof tools.jackson.databind.node.ObjectNode routeObj2) {
                for (String fieldName : routeObj2.propertyNames()) {
                    routeObj1.set(fieldName, routeObj2.get(fieldName));
                }
                return jsonMapper.writeValueAsString(root1);
            }
        } catch (Exception ex) {
            log.debug("Directions 응답 병합 생략: {}", ex.getMessage());
        }
        return body1;
    }

    private static String buildRouteCacheKey(
            String apiType,
            String option,
            double startLat,
            double startLng,
            double goalLat,
            double goalLng,
            String waypoints
    ) {
        return String.format(
                Locale.US,
                "%s|%s|%.5f,%.5f|%.5f,%.5f|%s",
                apiType, option, startLat, startLng, goalLat, goalLng, waypoints
        );
    }

    /**
     * [메서드 역할]
     * 네이버 Directions 5 / 15 JSON 응답 본문을 파싱하여 {@link DirectionsResponseDto}로 변환합니다.
     * 응답에 여러 탐색 옵션({@code trafast}, {@code traoptimal}, {@code traavoidtoll} 등)이 함께 들어 있는 경우
     * {@code alternatives} 비교 요약 리스트도 함께 생성합니다.
     */
    public DirectionsResponseDto parseDirectionsJson(
            String jsonBody,
            String apiType,
            String apiLabel,
            String option,
            String optionLabel
    ) {
        try {
            JsonNode root = jsonMapper.readTree(jsonBody);
            int code = root.path("code").asInt(-1);
            String message = root.path("message").asString("");

            if (code != 0) {
                return createErrorResponse(apiType, apiLabel, option, optionLabel, code, message);
            }

            JsonNode routeNode = root.path("route");

            // 응답 내 존재하는 모든 옵션 요약(alternatives) 추출
            List<DirectionsResponseDto.RouteOptionSummaryDto> alternatives = new ArrayList<>();
            List<String> optionOrder = List.of("trafast", "traoptimal", "tracomfort", "traavoidtoll", "traavoidcaronly");
            for (String optKey : optionOrder) {
                JsonNode arr = routeNode.path(optKey);
                if (arr.isArray() && !arr.isEmpty()) {
                    JsonNode sum = arr.get(0).path("summary");
                    int distM = sum.path("distance").asInt(0);
                    double distKm = Math.round((distM / 100.0)) / 10.0;
                    long durMs = sum.path("duration").asLong(0L);
                    int durMin = Math.max(1, (int) Math.round(durMs / 60000.0));
                    alternatives.add(new DirectionsResponseDto.RouteOptionSummaryDto(
                            optKey,
                            OPTION_LABELS.getOrDefault(optKey, optKey),
                            distM,
                            distKm,
                            durMs,
                            durMin,
                            formatDurationKorean(durMs),
                            sum.path("tollFare").asInt(0),
                            sum.path("taxiFare").asInt(0),
                            sum.path("fuelPrice").asInt(0)
                    ));
                }
            }

            JsonNode optionArray = routeNode.path(option);
            if (!optionArray.isArray() || optionArray.isEmpty()) {
                var fieldNames = routeNode.propertyNames();
                for (String fieldName : fieldNames) {
                    JsonNode candidate = routeNode.path(fieldName);
                    if (candidate.isArray() && !candidate.isEmpty()) {
                        optionArray = candidate;
                        break;
                    }
                }
            }

            if (!optionArray.isArray() || optionArray.isEmpty()) {
                return createErrorResponse(
                        apiType, apiLabel, option, optionLabel,
                        code, "탐색된 도로 경로 데이터가 없습니다."
                );
            }

            JsonNode firstRoute = optionArray.get(0);
            JsonNode summary = firstRoute.path("summary");

            int distanceMeters = summary.path("distance").asInt(0);
            double distanceKm = Math.round((distanceMeters / 100.0)) / 10.0;
            long durationMs = summary.path("duration").asLong(0L);
            int durationMinutes = Math.max(1, (int) Math.round(durationMs / 60000.0));
            String durationText = formatDurationKorean(durationMs);
            String departureTime = summary.path("departureTime").asString("");
            int tollFare = summary.path("tollFare").asInt(0);
            int taxiFare = summary.path("taxiFare").asInt(0);
            int fuelPrice = summary.path("fuelPrice").asInt(0);

            JsonNode pathNode = firstRoute.path("path");
            List<List<Double>> path = new ArrayList<>();
            if (pathNode.isArray()) {
                for (JsonNode coord : pathNode) {
                    if (coord.isArray() && coord.size() >= 2) {
                        double lng = coord.get(0).asDouble();
                        double lat = coord.get(1).asDouble();
                        path.add(List.of(lng, lat));
                    }
                }
            }

            JsonNode sectionNode = firstRoute.path("section");
            JsonNode guideNode = firstRoute.path("guide");

            List<DirectionsResponseDto.RouteSectionDto> sections = buildDetailedSections(
                    sectionNode, guideNode
            );

            List<String> guidePrompts = new ArrayList<>();
            if (guideNode.isArray()) {
                for (JsonNode step : guideNode) {
                    String instructions = step.path("instructions").asString("").trim();
                    if (!instructions.isEmpty()) {
                        guidePrompts.add(instructions);
                    }
                }
            }

            return new DirectionsResponseDto(
                    apiType,
                    apiLabel,
                    option,
                    optionLabel,
                    code,
                    message,
                    distanceMeters,
                    distanceKm,
                    durationMs,
                    durationMinutes,
                    durationText,
                    departureTime,
                    tollFare,
                    taxiFare,
                    fuelPrice,
                    List.copyOf(path),
                    List.copyOf(sections),
                    List.copyOf(guidePrompts),
                    List.copyOf(alternatives)
            );
        } catch (Exception ex) {
            log.warn("네이버 Directions JSON 파싱 오류: {}", ex.getMessage());
            return createErrorResponse(
                    apiType, apiLabel, option, optionLabel,
                    -2, "경로 응답 JSON 파싱에 실패했습니다."
            );
        }
    }

    /**
     * [메서드 역할 — 주변 후보 병원 실도로 주행시간(ETA) 병렬 비교 추천]
     * 직선거리({@code haversineKm})로 1차 후보 병원을 추린 뒤, 각 병원까지 {@code CompletableFuture.supplyAsync}로
     * 네이버 Directions 5 API를 **동시에 병렬 호출**하여 실제 도로 주행 시간이 가장 짧은 순서대로 순위를 매깁니다.
     * <p>
     * [코드의 경로성]
     * - [어디서 왔는가 (Incoming)] : {@code EmergencyApiController.rankDirections()} ({@code GET /api/directions/rank}) ← {@code frontend/app.js:loadEtaRanking()}
     * - [어디로 가는가 (Outgoing)] : {@code List<HospitalEtaRankDto>}({@code dto/HospitalEtaRankDto.java}) 반환 → 좌측 패널 {@code #eta-rank-box} 렌더링!
     * <p>
     * [수정 시 영향 및 예시]
     * - 한 번에 비교할 최대 후보 병원 상한(기본 8개)을 10개로 늘리고 싶다면 아래 {@code safeLimit} 및 {@code .limit(...)}의 {@code 8}을 {@code 10}으로 변경하세요:
     *   예시(변경 전): {@code int safeLimit = Math.max(1, Math.min(limit, 8));}
     *   예시(변경 후): {@code int safeLimit = Math.max(1, Math.min(limit, 10));}
     */
    public List<HospitalEtaRankDto> rankHospitalsByDrivingTime(
            List<HospitalDto> allHospitals,
            double startLat,
            double startLng,
            double radiusKm,
            int limit,
            boolean includeOther
    ) {
        if (allHospitals == null || allHospitals.isEmpty()) {
            return List.of();
        }

        int safeLimit = Math.max(1, Math.min(limit, 8));

        // 1차 필터링: 반경 내 + 종별 필터 + 직선거리 기준 상위 후보 선별
        List<HospitalDto> candidates = allHospitals.stream()
                .filter(h -> {
                    if (!includeOther && h.type() != null && "other".equals(h.type().group()) && !h.regional()) {
                        return false;
                    }
                    if (radiusKm > 0) {
                        return haversineKm(startLat, startLng, h.lat(), h.lng()) <= radiusKm;
                    }
                    return true;
                })
                .sorted(Comparator.comparingDouble(h -> haversineKm(startLat, startLng, h.lat(), h.lng())))
                .limit(Math.min(safeLimit + 2, 8))
                .toList();

        // 반경 내 주요 병원이 없으면 전체 병원(일반 포함) 중 가장 가까운 곳들로 폴백
        if (candidates.isEmpty()) {
            candidates = allHospitals.stream()
                    .sorted(Comparator.comparingDouble(h -> haversineKm(startLat, startLng, h.lat(), h.lng())))
                    .limit(safeLimit)
                    .toList();
        }

        List<CompletableFuture<HospitalEtaRankDto>> futures = candidates.stream()
                .map(hospital -> CompletableFuture.supplyAsync(() -> {
                    double straightKm = Math.round(haversineKm(startLat, startLng, hospital.lat(), hospital.lng()) * 10.0) / 10.0;
                    DirectionsResponseDto route = getDrivingRoute(
                            startLat, startLng, hospital.lat(), hospital.lng(),
                            "directions5", "trafast", ""
                    );

                    String erBeds = hospital.beds() != null
                            ? hospital.beds().getOrDefault("hvec", "")
                            : "";
                    String typeGroup = hospital.regional()
                            ? "regional"
                            : (hospital.type() != null ? hospital.type().group() : "other");
                    String typeLabel = hospital.regional()
                            ? "권역응급의료센터"
                            : (hospital.type() != null ? hospital.type().label() : "응급의료기관");

                    if (route.code() == 0 && route.durationMinutes() > 0) {
                        return new HospitalEtaRankDto(
                                0,
                                hospital.id(),
                                hospital.name(),
                                typeGroup,
                                typeLabel,
                                hospital.regional(),
                                erBeds,
                                straightKm,
                                route.distanceKm(),
                                route.durationMinutes(),
                                route.durationText(),
                                route.tollFare(),
                                route.taxiFare(),
                                hospital.lat(),
                                hospital.lng()
                        );
                    }
                    // 경로 탐색 불가(섬 지역 등) 시 직선거리 기반 추정치 폴백
                    int estMin = Math.max(2, (int) Math.round(straightKm * 1.8));
                    return new HospitalEtaRankDto(
                            0,
                            hospital.id(),
                            hospital.name(),
                            typeGroup,
                            typeLabel,
                            hospital.regional(),
                            erBeds,
                            straightKm,
                            straightKm,
                            estMin,
                            "약 " + estMin + "분(추정)",
                            0,
                            0,
                            hospital.lat(),
                            hospital.lng()
                    );
                }))
                .toList();

        List<HospitalEtaRankDto> sorted = futures.stream()
                .map(CompletableFuture::join)
                .sorted(Comparator
                        .comparingInt(HospitalEtaRankDto::durationMinutes)
                        .thenComparingDouble(HospitalEtaRankDto::drivingDistanceKm))
                .limit(safeLimit)
                .toList();

        AtomicInteger rankCounter = new AtomicInteger(1);
        return sorted.stream()
                .map(item -> new HospitalEtaRankDto(
                        rankCounter.getAndIncrement(),
                        item.id(),
                        item.name(),
                        item.typeGroup(),
                        item.typeLabel(),
                        item.regional(),
                        item.erBeds(),
                        item.straightDistanceKm(),
                        item.drivingDistanceKm(),
                        item.durationMinutes(),
                        item.durationText(),
                        item.tollFare(),
                        item.taxiFare(),
                        item.lat(),
                        item.lng()
                ))
                .toList();
    }

    /**
     * [메서드 역할 — 역지오코딩: 위경도 좌표 → 한국어 도로명/지번 주소 변환]
     * <p>
     * [코드의 경로성]
     * - [어디서 왔는가 (Incoming)] : {@code EmergencyApiController.reverseGeocode()} ({@code GET /api/reverse-geocode}) ← {@code frontend/app.js:updateOriginAddress()}
     * - [어디로 가는가 (Outgoing)] : {@link #parseReverseGeocodeJson} → {@code dto/ReverseGeocodeResponseDto.java} → {@code #origin-address-text} 텍스트 갱신!
     * <p>
     * [성능 최적화]
     * - 좌표를 소수점 넷째 자리({@code %.4f,%.4f} ≈ 약 11m 격자)로 반올림한 캐시 키({@code reverseGeocodeCache})를 사용하여
     *   비슷한 지점을 다시 찍었을 때 외부 API 호출 없이 즉시 응답합니다.
     */
    public ReverseGeocodeResponseDto reverseGeocode(double lat, double lng) {
        String cacheKey = String.format(Locale.US, "%.4f,%.4f", lat, lng);
        CachedReverseGeocode cached = reverseGeocodeCache.get(cacheKey);
        if (cached != null && Instant.now().isBefore(cached.createdAt().plusSeconds(GEOCODE_CACHE_TTL_SECONDS))) {
            return cached.response();
        }

        String coordsParam = String.format(Locale.US, "%.6f,%.6f", lng, lat);
        String url = REVERSE_GEOCODE_URL
                + "?coords=" + URLEncoder.encode(coordsParam, StandardCharsets.UTF_8)
                + "&output=json&orders=roadaddr,addr,admcode";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("X-NCP-APIGW-API-KEY-ID", properties.naver().clientId())
                    .header("X-NCP-APIGW-API-KEY", properties.naver().clientSecret())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                ReverseGeocodeResponseDto parsed = parseReverseGeocodeJson(response.body(), lat, lng);
                if (parsed.code() == 0) {
                    reverseGeocodeCache.put(cacheKey, new CachedReverseGeocode(parsed, Instant.now()));
                }
                return parsed;
            }
        } catch (Exception ex) {
            log.warn("네이버 Reverse Geocoding 호출 오류: {}", ex.getMessage());
        }

        String fallbackCoord = String.format(Locale.US, "위도 %.4f, 경도 %.4f", lat, lng);
        return new ReverseGeocodeResponseDto(-1, "주소 변환 실패", lat, lng, fallbackCoord, fallbackCoord, "", "");
    }

    public ReverseGeocodeResponseDto parseReverseGeocodeJson(String jsonBody, double lat, double lng) {
        try {
            JsonNode root = jsonMapper.readTree(jsonBody);
            int code = root.path("status").path("code").asInt(-1);
            String message = root.path("status").path("message").asString("");

            JsonNode results = root.path("results");
            if (code != 0 || !results.isArray() || results.isEmpty()) {
                String coordLabel = String.format(Locale.US, "위도 %.4f, 경도 %.4f", lat, lng);
                return new ReverseGeocodeResponseDto(code, message, lat, lng, coordLabel, coordLabel, "", "");
            }

            String shortAddr = "";
            String roadAddr = "";
            String jibunAddr = "";

            for (JsonNode item : results) {
                String name = item.path("name").asString("");
                JsonNode region = item.path("region");
                String a1 = region.path("area1").path("name").asString("").trim();
                String a1Short = region.path("area1").path("alias").asString(a1).trim();
                String a2 = region.path("area2").path("name").asString("").trim();
                String a3 = region.path("area3").path("name").asString("").trim();
                String a4 = region.path("area4").path("name").asString("").trim();

                String regionPrefix = String.join(" ", Stream.of(a1, a2, a3, a4)
                        .filter(s -> !s.isEmpty()).toList());
                if (shortAddr.isEmpty() && !regionPrefix.isEmpty()) {
                    shortAddr = String.join(" ", Stream.of(a1Short.isEmpty() ? a1 : a1Short, a2, a3)
                            .filter(s -> !s.isEmpty()).toList());
                }

                JsonNode land = item.path("land");
                if ("roadaddr".equals(name)) {
                    String roadName = land.path("name").asString("").trim();
                    String num1 = land.path("number1").asString("").trim();
                    String num2 = land.path("number2").asString("").trim();
                    String bldg = land.path("addition0").path("value").asString("").trim();
                    String numPart = num2.isEmpty() ? num1 : (num1 + "-" + num2);
                    roadAddr = String.join(" ", Stream.of(a1, a2, roadName, numPart, bldg)
                            .filter(s -> !s.isEmpty()).toList());
                } else if ("addr".equals(name)) {
                    String num1 = land.path("number1").asString("").trim();
                    String num2 = land.path("number2").asString("").trim();
                    String numPart = num2.isEmpty() ? num1 : (num1 + "-" + num2);
                    jibunAddr = String.join(" ", Stream.of(regionPrefix, numPart)
                            .filter(s -> !s.isEmpty()).toList());
                }
            }

            String fullAddr = !roadAddr.isEmpty() ? roadAddr : (!jibunAddr.isEmpty() ? jibunAddr : shortAddr);
            if (shortAddr.isEmpty()) {
                shortAddr = fullAddr;
            }

            return new ReverseGeocodeResponseDto(
                    0,
                    "ok",
                    lat,
                    lng,
                    shortAddr,
                    fullAddr,
                    roadAddr,
                    jibunAddr
            );
        } catch (Exception ex) {
            String coordLabel = String.format(Locale.US, "위도 %.4f, 경도 %.4f", lat, lng);
            return new ReverseGeocodeResponseDto(-2, ex.getMessage(), lat, lng, coordLabel, coordLabel, "", "");
        }
    }

    /**
     * [메서드 역할 — 3단계 하이브리드 지오코딩: 주소/장소 검색어 → 위경도 좌표 변환]
     * <p>
     * [코드의 경로성]
     * - [어디서 왔는가 (Incoming)] : {@code EmergencyApiController.geocode()} ({@code GET /api/geocode}) ← {@code frontend/app.js:searchAddressAndMove()}
     * - [어디로 가는가 (Outgoing)] : {@code dto/GeocodeResponseDto.java} 반환 → 지도 중심 이동 및 출발지 갱신!
     * <p>
     * [3단계 폴백 탐색 순서]
     * 1단계: 네이버 클라우드 Geocoding API로 정확한 도로명·지번 주소 검색 ({@code parseGeocodeJson})
     * 2단계: 결과가 부족하면 백엔드 메모리의 전국 530개 병원 이름/주소({@code hospitals})에서 부분 일치 검색
     * 3단계: 그래도 결과가 없으면("원주시청", "강릉역" 같은 랜드마크 검색 시) OpenStreetMap Nominatim 한국 영역 검색으로 보조!
     */
    public GeocodeResponseDto geocode(String query, List<HospitalDto> hospitals) {
        String safeQuery = query != null ? query.trim() : "";
        if (safeQuery.isEmpty()) {
            return new GeocodeResponseDto(-1, "", List.of());
        }

        List<GeocodeResponseDto.GeocodeItemDto> results = new ArrayList<>();

        // 1. 네이버 클라우드 플랫폼 Geocoding API 호출
        try {
            String url = GEOCODE_URL + "?query=" + URLEncoder.encode(safeQuery, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("X-NCP-APIGW-API-KEY-ID", properties.naver().clientId())
                    .header("X-NCP-APIGW-API-KEY", properties.naver().clientSecret())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                results.addAll(parseGeocodeJson(response.body()));
            }
        } catch (Exception ex) {
            log.warn("네이버 Geocoding 호출 오류: {}", ex.getMessage());
        }

        // 2. 병원 데이터 내 주소/이름 매칭 보완
        if (hospitals != null && results.size() < 5) {
            String compactQuery = safeQuery.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            for (HospitalDto h : hospitals) {
                String name = h.name() != null ? h.name() : "";
                String addr = h.address() != null ? h.address() : "";
                if (name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT).contains(compactQuery)
                        || addr.replaceAll("\\s+", "").toLowerCase(Locale.ROOT).contains(compactQuery)) {
                    results.add(new GeocodeResponseDto.GeocodeItemDto(
                            name,
                            addr,
                            addr,
                            h.lat(),
                            h.lng()
                    ));
                    if (results.size() >= 6) {
                        break;
                    }
                }
            }
        }

        // 3. 랜드마크/장소명(예: "원주시청", "강릉역") 검색 시 결과가 없으면 Nominatim 보조 조회
        if (results.isEmpty()) {
            try {
                String nomUrl = "https://nominatim.openstreetmap.org/search?format=json&limit=4&countrycodes=kr&accept-language=ko&q="
                        + URLEncoder.encode(safeQuery, StandardCharsets.UTF_8);
                HttpRequest nomReq = HttpRequest.newBuilder()
                        .uri(URI.create(nomUrl))
                        .timeout(Duration.ofSeconds(6))
                        .header("User-Agent", "Yonsei119-Emergency-Service/1.0")
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                HttpResponse<String> nomRes = httpClient.send(
                        nomReq,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                if (nomRes.statusCode() == 200) {
                    JsonNode arr = jsonMapper.readTree(nomRes.body());
                    if (arr.isArray()) {
                        for (JsonNode item : arr) {
                            double lat = item.path("lat").asDouble(0.0);
                            double lng = item.path("lon").asDouble(0.0);
                            String displayName = item.path("display_name").asString("").trim();
                            String name = item.path("name").asString(safeQuery).trim();
                            if (lat > 32.0 && lat < 39.5 && lng > 124.0 && lng < 132.0) {
                                results.add(new GeocodeResponseDto.GeocodeItemDto(
                                        name.isEmpty() ? safeQuery : name,
                                        displayName,
                                        displayName,
                                        lat,
                                        lng
                                ));
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                log.debug("보조 지오코딩 조회 생략: {}", ex.getMessage());
            }
        }

        return new GeocodeResponseDto(results.isEmpty() ? 404 : 0, safeQuery, List.copyOf(results));
    }

    public List<GeocodeResponseDto.GeocodeItemDto> parseGeocodeJson(String jsonBody) {
        List<GeocodeResponseDto.GeocodeItemDto> list = new ArrayList<>();
        try {
            JsonNode root = jsonMapper.readTree(jsonBody);
            JsonNode addresses = root.path("addresses");
            if (addresses.isArray()) {
                for (JsonNode item : addresses) {
                    String road = item.path("roadAddress").asString("").trim();
                    String jibun = item.path("jibunAddress").asString("").trim();
                    double lng = Double.parseDouble(item.path("x").asString("0"));
                    double lat = Double.parseDouble(item.path("y").asString("0"));
                    if (lat > 32.0 && lat < 39.5 && lng > 124.0 && lng < 132.0) {
                        String title = !road.isEmpty() ? road : jibun;
                        list.add(new GeocodeResponseDto.GeocodeItemDto(title, road, jibun, lat, lng));
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Geocoding JSON 파싱 오류: {}", ex.getMessage());
        }
        return list;
    }

    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * [메서드 역할]
     * 밀리초({@code durationMs}) 단위의 예상 소요 시간을 사람이 읽기 쉬운 한국어 시간 문자열로 변환합니다.
     */
    public static String formatDurationKorean(long durationMs) {
        if (durationMs <= 0) {
            return "약 1분 미만";
        }
        int totalSeconds = (int) Math.round(durationMs / 1000.0);
        if (totalSeconds < 60) {
            return "약 " + Math.max(1, totalSeconds) + "초";
        }
        int totalMinutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        int hours = totalMinutes / 60;
        int minutes = totalMinutes % 60;
        if (hours <= 0) {
            if (seconds == 0) {
                return "약 " + minutes + "분";
            }
            return String.format("약 %d분 %d초", minutes, seconds);
        }
        if (minutes == 0) {
            return "약 " + hours + "시간";
        }
        return String.format("약 %d시간 %d분", hours, minutes);
    }

    private static String normalizeApiType(String apiType) {
        if (apiType != null && apiType.trim().equalsIgnoreCase("directions15")) {
            return "directions15";
        }
        return "directions5";
    }

    private static String normalizeOption(String option) {
        if (option == null || option.isBlank()) {
            return "trafast";
        }
        String lower = option.trim().toLowerCase(Locale.ROOT);
        return OPTION_LABELS.containsKey(lower) ? lower : "trafast";
    }

    /**
     * [메서드 역할 — 세부 구간별 주행 속도 기반 실시간 혼잡도 정밀 보정 엔진]
     * 네이버 Directions API의 기본 {@code section} 배열은 주요 큰 도로 단위로만 뭉뚱그려 내려오는 경우가 있어,
     * 교차로 단위 안내 배열인 {@code guide}(거리 {@code distance}와 소요시간 {@code duration} 포함)에서 구간 평균 속도({@code speedKmh})를 역산해
     * 고속도로({@code expressway}) vs 일반도로 기준에 맞춰 혼잡도({@code 1}: 원활 초록, {@code 2}: 서행 주황, {@code 3}: 정체 빨강)를 세밀하게 쪼개 줍니다!
     * <p>
     * [수정 시 영향 및 예시]
     * - 일반도로 정체(빨강, {@code 3}) 기준 속도를 시속 {@code 16km/h} 미만에서 {@code 20km/h} 미만으로 더 민감하게 바꾸고 싶다면
     *   아래 {@link #classifyCongestionBySpeed(int, boolean)} 메서드의 {@code if (speedKmh < 16)}을 {@code if (speedKmh < 20)}으로 수정하세요.
     */
    private static List<DirectionsResponseDto.RouteSectionDto> buildDetailedSections(
            JsonNode sectionNode,
            JsonNode guideNode
    ) {
        List<DirectionsResponseDto.RouteSectionDto> rawSections = new ArrayList<>();
        if (sectionNode != null && sectionNode.isArray()) {
            for (JsonNode sec : sectionNode) {
                int pointIndex = sec.path("pointIndex").asInt(0);
                int pointCount = sec.path("pointCount").asInt(0);
                int secDistance = sec.path("distance").asInt(0);
                String secName = sec.path("name").asString("").trim();
                int congestion = sec.path("congestion").asInt(0);
                int speed = sec.path("speed").asInt(0);
                if (pointCount > 0 && pointIndex >= 0) {
                    rawSections.add(new DirectionsResponseDto.RouteSectionDto(
                            pointIndex, pointCount, secDistance, secName, congestion, speed
                    ));
                }
            }
        }

        List<DirectionsResponseDto.RouteSectionDto> detailed = new ArrayList<>();
        boolean hasValidGuideTiming = false;

        if (guideNode != null && guideNode.isArray()) {
            int prevIdx = 0;
            for (JsonNode g : guideNode) {
                int toIdx = g.path("pointIndex").asInt(-1);
                int distMeters = g.path("distance").asInt(0);
                long durMs = g.path("duration").asLong(0L);
                int guideType = g.path("type").asInt(0);
                String instructions = g.path("instructions").asString("").trim();

                if (toIdx > prevIdx && distMeters > 0 && durMs > 0) {
                    hasValidGuideTiming = true;
                    int pointCount = toIdx - prevIdx;
                    double speedExact = (distMeters / 1000.0) / (durMs / 3_600_000.0);
                    int speedKmh = Math.max(1, (int) Math.round(speedExact));

                    int midIdx = prevIdx + (pointCount / 2);
                    DirectionsResponseDto.RouteSectionDto matchedSec = findOverlappingSection(rawSections, midIdx);
                    String secRoadName = matchedSec != null ? matchedSec.name() : "";

                    boolean expressway = isExpresswaySegment(secRoadName, instructions, guideType, speedKmh);
                    int congestion = classifyCongestionBySpeed(speedKmh, expressway);

                    if (matchedSec != null && matchedSec.congestion() > congestion && speedKmh < 35) {
                        congestion = matchedSec.congestion();
                    }

                    String label = resolveSegmentLabel(secRoadName, instructions);
                    detailed.add(new DirectionsResponseDto.RouteSectionDto(
                            prevIdx,
                            pointCount,
                            distMeters,
                            label,
                            congestion,
                            speedKmh
                    ));
                    prevIdx = toIdx;
                } else if (toIdx > prevIdx) {
                    prevIdx = toIdx;
                }
            }
        }

        if (!hasValidGuideTiming) {
            return rawSections;
        }

        for (DirectionsResponseDto.RouteSectionDto raw : rawSections) {
            if (raw.congestion() >= 2) {
                detailed.add(raw);
            }
        }

        return detailed;
    }

    private static DirectionsResponseDto.RouteSectionDto findOverlappingSection(
            List<DirectionsResponseDto.RouteSectionDto> rawSections,
            int targetIdx
    ) {
        for (DirectionsResponseDto.RouteSectionDto sec : rawSections) {
            int start = sec.pointIndex();
            int end = start + sec.pointCount();
            if (targetIdx >= start && targetIdx <= end) {
                return sec;
            }
        }
        return null;
    }

    private static boolean isExpresswaySegment(
            String sectionName,
            String instructions,
            int guideType,
            int speedKmh
    ) {
        if (speedKmh >= 68) {
            return true;
        }
        if (guideType >= 50 && guideType <= 53) {
            return true;
        }
        String combined = (sectionName + " " + instructions);
        return combined.contains("고속도로")
                || combined.contains("순환선")
                || combined.contains("고속화")
                || combined.contains("올림픽대로")
                || combined.contains("강변북로")
                || combined.contains("동부간선")
                || combined.contains("서부간선")
                || combined.contains("북부간선")
                || combined.contains("내부순환")
                || combined.contains("분당수서")
                || combined.contains("톨게이트")
                || combined.contains("분기점");
    }

    private static int classifyCongestionBySpeed(int speedKmh, boolean expressway) {
        if (expressway) {
            if (speedKmh < 40) {
                return 3;
            }
            if (speedKmh < 70) {
                return 2;
            }
            return 1;
        }
        if (speedKmh < 16) {
            return 3;
        }
        if (speedKmh < 30) {
            return 2;
        }
        return 1;
    }

    private static String resolveSegmentLabel(String sectionName, String instructions) {
        String fromIdxName = "";
        int fromPos = instructions.indexOf("에서 ");
        if (fromPos > 0) {
            fromIdxName = instructions.substring(0, fromPos).trim();
        }

        String quotedName = "";
        int q1 = instructions.indexOf('\'');
        int q2 = q1 >= 0 ? instructions.indexOf('\'', q1 + 1) : -1;
        if (q1 >= 0 && q2 > q1 + 1) {
            quotedName = instructions.substring(q1 + 1, q2).trim();
            int commaIdx = quotedName.indexOf(',');
            if (commaIdx > 0) {
                quotedName = quotedName.substring(0, commaIdx).trim();
            }
        }

        if (!sectionName.isEmpty()) {
            if (!fromIdxName.isEmpty() && !fromIdxName.equals(sectionName)) {
                return sectionName + "·" + fromIdxName;
            }
            return sectionName;
        }
        if (!fromIdxName.isEmpty() && !quotedName.isEmpty() && !quotedName.startsWith(fromIdxName)) {
            return fromIdxName + "→" + quotedName;
        }
        if (!fromIdxName.isEmpty()) {
            return fromIdxName;
        }
        if (!quotedName.isEmpty()) {
            return quotedName;
        }
        if (instructions.contains("목적지")) {
            return "병원 진입 구간";
        }
        return "주행 구간";
    }

    private static DirectionsResponseDto createErrorResponse(
            String apiType,
            String apiLabel,
            String option,
            String optionLabel,
            int code,
            String message
    ) {
        return new DirectionsResponseDto(
                apiType,
                apiLabel,
                option,
                optionLabel,
                code,
                message,
                0,
                0.0,
                0L,
                0,
                "-",
                "",
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private record CachedRoute(
            DirectionsResponseDto response,
            Instant createdAt
    ) {
    }

    private record CachedReverseGeocode(
            ReverseGeocodeResponseDto response,
            Instant createdAt
    ) {
    }
}
