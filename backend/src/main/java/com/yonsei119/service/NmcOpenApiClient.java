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
 * 국립중앙의료원(NEMC) 전국 응급의료정보조회서비스 OpenAPI(`ErmctInfoInqireService`)의
 * XML 데이터를 비동기 병렬로 수집하고, XXE(XML External Entity) 보안 취약점이 차단된
 * 안전한 파서로 정규화하는 HTTP 클라이언트입니다.
 *
 * [담당 기능]
 * 1. 기존 VanillaJS(`app.js`)의 `requestPage()`, `requestAll()`, `readXml()`, `fieldMap()`을
 *    백엔드 Java 17 표준 `HttpClient` + `CompletableFuture` 기반으로 완전 대체합니다.
 * 2. 공공데이터포털 인증키(`serviceKey`)가 Decoded 상태이든 Encoded 상태이든 자동 감지하여
 *    이중 인코딩(Double-Encoding)으로 인한 `SERVICE_KEY_IS_NOT_REGISTERED_ERROR`를 방지합니다.
 * 3. 한 페이지당 1,000건(`numOfRows=1000`)씩 요청하며, 만약 `totalCount`가 1,000건을 초과하는
 *    엔드포인트(예: 전국 응급실 메시지 폭증 시)가 있으면 자동으로 다음 페이지를 끝까지 수집합니다.
 * 4. 현장 실사 도메인 규칙 보존:
 *    - XML 태그 내부 텍스트를 파싱할 때 음수 값(예: `-4`, 초과수용 의미)이나 `0`, 빈 문자열(`""`)을
 *      절대 임의 변환하지 않고 원본 문자열 그대로 보존합니다.
 * 5. Render 클라우드(Free 플랜 0.1 vCPU & 해외 리전) 안정성 강화:
 *    - 7개 대용량 API를 한꺼번에 동시 호출할 때 발생하는 CPU 스로틀링 및 공공데이터포털 게이트웨이 지연을
 *      방지하기 위해 동시 요청 수를 {@link #MAX_CONCURRENT_REQUESTS}(2개)로 제한하고 HTTP/1.1 Keep-Alive
 *      TLS 세션을 재사용합니다.
 *    - 간헐적 네트워크 타임아웃 발생 시 지수 백오프(Exponential Backoff)로 최대 {@link #MAX_ATTEMPTS_PER_PAGE}회
 *      자동 재시도하며, 모든 재시도가 실패하더라도 직전 정상 수집 데이터(`lastSuccessfulByOperation`)를
 *      폴백(Fallback)으로 반환하여 병상·중증질환·메시지 데이터가 유실되지 않도록 보호합니다.
 *
 * [수정 시 영향 및 예시]
 * - 페이지당 요청 건수를 1000건에서 500건으로 줄이고 싶다면 {@link #DEFAULT_ROWS_PER_PAGE} 상수를 수정합니다.
 *   예시(변경 전): {@code private static final int DEFAULT_ROWS_PER_PAGE = 1000;}
 *   예시(변경 후): {@code private static final int DEFAULT_ROWS_PER_PAGE = 500;}
 * ============================================================================
 */
@Component
public class NmcOpenApiClient {

    private static final Logger log = LoggerFactory.getLogger(NmcOpenApiClient.class);

    /** 페이지당 기본 조회 건수 (전국 약 530개 응급의료기관을 단일 요청으로 수신하기 위해 1000으로 설정) */
    private static final int DEFAULT_ROWS_PER_PAGE = 1000;

    /** 무한 루프 방지를 위한 단일 오퍼레이션당 최대 페이지 조회 한도 */
    private static final int MAX_PAGES = 10;

    /**
     * Render Free 플랜(0.1 vCPU) 스로틀링 및 공공데이터포털 동시 연결 제한 방지를 위한 최대 동시 요청 스레드 수.
     * 2개 스레드가 HTTP/1.1 Keep-Alive 커넥션 풀의 TLS 소켓을 재사용하여 순차·병렬 수집합니다.
     */
    private static final int MAX_CONCURRENT_REQUESTS = 2;

    /** 단일 페이지 HTTP 요청 시 최대 시도 횟수 (초기 1회 + 재시도 2회 = 총 3회) */
    private static final int MAX_ATTEMPTS_PER_PAGE = 3;

    /** 재시도 간 기본 대기 시간(밀리초, 1차 재시도 800ms -> 2차 재시도 1600ms) */
    private static final long RETRY_BASE_BACKOFF_MS = 800L;

    /**
     * 국립중앙의료원 OpenAPI 7대 핵심 전국 조회 오퍼레이션 정의 맵.
     * - 참고: OpenAPI 활용가이드(V13)의 `getEgytLcinfoInqire` 및 `getStrmLcinfoInqire`는
     *   특정 단일 좌표(WGS84_LON, WGS84_LAT) 주변만 조회하는 API이며,
     *   전국 기관의 위경도(`wgs84Lat`, `wgs84Lon`)는 아래 `list` 및 `basic` 오퍼레이션에
     *   전수 포함되어 있으므로 불필요한 중복 호출을 제거하여 동기화 속도를 개선했습니다.
     */
    public static final Map<String, String> ENDPOINTS = createEndpointMap();

    private static Map<String, String> createEndpointMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("list", "getEgytListInfoInqire");
        map.put("basic", "getEgytBassInfoInqire");
        map.put("beds", "getEmrrmRltmUsefulSckbdInfoInqire");
        map.put("messages", "getEmrrmSrsillDissMsgInqire");
        map.put("severe", "getSrsillDissAceptncPosblInfoInqire");
        map.put("traumaList", "getStrmListInfoInqire");
        map.put("traumaBasic", "getStrmBassInfoInqire");
        return Map.copyOf(map);
    }

    private final Yonsei119Properties properties;
    private final HttpClient httpClient;
    private final ExecutorService fetchExecutor;

    /**
     * 개별 오퍼레이션별로 가장 최근에 정상 수집된 아이템 목록을 보관하는 스레드 안전 캐시입니다.
     * 특정 오퍼레이션(예: `getEmrrmSrsillDissMsgInqire`, `getSrsillDissAceptncPosblInfoInqire`)이
     * 모든 재시도 후에도 타임아웃될 경우, 빈 목록(`List.of()`)으로 기존 캐시를 덮어쓰지 않고
     * 직전 정상 데이터를 폴백(Fallback)으로 반환하여 데이터 유실을 방지합니다.
     */
    private final Map<String, List<Map<String, String>>> lastSuccessfulByOperation = new ConcurrentHashMap<>();

    public NmcOpenApiClient(Yonsei119Properties properties) {
        this.properties = properties;
        long timeoutMs = Math.max(5000L, properties.nemc().requestTimeoutMs());
        this.fetchExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_REQUESTS);
        // 주의: HttpClient 내부 비동기 SSL/소켓 이벤트 루프가 호출 스레드 풀(fetchExecutor)과
        // 데드락(Thread Starvation)을 일으키지 않도록 별도의 기본 스레드 풀과 HTTP/1.1을 사용합니다.
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * [메서드 역할]
     * 7개 NEMC OpenAPI 엔드포인트를 제한 병렬(`MAX_CONCURRENT_REQUESTS = 2`)로 호출하여
     * 엔드포인트 키별 아이템 목록 맵을 반환합니다.
     *
     * @return 엔드포인트 식별자(`list`, `basic`, `beds` 등)별 파싱된 아이템 리스트 맵
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
     * 특정 OpenAPI 오퍼레이션(예: `getEmrrmRltmUsefulSckbdInfoInqire`)의 1페이지부터 마지막 페이지까지
     * 전체 아이템을 조회하여 리스트로 반환합니다.
     * 만약 네트워크 타임아웃 등으로 수집에 실패하면 직전 정상 수집 데이터(`lastSuccessfulByOperation`)를 반환합니다.
     *
     * @param operation 국립중앙의료원 OpenAPI 오퍼레이션 이름
     * @return 해당 오퍼레이션에서 수신한 전체 XML {@code <item>} 맵 리스트 (실패 시 직전 정상 데이터 폴백)
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
     * 단일 페이지의 OpenAPI URL을 조립하고 HTTP GET 요청을 수행한 뒤 XML 응답을 파싱합니다.
     * 일시적인 네트워크 타임아웃이나 게이트웨이 지연 발생 시 최대 {@link #MAX_ATTEMPTS_PER_PAGE}회까지
     * 지수 백오프(800ms -> 1600ms)를 두고 자동 재시도합니다.
     *
     * @param operation 오퍼레이션명
     * @param pageNo    페이지 번호 (1부터 시작)
     * @param numOfRows 페이지당 결과 수
     * @return 파싱된 {@link ParsedXmlPage} 결과
     */
    public ParsedXmlPage fetchSinglePage(String operation, int pageNo, int numOfRows) {
        String baseUrl = properties.nemc().baseUrl().replaceAll("/+$", "");
        String encodedKey = ensureUrlEncodedServiceKey(properties.nemc().serviceKey());
        String requestUrl = String.format(
                "%s/%s?serviceKey=%s&pageNo=%d&numOfRows=%d",
                baseUrl, operation, encodedKey, pageNo, numOfRows
        );
        long timeoutMs = Math.max(5000L, properties.nemc().requestTimeoutMs());
        String lastFailureReason = "unknown";

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
                    lastFailureReason = "HTTP status " + response.statusCode();
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
                    lastFailureReason = "invalid XML or non-00 resultCode";
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
                lastFailureReason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                if (attempt < MAX_ATTEMPTS_PER_PAGE) {
                    log.info("NEMC OpenAPI 일시적 호출 지연 ({}차 시도/{}회, 재시도 예정): operation={}, page={}, 사유={}",
                            attempt, MAX_ATTEMPTS_PER_PAGE, operation, pageNo, lastFailureReason);
                    sleepBeforeRetry(attempt);
                } else {
                    log.warn("NEMC OpenAPI 호출 실패 (총 {}회 시도 소진): operation={}, page={}, 사유={}",
                            MAX_ATTEMPTS_PER_PAGE, operation, pageNo, lastFailureReason);
                }
            }
        }
        return new ParsedXmlPage(0, List.of(), false);
    }

    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(RETRY_BASE_BACKOFF_MS * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * [메서드 역할]
     * 공공데이터포털 인증키(`serviceKey`)가 이미 URL 인코딩되어 있는지(`%` 포함 여부) 확인하고,
     * 인코딩되지 않은 원본(Decoded) 키인 경우에만 UTF-8 URL 인코딩을 적용합니다.
     *
     * [수정 시 영향 및 예시]
     * - 입력(Decoded): {@code "abc+def/123=="} -> 출력: {@code "abc%2Bdef%2F123%3D%3D"}
     * - 입력(Encoded): {@code "abc%2Bdef%2F123%3D%3D"} -> 출력: {@code "abc%2Bdef%2F123%3D%3D"} (그대로 유지)
     *
     * @param rawKey 설정 파일 또는 환경변수에서 읽은 인증키
     * @return URL 쿼리 파라미터에 안전하게 삽입 가능한 인증키 문자열
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
     * 국립중앙의료원 OpenAPI가 반환한 XML 문자열을 파싱하여 {@code totalCount}와 {@code <item>} 리스트를 추출합니다.
     *
     * [보안 처리]
     * OWASP 권고에 따라 XXE(외부 엔티티 주입) 공격 방어 옵션을 모두 활성화한
     * {@link DocumentBuilderFactory}를 생성하여 사용합니다.
     *
     * [도메인 주의사항]
     * {@code <hvec>-5</hvec>} 같은 음수 병상 값(초과수용)이나 {@code <hv28></hv28>} 같은 빈 태그를
     * 숫자로 강제 형변환하지 않고 {@code .trim()}된 문자열 그대로 보존합니다.
     *
     * @param xmlText   원본 XML 응답 본문
     * @param operation 로깅용 오퍼레이션 이름
     * @return 파싱된 {@link ParsedXmlPage}
     */
    public ParsedXmlPage parseXmlResponse(String xmlText, String operation) {
        if (xmlText == null || xmlText.isBlank()) {
            return new ParsedXmlPage(0, List.of(), false);
        }

        try {
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
                    totalCount = 0;
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
     * 단일 XML 페이지 파싱 결과를 담는 내부 불변 레코드입니다.
     *
     * @param totalCount XML {@code <totalCount>} 값
     * @param items      추출된 {@code <item>} 맵 리스트
     * @param success    HTTP 통신 및 XML 파싱 성공 여부 (타임아웃/에러 시 {@code false})
     */
    public record ParsedXmlPage(
            int totalCount,
            List<Map<String, String>> items,
            boolean success
    ) {
        public ParsedXmlPage(int totalCount, List<Map<String, String>> items) {
            this(totalCount, items, true);
        }
    }
}
