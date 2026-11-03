package com.yonsei119.service;

import com.yonsei119.config.Yonsei119Properties;
import com.yonsei119.dto.DirectionsResponseDto;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ============================================================================
 * [클래스 역할]
 * 네이버 클라우드 플랫폼(NCP)의 **Directions 5** 및 **Directions 15** 자동차 경로 탐색 API를
 * 백엔드에서 안전하게 호출하여 예상 소요 시간(ETA), 주행 거리, 통행료/택시비/유류비,
 * 그리고 지도 폴리라인(`path`) 좌표열을 반환하는 서비스입니다.
 *
 * [담당 기능]
 * 1. **Directions 5 (`directions5`)**:
 *    - 엔드포인트: `https://maps.apigw.ntruss.com/map-direction/v1/driving`
 *    - 특징: 출발지 -> 목적지(최대 경유지 5곳) 실시간 빠른길/최적 경로 및 예상 소요 시간 조회에 최적화.
 * 2. **Directions 15 (`directions15`)**:
 *    - 엔드포인트: `https://maps.apigw.ntruss.com/map-direction-15/v1/driving`
 *    - 특징: 출발지 -> 목적지(최대 경유지 15곳) 다중 경유/광역 이송 경로 탐색 및 예상 소요 시간 조회 지원.
 * 3. 보안 보호:
 *    - NCP Client Secret(`X-NCP-APIGW-API-KEY`)을 브라우저에 노출하지 않고 백엔드 서버 헤더로만 전송합니다.
 * 4. 단기 인메모리 캐시(60초):
 *    - 동일한 출발지/목적지/옵션으로 반복 조회 시 60초 동안 캐시된 경로를 즉시 반환하여
 *      응답 속도를 높이고 NCP API 호출 쿼터를 절약합니다.
 *
 * [수정 시 영향 및 예시]
 * - 새로운 탐색 옵션(예: `traavoidcaronly` 자동차전용도로 회피)의 한글 라벨을 바꾸고 싶다면
 *   {@link #OPTION_LABELS} 맵의 값을 수정하면 프론트엔드 UI에 자동 반영됩니다.
 *   예시(변경 전): {@code "trafast", "실시간 빠른길"}
 *   예시(변경 후): {@code "trafast", "응급 최단시간(실시간)"}
 * ============================================================================
 */
@Service
public class NaverDirectionsService {

    private static final Logger log = LoggerFactory.getLogger(NaverDirectionsService.class);

    /** 동일 경로 재조회 시 캐시 유지 시간(초) */
    private static final long ROUTE_CACHE_TTL_SECONDS = 60L;

    /** 네이버 Directions API 지원 탐색 옵션 코드 -> 한글 표시 라벨 맵 */
    public static final Map<String, String> OPTION_LABELS = Map.of(
            "trafast", "실시간 빠른길",
            "traoptimal", "실시간 최적",
            "tracomfort", "실시간 편한길",
            "traavoidtoll", "무료 우선",
            "traavoidcaronly", "자동차전용 회피"
    );

    private final Yonsei119Properties properties;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final ConcurrentHashMap<String, CachedRoute> routeCache = new ConcurrentHashMap<>();

    public NaverDirectionsService(Yonsei119Properties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.jsonMapper = JsonMapper.builder().build();
    }

    /**
     * [메서드 역할]
     * 출발지 위경도(`startLat`, `startLng`)와 도착 병원 위경도(`goalLat`, `goalLng`),
     * 선택한 API 종류(`directions5` 또는 `directions15`), 경로 탐색 옵션(`trafast` 등),
     * 선택적 경유지(`waypoints`)를 받아 네이버 자동차 경로 탐색 결과를 반환합니다.
     *
     * [주의사항]
     * 네이버 Directions API는 좌표 파라미터를 반드시 **`경도,위도` (`lng,lat`)** 순서로 받습니다.
     * 이 메서드 내부에서 `String.format(Locale.US, "%.6f,%.6f", startLng, startLat)` 형태로
     * 안전하게 변환하여 호출합니다.
     *
     * @param startLat  출발지 WGS84 위도
     * @param startLng  출발지 WGS84 경도
     * @param goalLat   목적지(병원) WGS84 위도
     * @param goalLng   목적지(병원) WGS84 경도
     * @param apiType   사용할 API 구분 (`"directions5"` 또는 `"directions15"`)
     * @param option    탐색 옵션 (`"trafast"`, `"traoptimal"`, `"tracomfort"`, `"traavoidtoll"`)
     * @param waypoints 경유지 문자열 (선택사항, 형식: `"경도1,위도1|경도2,위도2"`)
     * @return 정제된 {@link DirectionsResponseDto}
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

        String cacheKey = String.format(
                Locale.US,
                "%s|%s|%.5f,%.5f|%.5f,%.5f|%s",
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

        String startParam = String.format(Locale.US, "%.6f,%.6f", startLng, startLat);
        String goalParam = String.format(Locale.US, "%.6f,%.6f", goalLng, goalLat);

        StringBuilder urlBuilder = new StringBuilder(baseEndpoint)
                .append("?start=").append(URLEncoder.encode(startParam, StandardCharsets.UTF_8))
                .append("&goal=").append(URLEncoder.encode(goalParam, StandardCharsets.UTF_8))
                .append("&option=").append(URLEncoder.encode(normalizedOption, StandardCharsets.UTF_8));

        if (!safeWaypoints.isEmpty()) {
            urlBuilder.append("&waypoints=").append(URLEncoder.encode(safeWaypoints, StandardCharsets.UTF_8));
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlBuilder.toString()))
                    .timeout(Duration.ofSeconds(10))
                    .header("X-NCP-APIGW-API-KEY-ID", properties.naver().clientId())
                    .header("X-NCP-APIGW-API-KEY", properties.naver().clientSecret())
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("네이버 {} HTTP 오류: status={}, body={}",
                        apiLabel, response.statusCode(), response.body());
                return createErrorResponse(
                        normalizedApi, apiLabel, normalizedOption, optionLabel,
                        response.statusCode(),
                        "네이버 경로 탐색 서버 응답 오류 (HTTP " + response.statusCode() + ")"
                );
            }

            DirectionsResponseDto parsed = parseDirectionsJson(
                    response.body(), normalizedApi, apiLabel, normalizedOption, optionLabel
            );
            if (parsed.code() == 0 && !parsed.path().isEmpty()) {
                routeCache.put(cacheKey, new CachedRoute(parsed, Instant.now()));
            }
            return parsed;
        } catch (Exception ex) {
            log.error("네이버 {} 호출 중 예외 발생: {}", apiLabel, ex.getMessage(), ex);
            return createErrorResponse(
                    normalizedApi, apiLabel, normalizedOption, optionLabel,
                    -1,
                    "경로 탐색 요청 중 네트워크 오류가 발생했습니다: " + ex.getMessage()
            );
        }
    }

    /**
     * [메서드 역할]
     * 네이버 Directions 5 / 15 JSON 응답 본문을 파싱하여 {@link DirectionsResponseDto}로 변환합니다.
     * 단위 테스트에서 JSON 파싱 로직을 독립 검증할 수 있도록 공개 메서드로 구성했습니다.
     *
     * @param jsonBody    네이버 API 원본 JSON 문자열
     * @param apiType     `"directions5"` 또는 `"directions15"`
     * @param apiLabel    `"Directions 5"` 또는 `"Directions 15"`
     * @param option      요청한 탐색 옵션 코드
     * @param optionLabel 탐색 옵션 한글 라벨
     * @return 파싱된 {@link DirectionsResponseDto}
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
            JsonNode optionArray = routeNode.path(option);
            if (!optionArray.isArray() || optionArray.isEmpty()) {
                // 요청한 옵션 키가 없으면 route 하위의 첫 번째 배열을 폴백으로 탐색
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

            JsonNode guideNode = firstRoute.path("guide");
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
                    List.copyOf(guidePrompts)
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
     * [메서드 역할]
     * 밀리초(`durationMs`) 단위의 예상 소요 시간을 사람이 읽기 쉬운 한국어 시간 문자열로 변환합니다.
     *
     * [수정 시 영향 및 예시]
     * - 입력: `840000L` (14분) -> 출력: `"약 14분"`
     * - 입력: `4320000L` (72분) -> 출력: `"약 1시간 12분"`
     *
     * @param durationMs 소요 시간(밀리초)
     * @return 한국어 소요 시간 텍스트
     */
    public static String formatDurationKorean(long durationMs) {
        if (durationMs <= 0) {
            return "약 1분 미만";
        }
        int totalMinutes = Math.max(1, (int) Math.round(durationMs / 60000.0));
        int hours = totalMinutes / 60;
        int minutes = totalMinutes % 60;
        if (hours <= 0) {
            return "약 " + minutes + "분";
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
                List.of()
        );
    }

    private record CachedRoute(
            DirectionsResponseDto response,
            Instant createdAt
    ) {
    }
}
