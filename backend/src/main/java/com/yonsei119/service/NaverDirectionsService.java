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
 * 그리고 지도 폴리라인(`path`) 좌표열과 구간별 실시간 교통 혼잡도(`section`)를 반환하는 서비스입니다.
 * <p>
 * [담당 기능]
 * 1. **Directions 5 (`directions5`)**:
 *    - 엔드포인트: {@code https://maps.apigw.ntruss.com/map-direction/v1/driving}
 *    - 특징: 출발지 -> 목적지(최대 경유지 5곳) 실시간 빠른길/최적 경로 및 예상 소요 시간 조회에 최적화.
 * 2. **Directions 15 (`directions15`)**:
 *    - 엔드포인트: {@code https://maps.apigw.ntruss.com/map-direction-15/v1/driving}
 *    - 특징: 출발지 -> 목적지(최대 경유지 15곳) 다중 경유/광역 이송 경로 탐색 및 예상 소요 시간 조회 지원.
 * 3. 보안 보호:
 *    - NCP Client Secret(`X-NCP-APIGW-API-KEY`)을 브라우저에 노출하지 않고 백엔드 서버 헤더로만 전송합니다.
 * 4. 단기 인메모리 캐시(60초):
 *    - 동일한 출발지/목적지/옵션으로 반복 조회 시 60초 동안 캐시된 경로를 즉시 반환하여
 *      응답 속도를 높이고 NCP API 호출 쿼터를 절약합니다.
 * <p>
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
     * <p>
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
     * <p>
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

    /**
     * [메서드 역할]
     * 네이버 Directions API의 `section`(대표 도로 3개 요약)과 `guide`(전 구간 세부 분기점별 거리·실시간 소요시간)를
     * 결합하여 출발지(0번 좌표)부터 목적지(마지막 좌표)까지 누락 없는 고정밀 실시간 혼잡도 구간 리스트를 생성합니다.
     * <p>
     * [개선 배경]
     * - 네이버 API의 `section` 배열은 전체 경로 중 거리가 가장 긴 대표 도로 3개만 평균값으로 내려주므로,
     *   지방 -> 서울 대학병원 경로 조회 시 고속도로 3개만 표시되고 서울 시내 진입 후 정체 구간이 통째로 누락되거나,
     *   시내 주행 시 출발·도착 부근의 병목 정체 구간이 빠지는 한계가 있습니다.
     * - 반면 `guide` 배열은 모든 교차로·고가차도·지하차도 구간마다 정확한 `pointIndex`, `distance`(m),
     *   `duration`(실시간 소요시간 ms)을 포함하므로, 구간별 실제 주행 속도(km/h)를 역산하여
     *   일반도로(정체 16km/h 미만, 서행 30km/h 미만) 및 고속화도로(정체 40km/h 미만, 서행 70km/h 미만) 기준의
     *   실시간 혼잡도를 네이버 지도 앱 수준으로 정밀하게 복원합니다.
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

        // 원본 section 배열에 명시된 서행(2)·정체(3) 구간도 함께 보존하여 누락 방지
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
                return 3; // 고속도로/도시고속도로 정체
            }
            if (speedKmh < 70) {
                return 2; // 고속도로/도시고속도로 서행
            }
            return 1;     // 고속도로/도시고속도로 원활
        }
        if (speedKmh < 16) {
            return 3;     // 일반 시내도로 정체
        }
        if (speedKmh < 30) {
            return 2;     // 일반 시내도로 서행
        }
        return 1;         // 일반 시내도로 원활
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
                List.of()
        );
    }

    private record CachedRoute(
            DirectionsResponseDto response,
            Instant createdAt
    ) {
    }
}
