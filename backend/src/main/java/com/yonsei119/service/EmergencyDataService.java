package com.yonsei119.service;

import com.yonsei119.config.Yonsei119Properties;
import com.yonsei119.dto.EmergencySnapshotDto;
import com.yonsei119.dto.HospitalDto;
import com.yonsei119.dto.HospitalTypeDto;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ============================================================================
 * [클래스 역할]
 * 국립중앙의료원(NEMC) 7개 OpenAPI 엔드포인트에서 수집한 데이터를 기관 고유코드(`HPID`) 기준으로
 * 교차 병합(Cross-Merge)하고, 인메모리 캐시(TTL), **서버 시작 즉시 사전 로딩(Cache Pre-warming)**,
 * **백그라운드 주기적 자동 갱신(`@Scheduled`)**을 통해 무지연으로 제공하는 핵심 서비스입니다.
 *
 * [담당 기능]
 * 1. 기존 VanillaJS(`app.js`)의 `fetchData()`, `groupById()`, 좌표 검증 루프,
 *    기관 종별 분류(`classify`, `isRegional`)를 백엔드에서 일괄 수행합니다.
 * 2. OpenAPI 설명서(V13) 더블체크 및 현장 실사 도메인 규칙 보존:
 *    - 위경도 태그가 `wgs84Lat`/`wgs84Lon`으로 내려오는 API와 `latitude`/`longitude`로 내려오는 API를 모두 지원합니다.
 *    - 현장 실사 도메인 규칙인 병상 음수 값(예: `-3` = 초과수용)과 미보고 빈 값(`""` = `-`)을
 *      손실 없이 그대로 `HospitalDto.beds()`에 담아 전달합니다.
 * 3. **서버 시작 즉시 사전 로딩(Pre-warming) 및 백그라운드 주기적 자동 갱신**:
 *    - 서버가 켜지자마자({@link ApplicationReadyEvent}) 사용자가 접속하기 전에 백그라운드에서
 *      전국 530여 개 병원 데이터를 미리 수집해 메모리에 적재합니다.
 *    - 이후 설정된 주기(`refreshIntervalMs`, 기본 45초)마다 백그라운드 스케줄러({@link Scheduled})가
 *      자동으로 최신 데이터를 갈아끼워, 최초 접속자나 오랜만에 접속한 사용자도 대기 시간 없이
 *      즉시(0.05초 이내) 병원 목록을 볼 수 있습니다.
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
    private final AtomicBoolean backgroundRefreshInProgress = new AtomicBoolean(false);
    private final ExecutorService backgroundRefreshExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "yonsei119-cache-warmer");
        thread.setDaemon(true);
        return thread;
    });

    private volatile EmergencySnapshotDto cachedSnapshot;
    private volatile Instant cachedAt = Instant.EPOCH;

    /**
     * [생성자 역할]
     * OpenAPI 클라이언트, 병원 종별 분류기, 애플리케이션 설정 프로퍼티를 주입받아 초기화합니다.
     *
     * @param openApiClient NEMC 7대 OpenAPI 수집 클라이언트
     * @param classifier    상급종합/권역/의료원 분류기
     * @param properties    애플리케이션 통합 설정 프로퍼티
     */
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
     * [메서드 역할 - 요구사항 반영 1: 서버 시작 즉시 사전 로딩(Cache Pre-warming)]
     * Spring Boot 내장 Tomcat이 포트 바인딩을 완료한 직후({@link ApplicationReadyEvent}) 자동으로 실행됩니다.
     *
     * [동작 원리]
     * - Render 클라우드 헬스체크(`/api/health`)가 즉시 통과할 수 있도록 메인 스레드를 블로킹하지 않고
     *   비동기 데몬 스레드(`yonsei119-cache-warmer`)에서 즉시 전국 530개 병원 데이터를 수집해 캐시에 채워둡니다.
     * - 덕분에 배포 직후나 서버 재시작 직후 첫 번째 사용자가 접속하더라도 빈 캐시를 기다리지 않고
     *   이미 준비된 병원 데이터를 즉시 응답받을 수 있습니다.
     *
     * [수정 시 영향 및 예시]
     * - 서버 기동 시 자동 사전 로딩을 끄고 싶다면 `application.yml`에서
     *   `yonsei119.nemc.auto-refresh-enabled: false`로 설정하면 됩니다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpCacheOnStartup() {
        if (!properties.nemc().autoRefreshEnabled()) {
            log.info("NEMC 자동 캐시 사전 로딩(Pre-warming)이 비활성화 상태입니다 (auto-refresh-enabled=false).");
            return;
        }
        log.info("서버 기동 완료 — 백그라운드에서 전국 응급의료기관 초기 캐시 사전 로딩(Pre-warming)을 시작합니다.");
        triggerAsyncBackgroundRefresh("서버 시작 직후 사전 로딩(Pre-warming)");
    }

    /**
     * [메서드 역할 - 요구사항 반영 2: 백그라운드 주기적 자동 갱신(@Scheduled)]
     * 사용자의 접속 여부와 무관하게 설정된 주기(`yonsei119.nemc.refresh-interval-ms`, 기본 45초)마다
     * 백그라운드에서 자동으로 공공데이터포털 7종 API를 호출해 인메모리 캐시를 최신 상태로 교체합니다.
     *
     * [수정 시 영향 및 예시]
     * - 갱신 주기를 45초(45000ms)에서 60초(60000ms)로 늘리고 싶다면 `application.yml`의
     *   `refresh-interval-ms: 60000` 값을 변경하면 코드 수정 없이 즉시 반영됩니다.
     */
    @Scheduled(
            initialDelayString = "${yonsei119.nemc.refresh-interval-ms:45000}",
            fixedDelayString = "${yonsei119.nemc.refresh-interval-ms:45000}"
    )
    public void scheduledBackgroundRefresh() {
        if (!properties.nemc().autoRefreshEnabled()) {
            return;
        }
        triggerAsyncBackgroundRefresh("주기적 스케줄러(@Scheduled) 자동 갱신");
    }

    /**
     * [메서드 역할]
     * 이미 백그라운드 갱신이 진행 중이 아닐 때만 비동기 스레드에서 캐시 갱신을 수행합니다.
     * 중복 호출이 겹쳐도 `AtomicBoolean.compareAndSet(false, true)`를 통해 단 하나의 갱신 작업만 실행됩니다.
     *
     * @param reason 로그 추적용 갱신 사유 문자열
     */
    public void triggerAsyncBackgroundRefresh(String reason) {
        if (!backgroundRefreshInProgress.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                refreshCacheSynchronously(reason);
            } finally {
                backgroundRefreshInProgress.set(false);
            }
        }, backgroundRefreshExecutor);
    }

    /**
     * [메서드 역할]
     * 전국 응급의료기관 통합 스냅샷을 반환합니다.
     *
     * [성능 최적화 (Stale-While-Revalidate 패턴)]
     * 1. 일반 조회(`forceRefresh = false`) 시 이미 메모리에 캐시된 스냅샷(`cachedSnapshot`)이 존재하면:
     *    - 사용자 HTTP 요청 스레드를 절대 대기시키지 않고 **즉시(0.01초 이내) 캐시본을 반환**합니다.
     *    - 만약 캐시 TTL이 지났다면, 사용자에게는 직전 캐시본을 즉시 돌려주면서 동시에 백그라운드 스레드에
     *      비동기 갱신(`triggerAsyncBackgroundRefresh`)을 지시하여 다음 조회 시 최신본이 나가도록 합니다.
     * 2. 아직 서버가 막 켜져서 첫 사전 로딩이 끝나기 전(`cachedSnapshot == null`)이거나,
     *    사용자가 화면의 '새로고침' 버튼을 직접 눌러 강제 동기화(`forceRefresh = true`)를 요청한 경우에만
     *    동기화 락(`cacheLock`) 안에서 즉시 수집 후 최신 스냅샷을 반환합니다.
     *
     * @param forceRefresh 사용자가 '새로고침' 버튼을 눌러 캐시를 무시하고 즉시 갱신을 요청했는지 여부
     * @return 통합된 {@link EmergencySnapshotDto}
     */
    public EmergencySnapshotDto getSnapshot(boolean forceRefresh) {
        long ttlSeconds = Math.max(5L, properties.nemc().cacheTtlSeconds());
        Instant now = Instant.now();

        EmergencySnapshotDto current = this.cachedSnapshot;
        if (!forceRefresh && current != null) {
            if (now.isAfter(this.cachedAt.plusSeconds(ttlSeconds))) {
                triggerAsyncBackgroundRefresh("TTL 경과에 따른 비동기 Stale-While-Revalidate 갱신");
            }
            return withCachedFlag(current, true);
        }

        return refreshCacheSynchronously(forceRefresh ? "사용자 수동 새로고침 요청" : "최초 콜드 스타트 동기 수집");
    }

    /**
     * [메서드 역할]
     * 동기화 락(`cacheLock`) 내부에서 NEMC OpenAPI 7종 데이터를 새로 수집·병합하여 인메모리 캐시를 갱신합니다.
     *
     * @param reason 로그 출력용 갱신 사유
     * @return 갱신된(또는 폴백 유지된) {@link EmergencySnapshotDto}
     */
    private EmergencySnapshotDto refreshCacheSynchronously(String reason) {
        synchronized (cacheLock) {
            EmergencySnapshotDto current = this.cachedSnapshot;
            long startMs = System.currentTimeMillis();
            try {
                EmergencySnapshotDto fresh = buildFreshSnapshot();
                long elapsedMs = System.currentTimeMillis() - startMs;
                if (!fresh.hospitals().isEmpty() || current == null) {
                    this.cachedSnapshot = fresh;
                    this.cachedAt = Instant.now();
                    log.info("응급의료기관 캐시 갱신 완료 [사유: {}] — 총 {}개 기관 ({}ms 소요)",
                            reason, fresh.totalCount(), elapsedMs);
                    return fresh;
                }
                log.warn("신규 수집된 병원 목록이 비어 있어 직전 정상 캐시 스냅샷을 유지합니다 [사유: {}]", reason);
                return withCachedFlag(current, true);
            } catch (Exception ex) {
                log.error("응급의료 데이터 스냅샷 갱신 중 오류 발생 [사유: {}]: {}", reason, ex.getMessage(), ex);
                if (current != null) {
                    return withCachedFlag(current, true);
                }
                throw ex;
            }
        }
    }

    /**
     * 애플리케이션 종료 시 백그라운드 캐시 갱신 스레드 풀을 안전하게 정리합니다.
     */
    @PreDestroy
    public void shutdown() {
        backgroundRefreshExecutor.shutdownNow();
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
