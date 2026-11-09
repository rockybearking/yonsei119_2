package com.yonsei119.service;

import com.yonsei119.config.Yonsei119Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ============================================================================
 * [클래스 역할]
 * 국립중앙의료원(NEMC) 전국 응급의료정보조회서비스 OpenAPI({@code ErmctInfoInqireService})의
 * 7개 XML 엔드포인트를 비동기 병렬로 호출하고, 보안(XXE 방어)이 적용된 XML 파서로 읽어
 * Java {@code Map<String, String>} 리스트로 변환해 주는 **외부 공공데이터 통신 전담 클라이언트**입니다.
 * <p>
 * [파이썬 장고(Django) 비유]
 * - 파이썬 백엔드에서 외부 공공데이터를 긁어올 때 만드는 {@code clients/nemc_api_client.py} 파일과 똑같습니다!
 * - 파이썬의 {@code httpx.AsyncClient} / {@code requests} + {@code concurrent.futures.ThreadPoolExecutor} +
 *   {@code defusedxml.ElementTree} 조합을 Java 17 표준 내장 라이브러리만으로 구현한 클래스입니다.
 * <p>
 * [코드의 경로성 (어디서 오고 어디로 가는가?)]
 * - [설정을 어디서 읽는가 (Config Source)] :
 *   {@code application.yml} ({@code yonsei119.nemc.*}) → {@code config/Yonsei119Properties.java}에서 URL·인증키·타임아웃 주입
 * - [누가 이 클래스를 호출하는가 (Incoming Caller)] :
 *   1) {@code service/EmergencyDataService.java}의 {@code buildFreshSnapshot()}이 {@link #fetchAllDatasets()}를 호출합니다.
 *   2) {@code Yonsei119BackendTests.java}에서 {@link #parseXmlResponse} 및 {@link #ensureUrlEncodedServiceKey}를 단위 검증합니다.
 * - [결과가 어디로 가는가 (Outgoing)] :
 *   파싱된 7종 맵 데이터({@code list}, {@code basic}, {@code beds}, {@code messages}, {@code severe}, {@code traumaList}, {@code traumaBasic})가
 *   {@code service/EmergencyDataService.java}의 {@code mergeDatasetsIntoSnapshot()}으로 넘어가 {@code HPID} 기준으로 하나로 합쳐집니다!
 * <p>
 * [핵심 안정성 및 도메인 설계 포인트]
 * 1. **인증키 이중 인코딩 자동 방지** ({@link #ensureUrlEncodedServiceKey}):
 *    공공데이터포털의 고질적인 {@code SERVICE_KEY_IS_NOT_REGISTERED_ERROR} 원인이 되는 {@code %} 이중 인코딩을 자동 감지해 막습니다.
 * 2. **응급실 도메인 값 원본 보존** ({@link #parseXmlResponse}):
 *    {@code <hvec>-4</hvec>}(초과수용 4석)나 {@code <hv28></hv28>}(미보고)를 숫자 {@code 0}으로 왜곡하지 않고 원본 문자열 그대로 보존합니다.
 * 3. **클라우드 저사양(0.1 vCPU) 보호 및 자동 재시도·폴백**:
 *    동시 요청 스레드를 {@link #MAX_CONCURRENT_REQUESTS}(2개)로 제한하고, 일시적 타임아웃 시 지수 백오프 재시도 및
 *    직전 성공 데이터({@code lastSuccessfulByOperation}) 폴백으로 데이터 유실을 방어합니다.
 * <p>
 * [수정 시 영향 및 예시]
 * - 서버 사양이 넉넉한 환경(예: 2 vCPU 이상)으로 이전하여 7개 API를 더 빠르게 한꺼번에 받고 싶다면:
 *   예시(변경 전): {@code private static final int MAX_CONCURRENT_REQUESTS = 2;}
 *   예시(변경 후): {@code private static final int MAX_CONCURRENT_REQUESTS = 4;}
 * ============================================================================
 */
@Component
public class NmcOpenApiClient {

    private static final Logger log = LoggerFactory.getLogger(NmcOpenApiClient.class);

    /**
     * 페이지당 요청 건수. 전국 응급의료기관이 약 530여 개이므로 {@code 1000}으로 설정하면 대부분 1페이지(단 1번 호출)로 끝납니다.
     * - [수정 예시] 만약 공공데이터포털이 최대 {@code 500}건 제한으로 정책을 바꾼다면 {@code 1000}을 {@code 500}으로 고치세요 (자동 페이징이 2페이지까지 돌아갑니다).
     */
    private static final int DEFAULT_ROWS_PER_PAGE = 1000;

    /** 무한 루프 방지를 위한 단일 오퍼레이션당 최대 페이지 조회 한도 (최대 10,000건) */
    private static final int MAX_PAGES = 10;

    /**
     * 동시 요청 스레드 상한 (기본 2개).
     * Render Free 플랜(0.1 vCPU) 스로틀링 및 공공데이터포털 게이트웨이 동시 연결 차단을 예방합니다.
     */
    private static final int MAX_CONCURRENT_REQUESTS = 2;

    /** 단일 페이지 HTTP 요청 최대 시도 횟수 (1차 시도 + 실패 시 재시도 2회 = 총 3회) */
    private static final int MAX_ATTEMPTS_PER_PAGE = 3;

    /** 재시도 대기 기준 시간(밀리초): 1차 실패 후 800ms 대기, 2차 실패 후 1600ms 대기 */
    private static final long RETRY_BASE_BACKOFF_MS = 800L;

    /**
     * [상수 역할] 국립중앙의료원 OpenAPI 7대 핵심 전국 조회 오퍼레이션 매핑 사전.
     * - [수정 시 영향 및 예시] 만약 국립중앙의료원에서 8번째 신규 오퍼레이션(예: 소아 야간진료 {@code getPediatricInfo})을 추가한다면
     *   아래 {@code createEndpointMap()}에 {@code map.put("pediatric", "getPediatricInfo");} 한 줄을 추가하면 자동으로 함께 병렬 수집됩니다!
     */
    public static final Map<String, String> ENDPOINTS = createEndpointMap();

    private static Map<String, String> createEndpointMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("list", "getEgytListInfoInqire");                     // 1. 전국 응급의료기관 목록 조회 (좌표·주소·전화)
        map.put("basic", "getEgytBassInfoInqire");                    // 2. 응급의료기관 기본정보 조회 (진료과목·장비 보유)
        map.put("beds", "getEmrrmRltmUsefulSckbdInfoInqire");         // 3. 응급실 실시간 가용병상 조회 (핵심 실시간 데이터!)
        map.put("messages", "getEmrrmSrsillDissMsgInqire");           // 4. 응급실 및 중증질환 실시간 전달 메시지 조회
        map.put("severe", "getSrsillDissAceptncPosblInfoInqire");     // 5. 28대 중증질환 실시간 수용 가능 여부 조회
        map.put("traumaList", "getStrmListInfoInqire");               // 6. 권역외상센터 목록 조회
        map.put("traumaBasic", "getStrmBassInfoInqire");              // 7. 권역외상센터 기본정보 조회
        return Map.copyOf(map);
    }

    private final Yonsei119Properties properties;
    private final HttpClient httpClient;
    private final ExecutorService fetchExecutor;

    /**
     * 오퍼레이션별 마지막 정상 수집 결과를 보관하는 스레드 안전(Thread-Safe) 백업 캐시입니다.
     * 공공데이터포털 특정 API가 일시 점검/타임아웃이더라도 빈 화면이 뜨지 않고 직전 정상 데이터를 유지합니다.
     */
    private final Map<String, List<Map<String, String>>> lastSuccessfulByOperation = new ConcurrentHashMap<>();

    public NmcOpenApiClient(Yonsei119Properties properties) {
        this.properties = properties;
        long timeoutMs = Math.max(5000L, properties.nemc().requestTimeoutMs());
        this.fetchExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_REQUESTS);
        // 주의: HttpClient 내부 소켓 스레드와 호출용 fetchExecutor 스레드를 분리하고 HTTP/1.1 Keep-Alive를 사용하여
        // TLS 핸드셰이크 비용을 절약하고 스레드 고갈(Deadlock)을 방지합니다.
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * [메서드 역할]
     * 위 {@link #ENDPOINTS}에 정의된 7개 공공데이터 API를 비동기로 모두 수집해 결과 맵으로 돌려줍니다.
     * <p>
     * [코드의 경로성]
     * - [어디서 왔는가 (Incoming)] : {@code EmergencyDataService.buildFreshSnapshot()}에서 호출
     * - [어디로 가는가 (Outgoing)] : 내부 {@link #fetchAllPages(String)} 호출 → 완료 후 {@code EmergencyDataService.mergeDatasetsIntoSnapshot()}에 전달
     *
     * @return 엔드포인트 키({@code list}, {@code basic}, {@code beds} 등) → 파싱된 아이템 리스트 맵
     */
    public Map<String, List<Map<String, String>>> fetchAllDatasets() {
        Map<String, CompletableFuture<List<Map<String, String>>>> futures = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : ENDPOINTS.entrySet()) {
            String key = entry.getKey();
            String operation = entry.getValue();
            futures.put(key, CompletableFuture.supplyAsync(() -> fetchAllPages(operation), fetchExecutor));
        }

        Map<String, List<Map<String, String>>> results = new LinkedHashMap<>();
        for (Map.Entry<String, CompletableFuture<List<Map<String, String>>>> entry : futures.entrySet()) {
            String key = entry.getKey();
            String operation = ENDPOINTS.get(key);
            try {
                results.put(key, entry.getValue().join());
            } catch (Exception ex) {
                List<Map<String, String>> fallback = lastSuccessfulByOperation.getOrDefault(operation, List.of());
                log.warn("NEMC OpenAPI 엔드포인트 [{}] 수집 중 예외 발생 (직전 정상 데이터 {}건으로 폴백): {}",
                        key, fallback.size(), ex.getMessage());
                results.put(key, fallback);
            }
        }
        return results;
    }

    /**
     * [메서드 역할]
     * 단일 OpenAPI 오퍼레이션의 1페이지부터 마지막 페이지({@code totalCount} 도달 시점)까지 반복 조회해 합칩니다.
     * <p>
     * [코드의 경로성]
     * - [어디서 왔는가] {@link #fetchAllDatasets()}
     * - [어디로 가는가] {@link #fetchSinglePage(String, int, int)}
     *
     * @param operation 국립중앙의료원 OpenAPI 오퍼레이션 이름 (예: {@code "getEmrrmRltmUsefulSckbdInfoInqire"})
     * @return 수신된 전체 {@code <item>} 맵 리스트 (도중 실패 시 {@code lastSuccessfulByOperation} 폴백 반환)
     */
    public List<Map<String, String>> fetchAllPages(String operation) {
        List<Map<String, String>> mergedItems = new ArrayList<>();
        int page = 1;
        boolean allPagesSucceeded = true;

        while (page <= MAX_PAGES) {
            ParsedXmlPage parsed = fetchSinglePage(operation, page, DEFAULT_ROWS_PER_PAGE);
            if (!parsed.success()) {
                allPagesSucceeded = false;
                break;
            }
            if (parsed.items().isEmpty()) {
                break;
            }
            mergedItems.addAll(parsed.items());
            // 현재 페이지까지 받은 개수가 전체 개수(totalCount) 이상이면 추가 페이지 요청 없이 즉시 종료!
            if (parsed.totalCount() <= 0 || page * DEFAULT_ROWS_PER_PAGE >= parsed.totalCount()) {
                break;
            }
            page++;
        }

        if (allPagesSucceeded) {
            List<Map<String, String>> snapshot = List.copyOf(mergedItems);
            lastSuccessfulByOperation.put(operation, snapshot);
            return snapshot;
        }

        List<Map<String, String>> fallback = lastSuccessfulByOperation.get(operation);
        if (fallback != null && !fallback.isEmpty() && mergedItems.size() < fallback.size()) {
            log.warn("NEMC OpenAPI [{}] 호출 실패로 직전 정상 캐시 데이터({}건)를 폴백(Fallback)으로 유지합니다.",
                    operation, fallback.size());
            return fallback;
        }
        return mergedItems;
    }

    /**
     * [메서드 역할]
     * 실제 공공데이터포털 URL을 조립하여 HTTP GET 요청을 보내고 응답 본문(XML)을 {@link #parseXmlResponse}에 넘깁니다.
     * <p>
     * [수정 시 영향 및 예시]
     * - 만약 공공데이터포털에 시·도 필터({@code STAGE1=강원특별자치도})만 한정해서 조회하도록 바꾸고 싶다면 {@code requestUrl} 포맷을 수정합니다:
     *   예시(변경 전): {@code "%s/%s?serviceKey=%s&pageNo=%d&numOfRows=%d"}
     *   예시(변경 후): {@code "%s/%s?serviceKey=%s&STAGE1=%s&pageNo=%d&numOfRows=%d"} (단, 현재는 전국 조회를 지원하므로 전체 조회가 기본입니다)
     */
    public ParsedXmlPage fetchSinglePage(String operation, int pageNo, int numOfRows) {
        String baseUrl = properties.nemc().baseUrl().replaceAll("/+$", "");
        String encodedKey = ensureUrlEncodedServiceKey(properties.nemc().serviceKey());
        String requestUrl = String.format(
                "%s/%s?serviceKey=%s&pageNo=%d&numOfRows=%d",
                baseUrl, operation, encodedKey, pageNo, numOfRows
        );
        long timeoutMs = Math.max(5000L, properties.nemc().requestTimeoutMs());

        for (int attempt = 1; attempt <= MAX_ATTEMPTS_PER_PAGE; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(requestUrl))
                        .timeout(Duration.ofMillis(timeoutMs))
                        .header("Accept", "application/xml, text/xml, */*")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    if (attempt < MAX_ATTEMPTS_PER_PAGE) {
                        log.info("NEMC OpenAPI HTTP 상태코드 비정상 ({}차 시도, 재시도 예정): operation={}, page={}, status={}",
                                attempt, operation, pageNo, response.statusCode());
                        sleepBeforeRetry(attempt);
                        continue;
                    }
                    log.warn("NEMC OpenAPI HTTP 상태코드 비정상 (최종 실패): operation={}, page={}, status={}",
                            operation, pageNo, response.statusCode());
                    return new ParsedXmlPage(0, List.of(), false);
                }

                ParsedXmlPage parsed = parseXmlResponse(response.body(), operation);
                if (!parsed.success() && attempt < MAX_ATTEMPTS_PER_PAGE) {
                    log.info("NEMC OpenAPI 응답 비정상 ({}차 시도, 재시도 예정): operation={}, page={}",
                            attempt, operation, pageNo);
                    sleepBeforeRetry(attempt);
                    continue;
                }
                if (attempt > 1 && parsed.success()) {
                    log.info("NEMC OpenAPI 재시도 성공 ({}차 시도): operation={}, page={}, 수신건수={}",
                            attempt, operation, pageNo, parsed.items().size());
                }
                return parsed;
            } catch (Exception ex) {
                String failureReason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                if (attempt < MAX_ATTEMPTS_PER_PAGE) {
                    log.info("NEMC OpenAPI 일시적 호출 지연 ({}차 시도/{}회, 재시도 예정): operation={}, page={}, 사유={}",
                            attempt, MAX_ATTEMPTS_PER_PAGE, operation, pageNo, failureReason);
                    sleepBeforeRetry(attempt);
                } else {
                    log.warn("NEMC OpenAPI 호출 실패 (총 {}회 시도 소진): operation={}, page={}, 사유={}",
                            MAX_ATTEMPTS_PER_PAGE, operation, pageNo, failureReason);
                }
            }
        }
        return new ParsedXmlPage(0, List.of(), false);
    }

    /** 재시도 전 지수 백오프 대기 헬퍼 ({@code attempt = 1}일 때 800ms, {@code attempt = 2}일 때 1600ms 대기) */
    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(RETRY_BASE_BACKOFF_MS * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * [메서드 역할]
     * 공공데이터포털 인증키({@code serviceKey})가 이미 URL 인코딩되어 있는지({@code %} 포함 여부) 검사하여,
     * 평문(Decoded) 키일 때만 {@code URLEncoder.encode()}를 수행합니다.
     * <p>
     * [왜 중요한가요?]
     * 공공데이터포털 마이페이지에는 {@code Encoding 인증키}({@code %2F}, {@code %2B} 포함)와 {@code Decoding 인증키}({@code +}, {@code /} 포함) 두 개가 적혀 있는데,
     * 사용자가 둘 중 어느 것을 {@code application.yml}이나 환경변수에 복사해 넣더라도 에러 없이 100% 작동하게 해줍니다!
     */
    public static String ensureUrlEncodedServiceKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return "";
        }
        String trimmed = rawKey.trim();
        if (trimmed.contains("%")) {
            return trimmed;
        }
        return URLEncoder.encode(trimmed, StandardCharsets.UTF_8);
    }

    /**
     * [메서드 역할]
     * 국립중앙의료원 OpenAPI가 반환한 XML 문자열을 안전하게 파싱하여 {@code totalCount}와 각 {@code <item>}의 태그 맵 리스트를 추출합니다.
     * <p>
     * [주의: 태그 이름 소문자 통일 규칙]
     * - XML 원본 태그는 {@code <dutyName>}, {@code <HPID>}, {@code <hvec>}처럼 대소문자가 섞여 있지만,
     *   여기 {@code line 381}에서 {@code child.getNodeName().toLowerCase(Locale.ROOT)}로 **모든 키를 소문자({@code dutyname}, {@code hpid}, {@code hvec})로 통일**합니다!
     * - 따라서 {@code EmergencyDataService.java}와 프론트엔드 {@code frontend/app.js}에서도 항상 소문자 키({@code beds.hvec}, {@code basic.dutyeryn})로 꺼내 읽습니다.
     */
    public ParsedXmlPage parseXmlResponse(String xmlText, String operation) {
        if (xmlText == null || xmlText.isBlank()) {
            return new ParsedXmlPage(0, List.of(), false);
        }

        try {
            // OWASP 권고 XXE(외부 엔티티 주입) 공격 차단 설정 (파이썬의 `defusedxml`과 동일한 보안 조치)
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xmlText)));

            String resultCode = getFirstTagText(doc, "resultCode");
            if (!resultCode.isEmpty() && !"00".equals(resultCode)) {
                String resultMsg = getFirstTagText(doc, "resultMsg");
                log.warn("NEMC OpenAPI 응답 에러 코드: operation={}, resultCode={}, resultMsg={}",
                        operation, resultCode, resultMsg);
                return new ParsedXmlPage(0, List.of(), false);
            }

            int totalCount = 0;
            String totalCountText = getFirstTagText(doc, "totalCount");
            if (!totalCountText.isEmpty()) {
                try {
                    totalCount = Integer.parseInt(totalCountText);
                } catch (NumberFormatException ignored) {
                    // 숫자가 아닌 비정상 문자열인 경우 초기값(0)을 그대로 유지합니다.
                }
            }

            NodeList itemNodes = doc.getElementsByTagName("item");
            List<Map<String, String>> items = new ArrayList<>(itemNodes.getLength());
            for (int i = 0; i < itemNodes.getLength(); i++) {
                Node itemNode = itemNodes.item(i);
                if (itemNode.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element itemElement = (Element) itemNode;
                NodeList children = itemElement.getChildNodes();
                Map<String, String> fieldMap = new LinkedHashMap<>();
                for (int j = 0; j < children.getLength(); j++) {
                    Node child = children.item(j);
                    if (child.getNodeType() == Node.ELEMENT_NODE) {
                        // 모든 XML 태그명을 소문자로 통일하고, 값(음수 '-4'나 빈 문자열 '')은 왜곡 없이 trim만 수행해 저장
                        String key = child.getNodeName().toLowerCase(Locale.ROOT);
                        String value = child.getTextContent() != null ? child.getTextContent().trim() : "";
                        fieldMap.put(key, value);
                    }
                }
                if (!fieldMap.isEmpty()) {
                    items.add(fieldMap);
                }
            }
            return new ParsedXmlPage(totalCount, items, true);
        } catch (Exception ex) {
            log.warn("NEMC OpenAPI XML 파싱 실패: operation={}, 사유={}", operation, ex.getMessage());
            return new ParsedXmlPage(0, List.of(), false);
        }
    }

    private static String getFirstTagText(Document doc, String tagName) {
        NodeList list = doc.getElementsByTagName(tagName);
        if (list == null || list.getLength() == 0 || list.item(0) == null) {
            return "";
        }
        String text = list.item(0).getTextContent();
        return text != null ? text.trim() : "";
    }

    /**
     * 단일 XML 페이지 파싱 결과를 담는 불변 레코드입니다.
     *
     * @param totalCount XML {@code <totalCount>} 전체 데이터 건수
     * @param items      추출된 {@code <item>} 맵 리스트
     * @param success    통신 및 파싱 성공 여부 ({@code resultCode == "00"}일 때 {@code true})
     */
    public record ParsedXmlPage(
            int totalCount,
            List<Map<String, String>> items,
            boolean success
    ) {
    }
}
