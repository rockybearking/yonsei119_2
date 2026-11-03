package com.yonsei119.service;

import com.yonsei119.config.Yonsei119Properties;
import com.yonsei119.dto.EmergencySnapshotDto;
import com.yonsei119.dto.HospitalDto;
import com.yonsei119.dto.HospitalTypeDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ============================================================================
 * [클래스 역할]
 * 국립중앙의료원(NEMC) 7개 OpenAPI 엔드포인트에서 수집한 데이터를 기관 고유코드(`HPID`) 기준으로
 * 교차 병합(Cross-Merge)하고, 인메모리 캐시(TTL)를 통해 빠르고 안정적으로 제공하는 핵심 서비스입니다.
 *
 * [담당 기능]
 * 1. 기존 VanillaJS(`app.js`)의 `fetchData()`, `groupById()`, 좌표 검증 루프,
 *    기관 종별 분류(`classify`, `isRegional`)를 백엔드에서 일괄 수행합니다.
 * 2. OpenAPI 설명서(V13) 더블체크 및 현장 실사 도메인 규칙 보존:
 *    - 위경도 태그가 `wgs84Lat`/`wgs84Lon`으로 내려오는 API와 `latitude`/`longitude`로 내려오는 API를 모두 지원합니다.
 *    - 현장 실사 도메인 규칙인 병상 음수 값(예: `-3` = 초과수용)과 미보고 빈 값(`""` = `-`)을
 *      손실 없이 그대로 `HospitalDto.beds()`에 담아 전달합니다.
 * 3. 다수의 사용자가 동시에 접속하거나 새로고침을 눌러도 인메모리 캐시(`cacheTtlSeconds`)와
 *    동기화 락을 통해 공공데이터포털 API 호출 횟수를 안전하게 보호합니다.
 *
 * [수정 시 영향 및 예시]
 * - 대한민국 유효 위경도 범위(`MIN_LAT` ~ `MAX_LAT`, `MIN_LNG` ~ `MAX_LNG`)를 조정하고 싶다면
 *   하단 상수 값을 변경하면 됩니다.
 *   예시(변경 전): {@code private static final double MIN_LAT = 33.0;}
 *   예시(변경 후): {@code private static final double MIN_LAT = 32.0;}
 * ============================================================================
 */
@Service
public class EmergencyDataService {

    private static final Logger log = LoggerFactory.getLogger(EmergencyDataService.class);

    /** 대한민국 영역 유효 위도(Latitude) 최소/최대 범위 */
    private static final double MIN_LAT = 33.0;
    private static final double MAX_LAT = 39.0;

    /** 대한민국 영역 유효 경도(Longitude) 최소/최대 범위 */
    private static final double MIN_LNG = 124.0;
    private static final double MAX_LNG = 132.0;

    /** 한국 표준시(KST) 시간대 */
    private static final ZoneId KST_ZONE = ZoneId.of("Asia/Seoul");

    private final NmcOpenApiClient openApiClient;
    private final HospitalClassifier classifier;
    private final Yonsei119Properties properties;

    private final Object cacheLock = new Object();
    private volatile EmergencySnapshotDto cachedSnapshot;
    private volatile Instant cachedAt = Instant.EPOCH;

    public EmergencyDataService(
            NmcOpenApiClient openApiClient,
            HospitalClassifier classifier,
            Yonsei119Properties properties
    ) {
        this.openApiClient = openApiClient;
        this.classifier = classifier;
        this.properties = properties;
    }

    /**
     * [메서드 역할]
     * 전국 응급의료기관 통합 스냅샷을 반환합니다.
     * 캐시 유효 시간(`cacheTtlSeconds`) 이내이고 `forceRefresh`가 `false`이면 캐시된 스냅샷을 즉시 반환하며,
     * 만료되었거나 강제 새로고침(`forceRefresh = true`) 요청이면 NEMC OpenAPI를 새로 호출합니다.
     *
     * @param forceRefresh 사용자가 '새로고침' 버튼을 눌러 캐시를 무시하고 즉시 갱신을 요청했는지 여부
     * @return 통합된 {@link EmergencySnapshotDto}
     */
    public EmergencySnapshotDto getSnapshot(boolean forceRefresh) {
        long ttlSeconds = Math.max(5L, properties.nemc().cacheTtlSeconds());
        Instant now = Instant.now();

        EmergencySnapshotDto current = this.cachedSnapshot;
        if (!forceRefresh && current != null && now.isBefore(this.cachedAt.plusSeconds(ttlSeconds))) {
            return withCachedFlag(current, true);
        }

        synchronized (cacheLock) {
            now = Instant.now();
            current = this.cachedSnapshot;
            if (!forceRefresh && current != null && now.isBefore(this.cachedAt.plusSeconds(ttlSeconds))) {
                return withCachedFlag(current, true);
            }

            try {
                EmergencySnapshotDto fresh = buildFreshSnapshot();
                if (!fresh.hospitals().isEmpty() || current == null) {
                    this.cachedSnapshot = fresh;
                    this.cachedAt = Instant.now();
                    return fresh;
                }
                log.warn("신규 수집된 병원 목록이 비어 있어 직전 정상 캐시 스냅샷을 유지합니다.");
                return withCachedFlag(current, true);
            } catch (Exception ex) {
                log.error("응급의료 데이터 스냅샷 갱신 중 오류 발생: {}", ex.getMessage(), ex);
                if (current != null) {
                    return withCachedFlag(current, true);
                }
                throw ex;
            }
        }
    }

    /**
     * [메서드 역할]
     * NEMC OpenAPI 7개 엔드포인트를 병렬 수집하여 기관코드(`HPID`) 기준으로 병합하고
     * 새로운 {@link EmergencySnapshotDto}를 생성합니다.
     *
     * @return 신규 생성된 스냅샷 DTO
     */
    public EmergencySnapshotDto buildFreshSnapshot() {
        Map<String, List<Map<String, String>>> rawDatasets = openApiClient.fetchAllDatasets();
        return mergeDatasetsIntoSnapshot(rawDatasets);
    }

    /**
     * [메서드 역할]
     * 수집된 7개 엔드포인트 원본 리스트 맵을 받아 `HPID` 기준으로 교차 병합(Cross-Merge)합니다.
     * 단위 테스트에서도 직접 호출하여 병합 정확도를 검증할 수 있도록 공개 메서드로 분리되어 있습니다.
     *
     * @param rawDatasets 엔드포인트 키 -> 아이템 맵 리스트
     * @return 병합 완료된 {@link EmergencySnapshotDto}
     */
    public EmergencySnapshotDto mergeDatasetsIntoSnapshot(Map<String, List<Map<String, String>>> rawDatasets) {
        List<Map<String, String>> listItems = rawDatasets.getOrDefault("list", List.of());
        List<Map<String, String>> basicItems = rawDatasets.getOrDefault("basic", List.of());
        List<Map<String, String>> bedItems = rawDatasets.getOrDefault("beds", List.of());
        List<Map<String, String>> messageItems = rawDatasets.getOrDefault("messages", List.of());
        List<Map<String, String>> severeItems = rawDatasets.getOrDefault("severe", List.of());
        List<Map<String, String>> traumaListItems = rawDatasets.getOrDefault("traumaList", List.of());
        List<Map<String, String>> traumaBasicItems = rawDatasets.getOrDefault("traumaBasic", List.of());

        Map<String, Map<String, String>> listById = groupSingleById(listItems);
        Map<String, Map<String, String>> basicById = groupSingleById(basicItems);
        Map<String, Map<String, String>> bedsById = groupSingleById(bedItems);
        Map<String, List<Map<String, String>>> messagesById = groupMultiById(messageItems);
        Map<String, Map<String, String>> severeById = groupSingleById(severeItems);
        Map<String, Map<String, String>> traumaListById = groupSingleById(traumaListItems);
        Map<String, Map<String, String>> traumaBasicById = groupSingleById(traumaBasicItems);

        Set<String> uniqueIds = new LinkedHashSet<>(listById.keySet());
        uniqueIds.addAll(traumaListById.keySet());

        List<HospitalDto> hospitals = new ArrayList<>(uniqueIds.size());
        int tertiaryCount = 0;
        int regionalCount = 0;
        int medicalCount = 0;
        int traumaCount = 0;
        int otherCount = 0;

        for (String id : uniqueIds) {
            Map<String, String> listFields = listById.getOrDefault(id, traumaListById.getOrDefault(id, Map.of()));
            Map<String, String> basicFields = basicById.getOrDefault(id, Map.of());

            Map<String, String> mergedBasic = new LinkedHashMap<>(basicFields);
            for (Map.Entry<String, String> entry : listFields.entrySet()) {
                mergedBasic.putIfAbsent(entry.getKey(), entry.getValue());
            }

            Map<String, String> traumaFields = traumaBasicById.getOrDefault(
                    id,
                    traumaListById.getOrDefault(id, null)
            );

            Map<String, String> beds = bedsById.get(id);
            Map<String, String> severe = severeById.get(id);
            List<Map<String, String>> messages = messagesById.getOrDefault(id, List.of());

            double lat = parseCoordinate(
                    firstNonBlank(
                            listFields.get("wgs84lat"),
                            basicFields.get("wgs84lat"),
                            traumaFields != null ? traumaFields.get("wgs84lat") : null,
                            listFields.get("latitude")
                    )
            );
            double lng = parseCoordinate(
                    firstNonBlank(
                            listFields.get("wgs84lon"),
                            basicFields.get("wgs84lon"),
                            traumaFields != null ? traumaFields.get("wgs84lon") : null,
                            listFields.get("longitude")
                    )
            );

            if (!isValidKoreaCoordinate(lat, lng)) {
                continue;
            }

            String name = displayValue(firstNonBlank(listFields.get("dutyname"), basicFields.get("dutyname")));
            String address = displayValue(firstNonBlank(listFields.get("dutyaddr"), basicFields.get("dutyaddr")));
            String tel = displayValue(firstNonBlank(listFields.get("dutytel1"), basicFields.get("dutytel1")));
            String erTel = displayValue(firstNonBlank(
                    listFields.get("dutytel3"),
                    basicFields.get("dutytel3"),
                    beds != null ? beds.get("dutytel3") : null
            ));

            Map<String, String> classificationFields = new LinkedHashMap<>(basicFields);
            classificationFields.putAll(listFields);
            classificationFields.put("dutyname", firstNonBlank(listFields.get("dutyname"), basicFields.get("dutyname")));
            classificationFields.put("dutyaddr", firstNonBlank(listFields.get("dutyaddr"), basicFields.get("dutyaddr")));

            boolean regional = classifier.isRegional(classificationFields);
            HospitalTypeDto type = classifier.classify(classificationFields, name);
            boolean hasTrauma = traumaFields != null && !traumaFields.isEmpty();

            if (regional) {
                regionalCount++;
            }
            if ("tertiary".equals(type.group())) {
                tertiaryCount++;
            } else if ("medical".equals(type.group())) {
                medicalCount++;
            } else {
                otherCount++;
            }
            if (hasTrauma) {
                traumaCount++;
            }

            hospitals.add(new HospitalDto(
                    id,
                    name,
                    address,
                    tel,
                    erTel,
                    lat,
                    lng,
                    regional,
                    type,
                    Map.copyOf(basicFields.isEmpty() ? mergedBasic : basicFields),
                    hasTrauma ? Map.copyOf(traumaFields) : null,
                    beds != null ? Map.copyOf(beds) : null,
                    List.copyOf(messages),
                    severe != null ? Map.copyOf(severe) : null
            ));
        }

        Map<String, Integer> endpointCounts = new LinkedHashMap<>();
        endpointCounts.put("list", listItems.size());
        endpointCounts.put("basic", basicItems.size());
        endpointCounts.put("beds", bedItems.size());
        endpointCounts.put("messages", messageItems.size());
        endpointCounts.put("severe", severeItems.size());
        endpointCounts.put("traumaList", traumaListItems.size());
        endpointCounts.put("traumaBasic", traumaBasicItems.size());

        Map<String, Boolean> sources = new LinkedHashMap<>();
        sources.put("list", !listItems.isEmpty());
        sources.put("basic", !basicItems.isEmpty());
        sources.put("beds", !bedItems.isEmpty());
        sources.put("messages", rawDatasets.containsKey("messages"));
        sources.put("severe", !severeItems.isEmpty());
        sources.put("traumaList", !traumaListItems.isEmpty());
        sources.put("traumaBasic", !traumaBasicItems.isEmpty());

        String updatedAt = ZonedDateTime.now(KST_ZONE)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        return new EmergencySnapshotDto(
                updatedAt,
                false,
                hospitals.size(),
                tertiaryCount,
                regionalCount,
                medicalCount,
                traumaCount,
                otherCount,
                Map.copyOf(sources),
                Map.copyOf(endpointCounts),
                List.copyOf(hospitals)
        );
    }

    private static Map<String, Map<String, String>> groupSingleById(List<Map<String, String>> items) {
        Map<String, Map<String, String>> map = new LinkedHashMap<>();
        for (Map<String, String> item : items) {
            String rawId = item.get("hpid");
            if (rawId == null || rawId.isBlank()) {
                continue;
            }
            String id = rawId.trim();
            map.put(id, item);
        }
        return map;
    }

    private static Map<String, List<Map<String, String>>> groupMultiById(List<Map<String, String>> items) {
        Map<String, List<Map<String, String>>> map = new LinkedHashMap<>();
        for (Map<String, String> item : items) {
            String rawId = item.get("hpid");
            if (rawId == null || rawId.isBlank()) {
                continue;
            }
            String id = rawId.trim();
            map.computeIfAbsent(id, k -> new ArrayList<>()).add(Map.copyOf(item));
        }
        return map;
    }

    private static double parseCoordinate(String text) {
        if (text == null || text.isBlank()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    private static boolean isValidKoreaCoordinate(double lat, double lng) {
        return Double.isFinite(lat)
                && Double.isFinite(lng)
                && lat >= MIN_LAT && lat <= MAX_LAT
                && lng >= MIN_LNG && lng <= MAX_LNG;
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return "";
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }

    private static String displayValue(String value) {
        if (value == null || value.isBlank()) {
            return "미정보";
        }
        return value.trim();
    }

    private static EmergencySnapshotDto withCachedFlag(EmergencySnapshotDto snapshot, boolean cached) {
        return new EmergencySnapshotDto(
                snapshot.updatedAt(),
                cached,
                snapshot.totalCount(),
                snapshot.tertiaryCount(),
                snapshot.regionalCount(),
                snapshot.medicalCount(),
                snapshot.traumaCount(),
                snapshot.otherCount(),
                snapshot.sources(),
                snapshot.endpointCounts(),
                snapshot.hospitals()
        );
    }
}
