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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ============================================================================
 * [클래스 역할]
 * 국립중앙의료원(NEMC) 7개 OpenAPI에서 수집한 흩어진 데이터를 **기관 고유코드({@code HPID}) 기준으로 하나로 병합**하고,
 * **인메모리 캐시(Stale-While-Revalidate)** + **서버 시작 즉시 사전 로딩(Pre-warming)** +
 * **20분 주기 백그라운드 자동 갱신({@code @Scheduled})**을 통해 사용자에게 0.01초 만에 응답하는 핵심 비즈니스 서비스입니다.
 * <p>
 * [파이썬 장고(Django) 비유]
 * - 장고 프로젝트에서 아래 3가지를 합쳐 놓은 핵심 비즈니스 계층입니다:
 *   1) **{@code services/emergency_service.py}** : 7종 공공데이터 병합 및 비즈니스 규칙 처리 ({@code mergeDatasetsIntoSnapshot})
 *   2) **{@code django.core.cache} (LocMemCache)** : 메모리 캐싱으로 매번 외부 API를 부르지 않고 초고속 응답 ({@code getSnapshot})
 *   3) **{@code apps.py}의 {@code ready()} + {@code Celery Beat} / {@code APScheduler}** : 서버 부팅 직후 사전 로딩({@code warmUpCacheOnStartup}) 및
 *      주기적 백그라운드 자동 갱신({@code scheduledBackgroundRefresh})
 * <p>
 * [코드의 경로성 (어디서 오고 어디로 가는가?)]
 * - [어디서 호출되는가 (Incoming)] :
 *   1) {@code controller/EmergencyApiController.java}의 {@code getHospitals()} ({@code GET /api/hospitals}),
 *      {@code rankDirections()} ({@code GET /api/directions/rank}), {@code geocode()} ({@code GET /api/geocode})에서 {@link #getSnapshot(boolean)} 호출!
 *   2) Spring 프레임워크 이벤트/스케줄러가 {@link #warmUpCacheOnStartup()}과 {@link #scheduledBackgroundRefresh()}를 자동 호출!
 * - [어느 파일을 부르는가 (Outgoing)] :
 *   1) {@code service/NmcOpenApiClient.java} ({@code fetchAllDatasets()}) → 공공데이터 XML 7종 수집
 *   2) {@code service/HospitalClassifier.java} ({@code isRegional()}, {@code classify()}) → 각 병원의 종별(상급종합/권역/의료원/기타) 판별
 *   3) {@code dto/HospitalDto.java} 및 {@code dto/EmergencySnapshotDto.java} 조립 후 컨트롤러로 반환 → {@code frontend/app.js}로 전송!
 * <p>
 * [수정 시 영향 및 예시]
 * - 대한민국 유효 위경도 필터 범위({@code MIN_LAT} ~ {@code MAX_LAT}, {@code MIN_LNG} ~ {@code MAX_LNG})를 넓히고 싶다면 아래 상수를 조정합니다:
 *   예시(변경 전): {@code private static final double MIN_LAT = 33.0;}
 *   예시(변경 후 — 이어도 인근 해역까지 포함 시): {@code private static final double MIN_LAT = 32.0;}
 * ============================================================================
 */
@Service
public class EmergencyDataService {

    private static final Logger log = LoggerFactory.getLogger(EmergencyDataService.class);

    /** 대한민국 영역 유효 위도(Latitude) 최소/최대 범위 — {@code (0.0, 0.0)} 같은 좌표 오류 병원이 지도를 망가뜨리는 것을 차단합니다. */
    private static final double MIN_LAT = 33.0;
    private static final double MAX_LAT = 39.0;

    /** 대한민국 영역 유효 경도(Longitude) 최소/최대 범위 */
    private static final double MIN_LNG = 124.0;
    private static final double MAX_LNG = 132.0;

    /** 스냅샷 생성 시각({@code updatedAt}) 표기를 위한 한국 표준시(KST, UTC+9) 시간대 */
    private static final ZoneId KST_ZONE = ZoneId.of("Asia/Seoul");

    /** 수동 강제 새로고침({@code ?refresh=true}) 연타 및 동시 호출로부터 공공데이터 트래픽 한도를 보호하기 위한 최소 쿨다운(초) */
    private static final long MIN_FORCE_REFRESH_COOLDOWN_SECONDS = 15L;

    private final NmcOpenApiClient openApiClient;
    private final HospitalClassifier classifier;
    private final Yonsei119Properties properties;

    /** 수동 새로고침이나 초기 콜드 스타트 시 여러 스레드가 동시에 공공데이터 API를 중복 호출하지 못하게 막는 동기화 자물쇠 */
    private final Object cacheLock = new Object();

    /** 백그라운드 비동기 갱신이 이미 돌고 있으면 중복 실행을 건너뛰기 위한 원자적 플래그 */
    private final AtomicBoolean backgroundRefreshInProgress = new AtomicBoolean(false);

    /** 사용자 요청 스레드와 완전히 분리된 백그라운드 캐시 워머 전용 단일 데몬 스레드 */
    private final ExecutorService backgroundRefreshExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "yonsei119-cache-warmer");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 가장 최근에 완성된 전국 병원 통합 스냅샷 인메모리 캐시.
     * {@code volatile} 키워드를 붙여 백그라운드 스레드가 새 스냅샷으로 갈아끼우는 즉시 모든 웹 요청 스레드가 최신본을 읽도록 보장합니다.
     */
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
     * [메서드 역할 — 서버 부팅 직후 자동 사전 로딩(Cache Pre-warming)]
     * Spring Boot 서버가 포트(8080) 개방을 마치자마자({@code ApplicationReadyEvent}) 자동 실행됩니다.
     * (장고 {@code AppConfig.ready()}에서 백그라운드 워커를 깨우는 것과 유사합니다.)
     * <p>
     * [왜 필요한가요?]
     * 첫 번째 방문자가 접속했을 때 7개 API를 그때서야 부르면 수 초를 기다려야 하지만,
     * 서버가 켜지자마자 미리 백그라운드에서 받아두면 첫 방문자도 0.01초 만에 전국 병원을 확인할 수 있습니다!
     * <p>
     * [수정 시 영향] 끄고 싶다면 {@code application.yml}의 {@code yonsei119.nemc.auto-refresh-enabled: false}로 바꾸세요.
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
     * [메서드 역할 — 20분 주기 백그라운드 자동 갱신(@Scheduled)]
     * {@code application.yml}의 {@code yonsei119.nemc.refresh-interval-ms}(기본 1,200,000ms = 20분) 간격으로 자동 실행됩니다.
     * ({@code Yonsei119Application.java}의 {@code @EnableScheduling} 덕분에 별도 Celery 서버 없이 내장 스케줄러로 동작합니다!)
     * <p>
     * [수정 시 영향 및 예시]
     * - 코드 수정 없이 {@code application.yml}의 {@code refresh-interval-ms: 600000}(10분)만 수정하면 주기가 바뀝니다.
     * - 만약 매일 정각마다 돌리는 Cron 표현식 방식으로 바꾸고 싶다면 어노테이션을 아래처럼 교체할 수 있습니다:
     *   예시(변경 후): {@code @Scheduled(cron = "0 0/15 * * * *", zone = "Asia/Seoul")} (매 15분 정각 실행)
     */
    @Scheduled(
            initialDelayString = "${yonsei119.nemc.refresh-interval-ms:1200000}",
            fixedDelayString = "${yonsei119.nemc.refresh-interval-ms:1200000}"
    )
    public void scheduledBackgroundRefresh() {
        if (!properties.nemc().autoRefreshEnabled()) {
            return;
        }
        triggerAsyncBackgroundRefresh("주기적 스케줄러(@Scheduled) 자동 갱신");
    }

    /**
     * [메서드 역할] 중복 실행 없이 백그라운드 스레드({@code yonsei119-cache-warmer})에 캐시 갱신 작업을 예약합니다.
     */
    public void triggerAsyncBackgroundRefresh(String reason) {
        if (!backgroundRefreshInProgress.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                refreshCacheSynchronously(reason, false, null);
            } finally {
                backgroundRefreshInProgress.set(false);
            }
        }, backgroundRefreshExecutor);
    }

    /**
     * [메서드 역할 — 컨트롤러가 호출하는 대표 진입 메서드!]
     * 전국 응급의료기관 통합 스냅샷({@code EmergencySnapshotDto})을 반환합니다.
     * <p>
     * [코드의 경로성]
     * - [어디서 왔는가 (Incoming)] : {@code EmergencyApiController.getHospitals()}, {@code rankDirections()}, {@code geocode()}
     * - [어디로 가는가 (Outgoing)] :
     *   - 캐시 히트 시: 메모리의 {@code cachedSnapshot}을 {@code asCachedSnapshot()}({@code cached = true})으로 감싸 0.01초 내 즉시 반환!
     *   - 캐시 미스 또는 {@code forceRefresh = true}(화면 '새로고침' 클릭) 시: {@link #refreshCacheSynchronously(String, boolean, Instant)} 호출!
     *
     * @param forceRefresh {@code true}이면 기존 캐시를 무시하고 즉시 공공데이터포털에서 최신 병상 데이터를 새로 받아옵니다.
     * @return 통합된 {@link EmergencySnapshotDto}
     */
    public EmergencySnapshotDto getSnapshot(boolean forceRefresh) {
        long ttlSeconds = Math.max(5L, properties.nemc().cacheTtlSeconds());
        Instant now = Instant.now();

        EmergencySnapshotDto current = this.cachedSnapshot;
        if (!forceRefresh && current != null) {
            // Stale-While-Revalidate: TTL이 지났더라도 사용자는 기다리게 하지 않고 기존 캐시를 먼저 준 뒤 뒤에서 갱신!
            if (now.isAfter(this.cachedAt.plusSeconds(ttlSeconds))) {
                triggerAsyncBackgroundRefresh("TTL 경과에 따른 비동기 Stale-While-Revalidate 갱신");
            }
            return asCachedSnapshot(current);
        }

        if (forceRefresh && current != null
                && now.isBefore(this.cachedAt.plusSeconds(MIN_FORCE_REFRESH_COOLDOWN_SECONDS))) {
            return asCachedSnapshot(current);
        }

        return refreshCacheSynchronously(
                forceRefresh ? "사용자 수동 새로고침 요청" : "최초 콜드 스타트 동기 수집",
                forceRefresh,
                now
        );
    }

    /**
     * [메서드 역할]
     * 동기화 블록({@code synchronized (cacheLock)}) 안에서 안전하게 신규 스냅샷을 만들어 {@code cachedSnapshot}을 교체합니다.
     * 동시 요청 병합(Request Coalescing)과 최소 쿨다운을 적용해 중복 외부 API 호출을 차단하며,
     * 외부 공공데이터포털 전체 장애로 빈 목록이 오더라도 기존 {@code current} 캐시를 날리지 않고 보존합니다!
     */
    private EmergencySnapshotDto refreshCacheSynchronously(
            String reason,
            boolean respectForceCooldown,
            Instant requestEnteredAt
    ) {
        synchronized (cacheLock) {
            EmergencySnapshotDto current = this.cachedSnapshot;
            Instant currentCachedAt = this.cachedAt;
            if (current != null) {
                if (requestEnteredAt != null && currentCachedAt.isAfter(requestEnteredAt)) {
                    return asCachedSnapshot(current);
                }
                if (respectForceCooldown
                        && Instant.now().isBefore(currentCachedAt.plusSeconds(MIN_FORCE_REFRESH_COOLDOWN_SECONDS))) {
                    return asCachedSnapshot(current);
                }
            }

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
                return asCachedSnapshot(current);
            } catch (Exception ex) {
                log.error("응급의료 데이터 스냅샷 갱신 중 오류 발생 [사유: {}]: {}", reason, ex.getMessage(), ex);
                if (current != null) {
                    return asCachedSnapshot(current);
                }
                throw ex;
            }
        }
    }

    /** 단위/통합 테스트에서 초기 캐시 스냅샷 상태(부분·전체 장애 폴백 검증용)를 설정합니다. */
    public void setCachedSnapshotForTest(EmergencySnapshotDto snapshot) {
        synchronized (cacheLock) {
            this.cachedSnapshot = snapshot;
            this.cachedAt = snapshot != null ? Instant.now() : Instant.EPOCH;
        }
    }

    /** 서버 종료 시 백그라운드 데몬 스레드 풀을 깔끔하게 정리합니다. */
    @PreDestroy
    public void shutdown() {
        backgroundRefreshExecutor.shutdownNow();
    }

    /**
     * [메서드 역할]
     * {@code NmcOpenApiClient.fetchAllDatasets()}로 7개 XML 데이터를 받아온 뒤 {@link #mergeDatasetsIntoSnapshot}으로 병합합니다.
     */
    public EmergencySnapshotDto buildFreshSnapshot() {
        Map<String, List<Map<String, String>>> rawDatasets = openApiClient.fetchAllDatasets();
        return mergeDatasetsIntoSnapshot(rawDatasets);
    }

    /**
     * [메서드 역할 — 7종 OpenAPI 데이터 교차 병합(Cross-Merge) 엔진]
     * 각각 따로 내려오는 7개 API 결과({@code list}, {@code basic}, {@code beds}, {@code messages}, {@code severe}, {@code traumaList}, {@code traumaBasic})를
     * 모든 병원의 공통 열쇠인 **기관코드({@code hpid}, 예: {@code "A2200001"})** 기준으로 레고 블록처럼 하나의 {@code HospitalDto}로 조립합니다.
     * (파이썬 Pandas의 {@code df_list.merge(df_beds, on="hpid", how="left")} 조인 연산을 순수 Java Map으로 초고속 처리하는 부분입니다!)
     * <p>
     * [코드의 경로성]
     * - [입력] {@code NmcOpenApiClient.fetchAllDatasets()} 결과 (또는 {@code Yonsei119BackendTests.testDatasetCrossMerge()}의 테스트 데이터)
     * - [내부 참조] {@code HospitalClassifier.isRegional()}, {@code HospitalClassifier.classify()} 호출
     * - [출력] {@code EmergencySnapshotDto} ({@code dto/EmergencySnapshotDto.java})
     */
    public EmergencySnapshotDto mergeDatasetsIntoSnapshot(Map<String, List<Map<String, String>>> rawDatasets) {
        List<Map<String, String>> listItems = rawDatasets.getOrDefault("list", List.of());
        List<Map<String, String>> basicItems = rawDatasets.getOrDefault("basic", List.of());
        List<Map<String, String>> bedItems = rawDatasets.getOrDefault("beds", List.of());
        List<Map<String, String>> messageItems = rawDatasets.getOrDefault("messages", List.of());
        List<Map<String, String>> severeItems = rawDatasets.getOrDefault("severe", List.of());
        List<Map<String, String>> traumaListItems = rawDatasets.getOrDefault("traumaList", List.of());
        List<Map<String, String>> traumaBasicItems = rawDatasets.getOrDefault("traumaBasic", List.of());

        // 빠른 O(1) 조회를 위해 각 리스트를 `hpid -> 데이터` 해시맵으로 인덱싱합니다 (중첩 루프 O(N^2) 방지)
        Map<String, Map<String, String>> listById = groupSingleById(listItems);
        Map<String, Map<String, String>> basicById = groupSingleById(basicItems);
        Map<String, Map<String, String>> bedsById = groupSingleById(bedItems);
        Map<String, List<Map<String, String>>> messagesById = groupMultiById(messageItems);
        Map<String, Map<String, String>> severeById = groupSingleById(severeItems);
        Map<String, Map<String, String>> traumaListById = groupSingleById(traumaListItems);
        Map<String, Map<String, String>> traumaBasicById = groupSingleById(traumaBasicItems);

        // 부분 장애 방어: 특정 API 하나만 일시적으로 빈 리스트를 반환하더라도 직전 캐시의 기관별 필드를 유지합니다.
        Map<String, HospitalDto> previousById = new LinkedHashMap<>();
        EmergencySnapshotDto previousSnapshot = this.cachedSnapshot;
        if (previousSnapshot != null && previousSnapshot.hospitals() != null) {
            for (HospitalDto prevHospital : previousSnapshot.hospitals()) {
                if (prevHospital.id() != null && !prevHospital.id().isBlank()) {
                    previousById.put(prevHospital.id(), prevHospital);
                }
            }
        }

        Set<String> uniqueIds = new LinkedHashSet<>(listById.keySet());
        uniqueIds.addAll(traumaListById.keySet());
        if (uniqueIds.isEmpty() && !previousById.isEmpty()) {
            uniqueIds.addAll(previousById.keySet());
        }

        List<HospitalDto> hospitals = new ArrayList<>(uniqueIds.size());
        int tertiaryCount = 0;
        int regionalCount = 0;
        int medicalCount = 0;
        int traumaCount = 0;
        int otherCount = 0;

        for (String id : uniqueIds) {
            HospitalDto prev = previousById.get(id);
            Map<String, String> listFields = listById.getOrDefault(id, traumaListById.getOrDefault(id, Map.of()));
            Map<String, String> basicFields = basicById.getOrDefault(id, Map.of());
            if (basicFields.isEmpty() && prev != null && prev.basic() != null) {
                basicFields = prev.basic();
            }

            Map<String, String> mergedBasic = new LinkedHashMap<>(basicFields);
            for (Map.Entry<String, String> entry : listFields.entrySet()) {
                String existing = mergedBasic.get(entry.getKey());
                if (existing == null || existing.isBlank()) {
                    mergedBasic.put(entry.getKey(), entry.getValue());
                }
            }

            Map<String, String> traumaFields = traumaBasicById.getOrDefault(
                    id,
                    traumaListById.getOrDefault(id, null)
            );
            if (traumaFields == null && traumaListItems.isEmpty() && traumaBasicItems.isEmpty()
                    && prev != null && prev.trauma() != null) {
                traumaFields = prev.trauma();
            }

            Map<String, String> beds = bedsById.get(id);
            if (beds == null && bedItems.isEmpty() && prev != null && prev.beds() != null) {
                beds = prev.beds();
            }

            Map<String, String> severe = severeById.get(id);
            if (severe == null && severeItems.isEmpty() && prev != null && prev.severe() != null) {
                severe = prev.severe();
            }

            List<Map<String, String>> messages = messagesById.getOrDefault(id, List.of());
            if (messages.isEmpty() && messageItems.isEmpty() && prev != null
                    && prev.messages() != null && !prev.messages().isEmpty()) {
                messages = prev.messages();
            }

            // 위도·경도 추출 (`wgs84lat`/`wgs84lon` 우선, 없으면 `latitude`/`longitude` 보조 태그 확인)
            double lat = parseCoordinate(
                    firstNonBlank(
                            listFields.get("wgs84lat"),
                            basicFields.get("wgs84lat"),
                            traumaFields != null ? traumaFields.get("wgs84lat") : null,
                            listFields.get("latitude")
                    )
            );
            if (!Double.isFinite(lat) && prev != null) {
                lat = prev.lat();
            }

            double lng = parseCoordinate(
                    firstNonBlank(
                            listFields.get("wgs84lon"),
                            basicFields.get("wgs84lon"),
                            traumaFields != null ? traumaFields.get("wgs84lon") : null,
                            listFields.get("longitude")
                    )
            );
            if (!Double.isFinite(lng) && prev != null) {
                lng = prev.lng();
            }

            // 대한민국 영역 밖이거나 (0, 0) 오류 좌표인 기관은 지도 마커 왜곡 방지를 위해 건너뜁니다.
            if (!isValidKoreaCoordinate(lat, lng)) {
                continue;
            }

            String name = displayValue(firstNonBlank(
                    listFields.get("dutyname"),
                    basicFields.get("dutyname"),
                    prev != null ? prev.name() : null
            ));
            String address = displayValue(firstNonBlank(
                    listFields.get("dutyaddr"),
                    basicFields.get("dutyaddr"),
                    prev != null ? prev.address() : null
            ));
            String tel = displayValue(firstNonBlank(
                    listFields.get("dutytel1"),
                    basicFields.get("dutytel1"),
                    prev != null ? prev.tel() : null
            ));
            String erTel = displayValue(firstNonBlank(
                    listFields.get("dutytel3"),
                    basicFields.get("dutytel3"),
                    beds != null ? beds.get("dutytel3") : null,
                    prev != null ? prev.erTel() : null
            ));

            Map<String, String> classificationFields = new LinkedHashMap<>(mergedBasic);
            String resolvedDutyName = firstNonBlank(listFields.get("dutyname"), basicFields.get("dutyname"));
            if (!resolvedDutyName.isEmpty()) {
                classificationFields.put("dutyname", resolvedDutyName);
            }
            String resolvedDutyAddr = firstNonBlank(listFields.get("dutyaddr"), basicFields.get("dutyaddr"));
            if (!resolvedDutyAddr.isEmpty()) {
                classificationFields.put("dutyaddr", resolvedDutyAddr);
            }

            // `list`·`basic`이 동시에 빈 장애 상황에서는 직전 정상 스냅샷(`prev`)의 권역/종별 등급을 보존합니다.
            boolean upstreamIdentityMissing = listItems.isEmpty() && basicItems.isEmpty() && traumaListItems.isEmpty();
            boolean regional = (upstreamIdentityMissing && prev != null)
                    ? (prev.regional() || classifier.isRegional(classificationFields))
                    : classifier.isRegional(classificationFields);
            HospitalTypeDto type = (upstreamIdentityMissing && prev != null && prev.type() != null)
                    ? prev.type()
                    : classifier.classify(classificationFields, name);
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
                    Map.copyOf(mergedBasic),
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

    /** 1:1 관계 데이터({@code list}, {@code basic}, {@code beds}, {@code severe})를 {@code hpid} 키 기준 단일 맵으로 묶는 헬퍼 */
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

    /** 1:N 관계 데이터({@code messages} — 한 병원이 여러 개의 응급실 공지 메시지를 올릴 수 있음)를 {@code hpid} 키 기준 리스트로 묶는 헬퍼 */
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

    /** 캐시에서 꺼내 줄 때 {@code cached} 플래그만 {@code true}로 표시한 복제 DTO를 생성합니다. */
    private static EmergencySnapshotDto asCachedSnapshot(EmergencySnapshotDto snapshot) {
        return new EmergencySnapshotDto(
                snapshot.updatedAt(),
                true,
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
