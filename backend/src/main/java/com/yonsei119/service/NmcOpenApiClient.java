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

    public NmcOpenApiClient(Yonsei119Properties properties) {
        this.properties = properties;
        long timeoutMs = Math.max(5000L, properties.nemc().requestTimeoutMs());
        this.fetchExecutor = Executors.newFixedThreadPool(ENDPOINTS.size());
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
     * 7개 NEMC OpenAPI 엔드포인트를 병렬(`CompletableFuture`)로 동시에 호출하여
     * 엔드포인트 키별 아이템 목록 맵을 반환합니다.
     *
     * @return 엔드포인트 식별자(`list`, `basic`, `beds` 등) -> 파싱된 아이템 리스트 맵
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
            try {
                results.put(entry.getKey(), entry.getValue().join());
            } catch (Exception ex) {
                log.warn("NEMC OpenAPI 엔드포인트 [{}] 수집 중 예외 발생 (빈 목록으로 대체): {}",
                        entry.getKey(), ex.getMessage());
                results.put(entry.getKey(), List.of());
            }
        }
        return results;
    }

    /**
     * [메서드 역할]
     * 특정 OpenAPI 오퍼레이션(예: `getEmrrmRltmUsefulSckbdInfoInqire`)의 1페이지부터 마지막 페이지까지
     * 전체 아이템을 조회하여 리스트로 반환합니다.
     *
     * @param operation 국립중앙의료원 OpenAPI 오퍼레이션 이름
     * @return 해당 오퍼레이션에서 수신한 전체 XML `<item>` 맵 리스트
     */
    public List<Map<String, String>> fetchAllPages(String operation) {
        List<Map<String, String>> mergedItems = new ArrayList<>();
        int page = 1;
        while (page <= MAX_PAGES) {
            ParsedXmlPage parsed = fetchSinglePage(operation, page, DEFAULT_ROWS_PER_PAGE);
            if (parsed.items().isEmpty()) {
                break;
            }
            mergedItems.addAll(parsed.items());
            if (parsed.totalCount() <= 0 || page * DEFAULT_ROWS_PER_PAGE >= parsed.totalCount()) {
                break;
            }
            page++;
        }
        return mergedItems;
    }

    /**
     * [메서드 역할]
     * 단일 페이지의 OpenAPI URL을 조립하고 HTTP GET 요청을 수행한 뒤 XML 응답을 파싱합니다.
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

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(requestUrl))
                    .timeout(Duration.ofMillis(Math.max(5000L, properties.nemc().requestTimeoutMs())))
                    .header("Accept", "application/xml, text/xml, */*")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("NEMC OpenAPI HTTP 상태코드 비정상: operation={}, status={}",
                        operation, response.statusCode());
                return new ParsedXmlPage(0, List.of());
            }

            return parseXmlResponse(response.body(), operation);
        } catch (Exception ex) {
            log.warn("NEMC OpenAPI 호출 실패: operation={}, page={}, 사유={}",
                    operation, pageNo, ex.getMessage());
            return new ParsedXmlPage(0, List.of());
        }
    }

    /**
     * [메서드 역할]
     * 공공데이터포털 인증키(`serviceKey`)가 이미 URL 인코딩되어 있는지(`%` 포함 여부) 확인하고,
     * 인코딩되지 않은 원본(Decoded) 키인 경우에만 UTF-8 URL 인코딩을 적용합니다.
     *
     * [수정 시 영향 및 예시]
     * - 입력(Decoded): `"abc+def/123=="` -> 출력: `"abc%2Bdef%2F123%3D%3D"`
     * - 입력(Encoded): `"abc%2Bdef%2F123%3D%3D"` -> 출력: `"abc%2Bdef%2F123%3D%3D"` (그대로 유지)
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
     * 국립중앙의료원 OpenAPI가 반환한 XML 문자열을 파싱하여 `totalCount`와 `<item>` 리스트를 추출합니다.
     *
     * [보안 처리]
     * OWASP 권고에 따라 XXE(외부 엔티티 주입) 공격 방어 옵션을 모두 활성화한
     * {@link DocumentBuilderFactory}를 생성하여 사용합니다.
     *
     * [도메인 주의사항]
     * `<hvec>-5</hvec>` 같은 음수 병상 값(초과수용)이나 `<hv28></hv28>` 같은 빈 태그를
     * 숫자로 강제 형변환하지 않고 `.trim()`된 문자열 그대로 보존합니다.
     *
     * @param xmlText   원본 XML 응답 본문
     * @param operation 로깅용 오퍼레이션 이름
     * @return 파싱된 {@link ParsedXmlPage}
     */
    public ParsedXmlPage parseXmlResponse(String xmlText, String operation) {
        if (xmlText == null || xmlText.isBlank()) {
            return new ParsedXmlPage(0, List.of());
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
                return new ParsedXmlPage(0, List.of());
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
            return new ParsedXmlPage(totalCount, items);
        } catch (Exception ex) {
            log.warn("NEMC OpenAPI XML 파싱 실패: operation={}, 사유={}", operation, ex.getMessage());
            return new ParsedXmlPage(0, List.of());
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
     * @param totalCount XML `<totalCount>` 값
     * @param items      추출된 `<item>` 맵 리스트
     */
    public record ParsedXmlPage(
            int totalCount,
            List<Map<String, String>> items
    ) {
    }
}
