/* global naver */
'use strict';

/**
 * ==============================================================================
 * [파일 역할]
 * 연세 119 응급실 정보 시스템 — 프론트엔드 메인 컨트롤러 (frontend/app.js)
 *
 * [코드의 경로성 — 프론트엔드와 백엔드는 어떻게 연결되는가? (Python Django 비유 가이드)]
 * 파이썬 장고(Django)에서 `템플릿 HTML/JS ↔ urls.py ↔ views.py ↔ services.py ↔ serializers.py`가
 * 서로 바통을 주고받듯, 이 프로젝트도 아래와 같은 명확한 파일 경로로 이어집니다:
 *
 *   1) [진입 경로 (어떻게 이 JS 파일이 실행되는가?)]
 *      - 브라우저가 `http://localhost:8080/` 접속
 *      → `backend/.../config/WebConfig.java` (Django의 `urls.py` 정적 라우팅 + `STATICFILES_DIRS` 역할)
 *      → `frontend/index.html` 로드
 *      → `<script src="app.js" defer fetchpriority="high">`가 이 파일 하단의 `init()`을 자동 실행합니다.
 *
 *   2) [백엔드 API 호출 경로 (이 파일의 `fetch(...)`가 백엔드 어느 Java 파일로 가는가?)]
 *      ┌─────────────────────────────┬──────────────────────────────────────────┬─────────────────────────────────────────────┐
 *      │ 프론트엔드 함수 (`app.js`)   │ HTTP 엔드포인트 (`EmergencyApiController`) │ 실제 처리 백엔드 서비스 & 반환 DTO (`.java`) │
 *      │ (브라우저 클라이언트)         │ (Django의 `urls.py` + `views.py` 역할)    │ (Django의 `services.py` + `serializers.py`) │
 *      ├─────────────────────────────┼──────────────────────────────────────────┼─────────────────────────────────────────────┤
 *      │ `fetchData()`               │ `GET /api/hospitals`                     │ `EmergencyDataService` → `EmergencySnapshotDto` │
 *      │                             │                                          │ (내부에서 `NmcOpenApiClient`, `HospitalClassifier` 호출) │
 *      │ `loadDirectionsForHospital` │ `GET /api/directions`                    │ `NaverDirectionsService` → `DirectionsResponseDto` │
 *      │ & `refreshActiveRoute()`    │                                          │ (네이버 Directions 5 / 15 실도로 경로·혼잡도) │
 *      │ `loadEtaRanking()`          │ `GET /api/directions/rank`               │ `NaverDirectionsService` → `List<HospitalEtaRankDto>` │
 *      │ `geocodeAndMoveOrigin()`    │ `GET /api/geocode`                       │ `NaverDirectionsService` → `GeocodeResponseDto` │
 *      │ `updateOriginAddress()`     │ `GET /api/reverse-geocode`               │ `NaverDirectionsService` → `ReverseGeocodeResponseDto` │
 *      └─────────────────────────────┴──────────────────────────────────────────┴─────────────────────────────────────────────┘
 *
 *   3) [프론트엔드 내부 파일 간 상호작용 (`index.html` ↔ `app.js` ↔ `style.css`)]
 *      - `index.html`: `#map`, `#search-panel`, `#active-route-banner`, `#sheet` 등 DOM 뼈대(ID) 제공
 *      - `app.js`    : DOM ID(`byId(...)`)를 찾아 데이터 주입, 이벤트 바인딩, 네이버 WebGL 지도 제어
 *      - `style.css` : `app.js`가 토글하는 클래스(`.dark-mode`, `.open`, `.touch-device`, `.hospital-card`)와
 *                      인라인 `style.translate` 좌표를 받아 GPU 합성 레이어에서 60fps로 화면에 그림
 *
 * [Modern Web Guidance 4단계 지도·웹 성능 최적화 적용 요약]
 * - Phase 1: `createNaverDomOverlay`에서 레이아웃 리플로우를 유발하는 `left`/`top` 대신
 *            GPU 컴포지터 전용 `style.translate` 적용 (`CSS.supports('translate', '0px')` 폴백 포함)
 * - Phase 2: `state.markerMap` 기반 Key-Diffing으로 지도 이동 시 기존 `.hospital-card` DOM 재사용,
 *            `groupCards`를 $O(N^2)$에서 $O(N)$ 2D 공간 해시 격자(Spatial Hash Grid)로 개선,
 *            경로 안내 폴리라인을 단일 Mapbox GL GeoJSON 소스(`yonsei-route-source`)로 통합해 `< 1ms` 갱신
 * - Phase 3: 역지오코딩 및 백그라운드 자동 갱신 `fetch()`에 `priority: 'low'` 부여해 초기 지도 타일 대역폭 보호
 * - Phase 4: `<meta name="color-scheme">` 동기화, `resize` 이벤트 `requestAnimationFrame` 병합,
 *            모바일 메뉴 이동 시 상태 보존 `Element.prototype.moveBefore()` 점진적 향상 적용
 * ==============================================================================
 */

/**
 * 지도 위 병원 오버레이 카드의 가로/세로 기준 픽셀 크기 (2D 공간 해시 클러스터링 격자 기준)
 *
 * [코드의 경로성]
 * - 참조하는 곳: 이 파일의 `groupCards()`에서 카드 간 충돌 범위(`CARD_WIDTH + 10`, `CARD_HEIGHT + 14`) 계산에 사용
 * - 짝을 이루는 파일: `frontend/style.css`의 `.hospital-card { width: 168px; }`
 *
 * [수정 시 영향 및 예시]
 * - 병원 카드 가로 폭을 `168px`에서 `188px`로 넓히고 싶다면 (`style.css`의 `.hospital-card` `width`도 함께 수정):
 *   변경 전: const CARD_WIDTH = 168;
 *   변경 후: const CARD_WIDTH = 188;
 */
const CARD_WIDTH = 168;
const CARD_HEIGHT = 60;

/**
 * 브라우저가 개별 CSS `translate` 속성(GPU 컴포지터 직접 합성)을 지원하는지 최초 1회 판별합니다.
 * - 지원 브라우저(최신 Chrome/Safari/Edge/Firefox): `style.translate = "Xpx Ypx"` 사용 (리플로우 0회)
 * - 구형 브라우저 폴백: `style.left = "Xpx"; style.top = "Ypx"` 자동 전환
 */
const CAN_USE_CSS_TRANSLATE =
    typeof CSS !== 'undefined' &&
    typeof CSS.supports === 'function' &&
    CSS.supports('translate', '0px');

/**
 * 네이버 GL 지도 단일 경로 GeoJSON 소스 ID 상수
 * - 수십 개의 `naver.maps.Polyline` 객체를 매번 생성·파괴하는 대신,
 *   단 1개의 GeoJSON Source(`yonsei-route-source`) 데이터만 `setData()`로 교체하여 실시간 우클릭 경로를 1ms 내 갱신합니다.
 */
const GL_ROUTE_SOURCE_ID = 'yonsei-route-source';

/**
 * 이용 안내 모달(`index.html`의 `#notice-dialog`) '다시 보지 않기' LocalStorage 저장 키
 * - 안내 공지 내용을 전면 개편하여 모든 사용자에게 다시 1회 띄우고 싶다면 `'emergency-notice-hidden-v2'`로 변경하세요.
 */
const NOTICE_KEY = 'emergency-notice-hidden-v1';

/**
 * 네이버 지도 Style Editor 다크 모드(야간 관제) Custom Style ID
 *
 * [코드의 경로성]
 * - 사용되는 곳: 하단 `mountNaverMap()`에서 `state.darkMode === true`일 때 `glOptions.customStyleId`로 전달됨
 * - 백엔드 설정과 대응: `backend/src/main/resources/application.yml`의 `yonsei119.naver.dark-style-id`
 *
 * [수정 시 영향 및 예시]
 * - 네이버 클라우드 플랫폼 Style Editor에서 새로 발행한 다크 테마 스타일 ID로 교체하고 싶다면:
 *   변경 전: const DEFAULT_NAVER_DARK_STYLE_ID = '4b12b380-f70c-4d42-85df-ecf7961ff041';
 *   변경 후: const DEFAULT_NAVER_DARK_STYLE_ID = '새로-발급받은-스타일-UUID';
 */
const DEFAULT_NAVER_DARK_STYLE_ID = '4b12b380-f70c-4d42-85df-ecf7961ff041';

/**
 * 기본 출발 기준점 좌표 (사용자가 GPS 권한을 거부했거나 '초기화' 버튼을 눌렀을 때 돌아가는 기본 위치)
 *
 * [코드의 경로성]
 * - 참조하는 함수: `state` 초기값, `locate()` 권한 거부 시 안내, `resetOriginAndRoute()` 초기화 복귀 좌표
 *
 * [수정 시 영향 및 예시]
 * - 초기 기준점을 서울시청 대신 연세대학교 세브란스병원(신촌)으로 바꾸고 싶다면:
 *   변경 전: const DEFAULT_ORIGIN = { lat: 37.5668, lng: 126.9786, label: '서울시청' };
 *   변경 후: const DEFAULT_ORIGIN = { lat: 37.5621, lng: 126.9408, label: '연세대 세브란스병원' };
 */
const DEFAULT_ORIGIN = {
    lat: 37.31904016818661,
    lng: 127.90122866146605,
    label: '연세대학교 미래캠퍼스 (기본 위치)'
};

/* --------------------------------------------------------------------------
   [1. 필수 특수 의료 장비 11종 필드 매핑 (EQUIPMENT)]
   - 출처: 국립중앙의료원(NMC) 응급의료정보 OpenAPI 활용가이드(V13)
   - 경로성: 백엔드 `NmcOpenApiClient.java`가 공공데이터포털 XML에서 수집해
     `HospitalDto.beds` 맵에 소문자 키로 담아 보내주면, 이 사전을 참조해 한글 장비명으로 표시합니다.
   -------------------------------------------------------------------------- */
const EQUIPMENT = {
    hvamyn: '구급차',
    hvangioayn: '혈관촬영기',
    hvcrrtayn: '지속적 신대체요법(CRRT)',
    hvctayn: 'CT',
    hvecmoayn: 'ECMO',
    hvhypoayn: '중심체온조절유도기(저체온치료)',
    hvincuayn: '인큐베이터',
    hvmriayn: 'MRI',
    hvoxyayn: '고압산소치료기',
    hvventiayn: '인공호흡기 일반',
    hvventisoayn: '인공호흡기 조산아'
};

/* --------------------------------------------------------------------------
   [2. 실시간 가용 병상 수동 매핑 사전 (HV_LABELS)]
   - 역할: 공공데이터 XML/JSON의 영문 코드(`hv2`, `hv28` 등)를 의료진/시민이 읽을 수 있는 한글 명칭으로 변환합니다.
   - 사용되는 곳: `API_FIELD_LABELS` 통합 사전 및 하단 `renderRawBeds()` 개발자 모드 원본 인스펙터
   - 수정 예시: `hv28: '소아'`를 `hv28: '소아응급실'`로 바꾸면 개발자 모드 코드 설명에 즉시 반영됩니다.
   -------------------------------------------------------------------------- */
const HV_LABELS = {
    hv1: '예비항목2',
    hv2: '[중환자실] 내과',
    hv3: '[중환자실] 외과',
    hv4: '외과입원실(정형외과)',
    hv5: 'CT',
    hv6: '[중환자실] 신경외과',
    hv7: '혈관촬영기',
    hv8: '[중환자실] 화상',
    hv9: '[중환자실] 외상',
    hv10: 'VENTI(소아)',
    hv11: '인큐베이터(보육기)',
    hv12: '예비항목3',
    hv13: '격리진료구역 음압격리병상',
    hv14: '격리진료구역 일반격리병상',
    hv15: '소아음압격리',
    hv16: '소아일반격리',
    hv17: '[응급전용] 중환자실 음압격리',
    hv18: '[응급전용] 중환자실 일반격리',
    hv19: '[응급전용] 입원실 음압격리',
    hv21: '[응급전용] 입원실 일반격리',
    hv22: '감염병 전담병상 중환자실',
    hv23: '감염병 전담병상 중환자실 내 음압격리병상',
    hv24: '[감염] 중증 병상',
    hv25: '[감염] 준·중증 병상',
    hv26: '[감염] 중등증 병상',
    hv27: '코호트 격리',
    hv28: '소아',
    hv29: '응급실 음압 격리 병상',
    hv30: '응급실 일반 격리 병상',
    hv31: '[응급전용] 중환자실',
    hv32: '[중환자실] 소아',
    hv33: '[응급전용] 소아중환자실',
    hv34: '[중환자실] 심장내과',
    hv35: '[중환자실] 음압격리',
    hv36: '[응급전용] 입원실',
    hv37: '[응급전용] 소아입원실',
    hv38: '[입원실] 외상전용',
    hv39: '[기타] 외상전용 수술실',
    hv40: '[입원실] 정신과 폐쇄병동',
    hv41: '[입원실] 음압격리',
    hv42: '[기타] 분만실',
    hv43: '[기타] 화상전용처치실',
    hv60: '외상소생실',
    hv61: '외상환자진료구역'
};

/* --------------------------------------------------------------------------
   [3. 기준 병상수(총 허가/운영 병상) 수동 매핑 사전 (HVS_LABELS)]
   - 역할: 가용률(`현재 가용 병상 / 기준 병상`) 계산 시 분모(기준치) 역할을 하는 `hvs*` 코드 사전입니다.
   - 사용되는 곳: `getBedStatusBadge()`, `createEquipmentCell()`, `renderRawBeds()`
   -------------------------------------------------------------------------- */
const HVS_LABELS = {
    hvs01: '일반 기준',
    hvs02: '소아 기준',
    hvs03: '응급실 음압 격리 병상 기준',
    hvs04: '응급실 일반 격리 병상 기준',
    hvs05: '[응급전용] 중환자실 기준',
    hvs06: '[중환자실] 내과 기준',
    hvs07: '[중환자실] 외과 기준',
    hvs08: '[중환자실] 신생아 기준',
    hvs09: '[중환자실] 소아 기준',
    hvs10: '[응급전용] 소아중환자실 기준',
    hvs11: '[중환자실] 신경과 기준',
    hvs12: '[중환자실] 신경외과 기준',
    hvs13: '[중환자실] 화상 기준',
    hvs14: '[중환자실] 외상 기준',
    hvs15: '[중환자실] 심장내과 기준',
    hvs16: '[중환자실] 흉부외과 기준',
    hvs17: '[중환자실] 일반 기준',
    hvs18: '[중환자실] 음압격리 기준',
    hvs19: '[응급전용] 입원실 기준',
    hvs20: '[응급전용] 소아입원실 기준',
    hvs21: '[입원실] 외상전용 기준',
    hvs22: '[기타] 수술실 기준',
    hvs23: '[기타] 외상전용 수술실 기준',
    hvs24: '[입원실] 정신과 폐쇄병동 기준',
    hvs25: '[입원실] 음압격리 기준',
    hvs26: '[기타] 분만실 기준',
    hvs27: 'CT 기준',
    hvs28: 'MRI 기준',
    hvs29: '혈관촬영기 기준',
    hvs30: '인공호흡기 일반 기준',
    hvs31: '인공호흡기 조산아 기준',
    hvs32: '인큐베이터 기준',
    hvs33: 'CRRT 기준',
    hvs34: 'ECMO 기준',
    hvs35: '중심체온조절유도기 기준',
    hvs36: '[기타] 화상전용처치실 기준',
    hvs37: '고압산소치료기 기준',
    hvs38: '[입원실] 일반 기준',
    hvs46: '격리진료구역 음압격리 기준',
    hvs47: '격리진료구역 일반격리 기준',
    hvs48: '소아음압격리 기준',
    hvs49: '소아일반격리 기준',
    hvs50: '[응급전용] 중환자실 음압격리 기준',
    hvs51: '[응급전용] 중환자실 일반격리 기준',
    hvs52: '[응급전용] 입원실 음압격리 기준',
    hvs53: '[응급전용] 입원실 일반격리 기준',
    hvs54: '감염병 전담병상 중환자실 기준',
    hvs55: '감염병 전담병상 중환자실 내 음압격리병상 기준',
    hvs56: '[감염] 중증 병상 기준',
    hvs57: '[감염] 준·중증 병상 기준',
    hvs58: '[감염] 중등증 병상 기준',
    hvs59: '코호트 격리 기준',
    hvs60: '외상소생실 기준',
    hvs61: '외상환자진료구역 기준'
};

/* --------------------------------------------------------------------------
   [4. API 응답 전체 필드 통합 사전 (API_FIELD_LABELS)]
   - 역할: 위 `HV_LABELS`, `HVS_LABELS`, `EQUIPMENT`에 공통 핵심 코드(`hvec`, `hvoc` 등)를 합친 마스터 사전입니다.
   - 참조하는 함수: `fieldLabel(key)` → `renderRawBeds()`, `renderBasic()`
   -------------------------------------------------------------------------- */
const API_FIELD_LABELS = {
    ...HV_LABELS,
    ...HVS_LABELS,
    ...EQUIPMENT,
    hvec: '일반(응급실 일반병상)',
    hvoc: '[기타] 수술실',
    hvcc: '[중환자실] 신경과',
    hvncc: '[중환자실] 신생아',
    hvccc: '[중환자실] 흉부외과',
    hvicc: '[중환자실] 일반',
    hvgc: '[입원실] 일반',
    hvidate: '입력일시',
    hvdnm: '당직의',
    hvctayn: 'CT가용(가/부)',
    hvmriayn: 'MRI가용(가/부)',
    hvangioayn: '혈관촬영기가용(가/부)',
    hvventiayn: '인공호흡기가용(가/부)',
    hvventisoayn: '인공호흡기 조산아가용(가/부)',
    hvincuayn: '인큐베이터가용(가/부)',
    hvcrrtayn: 'CRRT가용(가/부)',
    hvecmoayn: 'ECMO가용(가/부)',
    hvoxyayn: '고압산소치료기가용(가/부)',
    hvhypoayn: '중심체온조절유도기가용(가/부)',
    hvamyn: '구급차가용여부'
};

/**
 * 애플리케이션 전역 런타임 상태 저장소 (`state`)
 *
 * [역할 설명]
 * 프론트엔드 화면 전체가 공유하는 '단일 진실 공급원(Single Source of Truth)' 메모리 객체입니다.
 * 지도 객체, 내 위치 좌표, 받아온 병원 목록, 현재 열려 있는 병원 시트, 활성화된 자동차 경로,
 * 그리고 Key-Diffing용 마커 맵(`markerMap`)이 모두 이곳에 저장됩니다.
 *
 * [주요 속성 수정 시 영향 예시]
 * - `selectedDirectionOption: 'trafast'`:
 *   최초 병원 클릭 시 기본 길찾기 옵션을 '실시간 빠른길(`trafast`)' 대신 '실시간 최적(`traoptimal`)'으로 바꾸려면:
 *   변경 전: selectedDirectionOption: 'trafast',
 *   변경 후: selectedDirectionOption: 'traoptimal',
 * - `darkMode: true`:
 *   최초 실행 테마를 라이트 모드로 바꾸려면 `false`로 변경하고 `initThemeToggle()`의 `applyTheme(false, false)`도 맞추세요.
 */
const state = {
    map: null,
    circle: null,
    locationMarker: null,
    waypointMarker: null,
    routePolyline: null,
    routeOutlinePolyline: null,
    routeSectionPolylines: [],
    glRouteActive: false,
    glRouteMap: null,
    sheetRouteData: null,
    activeRoute: null,
    activeRouteController: null,
    locating: false,
    origin: { lat: DEFAULT_ORIGIN.lat, lng: DEFAULT_ORIGIN.lng },
    originLabel: DEFAULT_ORIGIN.label,
    originAddress: '',
    selectedDirectionOption: 'trafast',
    mobileMenuOpen: false,
    trafficEnabled: false,
    trafficLayer: null,
    etaRankOpen: false,
    etaRankController: null,
    reverseGeocodeController: null,
    locationKnown: false,
    locationAccuracy: null,
    locationUpdated: null,
    lastUpdated: null,
    hospitals: [],
    markers: [],
    markerMap: new Map(),
    selected: null,
    sources: {},
    controller: null,
    directionsController: null,
    loading: false,
    previousFocus: null,
    inflight: null,
    inflightForce: false,
    statusTimer: null,
    renderedDevHospitalRef: null,
    clusterItems: [],
    hasFitted: false,
    darkMode: true,
    naverDarkStyleId: DEFAULT_NAVER_DARK_STYLE_ID,
    circleKey: null,
    circleMap: null,
    cardPointerDown: false,
    pendingMarkerUpdate: false,
    diagnostics: {
        circleCreatedCount: 0,
        markersUpdatedCount: 0,
        markersCreatedCount: 0,
        markersReusedCount: 0,
        markersRemovedCount: 0,
        glRouteUpdateCount: 0,
        idleEventCount: 0,
        themeSwitchCount: 0,
        lastHospitalOpened: null,
        lastError: null
    }
};

/**
 * `document.getElementById(id)` 단축 헬퍼 함수
 * - `frontend/index.html`에 선언된 `id="..."` 요소를 빠르게 조회할 때 파일 전역에서 사용합니다.
 */
const byId = id => document.getElementById(id);

/**
 * [함수 역할]
 * 메인 스레드 장기 작업(Long Task > 50ms)을 분할하여 브라우저가 사용자 터치·지도 드래그 입력을
 * 끊김 없이 먼저 처리할 수 있도록 실행 제어권을 잠시 양보(Yield)합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `fetchData()`에서 수백 개 병원 데이터를 전처리할 때 45ms 초과 시 호출
 * - 웹 표준 API: 최신 브라우저의 `window.scheduler.yield()`를 우선 사용하고, 미지원 환경에서는 `setTimeout(0)`으로 안전하게 폴백합니다.
 *
 * @returns {Promise<void>} 다음 작업 재개 시점 Promise
 */
function yieldToMain() {
    if (typeof window !== 'undefined' && 'scheduler' in window && typeof window.scheduler?.yield === 'function') {
        return window.scheduler.yield();
    }
    return new Promise(resolve => {
        setTimeout(resolve, 0);
    });
}

/**
 * [함수 역할]
 * 병원 객체(`hospital`)에 대응하는 네이버 지도 `naver.maps.LatLng` 인스턴스를 최초 1회만 생성하여
 * `hospital._latLng`에 캐싱해 두고 재사용합니다.
 *
 * [최적화 효과 & 코드의 경로성]
 * - 기존에는 지도를 드래그할 때마다(`idle` → `updateMarkers()`) 수백 개 병원마다 `new naver.maps.LatLng()` 객체를
 *   매번 새로 할당하여 가비지 컬렉션(GC) 부하가 발생했습니다.
 * - 이제 `fetchData()`, `updateMarkers()`, `focusHospitalOnMap()` 등에서 이 함수를 공유하여 객체 할당을 제거합니다.
 *
 * @param {Object} hospital 병원 객체 (`lat`, `lng` 포함)
 * @returns {Object} 캐싱된 `naver.maps.LatLng` 인스턴스
 */
function getHospitalLatLng(hospital) {
    if (
        !hospital._latLng ||
        hospital._latLngLat !== hospital.lat ||
        hospital._latLngLng !== hospital.lng
    ) {
        hospital._latLng = new naver.maps.LatLng(hospital.lat, hospital.lng);
        hospital._latLngLat = hospital.lat;
        hospital._latLngLng = hospital.lng;
    }
    return hospital._latLng;
}

/* --------------------------------------------------------------------------
   [5. 데이터 정제 및 결측치 방어 유틸리티]
   -------------------------------------------------------------------------- */

/**
 * [함수 역할]
 * 백엔드에서 내려온 문자열/숫자 값이 결측치(`null`, `undefined`, 빈 문자열, `'null'`, `'-'` 등)인지 판별합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `displayValue()`, `firstReported()`, `availability()`, `integerValue()`, `bedValue()`, `renderContacts()`
 *
 * [수정 시 영향 및 절대 주의사항]
 * - 숫자 `0`은 결측치가 아니라 **'가용 병상 0석(만실)'**이라는 매우 중요한 응급실 상태이므로 `false`를 반환합니다.
 * - 만약 추가로 `'미상'`이라는 문자열도 결측치로 취급하고 싶다면 정규식에 `|미상`을 추가하세요:
 *   변경 전: /^(?:\s*|null|undefined|none|n\/?a|nan|[-−–—]+)$/i
 *   변경 후: /^(?:\s*|null|undefined|none|n\/?a|nan|미상|[-−–—]+)$/i
 *
 * @param {*} value 검사할 원본 값
 * @returns {boolean} 결측치이면 `true`, 유효한 값이면 `false`
 */
function isMissing(value) {
    return value == null ||
        /^(?:\s*|null|undefined|none|n\/?a|nan|[-−–—]+)$/i.test(
            String(value).trim()
        );
}

/**
 * [함수 역할]
 * 결측치인 경우 사용자 안내용 문자열 `'미정보'`를 반환하고, 유효한 값이면 앞뒤 공백을 제거해 반환합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `renderMessages()`, `renderRawBeds()`, `renderBasic()`, `openHospital()`
 *
 * [수정 시 영향 및 예시]
 * - 값 미보고 시 `'미정보'` 대신 `'정보 없음'`으로 전체 문구를 통일하고 싶다면:
 *   변경 전: return isMissing(value) ? '미정보' : String(value).trim();
 *   변경 후: return isMissing(value) ? '정보 없음' : String(value).trim();
 *
 * @param {*} value 원본 값
 * @returns {string} 화면 표시용 문자열
 */
function displayValue(value) {
    return isMissing(value) ? '미정보' : String(value).trim();
}

/**
 * [함수 역할]
 * 여러 후보 필드 값 중 결측치가 아닌 첫 번째 값을 찾아 반환합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `renderSevere()`에서 중증질환 보고 시각(`fields.hvidate` 우선, 없으면 `fields.mkioskdate`) 선택 시 사용
 *
 * @param {...*} values 우선순위대로 나열한 후보 값 목록
 * @returns {*} 첫 번째 유효 값 (모두 결측치이면 `undefined`)
 */
function firstReported(...values) {
    return values.find(value => !isMissing(value));
}

/**
 * [함수 역할]
 * 공공데이터 OpenAPI 필드 영문 코드(예: `hvec`)를 `'hvec(일반(응급실 일반병상))'`처럼 한글 설명이 붙은 라벨로 변환합니다.
 *
 * [코드의 경로성]
 * - 참조하는 데이터: 상단 `API_FIELD_LABELS` 통합 사전
 * - 호출하는 곳(Incoming): `renderRawBeds()`, `renderBasic()` (병원 상세창 하단 개발자 모드 및 기본정보 표)
 *
 * @param {string} key XML/JSON 필드 키
 * @returns {string} 한글 설명이 포함된 필드 라벨
 */
function fieldLabel(key) {
    const label = API_FIELD_LABELS[key.toLowerCase()];
    if (label) {
        return `${key}(${label})`;
    }
    if (/^hvs?\d+$/i.test(key)) {
        return `${key}(설명 미정보)`;
    }
    return key;
}

/**
 * [함수 역할]
 * 중증응급질환 수용 여부(`Y`, `N`, `N1`, `가능`, `불가능` 등) 원본 코드를 표준 상태 객체(`{ text, className }`)로 변환합니다.
 *
 * [코드의 경로성]
 * - 데이터 출처: 백엔드 `NmcOpenApiClient.java` → `HospitalDto.severe` 맵
 * - 호출하는 곳(Incoming): `renderSevere()` (중증응급질환 28종 매트릭스 렌더링)
 *
 * [도메인 주의사항]
 * - 실제 국립중앙의료원 OpenAPI 응답에는 `'N'` 외에도 `'N1'`(장비·인력 미보유 불가 코드)이 내려오므로
 *   `upper.startsWith('N')` 조건으로 `'N1'`도 정확히 `'불가능'`(`no`)으로 처리합니다.
 *
 * @param {*} raw 원본 상태값
 * @param {string} [yes='가능'] 가능 시 표시 텍스트
 * @param {string} [no='불가능'] 불가능 시 표시 텍스트
 * @returns {{text: string, className: string}} 판별 결과 객체
 */
function availability(raw, yes = '가능', no = '불가능') {
    if (isMissing(raw)) {
        return { text: '-', className: 'unknown' };
    }

    const text = String(raw).trim();
    const upper = text.toUpperCase();

    if (upper === 'Y' || text === '가능' || text.startsWith('가능')) {
        return { text: yes, className: 'yes' };
    }

    if (
        upper === 'N' ||
        upper.startsWith('N') ||
        text === '불가' ||
        text === '불가능' ||
        text.includes('불가') ||
        text.includes('불가능')
    ) {
        return { text: no, className: 'no' };
    }

    if (text === '정보미제공' || text === '미정보' || text === '-') {
        return { text: '-', className: 'unknown' };
    }

    return { text, className: 'unknown' };
}

/**
 * [함수 역할]
 * 문자열이나 숫자를 부호 있는 안전 정수(음수 포함, 예: `-3`, `0`, `12`)로 파싱합니다.
 * 정수가 아니거나 결측치이면 `null`을 반환합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `populateWaypointSelect()`, `getBedStatusBadge()`, `createEquipmentCell()`
 *
 * @param {*} raw 원본 문자열 또는 숫자
 * @returns {number|null} 파싱된 정수 또는 `null`
 */
function integerValue(raw) {
    if (isMissing(raw)) {
        return null;
    }
    const text = String(raw).trim();
    if (!/^-?\d+$/.test(text)) {
        return null;
    }
    const value = Number(text);
    return Number.isSafeInteger(value) ? value : null;
}

/**
 * [함수 역할]
 * XSS 취약점이 있는 `innerHTML` 문자열 조합 대신, 안전한 표준 DOM 엘리먼트를 생성하고
 * 클래스명과 텍스트(`textContent`)를 한 줄로 지정하는 경량 DOM 팩토리 함수입니다.
 *
 * [코드의 경로성]
 * - 파일 전역의 거의 모든 UI 렌더링 함수(`updateMarkers`, `renderBeds`, `loadEtaRanking` 등)에서 호출됩니다.
 *
 * @param {string} tag 생성할 HTML 태그명 (예: `'div'`, `'button'`, `'span'`)
 * @param {string} [className=''] 적용할 CSS 클래스명 (`frontend/style.css`에 정의된 클래스)
 * @param {string|number} [content=undefined] 삽입할 안전한 텍스트 콘텐츠
 * @returns {HTMLElement} 생성된 DOM 엘리먼트
 */
function node(tag, className = '', content = undefined) {
    const item = document.createElement(tag);
    if (className) {
        item.className = className;
    }
    if (content !== undefined) {
        item.textContent = String(content);
    }
    return item;
}

/**
 * [함수 역할]
 * 칸 너비보다 긴 텍스트를 전광판(Ticker/Marquee)처럼 좌우 스크롤하기 위한 래퍼 엘리먼트를 생성합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `renderSevere()`, `createBedCell()`, `createEquipmentCell()`
 * - 연결되는 함수/CSS: 생성 후 `updateMarquees()`가 실제 넘침 픽셀을 측정해 `style.css`의 `.is-marquee` 애니메이션을 가동합니다.
 */
function createMarqueeEl(tag, className = '', text = '', title = undefined) {
    const el = node(tag, className);
    if (title) {
        el.title = title;
    }
    const inner = node('span', 'marquee-inner', text);
    el.append(inner);
    return el;
}

/**
 * [함수 역할]
 * 상세 시트(`#sheet`) 내 병상·장비·중증질환 셀 중 텍스트가 칸을 넘치는 항목만 골라
 * 전광판 스크롤 거리(`--ticker-diff`)와 속도(`--ticker-duration`) CSS 변수를 일괄 주입합니다.
 *
 * [성능 최적화 원리 & 코드의 경로성]
 * - 호출하는 곳(Incoming): `openHospital()` 시트 열림 직후 및 `init()`의 `resize` 이벤트 병합 콜백
 * - 연결되는 CSS: `frontend/style.css`의 `.is-marquee .marquee-inner` (`@keyframes egen-ticker`)
 * - 레이아웃 스래싱(Layout Thrashing) 방지를 위해 `scrollWidth`/`clientWidth` **읽기(Read) 루프**를 모두 끝낸 뒤
 *   **쓰기(Write) 루프**에서 클래스를 토글합니다.
 *
 * [수정 시 영향 및 예시]
 * - 전광판 글자가 흐르는 속도를 더 천천히 만들고 싶다면 `duration` 계산식의 나눗셈 분모(`25`)를 줄이세요:
 *   변경 전: const duration = Math.max(5, Math.min(22, (overflow / 25) + 3.5));
 *   변경 후: const duration = Math.max(7, Math.min(28, (overflow / 16) + 4.5));
 */
function updateMarquees() {
    requestAnimationFrame(() => {
        const containers = document.querySelectorAll('.egen-cell-param, .severe-remark-box');
        if (!containers.length) {
            return;
        }

        const measurements = [];
        for (const container of containers) {
            const inner = container.querySelector('.marquee-inner');
            if (inner) {
                measurements.push({
                    container,
                    inner,
                    overflow: inner.scrollWidth - container.clientWidth
                });
            }
        }

        for (const { container, inner, overflow } of measurements) {
            if (overflow > 2) {
                container.classList.add('is-marquee');
                inner.style.setProperty('--ticker-diff', `${Math.ceil(overflow + 14)}px`);
                const duration = Math.max(5, Math.min(22, (overflow / 25) + 3.5));
                inner.style.setProperty('--ticker-duration', `${duration.toFixed(1)}s`);
            } else {
                container.classList.remove('is-marquee');
                inner.style.removeProperty('--ticker-diff');
                inner.style.removeProperty('--ticker-duration');
            }
        }
    });
}

/**
 * [함수 역할]
 * 상단 비침투형 토스트(`index.html`의 `#status-toast` / `#status`)에 진행 상태·오류 메시지를 표시하거나 숨깁니다.
 * - `popover="manual"`을 사용하여 지도 조작 중 실수로 닫히지 않으며, 자동 타이머 또는 닫기 버튼으로 해제됩니다.
 * - 스크린 리더에는 중간 로딩 문구(`…`) 스팸을 제외하고 의미 있는 완료·오류 결과만 중앙 라이브 영역으로 공지합니다.
 */
function setStatus(message) {
    const statusEl = byId('status');
    const toastEl = byId('status-toast');
    if (!statusEl) {
        return;
    }

    if (state.statusTimer) {
        clearTimeout(state.statusTimer);
        state.statusTimer = null;
    }

    const text = message ? String(message).trim() : '';
    statusEl.textContent = text;
    statusEl.hidden = !text;

    if (!toastEl) {
        return;
    }

    const politeAnnouncer = byId('status-announcer');
    const alertAnnouncer = byId('alert-announcer');

    if (!text) {
        toastEl.dataset.open = 'false';
        delete toastEl.dataset.tone;
        if (typeof toastEl.hidePopover === 'function') {
            try {
                if (toastEl.matches(':popover-open')) {
                    toastEl.hidePopover();
                }
            } catch {
                // 미열림 상태 예외 무시
            }
        }
        return;
    }

    const isError = /(실패|오류|못했|거부|없습니다)/.test(text);
    const isInterstitial = /(확인 중…|조회하고 있습니다…)$/.test(text);

    toastEl.dataset.tone = isError ? 'error' : 'info';
    toastEl.dataset.open = 'true';
    if (typeof toastEl.showPopover === 'function') {
        try {
            if (!toastEl.matches(':popover-open')) {
                toastEl.showPopover();
            }
        } catch {
            // 구형 환경에서는 data-open 폴백 스타일이 동작
        }
    }

    if (!isInterstitial) {
        if (isError && alertAnnouncer) {
            alertAnnouncer.textContent = text;
        } else if (politeAnnouncer) {
            politeAnnouncer.textContent = text;
        }
    }

    // 지도 SDK 로드 실패 같은 치명적 초기 오류나 진행 중 로딩 문구는 수동 해제/완료 전까지 유지하고, 일반 알림은 자동 닫힘 처리
    const isSdkFatalError = !state.map && text.includes('SDK');
    if (!isInterstitial && !isSdkFatalError) {
        state.statusTimer = setTimeout(() => {
            setStatus('');
        }, isError ? 6500 : 4200);
    }
}

/**
 * [함수 역할]
 * 대상 컨테이너 내부를 비우고 안내 문구(`<p class="empty-state">`) 하나만 깔끔하게 표시합니다.
 */
function empty(container, message) {
    container.replaceChildren(node('p', 'empty-state', message));
}

/* --------------------------------------------------------------------------
   [6. 갱신 시간 및 반경 설정 UI 제어 (`index.html` #search-panel 연동)]
   -------------------------------------------------------------------------- */

/**
 * [함수 역할]
 * 검색 패널(`#search-panel`) 내 새로고침 버튼 옆의 `갱신 시간`(`index.html`의 `#updated-time`)을
 * 백엔드 스냅샷 기준 최신 시각(한국 표준시 KST)으로 업데이트합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `init()` 초기 실행 시, `fetchData()`가 `GET /api/hospitals` 수신을 완료했을 때
 * - 가리키는 DOM(Outgoing): `frontend/index.html`의 `<time id="updated-time">`
 *
 * [수정 시 영향 및 예시]
 * - 시간 표시에 '초(second)' 단위까지 함께 보여주고 싶다면:
 *   변경 전: hour: '2-digit', minute: '2-digit', hour12: false
 *   변경 후: hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
 */
function updateSummary() {
    const time = byId('updated-time');
    if (!time) {
        return;
    }

    time.textContent = state.lastUpdated
        ? state.lastUpdated.toLocaleTimeString('ko-KR', {
            timeZone: 'Asia/Seoul',
            hour: '2-digit',
            minute: '2-digit',
            hour12: false
        })
        : '확인 중';

    if (state.lastUpdated) {
        time.dateTime = state.lastUpdated.toISOString();
    }
}

/**
 * [함수 역할]
 * 현재 출발 기준점의 이름(`내 위치`, `우클릭 지정 출발지`, `서울시청` 등)과 역지오코딩된 도로명/행정동 주소를 합쳐
 * 사람이 읽기 쉬운 단일 문자열(예: `'우클릭 지정 출발지 (서울 중구 태평로1가)'`)로 반환합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `updateRadiusUI()`, `loadDirectionsForHospital()`, `openHospital()`
 */
function getOriginDisplayLabel() {
    const base = state.locationKnown ? (state.originLabel || '내 위치') : DEFAULT_ORIGIN.label;
    return state.originAddress ? `${base} (${state.originAddress})` : base;
}

/**
 * [함수 역할]
 * 탐색 반경 버튼(`#radius-button`), 반경 팝오버 도움말(`#radius-help`), 출발 기준 주소 표시줄(`#origin-address-text`),
 * 그리고 출발점 초기화 버튼(`#origin-reset-btn`)의 텍스트와 노출 여부를 현재 `state`와 동기화합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `initRadius()`, `locate()`, `updateOriginAddress()`, `setCustomOrigin()`, `resetOriginAndRoute()`
 * - 가리키는 DOM(Outgoing): `frontend/index.html`의 `#radius`, `#radius-value`, `#radius-button`, `#radius-help`, `#origin-address-text`, `#origin-reset-btn`
 */
function updateRadiusUI() {
    const value = byId('radius').value;
    const label = value === 'all' ? '전국' : `${value} km`;

    byId('radius-value').textContent = label;
    byId('radius-button').setAttribute('aria-label', `탐색 반경 설정, 현재 ${label}`);
    byId('radius-button').title = `탐색 반경: ${label}`;

    const originDesc = getOriginDisplayLabel();
    byId('radius-help').textContent = value === 'all'
        ? '전국 기관을 표시합니다. 선택하면 바로 적용됩니다.'
        : `${originDesc} 기준 · 선택하면 바로 적용됩니다.`;

    const addrText = byId('origin-address-text');
    if (addrText) {
        addrText.textContent = `출발 기준: ${originDesc}`;
        addrText.title = `출발 기준: ${originDesc} (${state.origin.lat.toFixed(5)}, ${state.origin.lng.toFixed(5)})`;
    }

    const originResetBtn = byId('origin-reset-btn');
    if (originResetBtn) {
        originResetBtn.hidden = !state.locationKnown;
    }
}

/**
 * [함수 역할]
 * 열려 있는 탐색 반경 설정 팝오버(`#radius-popover`)를 닫고, 필요 시 반경 버튼으로 키보드 포커스를 되돌립니다.
 */
function closeRadius(restoreFocus = false) {
    byId('radius-popover').hidden = true;
    byId('radius-button').setAttribute('aria-expanded', 'false');
    if (restoreFocus) {
        byId('radius-button').focus();
    }
}

/**
 * [함수 역할]
 * 탐색 반경 버튼 클릭, `<select id="radius">` 값 변경, 바깥 영역 클릭(Light Dismiss), `Escape` 닫기 이벤트를 초기화합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): 하단 `init()`에서 앱 시작 시 1회 호출
 * - 이어지는 동작(Outgoing): 반경 값이 바뀌면 `updateRadiusUI()` → `fitRadius()`(지도 줌 조정) → `updateMarkers()`(반경 내 마커 재필터링) 순으로 실행됩니다.
 *
 * [수정 시 영향 및 예시]
 * - 반경 선택 목록에 `'3 km'` 옵션을 추가하고 싶다면 `frontend/index.html`의 `<select id="radius">` 안에
 *   `<option value="3">3 km</option>` 한 줄만 추가하면 이 함수가 자동으로 숫자로 읽어 반경 원과 마커 필터에 즉시 반영합니다!
 */
function initRadius() {
    const button = byId('radius-button');
    const panel = byId('radius-popover');

    button.addEventListener('click', () => {
        if (!panel.hidden) {
            closeRadius(true);
            return;
        }
        byId('search-results').hidden = true;
        panel.hidden = false;
        button.setAttribute('aria-expanded', 'true');
        byId('radius').focus();
    });

    byId('radius-close').addEventListener('click', () => closeRadius(true));

    byId('radius').addEventListener('change', () => {
        updateRadiusUI();
        fitRadius();
        updateMarkers();
    });

    document.addEventListener('pointerdown', event => {
        if (!panel.hidden && !event.target.closest('.radius-tool')) {
            closeRadius();
        }
        if (state.mobileMenuOpen && !event.target.closest('#mobile-top-left-menu')) {
            toggleMobileMenu(false);
        }
        if (!event.target.closest('#search-panel')) {
            byId('search-results').hidden = true;
        }
    });

    document.addEventListener('focusin', event => {
        if (!panel.hidden && !event.target.closest('.radius-tool')) {
            closeRadius();
        }
    });

    panel.addEventListener('keydown', event => {
        if (event.key === 'Escape') {
            event.preventDefault();
            event.stopPropagation();
            closeRadius(true);
        }
    });

    updateRadiusUI();
}

/* --------------------------------------------------------------------------
   [7. 백엔드 Spring Boot API 연동 파이프라인]
   - 파이썬 장고(Django) 비유: 프론트엔드 JS가 `fetch('/api/hospitals')`를 호출하면
     Django의 `urls.py` + `views.py`에 해당하는 `EmergencyApiController.getHospitals()`가 받아
     `EmergencyDataService.getSnapshot()`(`services.py`) → `EmergencySnapshotDto`(`serializers.py`) JSON을 반환합니다.
   -------------------------------------------------------------------------- */

/**
 * [함수 역할]
 * 백엔드 공개 환경설정(`GET /api/config`)을 조회하여 기본 출발 좌표(`DEFAULT_ORIGIN`)와
 * 네이버 다크 지도 스타일 ID(`state.naverDarkStyleId`)를 서버 설정(`application.yml`)과 동기화합니다.
 */
async function syncFrontendConfig() {
    try {
        const response = await fetch('/api/config', {
            priority: 'low',
            headers: { Accept: 'application/json' }
        });
        if (!response.ok) {
            return;
        }
        const cfg = await response.json();
        if (Number.isFinite(cfg.defaultLat) && Number.isFinite(cfg.defaultLng)) {
            DEFAULT_ORIGIN.lat = cfg.defaultLat;
            DEFAULT_ORIGIN.lng = cfg.defaultLng;
            if (cfg.defaultLabel) {
                DEFAULT_ORIGIN.label = cfg.defaultLabel;
            }
            if (!state.locationKnown) {
                state.origin = { lat: DEFAULT_ORIGIN.lat, lng: DEFAULT_ORIGIN.lng };
                state.originLabel = DEFAULT_ORIGIN.label;
                updateRadiusUI();
                updateCircle();
                updateMarkers();
            }
        }
        if (cfg.naverDarkStyleId && cfg.naverDarkStyleId !== state.naverDarkStyleId) {
            state.naverDarkStyleId = cfg.naverDarkStyleId;
            if (state.darkMode && state.map) {
                mountNaverMap();
            }
        }
    } catch {
        // 오프라인 또는 정적 미리보기 환경에서는 내장 기본값 유지
    }
}

/**
 * [함수 역할]
 * 동시에 여러 곳에서 새로고침을 눌러도 백엔드로 중복 요청이 날아가지 않도록
 * 진행 중인 Promise(`state.inflight`)를 공유(In-flight Deduplication)하는 데이터 로드 진입점입니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming):
 *   1) `init()` 최초 앱 구동 시 `loadData(false)` (백엔드 메모리 캐시 스냅샷 즉시 조회)
 *   2) `#refresh-button`('새로고침' 버튼) 클릭 시 `loadData(true)` (공공데이터 강제 재수집 요청)
 *   3) `#developer-mode` 토글 열림 시 `loadData(false)`
 * - 호출하는 함수(Outgoing): `fetchData(forceRefresh)`
 *
 * @param {boolean} [forceRefresh=false] 백엔드 캐시를 무시하고 강제 새로고침(`?refresh=true`)할지 여부
 * @returns {Promise<void>}
 */
function loadData(forceRefresh = false) {
    if (state.inflight && (!forceRefresh || state.inflightForce)) {
        return state.inflight;
    }

    state.inflightForce = Boolean(forceRefresh);
    const requestPromise = fetchData(forceRefresh).finally(() => {
        if (state.inflight === requestPromise) {
            state.inflight = null;
            state.inflightForce = false;
        }
    });
    state.inflight = requestPromise;

    return requestPromise;
}

/**
 * [함수 역할]
 * Java Spring Boot 백엔드(`GET /api/hospitals`)로부터 전국 응급의료기관 통합 스냅샷을 수신하여
 * `state.hospitals`를 갱신하고 지도 마커·상세 시트·검색창·갱신 시각을 일괄 동기화합니다.
 *
 * [코드의 경로성 (프론트엔드 → 백엔드 → 외부 공공데이터 API 전체 흐름)]
 *   `app.js:fetchData()`
 *   ──(GET /api/hospitals)──▶ `EmergencyApiController.java:getHospitals()`
 *   ──▶ `EmergencyDataService.java:getSnapshot()`
 *   ──▶ (캐시 만료 시) `NmcOpenApiClient.java` (국립중앙의료원 9개 XML 엔드포인트 병렬 수집)
 *       + `HospitalClassifier.java` (권역응급·상급종합·의료원·일반기타 자동 분류)
 *   ──▶ `EmergencySnapshotDto.java` JSON 직렬화 응답 반환!
 *
 * [적용된 Modern Web 성능 최적화]
 * 1. `priority: 'low'` (배경 갱신 대역폭 양보): 이미 병원 목록이 로드된 뒤 백그라운드에서 재조회할 때는
 *    네트워크 우선순위를 `'low'`로 낮춰 WebGL 지도 벡터 타일 다운로드와 경쟁하지 않게 합니다.
 * 2. 사전 캐싱(`_vGroup`, `_latLng`) + 50ms 미만 청크 분할(`yieldToMain`):
 *    500여 개 병원의 시각 그룹과 `LatLng` 인스턴스를 미리 계산하되, 45ms가 넘으면 메인 스레드에 양보합니다.
 *
 * [수정 시 영향 및 예시]
 * - 초기 로드 완료 후 열려 있는 병원 시트를 닫지 않고 그대로 최신 병상 수만 반영하도록 되어 있습니다(`openHospital(fresh, false)`).
 *   만약 새로고침 때마다 시트 스크롤을 맨 위로 올리고 싶다면 `openHospital(fresh, true)`로 바꾸면 됩니다.
 *
 * @param {boolean} [forceRefresh=false] 강제 새로고침 여부 (`true`이면 `?refresh=true` 전달)
 */
async function fetchData(forceRefresh = false) {
    state.loading = true;

    byId('refresh-button').disabled = true;
    document.body.classList.add('is-loading');
    byId('developer-spinner').hidden = false;
    byId('developer-status').textContent = '실시간 정보 새로 수신 중...';

    state.controller?.abort();
    const currentController = new AbortController();
    state.controller = currentController;

    setStatus('정보 확인 중…');

    try {
        const endpointUrl = forceRefresh ? '/api/hospitals?refresh=true' : '/api/hospitals';
        const isBackgroundRefresh = !forceRefresh && state.hospitals.length > 0;

        const response = await fetch(endpointUrl, {
            signal: currentController.signal,
            cache: 'no-store',
            priority: isBackgroundRefresh ? 'low' : 'high',
            headers: {
                Accept: 'application/json'
            }
        });

        if (!response.ok) {
            return Promise.reject(new Error(`백엔드 서버 응답 오류 (HTTP ${response.status})`)).catch(err => {
                setStatus(`조회 실패: ${err.message} · 이전 정보가 있다면 재확인하세요.`);
                byId('developer-status').textContent = `갱신 실패: ${err.message}`;
            });
        }

        const snapshot = await response.json();
        if (currentController.signal.aborted) {
            return;
        }

        const incomingHospitals = Array.isArray(snapshot.hospitals) ? snapshot.hospitals : [];
        const canPrebuildLatLng = typeof naver !== 'undefined' && typeof naver.maps?.LatLng === 'function';
        let chunkStart = performance.now();

        for (let i = 0; i < incomingHospitals.length; i++) {
            const hospital = incomingHospitals[i];
            hospital._vGroup = hospital.regional ? 'regional' : hospital.type?.group;
            if (canPrebuildLatLng && Number.isFinite(hospital.lat) && Number.isFinite(hospital.lng)) {
                getHospitalLatLng(hospital);
            }
            // 50ms Long Task 경계를 넘기지 않도록 45ms마다 메인 스레드에 입력 처리 기회를 양보
            if ((i & 63) === 63 && performance.now() - chunkStart >= 45) {
                await yieldToMain();
                if (currentController.signal.aborted) {
                    return;
                }
                chunkStart = performance.now();
            }
        }

        state.hospitals = incomingHospitals;
        state.sources = snapshot.sources || {
            list: true,
            basic: true,
            beds: true,
            messages: true,
            severe: true,
            traumaList: true,
            traumaBasic: true
        };
        state.lastUpdated = snapshot.updatedAt ? new Date(snapshot.updatedAt) : new Date();

        if (!state.hasFitted && state.map) {
            fitRadius();
            state.hasFitted = true;
        }

        updateMarkers();

        if (state.selected) {
            const fresh = state.hospitals.find(item => item.id === state.selected.id);
            if (fresh) {
                openHospital(fresh, false, { skipDirections: true });
            } else {
                closeSheet();
            }
        }

        updateSearch();

        const missing = Object.entries(state.sources)
            .filter(([, ok]) => !ok)
            .map(([key]) => key);

        byId('developer-status').textContent = missing.length
            ? `조회 완료 · 일부 항목 지연: ${missing.join(', ')}`
            : `전체 데이터 갱신 완료 (${state.hospitals.length.toLocaleString('ko-KR')}개 기관)`;

        updateSummary();

        setStatus(
            missing.length
                ? '일부 정보를 불러오지 못했습니다. 상세 정보에서 확인해 주세요.'
                : ''
        );
    } catch (error) {
        if (error.name === 'AbortError') {
            return;
        }
        console.error('응급의료 데이터 갱신 실패:', error);
        setStatus(`조회 실패: ${error.message} · 이전 정보가 있다면 재확인하세요.`);
        byId('developer-status').textContent = `갱신 실패: ${error.message}`;
    } finally {
        if (state.controller === currentController) {
            state.loading = false;
            byId('refresh-button').disabled = false;
            document.body.classList.remove('is-loading');
            byId('developer-spinner').hidden = true;
        }
    }
}

/* --------------------------------------------------------------------------
   [8. 네이버 지도(NAVER Maps v3 GL) GIS 연동 및 지리 연산]
   -------------------------------------------------------------------------- */

/**
 * [함수 역할]
 * 지구 구체 위 두 위경도 좌표 간의 직선 거리(하버사인 공식, Haversine Formula)를 `km` 단위로 계산합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `updateMarkers()`, `resetOriginAndRoute()`, `populateWaypointSelect()`, `loadDirectionsForHospital()`, `openHospital()`
 * - 참고: 백엔드 `NaverDirectionsService.java`의 `haversineKm()`과 동일한 지구 반지름(`6371 km`) 수식을 사용합니다.
 *
 * @param {{lat: number, lng: number}} a 시작점 좌표
 * @param {{lat: number, lng: number}} b 도착점 좌표
 * @returns {number} 두 지점 사이 직선 거리 (`km`)
 */
function distanceKm(a, b) {
    const radians = degrees => degrees * Math.PI / 180;
    const dLat = radians(b.lat - a.lat);
    const dLng = radians(b.lng - a.lng);

    const arc =
        Math.sin(dLat / 2) ** 2 +
        Math.cos(radians(a.lat)) *
        Math.cos(radians(b.lat)) *
        Math.sin(dLng / 2) ** 2;

    return 6371 * 2 * Math.atan2(Math.sqrt(arc), Math.sqrt(1 - arc));
}

/**
 * [함수 역할]
 * 현재 네이버 지도 인스턴스(`state.map`)의 WebGL 스타일 로드가 완료되어
 * 도형 오버레이 및 화면 좌표 투영(`Projection`)을 안전하게 수행할 수 있는지 확인합니다.
 *
 * [동작 원리]
 * - GL 벡터 모드(`renderMode === 2`)에서는 비동기 `style.json` 로드 완료(`isGLReady() === true`) 전에는
 *   내부 Mapbox 투영기가 아직 준비되지 않으므로, 준비 완료 시점(`glReady` 이벤트) 이후에만 `true`를 반환합니다.
 */
function isMapReadyForOverlays() {
    if (!state.map) {
        return false;
    }
    try {
        if (state.map.get?.('renderMode') === 2 && typeof state.map.isGLReady === 'function') {
            return Boolean(state.map.isGLReady());
        }
    } catch {
        return false;
    }
    return true;
}

/**
 * [함수 역할]
 * 네이버 지도(`naver.maps.OverlayView`)를 상속하여 HTML DOM 엘리먼트(병원 카드 `.hospital-card`,
 * 내 위치 점 `.location-marker`, 경유지 배지 `.waypoint-marker`)를 위경도 좌표 위에 띄우는 커스텀 오버레이입니다.
 *
 * [Modern Web 성능 최적화 Phase 1 — GPU 컴포지터 `style.translate` 적용]
 * - 기존 방식(`style.left` / `style.top`)은 지도를 드래그할 때마다 메인 스레드에서 Layout(리플로우) + Paint를 유발했습니다.
 * - 개선 방식: `onAdd()`에서 `left: 0px; top: 0px;`로 레이아웃 원점을 고정하고,
 *   `draw()`에서는 개별 CSS 속성 `style.translate = "Xpx Ypx"`만 갱신하여 **100% GPU 컴포지터 스레드**에서 60fps로 합성합니다!
 * - 동시에 기존 `style.transform = translate(-50%, -100%)`(앵커 기준점 정렬)과 서로 덮어쓰지 않고 독립적으로 결합됩니다.
 * - 구형 브라우저 호환성: `CAN_USE_CSS_TRANSLATE`가 `false`이면 자동으로 `left`/`top` 폴백 경로를 탑니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `updateMarkers()`(병원 카드), `updateLocationMarker()`(출발점 파란 점), `drawRouteOnMap()`(경유지 배지)
 * - 짝을 이루는 CSS: `frontend/style.css`의 `.hospital-card { contain: layout style; translate: 0px; }`
 *
 * [수정 시 영향 및 예시]
 * - 병원 카드가 좌표 점의 '정중앙'에 오도록 앵커를 바꾸고 싶다면 호출부의 `yAnchor: 1.0`(하단 중앙)을 `0.5`로 변경하세요.
 *
 * @param {Object} options 오버레이 옵션 (`position`, `content`, `map`, `xAnchor`, `yAnchor`, `zIndex`)
 * @returns {Object} 생성된 `NaverDomOverlay` 인스턴스 (`setPosition` 메서드 포함)
 */
function createNaverDomOverlay(options) {
    class NaverDomOverlay extends naver.maps.OverlayView {
        constructor(opts) {
            super();
            this.position = opts.position;
            this.content = opts.content;
            this.xAnchor = opts.xAnchor ?? 0.5;
            this.yAnchor = opts.yAnchor ?? 1.0;
            this.zIndex = opts.zIndex ?? 5;
            this._lastTranslate = '';
            this._lastLeft = '';
            this._lastTop = '';
            if (opts.map) {
                this.setMap(opts.map);
            }
        }

        onAdd() {
            const panes = this.getPanes();
            const targetPane = panes?.floatPane || panes?.overlayLayer || panes?.overlayImage;
            if (!targetPane) {
                return;
            }
            this.content.style.position = 'absolute';
            this.content.style.left = '0px';
            this.content.style.top = '0px';
            this.content.style.zIndex = String(this.zIndex);
            if (CAN_USE_CSS_TRANSLATE && !this._lastTranslate) {
                this.content.style.translate = '0px 0px';
            }
            this.content.style.transform = `translate(${-this.xAnchor * 100}%, ${-this.yAnchor * 100}%)`;
            targetPane.appendChild(this.content);
        }

        setPosition(nextPosition) {
            this.position = nextPosition;
            this.draw();
        }

        draw() {
            if (!this.getMap() || !isMapReadyForOverlays()) {
                return;
            }
            try {
                const projection = this.getProjection();
                if (!projection || typeof projection.fromCoordToOffset !== 'function') {
                    return;
                }
                const offset = projection.fromCoordToOffset(this.position);
                if (offset && Number.isFinite(offset.x) && Number.isFinite(offset.y)) {
                    const rx = Math.round(offset.x);
                    const ry = Math.round(offset.y);
                    if (CAN_USE_CSS_TRANSLATE) {
                        const nextTranslate = `${rx}px ${ry}px`;
                        if (this._lastTranslate !== nextTranslate) {
                            this._lastTranslate = nextTranslate;
                            this.content.style.translate = nextTranslate;
                        }
                    } else {
                        const nextLeft = `${rx}px`;
                        const nextTop = `${ry}px`;
                        if (this._lastLeft !== nextLeft) {
                            this._lastLeft = nextLeft;
                            this.content.style.left = nextLeft;
                        }
                        if (this._lastTop !== nextTop) {
                            this._lastTop = nextTop;
                            this.content.style.top = nextTop;
                        }
                    }
                }
            } catch {
                // GL 비동기 스타일 초기화 직전 투영 예외 방어
            }
        }

        onRemove() {
            if (this.content && this.content.parentNode) {
                this.content.parentNode.removeChild(this.content);
            }
        }
    }

    return new NaverDomOverlay(options);
}

/**
 * [함수 역할]
 * 네이버 지도 기본 GL 도형 오버레이(`Circle`, 2D 폴백 `Polyline`)가 지도 이동 중 매 프레임 동일한 GeoJSON을
 * WebWorker로 중복 전송하며 깜빡이거나 `idle` 무한 루프를 일으키는 현상을 차단합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `updateCircle()`, 그리고 `drawRouteOnMap()`의 비-GL 폴백 경로
 */
function guardGlShapeRedraw(shapeOverlay) {
    if (!shapeOverlay || typeof shapeOverlay.drawGL !== 'function' || shapeOverlay._glRedrawGuarded) {
        return shapeOverlay;
    }
    const origDrawGL = shapeOverlay.drawGL.bind(shapeOverlay);
    shapeOverlay._glRedrawGuarded = true;
    shapeOverlay.drawGL = function () {
        if (this.hasSource?.() && this._glSourceLoadedOnce) {
            return;
        }
        origDrawGL();
        if (this.hasSource?.()) {
            this._glSourceLoadedOnce = true;
        }
    };
    return shapeOverlay;
}

/**
 * [함수 역할]
 * 현재 출발 기준점(`state.origin`)을 중심으로 탐색 반경 원(`naver.maps.Circle`)을 지도 위에 표시합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `updateMarkers()`, `fitRadius()`, `locate()`, `setCustomOrigin()`, `resetOriginAndRoute()`, `mountNaverMap()`
 * - 읽는 DOM 값: `frontend/index.html`의 `<select id="radius">`
 *
 * [수정 시 영향 및 예시]
 * - 다크 모드에서 반경 원 테두리 색상을 하늘색(`#60a5fa`) 대신 에메랄드색(`#34d399`)으로 바꾸고 싶다면:
 *   변경 전: strokeColor: state.darkMode ? '#60a5fa' : '#2873df',
 *   변경 후: strokeColor: state.darkMode ? '#34d399' : '#2873df',
 *
 * @param {boolean} [force=false] 캐시 키(`state.circleKey`)가 같아도 강제로 다시 그릴지 여부
 */
function updateCircle(force = false) {
    if (!state.map || !isMapReadyForOverlays()) {
        return;
    }

    const radius = byId('radius').value;

    if (radius === 'all') {
        if (state.circle) {
            try {
                state.circle.setMap(null);
            } catch {
                // 이전 반경 원 해제 중 예외 무시
            }
            state.circle = null;
        }
        state.circleKey = null;
        state.circleMap = null;
        return;
    }

    const nextKey = `${state.origin.lat.toFixed(6)},${state.origin.lng.toFixed(6)},${radius},${state.darkMode ? 'dark' : 'light'}`;

    if (!force && state.circle && state.circleMap === state.map && state.circleKey === nextKey) {
        return;
    }

    if (state.circle) {
        try {
            state.circle.setMap(null);
        } catch {
            // 이전 반경 원 해제 중 예외 무시
        }
        state.circle = null;
    }

    try {
        const circle = new naver.maps.Circle({
            map: state.map,
            center: new naver.maps.LatLng(state.origin.lat, state.origin.lng),
            radius: Number(radius) * 1000,
            strokeWeight: 2,
            strokeColor: state.darkMode ? '#60a5fa' : '#2873df',
            strokeOpacity: state.darkMode ? 0.8 : 0.65,
            fillColor: state.darkMode ? '#3b82f6' : '#5e9cf0',
            fillOpacity: state.darkMode ? 0.11 : 0.07,
            clickable: false
        });
        state.circle = guardGlShapeRedraw(circle);
        state.circleKey = nextKey;
        state.circleMap = state.map;
        state.diagnostics.circleCreatedCount += 1;
    } catch {
        // GL 레이어 초기화 직전 예외 방어 (glReady 이벤트에서 즉시 재실행됨)
        state.circle = null;
        state.circleKey = null;
        state.circleMap = null;
    }
}

/**
 * [함수 역할]
 * 내 GPS 위치 또는 우클릭으로 지정한 출발 기준점(`state.origin`)에 파란색 맥동 점(`.location-marker`)을 표시합니다.
 * - `state.locationKnown === false`(예: '초기화' 버튼을 눌러 기본 상태로 돌아간 직후)이면 점을 깨끗이 제거합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `locate()`, `setCustomOrigin()`, `resetOriginAndRoute()`, `mountNaverMap()`
 * - 짝을 이루는 CSS: `frontend/style.css`의 `.location-marker` 및 `.location-dot` (`@keyframes location-wave`)
 */
function updateLocationMarker() {
    try {
        state.locationMarker?.setMap(null);
    } catch {
        // 이전 마커 해제 중 예외 무시
    }
    state.locationMarker = null;

    if (!state.map || !state.locationKnown || !isMapReadyForOverlays()) {
        return;
    }

    const marker = node('div', 'location-marker');
    marker.setAttribute('role', 'img');
    marker.setAttribute('aria-label', '내 현재 위치');
    marker.append(node('span', 'location-dot'));

    state.locationMarker = createNaverDomOverlay({
        map: state.map,
        position: new naver.maps.LatLng(state.origin.lat, state.origin.lng),
        content: marker,
        xAnchor: 0.5,
        yAnchor: 0.5,
        zIndex: 1000
    });
}

/**
 * [함수 역할]
 * 브라우저 표준 `navigator.geolocation` API로 사용자의 현재 GPS 좌표를 측정하여
 * 출발 기준점(`state.origin`)으로 설정하고 반경 원·마커·역지오코딩 주소·활성 경로를 자동 갱신합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `init()` 앱 최초 실행 시 1회, 그리고 `#location-button`('내 위치' 버튼) 클릭 시
 * - 이어지는 호출(Outgoing): `updateRadiusUI()`, `updateLocationMarker()`, `updateOriginAddress()`(`GET /api/reverse-geocode`),
 *   `updateMarkers()`, 그리고 활성 경로가 있으면 `refreshActiveRoute()`(`GET /api/directions`)까지 연쇄 갱신합니다!
 *
 * [수정 시 영향 및 예시]
 * - 실내에서 GPS 수신 대기 제한 시간(`timeout`)을 10초(`10000`)에서 5초(`5000`)로 줄여 빠르게 폴백하고 싶다면:
 *   변경 전: timeout: 10000,
 *   변경 후: timeout: 5000,
 */
function locate() {
    if (state.locating || !state.map) {
        return;
    }

    if (!navigator.geolocation) {
        setStatus('이 브라우저에서 위치 기능을 사용할 수 없습니다.');
        return;
    }

    state.locating = true;
    byId('location-button').disabled = true;

    const finish = () => {
        state.locating = false;
        byId('location-button').disabled = false;
    };

    navigator.geolocation.getCurrentPosition(
        position => {
            state.origin = {
                lat: position.coords.latitude,
                lng: position.coords.longitude
            };
            state.originLabel = '내 위치';
            state.originAddress = '';

            state.locationKnown = true;
            state.locationAccuracy = position.coords.accuracy;
            state.locationUpdated = new Date(position.timestamp || Date.now());

            updateRadiusUI();
            updateLocationMarker();
            updateOriginAddress();

            if (!state.selected) {
                fitRadius();

                if (byId('radius').value === 'all') {
                    state.map.panTo(new naver.maps.LatLng(state.origin.lat, state.origin.lng));
                }
            } else {
                updateCircle();
            }

            updateMarkers();
            if (state.etaRankOpen) {
                loadEtaRanking();
            }
            if (state.selected) {
                const isSelectedActiveRoute = Boolean(
                    state.activeRoute && state.activeRoute.hospital?.id === state.selected.id
                );
                populateWaypointSelect(state.selected);
                loadDirectionsForHospital(state.selected, isSelectedActiveRoute, { fitMap: false });
                if (state.activeRoute && !isSelectedActiveRoute) {
                    refreshActiveRoute({ fitMap: false });
                }
            } else if (state.activeRoute) {
                refreshActiveRoute({ fitMap: false });
            }
            finish();
        },
        error => {
            const reason = error.code === 1
                ? '위치 권한이 허용되지 않았습니다.'
                : '현재 위치를 확인하지 못했습니다.';

            updateRadiusUI();
            updateOriginAddress();
            setStatus(
                reason +
                (state.locationKnown
                    ? ' 마지막 측정 위치를 표시합니다.'
                    : ` ${DEFAULT_ORIGIN.label}을(를) 기준으로 표시합니다.`)
            );
            finish();
        },
        {
            enableHighAccuracy: true,
            timeout: 10000,
            maximumAge: 0
        }
    );
}

/**
 * [함수 역할]
 * 백엔드 Reverse Geocoding API(`GET /api/reverse-geocode`)를 호출하여
 * 현재 출발 기준 좌표(`state.origin`)의 행정동·도로명 주소를 조회하고 `#origin-address-text`에 표시합니다.
 *
 * [코드의 경로성]
 *   `app.js:updateOriginAddress()`
 *   ──(GET /api/reverse-geocode?lat=...&lng=...)──▶ `EmergencyApiController.java:getReverseGeocode()`
 *   ──▶ `NaverDirectionsService.java:getReverseGeocode()`
 *   ──▶ `ReverseGeocodeResponseDto.java` JSON 응답 → `updateRadiusUI()`로 화면 반영!
 *
 * [적용된 Modern Web 성능 최적화]
 * - 주소 문자열은 부가 정보이므로 `priority: 'low'` 옵션을 부여해 병원 스냅샷(`GET /api/hospitals`) 및
 *   WebGL 지도 타일 로딩을 절대 방해하지 않도록 네트워크 우선순위를 낮췄습니다.
 */
async function updateOriginAddress() {
    state.reverseGeocodeController?.abort();
    state.reverseGeocodeController = new AbortController();

    try {
        const params = new URLSearchParams({
            lat: String(state.origin.lat),
            lng: String(state.origin.lng)
        });
        const response = await fetch(`/api/reverse-geocode?${params.toString()}`, {
            signal: state.reverseGeocodeController.signal,
            priority: 'low',
            headers: { Accept: 'application/json' }
        });
        if (!response.ok) {
            return;
        }
        const data = await response.json();
        const addr = data.shortAddress || data.fullAddress || data.roadAddress || data.jibunAddress;
        if (data.code === 0 && addr) {
            state.originAddress = addr;
            updateRadiusUI();
        }
    } catch (err) {
        if (err?.name !== 'AbortError') {
            // 역지오코딩 실패 시 기본 라벨 유지
        }
    }
}

/**
 * [함수 역할]
 * 지도 우클릭(데스크톱 `rightclick`), 모바일 길게 누르기(`longtap`), 또는 주소 검색(`geocodeAndMoveOrigin`)을 통해
 * 사용자가 지정한 임의의 위경도 좌표를 새로운 출발 기준점(`state.origin`)으로 설정합니다.
 * 특히 **지도에 자동차 경로가 표시되어 있는 상태(`state.activeRoute !== null`)라면 새 출발점에서 목적지 병원까지
 * 경로를 즉시 실시간 재탐색(`refreshActiveRoute`)하여 폴리라인과 상단 배너 시간을 갱신**합니다!
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `mountNaverMap()`의 `'rightclick'` / `'longtap'` 리스너, `geocodeAndMoveOrigin()`
 * - 호출하는 함수(Outgoing): `updateRadiusUI()`, `updateLocationMarker()`, `updateCircle(true)`, `updateMarkers()`,
 *   `updateOriginAddress()`, `loadEtaRanking()`, `loadDirectionsForHospital()`, `refreshActiveRoute()`
 *
 * @param {number} lat 위도
 * @param {number} lng 경도
 * @param {string} [customLabel='지정 출발지'] 출발 기준점 표시 이름
 */
function setCustomOrigin(lat, lng, customLabel = '지정 출발지') {
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
        return;
    }

    state.origin = { lat, lng };
    state.locationKnown = true;
    state.originLabel = customLabel;
    state.originAddress = '';
    state.locationUpdated = new Date();

    updateRadiusUI();
    updateLocationMarker();
    updateCircle(true);
    updateMarkers();
    updateOriginAddress();
    setStatus(`출발 기준점을 선택한 위치(${lat.toFixed(4)}, ${lng.toFixed(4)})로 변경했습니다. (지도 우클릭으로 언제든 변경 가능)`);

    if (state.etaRankOpen) {
        loadEtaRanking();
    }

    if (state.selected) {
        const isSelectedActiveRoute = Boolean(
            state.activeRoute && state.activeRoute.hospital?.id === state.selected.id
        );
        populateWaypointSelect(state.selected);
        loadDirectionsForHospital(state.selected, isSelectedActiveRoute, { fitMap: false });
        if (state.activeRoute && !isSelectedActiveRoute) {
            refreshActiveRoute({ fitMap: false });
        }
    } else if (state.activeRoute) {
        refreshActiveRoute({ fitMap: false });
    }
}

/**
 * [함수 역할]
 * 네이버 지도 GL 다크 스타일(`customStyleId`)처럼 기본 스타일 JSON에 실시간 교통 벡터 타일(`ctt`)이 빠져 있는 경우,
 * 내부 Mapbox GL 인스턴스(`state.map.getMapbox()`)에 직접 교통 벡터 소스와 소통 색상 라인 레이어 6개를 주입·토글합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `syncTrafficLayer()` → `#traffic-toggle`('교통소통' 버튼)
 *
 * [수정 시 영향 및 예시]
 * - 실시간 도로 정체 구간 선 색상을 `#ff3b30`에서 더 진한 자주색(`#e11d48`)으로 바꾸려면 `yonsei-ctt-4`의 `color`를 수정하세요.
 */
function syncGlTrafficLayers(mapbox, visible) {
    if (!mapbox || typeof mapbox.getStyle !== 'function') {
        return;
    }

    const visibility = visible ? 'visible' : 'none';
    const style = mapbox.getStyle();
    if (!style || !Array.isArray(style.layers)) {
        return;
    }

    // 1. 기본 라이트 GL 스타일에 내장된 CTT 레이어가 있는 경우 함께 토글
    for (const layer of style.layers) {
        const group = layer?.metadata?.group;
        if (group === 'ctt' || group === 'dctt' || String(layer?.id || '').startsWith('CTT/')) {
            try {
                mapbox.setLayoutProperty(layer.id, 'visibility', visibility);
            } catch {
                // 레이어 상태 전환 중 예외 무시
            }
        }
    }

    // 2. 다크 모드 커스텀 스타일(customStyleId)처럼 'ctt' 소스가 없는 경우 직접 주입
    if (!mapbox.getSource('ctt')) {
        if (!visible) {
            return;
        }
        try {
            mapbox.addSource('ctt', {
                type: 'vector',
                url: 'https://map.pstatic.net/nvc/ctt/wmts/naver/getMetadata/v1'
            });
        } catch (err) {
            console.warn('다크모드 교통 벡터 소스(ctt) 추가 중 예외:', err);
            return;
        }
    }

    const bgWidthStops = [
        [6, 1.4], [7, 2.4], [8, 2.8], [9, 3.8], [10, 3.8],
        [11, 3.8], [12, 3.8], [13, 3.8], [14, 3.8], [15, 4], [16, 4], [17, 5]
    ];
    const fgWidthStops = [
        [6, 1.1], [7, 1.6], [8, 2.0], [9, 2.2], [10, 2.2],
        [11, 2.2], [12, 2.2], [13, 2.2], [14, 2.2], [15, 2.5], [16, 2.8], [17, 3.5]
    ];
    const lineOffsetStops = [
        [6, 1], [7, 1.5], [8, 2], [9, 2], [10, 3], [11, 3],
        [12, 3], [13, 3], [14, 3], [15, 4], [16, 5], [17, 8], [18, 8], [19, 32]
    ];

    const firstSymbolLayer = style.layers.find(l => l.type === 'symbol')?.id;

    const specs = [
        { id: 'yonsei-ctt-2-bg', stateVal: 1, color: 'rgb(0, 0, 0)', widthStops: bgWidthStops },
        { id: 'yonsei-ctt-3-bg', stateVal: 2, color: 'rgb(0, 0, 0)', widthStops: bgWidthStops },
        { id: 'yonsei-ctt-4-bg', stateVal: 4, color: 'rgb(0, 0, 0)', widthStops: bgWidthStops },
        { id: 'yonsei-ctt-2', stateVal: 1, color: '#27c441', widthStops: fgWidthStops },
        { id: 'yonsei-ctt-3', stateVal: 2, color: '#ff8d00', widthStops: fgWidthStops },
        { id: 'yonsei-ctt-4', stateVal: 4, color: '#ff3b30', widthStops: fgWidthStops }
    ];

    for (const spec of specs) {
        if (!mapbox.getLayer(spec.id)) {
            try {
                mapbox.addLayer({
                    id: spec.id,
                    type: 'line',
                    source: 'ctt',
                    'source-layer': 'ctt',
                    minzoom: 4,
                    maxzoom: 22,
                    filter: ['==', 'state', spec.stateVal],
                    layout: {
                        visibility,
                        'line-cap': 'round',
                        'line-join': 'miter'
                    },
                    paint: {
                        'line-color': spec.color,
                        'line-width': { stops: spec.widthStops },
                        'line-offset': { stops: lineOffsetStops }
                    }
                }, firstSymbolLayer);
            } catch (err) {
                console.warn(`다크모드 교통 레이어(${spec.id}) 추가 실패:`, err);
            }
        } else {
            try {
                mapbox.setLayoutProperty(spec.id, 'visibility', visibility);
            } catch {
                // 무시
            }
        }
    }
}

/**
 * [함수 역할]
 * 네이버 지도 실시간 교통소통 레이어(`naver.maps.TrafficLayer` 및 GL `ctt` 벡터 레이어)를
 * 현재 `state.trafficEnabled` 상태에 맞춰 활성화/비활성화합니다.
 * - 다크/라이트 모드를 전환(`mountNaverMap`)하더라도 켜 두었던 교통소통 레이어가 그대로 자동 복원됩니다.
 */
function syncTrafficLayer() {
    if (!state.map || typeof naver === 'undefined' || !naver.maps?.TrafficLayer) {
        return;
    }

    if (state.map.get?.('renderMode') === 2 && !isMapReadyForOverlays()) {
        naver.maps.Event.once(state.map, 'glReady', () => {
            syncTrafficLayer();
        });
        return;
    }

    if (state.trafficEnabled) {
        try {
            if (!state.trafficLayer) {
                state.trafficLayer = new naver.maps.TrafficLayer({ interval: 300000 });
            }
            state.trafficLayer.setMap(state.map);
        } catch (err) {
            console.warn('실시간 교통소통 레이어 활성화 중 예외:', err);
        }
    } else if (state.trafficLayer) {
        try {
            state.trafficLayer.setMap(null);
        } catch {
            // 레이어 해제 중 예외 무시
        }
    }

    try {
        const mapbox = state.map.getMapbox?.();
        if (mapbox) {
            syncGlTrafficLayers(mapbox, state.trafficEnabled);
        }
    } catch (err) {
        console.warn('GL 실시간 교통 레이어 동기화 중 예외:', err);
    }
}

/**
 * [함수 역할]
 * 도구 모음의 `교통소통` 버튼(`index.html`의 `#traffic-toggle`) 클릭 시 실시간 도로 소통 표시를 ON/OFF 전환합니다.
 */
function toggleTrafficLayer() {
    state.trafficEnabled = !state.trafficEnabled;
    const btn = byId('traffic-toggle');
    if (btn) {
        btn.setAttribute('aria-pressed', String(state.trafficEnabled));
    }
    syncTrafficLayer();
}

/**
 * [함수 역할]
 * 현재 출발 기준점(`state.origin`)에서 주변 후보 응급의료기관까지의 **실도로 주행 소요시간(ETA)**을
 * 백엔드(`GET /api/directions/rank`)로 일괄 비교하여 최단시간 상위 병원 추천 목록(`#eta-rank-box`)을 렌더링합니다.
 *
 * [코드의 경로성]
 *   `#eta-rank-btn` 클릭 (`init()` 리스너) / 출발지 변경 시 자동 갱신
 *   ──▶ `app.js:loadEtaRanking()`
 *   ──(GET /api/directions/rank)──▶ `EmergencyApiController.java:getEtaRanking()`
 *   ──▶ `NaverDirectionsService.java:rankHospitalsByEta()` (직선거리 상위 후보 추출 후 네이버 Directions 병렬 호출)
 *   ──▶ `List<HospitalEtaRankDto>` JSON 반환 → 추천 행 클릭 시 `openHospital()` + `loadDirectionsForHospital(..., true)` 실행!
 *
 * [수정 시 영향 및 예시]
 * - 추천 병원 개수를 상위 5곳(`'5'`)에서 상위 8곳(`'8'`)으로 늘리고 싶다면:
 *   변경 전: limit: '5',
 *   변경 후: limit: '8', (백엔드 `rankHospitalsByEta`는 최대 10곳까지 허용합니다)
 */
async function loadEtaRanking() {
    const box = byId('eta-rank-box');
    const btn = byId('eta-rank-btn');
    if (!box) {
        return;
    }

    state.etaRankOpen = true;
    box.hidden = false;
    if (btn) {
        btn.setAttribute('aria-expanded', 'true');
    }

    state.etaRankController?.abort();
    state.etaRankController = new AbortController();

    box.replaceChildren(
        node('p', 'empty-state', '주변 응급의료기관의 실시간 도로 소요시간(ETA)을 비교하고 있습니다…')
    );

    const radiusVal = byId('radius')?.value || '10';
    const radiusKm = radiusVal === 'all' ? '30' : radiusVal;
    const includeOther = Boolean(byId('filter-other')?.checked);

    try {
        const params = new URLSearchParams({
            startLat: String(state.origin.lat),
            startLng: String(state.origin.lng),
            radiusKm: String(radiusKm),
            includeOther: String(includeOther),
            limit: '5',
            option: 'trafast'
        });

        const response = await fetch(`/api/directions/rank?${params.toString()}`, {
            signal: state.etaRankController.signal,
            headers: { Accept: 'application/json' }
        });

        if (!response.ok) {
            box.replaceChildren(
                node('p', 'empty-state', `최단시간 병원 조회 실패 (HTTP ${response.status})`)
            );
            return;
        }

        const ranks = await response.json();
        box.replaceChildren();

        const header = node('div', 'eta-rank-header');
        header.append(
            node('span', '', `실도로 최단시간 병원 추천 (반경 ${radiusKm}km 내 상위 ${ranks.length}곳)`)
        );
        const closeBtn = node('button', 'eta-rank-close', '닫기');
        closeBtn.type = 'button';
        closeBtn.addEventListener('click', () => {
            state.etaRankOpen = false;
            box.hidden = true;
            btn?.setAttribute('aria-expanded', 'false');
        });
        header.append(closeBtn);
        box.append(header);

        if (!Array.isArray(ranks) || !ranks.length) {
            box.append(node('p', 'empty-state', '해당 반경 내 비교 가능한 응급의료기관이 없습니다. 반경을 넓혀보세요.'));
            return;
        }

        const list = node('div', 'eta-rank-list');
        for (const item of ranks) {
            const row = node('button', `eta-rank-item ${item.rank === 1 ? 'rank-1' : ''}`);
            row.type = 'button';

            const badge = node('span', 'eta-rank-badge', `${item.rank}위`);
            const main = node('div', 'eta-rank-main');
            const drivingKm = item.drivingDistanceKm ?? item.roadDistanceKm ?? '-';
            main.append(
                node('strong', '', `${item.name} · ${item.durationText}`),
                node(
                    'small',
                    '',
                    `${item.typeLabel} · 실도로 ${drivingKm}km (직선 ${item.straightDistanceKm}km)`
                )
            );
            const erBadge = node('span', 'eta-rank-er', `응급실 ${bedValue(item.erBeds)}`);

            row.append(badge, main, erBadge);
            row.addEventListener('click', () => {
                const target = state.hospitals.find(h => h.id === item.id);
                if (target) {
                    openHospital(target);
                    focusHospitalOnMap(target, 14);
                    loadDirectionsForHospital(target, true);
                }
            });

            list.append(row);
        }
        box.append(list);
    } catch (err) {
        if (err?.name === 'AbortError') {
            return;
        }
        box.replaceChildren(
            node('p', 'empty-state', `최단시간 병원 조회 중 오류가 발생했습니다: ${err.message}`)
        );
    }
}

/* --------------------------------------------------------------------------
   [9. 병원 지도 카드 렌더링, O(N) 2D 공간 해시 클러스터링 & Key-Diffing 엔진]
   -------------------------------------------------------------------------- */

/**
 * [함수 역할]
 * 병원의 시각적 색상 그룹 코드(`'regional'`, `'tertiary'`, `'medical'`, `'other'`)를 반환합니다.
 * - `fetchData()`에서 미리 계산해 둔 `hospital._vGroup`이 있으면 즉시 반환합니다.
 * - `frontend/style.css`의 `.hospital-card.regional`(빨강), `.tertiary`(주황), `.medical`(파랑), `.other`(초록) 클래스와 1:1 매칭됩니다.
 */
function visualGroup(hospital) {
    return hospital._vGroup || (hospital.regional ? 'regional' : hospital.type.group);
}

/**
 * [함수 역할]
 * 상세 시트 상단 배지(`#hospital-type`)와 마커 툴팁에 표시할 기관 종별 한글 설명을 생성합니다.
 */
function hospitalLabel(hospital) {
    return hospital.regional
        ? `권역응급의료센터 · ${hospital.type.label}`
        : hospital.type.label;
}

/**
 * [함수 역할]
 * 검색 패널 하단 4개 필터 체크박스(`권역응급`, `상급종합`, `의료원`, `일반·기타`) 중
 * 해당 병원이 표시 대상에 해당하는지 판별합니다.
 *
 * [도메인 규칙]
 * - 상급종합병원이면서 동시에 권역응급의료센터인 병원(예: 세브란스병원, 고대안암병원 등)은
 *   `권역응급` 또는 `상급종합` 둘 중 하나만 켜져 있어도 지도에 표시됩니다.
 */
function matchesFilters(hospital, enabled) {
    return Boolean(
        enabled[hospital.type.group] ||
        (hospital.regional && enabled.regional)
    );
}

/**
 * [함수 역할]
 * 화면 픽셀 좌표가 겹치는 병원 카드들을 하나의 클러스터 그룹(`"주변 N개 병원"`)으로 묶습니다.
 *
 * [Modern Web 성능 최적화 Phase 2 — O(N^2) 전수 비교 → O(N) 2D 공간 해시 격자(Spatial Hash Grid)]
 * - 기존 방식은 카드마다 기존 모든 그룹을 순회(`groups.find(...)`)하여 전국 보기 시 최대 수십만 번 비교했습니다.
 * - 개선 방식: 화면을 `(CARD_WIDTH + 10) × (CARD_HEIGHT + 14)` 크기의 2D 격자 셀로 나누어 `Map`에 등록하고,
 *   주변 `3 × 3 = 9`개 인접 셀만 조회합니다.
 * - 특히 여러 후보가 겹칠 때 가장 먼저 생성된 그룹(`_idx` 최소값)을 선택하도록 구현하여,
 *   기존 `groups.find()`와 **결과가 100% 수학적으로 동일**하면서도 시간 복잡도는 **$O(N)$**으로 단축되었습니다!
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `updateMarkers()`
 *
 * [수정 시 영향 및 예시]
 * - 카드끼리 조금 더 가까이 붙어도 뭉치지 않게(개별 카드로 더 많이 보이게) 하고 싶다면 여백 숫자(`10`, `14`)를 줄이세요:
 *   변경 전: const cellW = CARD_WIDTH + 10; const cellH = CARD_HEIGHT + 14;
 *   변경 후: const cellW = CARD_WIDTH + 2;  const cellH = CARD_HEIGHT + 4;
 *
 * @param {Array<{x: number, y: number, hospital: Object}>} points 화면 픽셀 좌표가 부여된 병원 후보 목록
 * @returns {Array<{x: number, y: number, hospital: Object, items: Array<Object>}>} 클러스터링된 카드 그룹 배열
 */
function groupCards(points) {
    const groups = [];
    const cellW = CARD_WIDTH + 10;
    const cellH = CARD_HEIGHT + 14;
    const grid = new Map();

    for (const point of points) {
        const cx = Math.floor(point.x / cellW);
        const cy = Math.floor(point.y / cellH);
        let bestNear = null;

        for (let dy = -1; dy <= 1; dy++) {
            for (let dx = -1; dx <= 1; dx++) {
                const bucket = grid.get(`${cx + dx},${cy + dy}`);
                if (!bucket) {
                    continue;
                }
                for (let i = 0; i < bucket.length; i++) {
                    const candidate = bucket[i];
                    if (
                        Math.abs(candidate.x - point.x) < cellW &&
                        Math.abs(candidate.y - point.y) < cellH &&
                        (!bestNear || candidate._idx < bestNear._idx)
                    ) {
                        bestNear = candidate;
                    }
                }
            }
        }

        if (bestNear) {
            bestNear.items.push(point.hospital);
        } else {
            const created = {
                ...point,
                items: [point.hospital],
                _idx: groups.length
            };
            groups.push(created);

            const cellKey = `${cx},${cy}`;
            let bucket = grid.get(cellKey);
            if (!bucket) {
                bucket = [];
                grid.set(cellKey, bucket);
            }
            bucket.push(created);
        }
    }
    return groups;
}

/**
 * [함수 역할]
 * 병원 마커 카드(`.hospital-card`) 위에 포인터/클릭 이벤트를 단 1회만 바인딩합니다.
 * - 카드 위에서 지도를 드래그(`6px` 초과 이동)했을 때는 실수로 병원 상세창이 열리지 않도록 막고,
 *   제자리 클릭일 때만 `card._onActivate()` 콜백을 실행합니다.
 * - Key-Diffing으로 카드가 재사용될 때 이벤트 리스너가 중복 누적되지 않도록 `card._hospitalCardBound` 플래그로 보호합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `updateMarkers()`
 * - 이어지는 동작(Outgoing): 클릭 확정 시 단일 병원이면 `openHospital()`, 겹침 클러스터이면 `openCluster()` 호출
 */
function bindHospitalCard(card, open) {
    card._onActivate = open;
    if (card._hospitalCardBound) {
        return;
    }
    card._hospitalCardBound = true;

    let moved = false;
    let tracker = null;

    card.draggable = false;

    card.addEventListener('pointerdown', event => {
        if (event.button !== 0) {
            return;
        }

        state.cardPointerDown = true;
        tracker?.abort();
        tracker = new AbortController();
        moved = false;

        const startX = event.clientX;
        const startY = event.clientY;
        const pointerId = event.pointerId;

        const track = next => {
            if (next.pointerId !== pointerId) {
                return;
            }
            const distance = Math.hypot(next.clientX - startX, next.clientY - startY);
            if (distance > 6) {
                moved = true;
            }
        };

        const finish = next => {
            track(next);
            if (next.pointerId !== pointerId) {
                return;
            }
            if (next.type === 'pointercancel') {
                moved = true;
            }
            state.cardPointerDown = false;
            tracker.abort();
            if (state.pendingMarkerUpdate) {
                state.pendingMarkerUpdate = false;
                requestAnimationFrame(() => updateMarkers());
            }
        };

        const options = {
            capture: true,
            passive: true,
            signal: tracker.signal
        };

        window.addEventListener('pointermove', track, options);
        window.addEventListener('pointerup', finish, options);
        window.addEventListener('pointercancel', finish, options);
    });

    card.addEventListener('click', event => {
        if (event.detail !== 0 && moved) {
            event.preventDefault();
            return;
        }
        card._onActivate?.();
    });
}

/**
 * [함수 역할]
 * 위경도 좌표를 네이버 지도 화면 픽셀 좌표(`{ x, y }`)로 투영합니다.
 * - 캐싱된 `naver.maps.LatLng` 객체(`cachedLatLng`)를 전달받으면 불필요한 객체 생성을 생략합니다.
 */
function coordToScreenPoint(lat, lng, cachedLatLng = null) {
    const latLng = cachedLatLng || new naver.maps.LatLng(lat, lng);
    if (isMapReadyForOverlays()) {
        try {
            const projection = state.map.getProjection?.();
            if (projection && typeof projection.fromCoordToOffset === 'function') {
                const offset = projection.fromCoordToOffset(latLng);
                if (offset && Number.isFinite(offset.x) && Number.isFinite(offset.y)) {
                    return { x: offset.x, y: offset.y };
                }
            }
        } catch {
            // GL 비동기 스타일 초기화 중 투영 객체 미준비 예외 방어
        }
    }
    const zoom = state.map.getZoom?.() || 12;
    const scale = 256 * (2 ** zoom);
    const x = ((lng + 180) / 360) * scale;
    const sinLat = Math.sin((lat * Math.PI) / 180);
    const y = (0.5 - Math.log((1 + sinLat) / (1 - sinLat)) / (4 * Math.PI)) * scale;
    return { x, y };
}

/**
 * [함수 역할]
 * 지도 위에 올려진 모든 병원 카드 오버레이(`state.markers` 및 `state.markerMap`)를 완전히 제거합니다.
 * - 테마 전환(`mountNaverMap`)처럼 지도 인스턴스 자체가 교체될 때 호출됩니다.
 */
function clearMarkers() {
    for (const overlay of state.markerMap.values()) {
        try {
            overlay.setMap(null);
        } catch {
            // 오버레이 해제 중 예외 무시
        }
    }
    state.markerMap.clear();

    for (const marker of state.markers) {
        try {
            marker.setMap(null);
        } catch {
            // 오버레이 해제 중 예외 무시
        }
    }
    state.markers = [];
}

/**
 * [함수 역할]
 * 현재 지도 화면 범위(`viewport`), 활성화된 종별 체크박스 필터, 탐색 반경(`#radius`)에 맞는 병원을 추려
 * 네이버 지도 위에 병원 카드 오버레이를 렌더링합니다.
 *
 * [Modern Web 성능 최적화 Phase 2 — 3중 가속 파이프라인]
 * 1. **초고속 위경도 사각 경계(BBox) 사전 필터링 + 거리 1회 선계산**:
 *    화면 밖 병원을 먼저 걸러내고, 반경 사각 경계 밖 병원은 삼각함수(`distanceKm`) 호출 자체를 생략합니다.
 *    또한 `.sort()` 비교 함수 내부에서 거리를 반복 재계산하지 않고 후보당 딱 1번만 계산합니다.
 * 2. **$O(N)$ 2D 공간 해시 클러스터링(`groupCards`)**: 겹치는 카드를 선형 시간에 그룹화합니다.
 * 3. **Key-Diffing DOM 재사용(`state.markerMap`)**:
 *    기존에는 지도를 살짝만 움직여도(`idle`) 화면의 모든 `.hospital-card` DOM을 전부 삭제(`clearMarkers`)하고
 *    새로 만들어 GC 스파이크와 깜빡임이 발생했습니다.
 *    이제는 `single:{id}` / `cluster:{ids}` 고유 키를 비교해 **화면 밖으로 나간 카드만 제거하고,
 *    유지되는 카드는 기존 DOM 엘리먼트를 그대로 재사용하여 좌표(`style.translate`)와 텍스트만 갱신**합니다!
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `mountNaverMap()`의 `'idle'` 이벤트, 필터 체크박스 변경, 반경 변경, `fetchData()`, `locate()`, `setCustomOrigin()`
 * - 가리키는 DOM(Outgoing): `#map` 내부 오버레이 Pane 및 하단 안내 문구 `#map-count`
 */
function updateMarkers() {
    if (!state.map) {
        return;
    }

    // 사용자가 병원 카드를 누르고 있는 도중(pointerdown ~ pointerup 사이)에는
    // DOM 노드가 교체되어 클릭 이벤트가 유실되지 않도록 마커 갱신을 클릭 직후로 지연합니다.
    if (state.cardPointerDown) {
        state.pendingMarkerUpdate = true;
        return;
    }

    updateCircle();

    if (!isMapReadyForOverlays()) {
        return;
    }

    state.diagnostics.markersUpdatedCount += 1;

    const radius = byId('radius').value;
    const numRadius = radius === 'all' ? null : Number(radius);
    const radiusBox = numRadius !== null ? radiusExtent(state.origin, numRadius) : null;
    const enabled = {};

    for (const group of ['regional', 'tertiary', 'medical', 'other']) {
        enabled[group] = byId(`filter-${group}`).checked;
    }

    let viewport = null;
    try {
        viewport = state.map.getBounds();
    } catch {
        viewport = null;
    }

    const rank = {
        regional: 0,
        tertiary: 1,
        medical: 2,
        other: 3
    };

    const candidateEntries = [];
    for (let i = 0; i < state.hospitals.length; i++) {
        const hospital = state.hospitals[i];
        const isSelected = Boolean(state.selected && hospital.id === state.selected.id);

        if (!isSelected && !matchesFilters(hospital, enabled)) {
            continue;
        }

        const pos = getHospitalLatLng(hospital);
        if (viewport) {
            const inViewport = typeof viewport.hasLatLng === 'function'
                ? viewport.hasLatLng(pos)
                : viewport.hasPoint(pos);
            if (!inViewport) {
                continue;
            }
        }

        if (!isSelected && radiusBox) {
            if (
                hospital.lat < radiusBox.south - 0.005 ||
                hospital.lat > radiusBox.north + 0.005 ||
                hospital.lng < radiusBox.west - 0.005 ||
                hospital.lng > radiusBox.east + 0.005
            ) {
                continue;
            }
        }

        const dist = distanceKm(state.origin, hospital);
        if (!isSelected && numRadius !== null && dist > numRadius) {
            continue;
        }

        candidateEntries.push({
            hospital,
            pos,
            rankVal: rank[visualGroup(hospital)] ?? 3,
            dist
        });
    }

    candidateEntries.sort((a, b) => (a.rankVal - b.rankVal) || (a.dist - b.dist));

    const points = new Array(candidateEntries.length);
    for (let i = 0; i < candidateEntries.length; i++) {
        const { hospital, pos } = candidateEntries[i];
        const point = coordToScreenPoint(hospital.lat, hospital.lng, pos);
        points[i] = {
            x: point.x,
            y: point.y,
            hospital
        };
    }

    const groups = groupCards(points);
    const nextGroupByKey = new Map();

    for (const group of groups) {
        const multiple = group.items.length > 1;
        const key = multiple
            ? `cluster:${group.items.map(item => item.id).join(',')}`
            : `single:${group.items[0].id}`;
        nextGroupByKey.set(key, group);
    }

    // 1단계: 현재 화면·필터에서 벗어난 마커만 선별 제거
    for (const [key, existingOverlay] of state.markerMap.entries()) {
        if (!nextGroupByKey.has(key) || existingOverlay.getMap() !== state.map) {
            try {
                existingOverlay.setMap(null);
            } catch {
                // 오버레이 해제 중 예외 무시
            }
            state.markerMap.delete(key);
            state.diagnostics.markersRemovedCount += 1;
        }
    }

    // 2단계: 기존 마커는 DOM을 재사용하여 속성·좌표만 갱신하고, 새로 진입한 마커만 생성
    for (const [key, group] of nextGroupByKey.entries()) {
        const hospital = group.items[0];
        const multiple = group.items.length > 1;
        const vGroup = visualGroup(hospital);
        const nextClassName = `hospital-card ${vGroup}`;

        const nextTitle = multiple
            ? group.items.map(item => item.name).join(' · ')
            : `${hospital.name} · ${hospitalLabel(hospital)}`;

        const nextAriaLabel = multiple
            ? `주변 ${group.items.length}개 병원 목록 열기`
            : `${hospital.name} 상세 정보 열기`;

        const shortType = hospital.regional
            ? '권역응급'
            : hospital.type.label;

        const nextStrongText = multiple ? `주변 ${group.items.length}개 병원` : hospital.name;
        const nextSmallText = multiple
            ? '목록에서 병원 선택 ›'
            : `${shortType} · 응급실 ${bedValue(hospital.beds?.hvec)}`;

        const activateHandler = () => {
            if (multiple) {
                openCluster(group.items);
            } else {
                openHospital(hospital);
            }
        };

        const targetPos = getHospitalLatLng(hospital);
        const existingOverlay = state.markerMap.get(key);

        if (existingOverlay && existingOverlay.content) {
            const card = existingOverlay.content;
            if (card.className !== nextClassName) {
                card.className = nextClassName;
            }
            if (card.title !== nextTitle) {
                card.title = nextTitle;
            }
            if (card.getAttribute('aria-label') !== nextAriaLabel) {
                card.setAttribute('aria-label', nextAriaLabel);
            }
            if (card._strongEl && card._strongEl.textContent !== nextStrongText) {
                card._strongEl.textContent = nextStrongText;
            }
            if (card._smallEl && card._smallEl.textContent !== nextSmallText) {
                card._smallEl.textContent = nextSmallText;
            }
            card._onActivate = activateHandler;
            existingOverlay.setPosition(targetPos);
            state.diagnostics.markersReusedCount += 1;
        } else {
            const card = node('button', nextClassName);
            card.type = 'button';
            card.title = nextTitle;
            card.setAttribute('aria-label', nextAriaLabel);

            const strongEl = node('strong', '', nextStrongText);
            const smallEl = node('small', '', nextSmallText);
            card._strongEl = strongEl;
            card._smallEl = smallEl;
            card.append(strongEl, smallEl);

            bindHospitalCard(card, activateHandler);

            const overlay = createNaverDomOverlay({
                map: state.map,
                position: targetPos,
                content: card,
                xAnchor: 0.5,
                yAnchor: 1.0,
                zIndex: 5
            });

            state.markerMap.set(key, overlay);
            state.diagnostics.markersCreatedCount += 1;
        }
    }

    state.markers = Array.from(state.markerMap.values());

    byId('map-count').textContent =
        `현재 화면 ${candidateEntries.length}개 기관 · 겹치는 카드는 묶어서 표시`;
}

/**
 * [함수 역할]
 * 겹쳐 있는 클러스터 카드(`"주변 N개 병원"`) 클릭 시 모달 다이얼로그(`index.html`의 `#cluster-dialog`)를 열어
 * 묶여 있는 병원 목록을 보여주고 원하는 병원을 선택하거나 지도를 확대할 수 있게 합니다.
 */
function openCluster(items) {
    state.clusterItems = items;
    byId('cluster-title').textContent = `주변 ${items.length}개 병원`;

    const list = byId('cluster-list');
    list.replaceChildren();

    for (const hospital of items) {
        const button = node('button', `cluster-item ${visualGroup(hospital)}`);
        button.type = 'button';

        button.append(
            node('strong', '', hospital.name),
            node('small', '', hospitalLabel(hospital)),
            node('small', '', `응급실 ${bedValue(hospital.beds?.hvec)}`)
        );

        button.addEventListener('click', () => {
            byId('cluster-dialog').close();
            openHospital(hospital);
        });

        list.append(button);
    }

    byId('cluster-dialog').showModal();
}

/* --------------------------------------------------------------------------
   [10. 실시간 병원 검색, 카메라 포커싱 & 주소 지오코딩]
   -------------------------------------------------------------------------- */

/**
 * [함수 역할]
 * 검색 결과나 최단시간 추천 목록에서 특정 병원을 선택했을 때,
 * 지도의 중심 이동과 줌 레벨 변경을 단일 원자적 애니메이션(`morph`)으로 부드럽게 수행합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `updateSearch()` 검색 결과 클릭, `loadEtaRanking()` 추천 순위 클릭
 *
 * [수정 시 영향 및 예시]
 * - 병원 선택 시 확대되는 기본 줌 레벨을 `15`(동네 상세)에서 `16`(건물 단위)으로 더 가깝게 바꾸려면:
 *   변경 전: function focusHospitalOnMap(hospital, targetZoom = 15)
 *   변경 후: function focusHospitalOnMap(hospital, targetZoom = 16)
 *
 * @param {Object} hospital 이동할 대상 병원 객체
 * @param {number} [targetZoom=15] 목표 지도 줌 레벨
 */
function focusHospitalOnMap(hospital, targetZoom = 15) {
    if (!state.map || !hospital || !Number.isFinite(hospital.lat) || !Number.isFinite(hospital.lng)) {
        return;
    }

    state.hasFitted = true;

    const targetLatLng = getHospitalLatLng(hospital);

    if (state.map.get?.('renderMode') === 2 && !isMapReadyForOverlays()) {
        naver.maps.Event.once(state.map, 'glReady', () => {
            focusHospitalOnMap(hospital, targetZoom);
        });
        return;
    }

    if (typeof state.map.morph === 'function') {
        state.map.morph(targetLatLng, targetZoom, {
            duration: 450,
            easing: 'easeOutCubic'
        });
    } else if (typeof state.map.updateBy === 'function') {
        state.map.updateBy(targetLatLng, targetZoom);
    } else {
        state.map.setCenter(targetLatLng);
        state.map.setZoom(targetZoom);
    }
}

/**
 * [함수 역할]
 * 검색창(`#keyword`)에 입력한 주소·역 이름·지역명(예: `'신촌역'`, `'원주시 흥업면'`)을
 * 백엔드 Geocoding API(`GET /api/geocode`)로 변환해 출발 기준점(`state.origin`)을 이동시킵니다.
 *
 * [코드의 경로성]
 *   `updateSearch()` 하단 `'... 주소·지역으로 출발 기준점 이동'` 버튼 클릭
 *   ──▶ `app.js:geocodeAndMoveOrigin()`
 *   ──(GET /api/geocode?query=...)──▶ `EmergencyApiController.java:getGeocode()`
 *   ──▶ `NaverDirectionsService.java:getGeocode()` → `GeocodeResponseDto.java`
 *   ──▶ `setCustomOrigin(targetLat, targetLng, label)`로 출발점·반경·경로 일괄 갱신!
 *
 * @param {string} rawQuery 검색할 주소 또는 지역명
 */
async function geocodeAndMoveOrigin(rawQuery) {
    const trimmed = String(rawQuery || '').trim();
    if (!trimmed) {
        return;
    }

    setStatus(`'${trimmed}' 위치 좌표를 조회하고 있습니다…`);
    try {
        const params = new URLSearchParams({ query: trimmed });
        const response = await fetch(`/api/geocode?${params.toString()}`, {
            headers: { Accept: 'application/json' }
        });
        if (!response.ok) {
            setStatus(`위치 조회 실패 (HTTP ${response.status})`);
            return;
        }
        const data = await response.json();
        const firstItem = Array.isArray(data.items) && data.items.length > 0 ? data.items[0] : data;
        const targetLat = Number(firstItem?.lat);
        const targetLng = Number(firstItem?.lng);
        if (data.code !== 0 || !Number.isFinite(targetLat) || !Number.isFinite(targetLng)) {
            setStatus(`'${trimmed}'에 해당하는 주소나 지역 좌표를 찾지 못했습니다.`);
            return;
        }

        const label = firstItem.title || firstItem.roadAddress || firstItem.jibunAddress || trimmed;
        byId('search-results').hidden = true;
        blurKeyword();

        setCustomOrigin(targetLat, targetLng, label);

        if (state.map) {
            state.hasFitted = true;
            if (byId('radius').value === 'all') {
                const targetLatLng = new naver.maps.LatLng(targetLat, targetLng);
                if (typeof state.map.morph === 'function') {
                    state.map.morph(targetLatLng, 13, { duration: 450, easing: 'easeOutCubic' });
                } else {
                    state.map.setCenter(targetLatLng);
                    state.map.setZoom(13);
                }
            } else {
                fitRadius();
            }
        }
        setStatus(`출발 기준점을 '${label}' 위치로 변경했습니다.`);
    } catch (err) {
        setStatus(`위치 조회 중 오류 발생: ${err.message}`);
    }
}

/**
 * [함수 역할]
 * 검색창(`index.html`의 `#keyword`) 입력값과 일치하는 병원명·주소를 실시간 자동완성 목록(`#search-results`)으로 보여줍니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `#keyword`의 `'input'` / `'focus'` 이벤트, `#search-form`의 `'submit'` 이벤트, `fetchData()`
 *
 * [수정 시 영향 및 예시]
 * - 자동완성 최대 표시 개수를 25개(`.slice(0, 25)`)에서 15개로 줄이고 싶다면 `.slice(0, 15)`로 수정하세요.
 */
function updateSearch() {
    closeRadius();

    const list = byId('search-results');
    const keywordInput = byId('keyword');
    const rawValue = keywordInput.value.trim();
    const query = rawValue
        .replace(/\s+/g, '')
        .toLowerCase();

    list.replaceChildren();

    const isKeywordFocused = document.activeElement === keywordInput;
    list.hidden =
        !query ||
        byId('search-content').hidden ||
        (state.selected !== null && !isKeywordFocused);

    if (!query) {
        return;
    }

    const matches = state.hospitals
        .filter(item =>
            item.name
                .replace(/\s+/g, '')
                .toLowerCase()
                .includes(query) ||
            String(item.address || '')
                .replace(/\s+/g, '')
                .toLowerCase()
                .includes(query)
        )
        .slice(0, 25);

    const fragment = document.createDocumentFragment();

    for (const hospital of matches) {
        const li = node('li');
        const button = node('button', 'result-button');
        button.type = 'button';

        button.append(
            node('strong', '', hospital.name),
            node('small', '', hospital.type.label)
        );

        button.addEventListener('click', () => {
            keywordInput.value = hospital.name;
            list.hidden = true;
            keywordInput.blur();

            openHospital(hospital);
            focusHospitalOnMap(hospital, 15);
            updateMarkers();
        });

        li.append(button);
        fragment.append(li);
    }

    // 주소/지역명 지오코딩을 통한 출발 기준점 이동 항목 추가
    const geoLi = node('li', 'search-geocode-li');
    const geoBtn = node('button', 'search-geocode-btn');
    geoBtn.type = 'button';
    geoBtn.append(
        node('strong', '', `'${rawValue}' 주소·지역으로 출발 기준점 이동`),
        node('small', '', '주변 병원 탐색')
    );
    geoBtn.addEventListener('click', () => {
        geocodeAndMoveOrigin(rawValue);
    });
    geoLi.append(geoBtn);
    fragment.append(geoLi);

    list.append(fragment);
}

/* --------------------------------------------------------------------------
   [11. 네이버 Directions 5 & 15 자동차 경로 탐색 및 단일 GeoJSON GL 레이어 엔진]
   -------------------------------------------------------------------------- */

/**
 * [함수 역할]
 * 현재 지도 위에 자동차 주행 경로가 렌더링되어 있는지(GL 단일 소스 또는 2D 폴백 폴리라인) 여부를 반환합니다.
 */
function hasRenderedRoute() {
    return Boolean(state.glRouteActive || state.routePolyline);
}

/**
 * [함수 역할]
 * 백엔드 `/api/directions` 응답(`data.path`, `data.sections`)을 단일 Mapbox GL GeoJSON `FeatureCollection`으로 변환합니다.
 * - 기본 전체 경로(`role: 'base'`), 혼잡 구간(`role: 'section'`, `congestion: 2 | 3`),
 *   출발점(`role: 'start'`), 도착점(`role: 'end'`)을 단 1개의 GeoJSON 데이터 구조 안에 모두 담습니다.
 */
function buildRouteGeoJson(data) {
    const validPath = (data.path || []).filter(
        pt => Array.isArray(pt) && pt.length >= 2 && Number.isFinite(pt[0]) && Number.isFinite(pt[1])
    );
    if (validPath.length < 2) {
        return { type: 'FeatureCollection', features: [] };
    }

    const features = [
        {
            type: 'Feature',
            properties: { role: 'base' },
            geometry: {
                type: 'LineString',
                coordinates: validPath
            }
        }
    ];

    if (Array.isArray(data.sections) && data.sections.length > 0) {
        const congestedSections = data.sections
            .filter(sec => sec.congestion === 2 || sec.congestion === 3)
            .sort((a, b) => a.congestion - b.congestion);

        for (const sec of congestedSections) {
            const startIdx = Math.max(0, Number(sec.pointIndex) || 0);
            const count = Math.max(0, Number(sec.pointCount) || 0);
            const endIdx = Math.min(validPath.length - 1, startIdx + count);
            if (endIdx <= startIdx) {
                continue;
            }
            const segmentCoords = validPath.slice(startIdx, endIdx + 1);
            if (segmentCoords.length < 2) {
                continue;
            }
            features.push({
                type: 'Feature',
                properties: {
                    role: 'section',
                    congestion: sec.congestion
                },
                geometry: {
                    type: 'LineString',
                    coordinates: segmentCoords
                }
            });
        }
    }

    features.push(
        {
            type: 'Feature',
            properties: { role: 'start' },
            geometry: {
                type: 'Point',
                coordinates: validPath[0]
            }
        },
        {
            type: 'Feature',
            properties: { role: 'end' },
            geometry: {
                type: 'Point',
                coordinates: validPath[validPath.length - 1]
            }
        }
    );

    return {
        type: 'FeatureCollection',
        features
    };
}

/**
 * [함수 역할]
 * 네이버 GL 지도 내부 Mapbox 인스턴스(`state.map.getMapbox()`)에 **단 1개의 GeoJSON Source(`yonsei-route-source`)**와
 * 소통 색상별 라인·출도착 포인트 레이어를 등록하고, 이후 경로 변경 시 `source.setData()`만 호출하여 `< 1ms` 내에 갱신합니다.
 *
 * [Modern Web 성능 최적화 Phase 2 — 다중 Polyline 생성·파괴 제거]
 * - 기존 방식은 정체/서행 구간이 20개면 `naver.maps.Polyline` 객체 42개(테두리+본선)를 매번 생성해
 *   내부적으로 42개의 개별 GeoJSON Source가 만들어지고 파괴되었습니다.
 * - 개선 방식은 전체 경로 + 서행 + 정체 + 출도착점을 **단일 GeoJSON Source 1개**로 통합하여,
 *   지도 우클릭으로 출발점을 연속 변경해도 레이어 재생성 없이 GPU 버퍼 데이터만 즉시 교체합니다!
 *
 * @param {Object} mapbox `state.map.getMapbox()` 인스턴스
 * @param {Object} data `/api/directions` 응답 객체
 * @returns {boolean} GL 단일 소스 렌더링 성공 여부 (`false`이면 2D `naver.maps.Polyline` 폴백 수행)
 */
function renderGlRoute(mapbox, data) {
    if (!mapbox || typeof mapbox.getStyle !== 'function' || typeof mapbox.getSource !== 'function') {
        return false;
    }

    const featureCollection = buildRouteGeoJson(data);
    if (!featureCollection.features.length) {
        return false;
    }

    const outlineColor = state.darkMode ? '#022c22' : '#064e3b';

    try {
        const existingSource = mapbox.getSource(GL_ROUTE_SOURCE_ID);
        if (existingSource && typeof existingSource.setData === 'function') {
            existingSource.setData(featureCollection);
            if (mapbox.getLayer('yonsei-route-outline')) {
                mapbox.setPaintProperty('yonsei-route-outline', 'line-color', outlineColor);
            }
            state.glRouteActive = true;
            state.glRouteMap = state.map;
            state.diagnostics.glRouteUpdateCount += 1;
            return true;
        }

        mapbox.addSource(GL_ROUTE_SOURCE_ID, {
            type: 'geojson',
            data: featureCollection
        });

        const lineLayout = {
            'line-cap': 'round',
            'line-join': 'round'
        };

        const layerSpecs = [
            {
                id: 'yonsei-route-outline',
                type: 'line',
                source: GL_ROUTE_SOURCE_ID,
                filter: ['==', 'role', 'base'],
                layout: lineLayout,
                paint: {
                    'line-color': outlineColor,
                    'line-width': 9,
                    'line-opacity': 0.6
                }
            },
            {
                id: 'yonsei-route-base',
                type: 'line',
                source: GL_ROUTE_SOURCE_ID,
                filter: ['==', 'role', 'base'],
                layout: lineLayout,
                paint: {
                    'line-color': '#03c75a',
                    'line-width': 5,
                    'line-opacity': 0.95
                }
            },
            {
                id: 'yonsei-route-slow-outline',
                type: 'line',
                source: GL_ROUTE_SOURCE_ID,
                filter: ['all', ['==', 'role', 'section'], ['==', 'congestion', 2]],
                layout: lineLayout,
                paint: {
                    'line-color': '#78350f',
                    'line-width': 9,
                    'line-opacity': 0.65
                }
            },
            {
                id: 'yonsei-route-slow',
                type: 'line',
                source: GL_ROUTE_SOURCE_ID,
                filter: ['all', ['==', 'role', 'section'], ['==', 'congestion', 2]],
                layout: lineLayout,
                paint: {
                    'line-color': '#f59e0b',
                    'line-width': 6,
                    'line-opacity': 0.98
                }
            },
            {
                id: 'yonsei-route-jam-outline',
                type: 'line',
                source: GL_ROUTE_SOURCE_ID,
                filter: ['all', ['==', 'role', 'section'], ['==', 'congestion', 3]],
                layout: lineLayout,
                paint: {
                    'line-color': '#7f1d1d',
                    'line-width': 9,
                    'line-opacity': 0.65
                }
            },
            {
                id: 'yonsei-route-jam',
                type: 'line',
                source: GL_ROUTE_SOURCE_ID,
                filter: ['all', ['==', 'role', 'section'], ['==', 'congestion', 3]],
                layout: lineLayout,
                paint: {
                    'line-color': '#ef4444',
                    'line-width': 6,
                    'line-opacity': 0.98
                }
            },
            {
                id: 'yonsei-route-start',
                type: 'circle',
                source: GL_ROUTE_SOURCE_ID,
                filter: ['==', 'role', 'start'],
                paint: {
                    'circle-radius': 5.5,
                    'circle-color': '#ffffff',
                    'circle-stroke-width': 3,
                    'circle-stroke-color': '#03c75a'
                }
            },
            {
                id: 'yonsei-route-end',
                type: 'circle',
                source: GL_ROUTE_SOURCE_ID,
                filter: ['==', 'role', 'end'],
                paint: {
                    'circle-radius': 6.5,
                    'circle-color': '#ef4444',
                    'circle-stroke-width': 2.5,
                    'circle-stroke-color': '#ffffff'
                }
            }
        ];

        for (const spec of layerSpecs) {
            if (!mapbox.getLayer(spec.id)) {
                mapbox.addLayer(spec);
            }
        }

        state.glRouteActive = true;
        state.glRouteMap = state.map;
        state.diagnostics.glRouteUpdateCount += 1;
        return true;
    } catch (err) {
        console.warn('GL 단일 경로 소스 렌더링 실패 — 2D Polyline으로 폴백합니다:', err);
        return false;
    }
}

/**
 * [함수 역할]
 * 지도 위에 그려진 경로 도형(GL 단일 GeoJSON 소스 데이터, 2D 폴백 폴리라인, 경유지 마커)만 지도에서 비웁니다.
 * - GL 소스가 이미 등록된 경우 레이어를 파괴하지 않고 빈 `FeatureCollection`만 `setData()`로 주입해 다음 경로 표시도 즉시 수행되게 합니다.
 */
function removeRouteOverlays() {
    try {
        const mapbox = state.map?.getMapbox?.();
        const glSource = mapbox?.getSource?.(GL_ROUTE_SOURCE_ID);
        if (glSource && typeof glSource.setData === 'function') {
            glSource.setData({ type: 'FeatureCollection', features: [] });
        }
    } catch {
        // GL 소스 비우기 중 예외 무시
    }
    state.glRouteActive = false;
    state.glRouteMap = null;

    try {
        state.routePolyline?.setMap(null);
    } catch {
        // 폴리라인 해제 중 예외 무시
    }
    state.routePolyline = null;

    try {
        state.routeOutlinePolyline?.setMap(null);
    } catch {
        // 외곽선 폴리라인 해제 중 예외 무시
    }
    state.routeOutlinePolyline = null;

    try {
        state.waypointMarker?.setMap(null);
    } catch {
        // 경유지 마커 해제 중 예외 무시
    }
    state.waypointMarker = null;

    if (Array.isArray(state.routeSectionPolylines)) {
        for (const poly of state.routeSectionPolylines) {
            try {
                poly?.setMap(null);
            } catch {
                // 구간 폴리라인 해제 중 예외 무시
            }
        }
    }
    state.routeSectionPolylines = [];
}

/**
 * [함수 역할]
 * 지도 위 자동차 경로와 상단 알약 배너(`#active-route-banner`)만 지웁니다.
 * - 사용자가 우클릭하거나 GPS로 잡은 출발 기준점 점(`.location-marker`)은 그대로 유지됩니다!
 * - 만약 출발 기준점 점과 경로를 **둘 다** 지우고 싶다면 아래 `resetOriginAndRoute()`가 호출됩니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): 상단 배너의 `'경로 지우기'`(`#active-route-clear`) 및 상세창의 `'경로 지우기'`(`#directions-clear-btn`)
 */
function clearRouteOnMap() {
    state.activeRouteController?.abort();
    state.activeRouteController = null;
    state.activeRoute = null;

    removeRouteOverlays();

    const banner = byId('active-route-banner');
    if (banner) {
        banner.hidden = true;
    }
    const clearBtn = byId('directions-clear-btn');
    if (clearBtn) {
        clearBtn.hidden = true;
    }
}

/**
 * [함수 역할]
 * 지도 위에 찍힌 출발 기준점 마커(우클릭 점·검색 지정 점·내 위치 점)와
 * 표시 중인 자동차 주행 경로를 **모두 한 번에 삭제**하고 초기 상태(`DEFAULT_ORIGIN`)로 되돌립니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming):
 *   1) 상단 활성 경로 배너 우측의 `'초기화'` 버튼(`index.html`의 `#active-route-reset`)
 *   2) 하단 검색 패널 출발 기준 옆의 `'초기화'` 버튼(`index.html`의 `#origin-reset-btn`)
 */
function resetOriginAndRoute() {
    state.directionsController?.abort();
    state.activeRouteController?.abort();
    state.reverseGeocodeController?.abort();

    clearRouteOnMap();
    state.sheetRouteData = null;

    state.origin = {
        lat: DEFAULT_ORIGIN.lat,
        lng: DEFAULT_ORIGIN.lng
    };
    state.originLabel = DEFAULT_ORIGIN.label;
    state.originAddress = '';
    state.locationKnown = false;
    state.locationAccuracy = null;
    state.locationUpdated = null;

    updateLocationMarker();
    updateRadiusUI();

    const radiusVal = byId('radius')?.value || '10';
    let shouldFitResetOrigin = false;
    if (state.map && radiusVal !== 'all') {
        try {
            const center = state.map.getCenter?.();
            if (center && typeof center.lat === 'function' && typeof center.lng === 'function') {
                const distFromDefault = distanceKm(
                    { lat: center.lat(), lng: center.lng() },
                    DEFAULT_ORIGIN
                );
                if (distFromDefault > Number(radiusVal) * 1.5) {
                    shouldFitResetOrigin = true;
                }
            }
        } catch {
            // 중심 좌표 확인 중 예외 무시
        }
    }

    if (shouldFitResetOrigin) {
        fitRadius();
    } else {
        updateCircle(true);
    }

    updateMarkers();
    updateOriginAddress();

    if (state.etaRankOpen) {
        loadEtaRanking();
    }

    if (state.selected) {
        populateWaypointSelect(state.selected);
        loadDirectionsForHospital(state.selected, false);
    }

    setStatus('지정 출발 위치 점과 경로를 모두 초기화했습니다.');
}

/**
 * [함수 역할]
 * 네이버 Directions API의 구간별 실시간 혼잡도 코드(`1`: 원활, `2`: 서행, `3`: 정체)에 맞는
 * 색상·배지 클래스·한글 라벨을 반환합니다.
 *
 * [수정 시 영향 및 예시]
 * - 정체 구간 색상을 `#ef4444`에서 `#dc2626`으로 바꾸려면 아래 `congestion === 3`의 `strokeColor`(및 `renderGlRoute`의 `yonsei-route-jam`)를 변경하세요.
 */
function getCongestionStyle(congestion) {
    if (congestion === 3) {
        return {
            label: '정체',
            badgeClass: 'congestion-jam',
            strokeColor: '#ef4444',
            outlineColor: '#7f1d1d',
            zIndex: 98
        };
    }
    if (congestion === 2) {
        return {
            label: '서행',
            badgeClass: 'congestion-slow',
            strokeColor: '#f59e0b',
            outlineColor: '#78350f',
            zIndex: 97
        };
    }
    return {
        label: '원활',
        badgeClass: 'congestion-smooth',
        strokeColor: '#03c75a',
        outlineColor: '#064e3b',
        zIndex: 96
    };
}

/**
 * [함수 역할]
 * 백엔드 `/api/directions`가 반환한 경로 데이터(`data`)를 받아 네이버 지도 위에 실시간 소통 색상 경로를 그리고,
 * 상단 알약 모양 활성 경로 배너(`#active-route-banner`)를 표시합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming):
 *   1) `#directions-draw-btn`('지도에 경로 표시' 버튼) 클릭 시
 *   2) `loadDirectionsForHospital(..., true)` 자동 표시 시
 *   3) `refreshActiveRoute()` (우클릭 출발점 변경 시 실시간 경로 갱신)
 *   4) `mountNaverMap()` (다크/라이트 테마 전환 후 기존 경로 자동 복원)
 * - 렌더링 경로(Outgoing): GL 지도에서는 `renderGlRoute()`(단일 GeoJSON 소스)를 우선 사용하고,
 *   GL 미지원 환경에서만 2D `naver.maps.Polyline`으로 자동 폴백합니다.
 *
 * @param {Object} hospital 목적지 병원 객체
 * @param {Object} data `/api/directions` 응답 DTO 객체 (`DirectionsResponseDto`)
 * @param {Object} [options={}] 렌더링 옵션 (`fitMap`, `apiType`, `option`, `rawWaypoint`)
 */
function drawRouteOnMap(hospital, data, options = {}) {
    if (!state.map || !hospital || !data || !Array.isArray(data.path) || !data.path.length) {
        return;
    }

    if (!isMapReadyForOverlays()) {
        naver.maps.Event.once(state.map, 'glReady', () => drawRouteOnMap(hospital, data, options));
        return;
    }

    const fitMap = options.fitMap !== false;
    const effectiveApiType = options.apiType || data.apiType || byId('directions-api')?.value || 'directions5';
    const effectiveOption = options.option || data.option || state.selectedDirectionOption || 'trafast';
    const effectiveRawWaypoint = options.rawWaypoint !== undefined
        ? options.rawWaypoint
        : (byId('directions-waypoint')?.value || '');

    state.activeRoute = {
        hospital,
        data,
        apiType: effectiveApiType,
        option: effectiveOption,
        rawWaypoint: effectiveRawWaypoint
    };

    const mapbox = state.map.getMapbox?.();
    let renderedViaGl = false;

    if (mapbox) {
        // 기존 2D 폴백 폴리라인이나 이전 경유지 배지가 남아 있으면 먼저 정리
        if (state.routePolyline || state.routeOutlinePolyline || state.routeSectionPolylines.length || state.waypointMarker) {
            removeRouteOverlays();
        }
        renderedViaGl = renderGlRoute(mapbox, data);
    }

    if (!renderedViaGl) {
        removeRouteOverlays();

        const latLngPath = data.path.map(([lng, lat]) => new naver.maps.LatLng(lat, lng));

        // 1. 2D 폴백용 전체 경로 기본 외곽선 폴리라인
        state.routeOutlinePolyline = guardGlShapeRedraw(new naver.maps.Polyline({
            map: state.map,
            path: latLngPath,
            strokeColor: state.darkMode ? '#022c22' : '#064e3b',
            strokeWeight: 9,
            strokeOpacity: 0.55,
            strokeLineCap: 'round',
            strokeLineJoin: 'round',
            zIndex: 90
        }));

        // 2. 2D 폴백용 전체 경로 기본 베이스 라인
        const polylineOpts = {
            map: state.map,
            path: latLngPath,
            strokeColor: '#03c75a',
            strokeWeight: 5,
            strokeOpacity: 0.95,
            strokeLineCap: 'round',
            strokeLineJoin: 'round',
            zIndex: 95
        };
        if (naver.maps?.PointingIcon) {
            polylineOpts.startIcon = naver.maps.PointingIcon.CIRCLE;
            polylineOpts.startIconSize = 10;
            polylineOpts.endIcon = naver.maps.PointingIcon.BLOCK_ARROW;
            polylineOpts.endIconSize = 14;
        }
        state.routePolyline = guardGlShapeRedraw(new naver.maps.Polyline(polylineOpts));

        // 3. 2D 폴백용 구간별 혼잡도 폴리라인
        if (Array.isArray(data.sections) && data.sections.length > 0) {
            const congestedSections = data.sections
                .filter(sec => sec.congestion === 2 || sec.congestion === 3)
                .sort((a, b) => a.congestion - b.congestion);

            for (const sec of congestedSections) {
                const startIdx = Math.max(0, Number(sec.pointIndex) || 0);
                const count = Math.max(0, Number(sec.pointCount) || 0);
                const endIdx = Math.min(latLngPath.length - 1, startIdx + count);
                if (endIdx <= startIdx) {
                    continue;
                }

                const segmentCoords = latLngPath.slice(startIdx, endIdx + 1);
                if (segmentCoords.length < 2) {
                    continue;
                }

                const style = getCongestionStyle(sec.congestion);

                const segOutline = guardGlShapeRedraw(new naver.maps.Polyline({
                    map: state.map,
                    path: segmentCoords,
                    strokeColor: style.outlineColor,
                    strokeWeight: 9,
                    strokeOpacity: 0.65,
                    strokeLineCap: 'round',
                    strokeLineJoin: 'round',
                    zIndex: style.zIndex - 1
                }));

                const segLine = guardGlShapeRedraw(new naver.maps.Polyline({
                    map: state.map,
                    path: segmentCoords,
                    strokeColor: style.strokeColor,
                    strokeWeight: 6,
                    strokeOpacity: 0.98,
                    strokeLineCap: 'round',
                    strokeLineJoin: 'round',
                    zIndex: style.zIndex
                }));

                state.routeSectionPolylines.push(segOutline, segLine);
            }
        }
    }

    // 경유지(Waypoint)가 선택되어 있는 경우 지도 위에 경유지 배지 표시
    try {
        state.waypointMarker?.setMap(null);
    } catch {
        // 무시
    }
    state.waypointMarker = null;

    if (effectiveRawWaypoint.includes('|')) {
        const [coordPart, wpName] = effectiveRawWaypoint.split('|');
        const [wpLngStr, wpLatStr] = coordPart.split(',');
        const wpLat = Number(wpLatStr);
        const wpLng = Number(wpLngStr);
        if (Number.isFinite(wpLat) && Number.isFinite(wpLng)) {
            const wpEl = node('div', 'waypoint-marker', `경유 · ${wpName}`);
            state.waypointMarker = createNaverDomOverlay({
                map: state.map,
                position: new naver.maps.LatLng(wpLat, wpLng),
                content: wpEl,
                xAnchor: 0.5,
                yAnchor: 1.2,
                zIndex: 120
            });
        }
    }

    // 상단 플로팅 활성 경로 배너(`index.html`의 `#active-route-banner`) 텍스트 갱신 및 노출
    const banner = byId('active-route-banner');
    if (banner) {
        const nameEl = byId('active-route-name');
        byId('active-route-api').textContent = data.apiLabel || 'Directions 5';
        if (nameEl) {
            nameEl.textContent = `${hospital.name}`;
            nameEl.title = `${hospital.name}`;
        }
        byId('active-route-meta').textContent = `${data.durationText} · ${data.distanceKm} km`;
        banner.hidden = false;
    }

    const clearBtn = byId('directions-clear-btn');
    if (clearBtn) {
        clearBtn.hidden = false;
    }

    // 최초 경로 표시 시 경로 전체가 화면 안에 들어오도록 지도 영역 피팅 (우클릭 실시간 갱신 시에는 카메라 고정)
    if (fitMap) {
        const firstCoord = data.path[0];
        const firstLatLng = new naver.maps.LatLng(firstCoord[1], firstCoord[0]);
        const bounds = new naver.maps.LatLngBounds(firstLatLng, firstLatLng);
        for (let i = 1; i < data.path.length; i++) {
            const [lng, lat] = data.path[i];
            bounds.extend(new naver.maps.LatLng(lat, lng));
        }
        fitBounds(bounds);
    }
}

/**
 * [함수 역할]
 * 지도 위에 자동차 경로가 표시된 상태에서 사용자가 다른 지점을 우클릭(또는 롱탭·내 위치 측정)했을 때,
 * 새로운 출발점(`state.origin`)에서 목적지 병원까지의 경로를 백엔드(`GET /api/directions`)로 즉시 재조회하여
 * 단일 GeoJSON 소스(`yonsei-route-source`)와 상단 알약 배너를 실시간 갱신합니다.
 *
 * [코드의 경로성]
 *   지도 우클릭 (`mountNaverMap`) → `setCustomOrigin()` → `refreshActiveRoute()`
 *   ──(GET /api/directions)──▶ `EmergencyApiController.java:getDirections()`
 *   ──▶ `NaverDirectionsService.java:getDrivingRoute()` → `DirectionsResponseDto.java`
 *   ──▶ `drawRouteOnMap()` → `renderGlRoute()` (`source.setData()`로 즉시 경로선 교체!)
 *
 * @param {Object} [options={}] 갱신 옵션 (`fitMap`)
 */
async function refreshActiveRoute(options = {}) {
    const active = state.activeRoute;
    if (!active?.hospital) {
        return;
    }

    const hospital = active.hospital;
    const fitMap = Boolean(options.fitMap);
    const apiType = active.apiType || byId('directions-api')?.value || 'directions5';
    const option = active.option || state.selectedDirectionOption || 'trafast';
    const rawWaypoint = active.rawWaypoint || '';
    const [waypointCoord] = rawWaypoint.includes('|')
        ? rawWaypoint.split('|')
        : [rawWaypoint];

    state.activeRouteController?.abort();
    const controller = new AbortController();
    state.activeRouteController = controller;

    const banner = byId('active-route-banner');
    const metaEl = byId('active-route-meta');
    if (banner && !banner.hidden && metaEl) {
        metaEl.textContent = '경로 갱신 중…';
    }

    try {
        const params = new URLSearchParams({
            startLat: String(state.origin.lat),
            startLng: String(state.origin.lng),
            goalLat: String(hospital.lat),
            goalLng: String(hospital.lng),
            apiType,
            option
        });
        if (waypointCoord) {
            params.set('waypoints', waypointCoord);
        }

        const response = await fetch(`/api/directions?${params.toString()}`, {
            signal: controller.signal,
            headers: { Accept: 'application/json' }
        });

        if (controller.signal.aborted || state.activeRoute?.hospital?.id !== hospital.id) {
            return;
        }

        if (!response.ok) {
            if (metaEl) {
                metaEl.textContent = `경로 갱신 실패 (HTTP ${response.status})`;
            }
            return;
        }

        const data = await response.json();
        if (controller.signal.aborted || state.activeRoute?.hospital?.id !== hospital.id) {
            return;
        }

        if (data.code !== 0 || !Array.isArray(data.path) || !data.path.length) {
            removeRouteOverlays();
            if (banner) {
                const nameEl = byId('active-route-name');
                if (nameEl) {
                    nameEl.textContent = `${hospital.name}`;
                }
                if (metaEl) {
                    metaEl.textContent = '해당 위치에서 도로 경로 탐색 불가';
                }
                banner.hidden = false;
            }
            return;
        }

        drawRouteOnMap(hospital, data, {
            fitMap,
            apiType,
            option,
            rawWaypoint
        });
    } catch (error) {
        if (error?.name === 'AbortError') {
            return;
        }
        if (metaEl && state.activeRoute?.hospital?.id === hospital.id) {
            metaEl.textContent = '경로 갱신 오류';
        }
    } finally {
        if (state.activeRouteController === controller) {
            state.activeRouteController = null;
        }
    }
}

/**
 * [함수 역할]
 * 최종 목적지 병원(`destinationHospital`)을 제외한 주변 응급의료기관 15곳을 출발 기준점 직선거리 순으로 정렬해
 * 상세 시트 내 경유지 드롭다운(`index.html`의 `#directions-waypoint`) 옵션을 채웁니다.
 *
 * [도메인 규칙 반영]
 * - `hospital.beds.hvec` 문자열을 `integerValue()` 및 `bedValue()`로 파싱하여
 *   음수 병상(`< 0`)일 때도 `'초과수용 N석'`으로 정확히 표시합니다.
 */
function populateWaypointSelect(destinationHospital) {
    const select = byId('directions-waypoint');
    if (!select || !destinationHospital) {
        return;
    }

    const prevValue = select.value;
    select.replaceChildren(
        node('option', '', '경유지 없음 (직행)')
    );
    select.firstElementChild.value = '';

    const nearby = state.hospitals
        .filter(h =>
            h.id !== destinationHospital.id &&
            Number.isFinite(h.lat) &&
            Number.isFinite(h.lng)
        )
        .map(h => ({
            hospital: h,
            dist: distanceKm(state.origin, h)
        }))
        .sort((a, b) => a.dist - b.dist)
        .slice(0, 15);

    for (const { hospital, dist } of nearby) {
        const opt = document.createElement('option');
        opt.value = `${hospital.lng},${hospital.lat}|${hospital.name}`;
        const erRaw = hospital.beds?.hvec;
        const erNum = integerValue(erRaw);
        const shortEr = erNum !== null
            ? (erNum > 0 ? `응급실 ${erNum}석` : `응급실 ${bedValue(erRaw)}`)
            : '응급실 미정보';
        opt.textContent = `경유: ${hospital.name} (${dist.toFixed(1)}km · ${shortEr})`;
        select.append(opt);
    }

    if (prevValue && [...select.options].some(o => o.value === prevValue)) {
        select.value = prevValue;
    } else {
        select.value = '';
    }
}

/**
 * [함수 역할]
 * 병원 상세창(`#sheet`)이 열릴 때 백엔드 `GET /api/directions`를 호출하여
 * 현재 출발 기준점(`state.origin`)에서 해당 병원까지의 **Directions 5 / Directions 15** 자동차 주행 시간(ETA),
 * 4대 경로 대안 비교(`실시간 빠른길 / 최적 / 편한길 / 무료 우선`), 예상 요금, 혼잡 구간 칩, 턴바이턴 안내를 렌더링합니다.
 *
 * [코드의 경로성]
 *   `openHospital()` / `#directions-api` 변경 / `#directions-waypoint` 변경 / `#directions-draw-btn` 클릭
 *   ──▶ `app.js:loadDirectionsForHospital()`
 *   ──(GET /api/directions)──▶ `EmergencyApiController.java:getDirections()`
 *   ──▶ `NaverDirectionsService.java:getDrivingRoute()` → `DirectionsResponseDto.java`
 *   ──▶ 상세 시트 `#directions-result` 렌더링 (+ `autoDraw === true`이면 `drawRouteOnMap()` 실행)
 *
 * @param {Object} hospital 목적지 병원 객체
 * @param {boolean} [autoDraw=false] 조회 완료 즉시 지도 위에 경로를 그릴지 여부
 * @param {Object} [drawOptions={}] `autoDraw` 시 `drawRouteOnMap`에 전달할 옵션 (`fitMap`)
 */
async function loadDirectionsForHospital(hospital, autoDraw = false, drawOptions = {}) {
    const container = byId('directions-result');
    const statusBadge = byId('directions-status-badge');
    if (!container || !hospital) {
        return;
    }

    const apiType = byId('directions-api')?.value || 'directions5';
    const option = state.selectedDirectionOption || 'trafast';
    const rawWaypoint = byId('directions-waypoint')?.value || '';
    const [waypointCoord, waypointName] = rawWaypoint.includes('|')
        ? rawWaypoint.split('|')
        : [rawWaypoint, ''];

    state.sheetRouteData = null;
    state.directionsController?.abort();
    const controller = new AbortController();
    state.directionsController = controller;

    if (statusBadge) {
        statusBadge.textContent = '경로 탐색 중…';
    }
    container.replaceChildren(
        node('p', 'empty-state', '네이버 Directions API로 실시간 자동차 경로와 예상 소요 시간을 조회하고 있습니다…')
    );

    try {
        const params = new URLSearchParams({
            startLat: String(state.origin.lat),
            startLng: String(state.origin.lng),
            goalLat: String(hospital.lat),
            goalLng: String(hospital.lng),
            apiType,
            option
        });
        if (waypointCoord) {
            params.set('waypoints', waypointCoord);
        }

        const response = await fetch(`/api/directions?${params.toString()}`, {
            signal: controller.signal,
            headers: { Accept: 'application/json' }
        });

        if (controller.signal.aborted) {
            return;
        }

        if (!response.ok) {
            if (statusBadge) {
                statusBadge.textContent = '오류';
            }
            container.replaceChildren(
                node('p', 'empty-state', `자동차 경로 조회 실패: HTTP ${response.status}`)
            );
            return;
        }

        const data = await response.json();
        if (controller.signal.aborted) {
            return;
        }

        if (!state.selected || state.selected.id === hospital.id) {
            state.sheetRouteData = data;
        }

        if (data.code !== 0) {
            if (statusBadge) {
                statusBadge.textContent = '탐색 불가';
            }
            container.replaceChildren(
                node('p', 'empty-state', `자동차 경로 안내 불가: ${data.message || '경로를 찾을 수 없습니다.'}`)
            );
            return;
        }

        const wpBadgeSuffix = waypointName ? ` · 경유(${waypointName})` : '';
        if (statusBadge) {
            statusBadge.textContent = `${data.apiLabel} · ${data.optionLabel}${wpBadgeSuffix}`;
        }

        // 바텀시트 상단 거리 요약 텍스트에 자동차 예상 소요 시간과 실도로 거리도 함께 병기
        const straightKm = distanceKm(state.origin, hospital).toFixed(1);
        const originName = getOriginDisplayLabel();
        byId('hospital-distance').textContent =
            `${originName}에서 직선 ${straightKm} km · 자동차 ${data.durationText} (실도로 ${data.distanceKm} km)`;

        container.replaceChildren();

        // 4대 경로 옵션(실시간 빠른길 / 실시간 최적 / 실시간 편한길 / 무료 우선) 동시 비교 카드 렌더링
        if (Array.isArray(data.alternatives) && data.alternatives.length > 0) {
            const altWrap = node('div', 'directions-alternatives');
            for (const alt of data.alternatives) {
                const isCurrent = alt.option === data.option;
                const altBtn = node('button', `directions-alt-btn ${isCurrent ? 'is-active' : ''}`);
                altBtn.type = 'button';
                altBtn.dataset.option = alt.option;
                altBtn.setAttribute('aria-pressed', String(isCurrent));
                const exactAltKm = Number.isFinite(alt.distanceMeters) && alt.distanceMeters > 0
                    ? (alt.distanceMeters / 1000).toFixed(2)
                    : alt.distanceKm;
                const tollInfo = alt.tollFare > 0
                    ? `통행료\u00A0${alt.tollFare.toLocaleString('ko-KR')}원`
                    : '무료도로';
                const taxiInfo = alt.taxiFare > 0
                    ? ` · 택시\u00A0${alt.taxiFare.toLocaleString('ko-KR')}원`
                    : '';
                altBtn.append(
                    node('span', 'directions-alt-title', alt.optionLabel),
                    node('strong', 'directions-alt-eta', alt.durationText),
                    node('span', 'directions-alt-meta', `${exactAltKm}\u00A0km · ${tollInfo}${taxiInfo}`)
                );
                altBtn.addEventListener('click', () => {
                    if (state.selectedDirectionOption !== alt.option) {
                        state.selectedDirectionOption = alt.option;
                        const isSelectedActive = Boolean(
                            state.activeRoute && state.activeRoute.hospital?.id === hospital.id
                        );
                        loadDirectionsForHospital(hospital, isSelectedActive, { fitMap: false });
                    }
                });
                altWrap.append(altBtn);
            }
            container.append(altWrap);
        }

        // KPI 3열 그리드 생성 (예상 소요 시간 / 실도로 주행 거리 / 예상 요금)
        const kpiGrid = node('div', 'directions-kpi-grid');

        const etaCard = node('div', 'directions-kpi-card');
        etaCard.append(
            node('span', 'directions-kpi-label', '예상 소요 시간 (ETA)'),
            node('strong', 'directions-kpi-value highlight', data.durationText),
            node('span', 'directions-kpi-sub', `${data.apiLabel} · ${data.optionLabel}${wpBadgeSuffix}`)
        );

        const exactMainKm = Number.isFinite(data.distanceMeters) && data.distanceMeters > 0
            ? (data.distanceMeters / 1000).toFixed(2)
            : data.distanceKm;
        const exactMetersText = Number.isFinite(data.distanceMeters) && data.distanceMeters > 0
            ? `정밀 ${data.distanceMeters.toLocaleString('ko-KR')}m · `
            : '';
        const distCard = node('div', 'directions-kpi-card');
        distCard.append(
            node('span', 'directions-kpi-label', '실도로 주행 거리'),
            node('strong', 'directions-kpi-value', `${exactMainKm} km`),
            node('span', 'directions-kpi-sub', `${exactMetersText}직선거리 ${straightKm} km`)
        );

        const fareCard = node('div', 'directions-kpi-card');
        const tollText = data.tollFare > 0
            ? `통행료 ${data.tollFare.toLocaleString('ko-KR')}원`
            : '통행료 무료';
        const taxiText = data.taxiFare > 0
            ? `택시 약 ${data.taxiFare.toLocaleString('ko-KR')}원`
            : '택시비 미산정';
        const fuelText = data.fuelPrice > 0
            ? `유류비 약 ${data.fuelPrice.toLocaleString('ko-KR')}원`
            : '';
        fareCard.append(
            node('span', 'directions-kpi-label', '통행료 · 택시 · 유류비'),
            node('strong', 'directions-kpi-value', tollText),
            node('span', 'directions-kpi-sub', [taxiText, fuelText].filter(Boolean).join(' · '))
        );

        kpiGrid.append(etaCard, distCard, fareCard);
        container.append(kpiGrid);

        // 실시간 구간별 교통 소통 범례(원활·서행·정체) 및 주요 도로 혼잡도 배지 표시
        const trafficBox = node('div', 'directions-traffic-summary');
        const legendRow = node('div', 'directions-traffic-legend');
        legendRow.append(
            node('span', 'traffic-legend-title', '경로 소통 색상'),
            node('span', 'traffic-legend-item smooth', '원활'),
            node('span', 'traffic-legend-item slow', '서행'),
            node('span', 'traffic-legend-item jam', '정체')
        );
        trafficBox.append(legendRow);

        if (Array.isArray(data.sections) && data.sections.length > 0) {
            const chipWrap = node('div', 'directions-section-chips');
            const seenKeys = new Set();
            const prioritized = [...data.sections]
                .filter(sec => {
                    const key = `${sec.name}|${sec.congestion}`;
                    if (seenKeys.has(key)) {
                        return false;
                    }
                    seenKeys.add(key);
                    return sec.congestion >= 2 || (sec.distance >= 1000 && sec.name !== '주행 구간');
                })
                .sort((a, b) => (b.congestion - a.congestion) || (b.distance - a.distance))
                .slice(0, 12)
                .sort((a, b) => a.pointIndex - b.pointIndex);

            const displayList = prioritized.length > 0 ? prioritized : data.sections.slice(0, 8);
            for (const sec of displayList) {
                const style = getCongestionStyle(sec.congestion);
                const roadName = sec.name || '주요 구간';
                const speedText = sec.speed > 0 ? ` ${sec.speed}km/h` : '';
                const chip = node(
                    'span',
                    `directions-section-chip ${style.badgeClass}`,
                    `${roadName} · ${style.label}${speedText}`
                );
                chipWrap.append(chip);
            }
            trafficBox.append(chipWrap);
        }
        container.append(trafficBox);

        // 주요 회전/주행 안내(guidePrompts)가 있으면 접이식 목록으로 제공
        if (Array.isArray(data.guidePrompts) && data.guidePrompts.length > 0) {
            const details = node('details', 'directions-guide-details');
            details.append(
                node('summary', '', `주요 주행 경로 안내 보기 (${data.guidePrompts.length}개 구간)`)
            );
            const ol = node('ol', 'directions-guide-list');
            for (const stepText of data.guidePrompts.slice(0, 20)) {
                ol.append(node('li', '', stepText));
            }
            details.append(ol);
            container.append(details);
        }

        if (autoDraw) {
            drawRouteOnMap(hospital, data, {
                fitMap: drawOptions.fitMap !== false,
                apiType,
                option,
                rawWaypoint
            });
        }
    } catch (error) {
        if (error.name === 'AbortError') {
            return;
        }
        if (statusBadge) {
            statusBadge.textContent = '오류';
        }
        container.replaceChildren(
            node('p', 'empty-state', `자동차 경로 조회 실패: ${error.message}`)
        );
    }
}

/* --------------------------------------------------------------------------
   [12. API 시각 파싱 및 실시간 진료 공지 렌더링 (`index.html` #messages 연동)]
   -------------------------------------------------------------------------- */

/**
 * [함수 역할]
 * 국립중앙의료원 OpenAPI가 반환하는 14자리 시각 문자열(`YYYYMMDDHHmmss`, 한국 표준시 기준)을
 * JavaScript 타임스탬프와 읽기 쉬운 날짜/시각(`"11월 10일 02:30"`) 객체로 변환합니다.
 *
 * [코드의 경로성]
 * - 데이터 출처: 백엔드 `NmcOpenApiClient.java` → `HospitalDto.messages` / `HospitalDto.beds.hvidate` / `HospitalDto.severe.hvidate`
 * - 호출하는 곳(Incoming): `renderMessages()`, `renderSevere()`, `renderBeds()`
 *
 * @param {string} value 14자리 숫자 문자열 (`YYYYMMDDHHmmss`)
 * @returns {{timestamp: number, display: string}|null} 파싱 결과 또는 형식이 다르면 `null`
 */
function parseApiDate(value) {
    if (!/^\d{14}$/.test(value || '')) {
        return null;
    }

    const year = Number(value.slice(0, 4));
    const month = Number(value.slice(4, 6));
    const day = Number(value.slice(6, 8));
    const hour = Number(value.slice(8, 10));
    const minute = Number(value.slice(10, 12));
    const second = Number(value.slice(12, 14));

    const date = new Date(Date.UTC(year, month - 1, day, hour - 9, minute, second));
    const display = new Intl.DateTimeFormat('ko-KR', {
        timeZone: 'Asia/Seoul',
        month: 'numeric',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
    }).format(date);

    return {
        timestamp: date.getTime(),
        display
    };
}

/**
 * [함수 역할]
 * 선택된 병원의 실시간 응급실·중증질환 진료 제한 공지(`hospital.messages`) 중
 * 현재 시각 기준 유효한 공지만 필터링하고 `[중증]` 공지를 최상단에 정렬하여 `#messages` 영역에 렌더링합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `openHospital()`
 * - 데이터 출처: 백엔드 `EmergencyDataService.java`가 병합한 `HospitalDto.messages` (`getEmrrmSrsillDissMsgInqire` XML 원본)
 * - 가리키는 DOM(Outgoing): `frontend/index.html`의 `#messages` 및 `#message-count`
 *
 * [수정 시 영향 및 예시]
 * - 기간이 지난 과거 공지까지 모두 보여주고 싶다면 `.filter(item => ...)` 블록을 제거하면 됩니다.
 */
function renderMessages(hospital) {
    const target = byId('messages');
    target.replaceChildren();

    if (!state.sources.messages) {
        empty(target, '미정보 · 공지 조회에 실패했습니다. 병원에 확인하세요.');
        byId('message-count').textContent = '';
        return;
    }

    const now = Date.now();

    const messages = (hospital.messages || [])
        .filter(item => {
            const start = parseApiDate(item.symblksttdtm);
            const end = parseApiDate(item.symblkenddtm);
            return (!start || start.timestamp <= now) && (!end || end.timestamp > now);
        })
        .sort((a, b) => {
            const isCritA = a.symblkmsgtyp === '중증' ? 0 : 1;
            const isCritB = b.symblkmsgtyp === '중증' ? 0 : 1;
            if (isCritA !== isCritB) {
                return isCritA - isCritB;
            }

            const deptA = String(a.trtprtcodmag || a.symtypcodmag || '').trim();
            const deptB = String(b.trtprtcodmag || b.symtypcodmag || '').trim();
            const deptCompare = deptA.localeCompare(deptB, 'ko', { numeric: true });
            if (deptCompare !== 0) {
                return deptCompare;
            }

            const msgA = String(a.symblkmsg || '').trim();
            const msgB = String(b.symblkmsg || '').trim();
            return msgA.localeCompare(msgB, 'ko');
        });

    byId('message-count').textContent = `${messages.length}건`;

    if (!messages.length) {
        empty(target, '현재 표시할 공지가 없습니다.');
        return;
    }

    for (const item of messages) {
        const level = item.symblkmsgtyp === '중증'
            ? 'critical'
            : item.symblkmsgtyp === '응급'
                ? 'urgent'
                : 'normal';

        const article = node('article', `message-card ${level}`);
        const heading = node('div', 'message-heading');

        const deptName = String(item.trtprtcodmag || '').trim() ||
            String(item.symtypcodmag || '').trim() ||
            '진료과 미지정';

        const badge = node(
            'span',
            'message-badge',
            item.symblkmsgtyp ? `[${item.symblkmsgtyp}]` : '[공지]'
        );

        const deptTitle = node('strong', 'message-dept', deptName);
        heading.append(badge, deptTitle);

        if (item.symtypcodmag && item.symtypcodmag !== deptName && item.symtypcodmag !== '응급실') {
            heading.append(node('span', 'message-subtag', item.symtypcodmag));
        }

        article.append(
            heading,
            node('p', 'message-text', displayValue(item.symblkmsg))
        );

        const end = parseApiDate(item.symblkenddtm);
        const start = parseApiDate(item.symblksttdtm);

        const meta = node('div', 'message-meta');
        if (end) {
            meta.append(node('small', 'muted', `종료 예정 ${end.display}`));
        } else if (start) {
            meta.append(node('small', 'muted', `등록 ${start.display}`));
        }

        if (meta.childElementCount > 0) {
            article.append(meta);
        }

        target.append(article);
    }
}

/* --------------------------------------------------------------------------
   [13. 중증응급질환 28종 분류 체계 및 공지 교차 검증 (`index.html` #severe 연동)]
   - 데이터 출처: 백엔드 `NmcOpenApiClient.java` (`getSrsillDissAceptncPosblInfoInqire`) → `HospitalDto.severe`
   - 특징: 단순 `Y/N` 값만 믿지 않고, 해당 병원의 실시간 차단 공지(`hospital.messages`)와 교차 검증하여
     실제 진료 불가 공지가 떠 있으면 붉은색 `'불가능'` 배지로 경고해 줍니다!
   -------------------------------------------------------------------------- */

const SEVERE_DISEASE_GROUPS = [
    {
        title: '뇌출혈수술',
        items: [
            { label: '거미막하출혈', key: 'mkioskty3' },
            { label: '거미막하출혈 외', key: 'mkioskty4' }
        ]
    },
    {
        title: '대동맥응급',
        items: [
            { label: '흉부', key: 'mkioskty5' },
            { label: '복부', key: 'mkioskty6' }
        ]
    },
    {
        title: '담낭담관질환',
        items: [
            { label: '담낭질환', key: 'mkioskty7' },
            { label: '담도포함질환', key: 'mkioskty8' }
        ]
    },
    {
        title: '복부응급수술',
        items: [
            { label: '비외상', key: 'mkioskty9' }
        ]
    },
    {
        title: '사지접합',
        items: [
            { label: '수족지접합', key: 'mkioskty20' },
            { label: '수족지접합 외', key: 'mkioskty21' }
        ]
    },
    {
        title: '산부인과응급',
        items: [
            { label: '분만', key: 'mkioskty16' },
            { label: '산과수술', key: 'mkioskty17' },
            { label: '부인과수술', key: 'mkioskty18' }
        ]
    },
    {
        title: '안과적수술',
        items: [
            { label: '응급', key: 'mkioskty25' }
        ]
    },
    {
        title: '영상의학혈관중재',
        items: [
            { label: '성인', key: 'mkioskty26' },
            { label: '영유아', key: 'mkioskty27' }
        ]
    },
    {
        title: '응급내시경',
        items: [
            { label: '성인 위장관', key: 'mkioskty11' },
            { label: '영유아 위장관', key: 'mkioskty12' },
            { label: '성인 기관지', key: 'mkioskty13' },
            { label: '영유아 기관지', key: 'mkioskty14' }
        ]
    },
    {
        title: '응급투석',
        items: [
            { label: 'HD', key: 'mkioskty22' },
            { label: 'CRRT', key: 'mkioskty23' }
        ]
    },
    {
        title: '장중첩/폐색',
        items: [
            { label: '영유아', key: 'mkioskty10' }
        ]
    },
    {
        title: '재관류중재술',
        items: [
            { label: '심근경색', key: 'mkioskty1' },
            { label: '뇌경색', key: 'mkioskty2' }
        ]
    },
    {
        title: '저체중출생아',
        items: [
            { label: '집중치료', key: 'mkioskty15' }
        ]
    },
    {
        title: '정신과적응급',
        items: [
            { label: '폐쇄병동입원', key: 'mkioskty24' }
        ]
    },
    {
        title: '중증화상',
        items: [
            { label: '전문치료', key: 'mkioskty19' }
        ]
    },
    {
        title: '응급실 게이트키퍼',
        items: [
            { label: '응급실(Emergency gate keeper)', key: 'mkioskty28' }
        ]
    }
];

const SPECIFIC_BLOCK_TARGETS = {
    mkioskty1: { codes: ['Y0010'], names: ['심근경색', '관상동맥중재술'] },
    mkioskty2: { codes: ['Y0020'], names: ['뇌경색', '혈전용해술'] },
    mkioskty3: { codes: ['Y0031'], names: ['거미막하출혈', '지주막하출혈', '뇌동맥류 수술'] },
    mkioskty4: { codes: ['Y0032'], names: ['거미막하출혈 외', '기타 뇌출혈'] },
    mkioskty5: { codes: ['Y0041'], names: ['대동맥응급(흉부)', '흉부대동맥'] },
    mkioskty6: { codes: ['Y0042'], names: ['대동맥응급(복부)', '복부대동맥'] },
    mkioskty7: { codes: ['Y0051'], names: ['담낭질환', '담낭 수술'] },
    mkioskty8: { codes: ['Y0052'], names: ['담도포함질환', '담도질환'] },
    mkioskty9: { codes: ['Y0060'], names: ['복부응급수술(비외상)', '비외상 복부수술'] },
    mkioskty10: { codes: ['Y0070'], names: ['장중첩/폐색(유아)', '장중첩'] },
    mkioskty11: { codes: ['Y0081'], names: ['위장관 응급내시경(성인)'] },
    mkioskty12: { codes: ['Y0082'], names: ['위장관 응급내시경(영유아)'] },
    mkioskty13: { codes: ['Y0091'], names: ['기관지 응급내시경(성인)'] },
    mkioskty14: { codes: ['Y0092'], names: ['기관지 응급내시경(영유아)'] },
    mkioskty15: { codes: ['Y0100'], names: ['저출생체중아', '저체중출생아'] },
    mkioskty16: { codes: ['Y0111'], names: ['산부인과 응급(분만)', '분만', '자연분만', '제왕절개'] },
    mkioskty17: { codes: ['Y0112'], names: ['산부인과 응급(산과수술)', '산과수술'] },
    mkioskty18: { codes: ['Y0113'], names: ['산부인과 응급(부인과수술)', '부인과수술'] },
    mkioskty19: { codes: ['Y0120'], names: ['중증화상', '화상 전문치료'] },
    mkioskty20: { codes: ['Y0131'], names: ['수족지접합'] },
    mkioskty21: { codes: ['Y0132'], names: ['수족지접합 외'] },
    mkioskty22: { codes: ['Y0141'], names: ['응급투석(HD)', '응급투석 (HD)', '혈액투석', '인공신장실'] },
    mkioskty23: { codes: ['Y0142'], names: ['응급투석(CRRT)', '응급투석 (CRRT)', 'CRRT'] },
    mkioskty24: { codes: ['Y0150'], names: ['정신과적 응급입원', '폐쇄병동'] },
    mkioskty25: { codes: ['Y0160'], names: ['안과적 응급 수술', '안과 응급 수술'] },
    mkioskty26: { codes: ['Y0171'], names: ['영상의학혈관중재(성인)'] },
    mkioskty27: { codes: ['Y0172'], names: ['영상의학혈관중재(영유아)'] }
};

/**
 * [함수 역할]
 * 개별 실시간 공지 메시지(`msg`)의 문구나 표출 코드가 '진료/수용 차단'을 의미하는지 판별합니다.
 */
function isNoticeBlocking(msg) {
    if (!msg) {
        return false;
    }
    const body = String(msg.symblkmsg || '').trim();
    const yon = String(msg.symoutdspyon || '').trim();

    if (yon === '차단') {
        return true;
    }
    if (/제한\s*없음|가능|원활/.test(body)) {
        return false;
    }
    return /(불가|불가능|차단|수용\s*불가|진료\s*불가|수술\s*불가|부재|중단)/.test(body);
}

/**
 * [함수 역할]
 * 활성 공지 목록(`activeMessages`) 중 특정 중증질환 코드(`itemKey`, 예: `mkioskty1`)를 명시적으로 차단한 공지가 있는지 확인합니다.
 */
function isDiseaseSpecificallyBlocked(activeMessages, itemKey) {
    const config = SPECIFIC_BLOCK_TARGETS[itemKey];
    if (!config || !activeMessages.length) {
        return false;
    }

    for (const msg of activeMessages) {
        if (!isNoticeBlocking(msg)) {
            continue;
        }

        const cod = String(msg.symtypcod || '').trim().toUpperCase();
        if (config.codes && config.codes.includes(cod)) {
            return true;
        }

        const symName = String(msg.symtypcodmag || '').trim();
        if (
            config.names &&
            config.names.some(target => symName === target || (target.length >= 4 && symName.includes(target)))
        ) {
            return true;
        }
    }

    return false;
}

/**
 * [함수 역할]
 * 중증응급질환 28종 수용 가능 여부 매트릭스 보드를 `index.html`의 `#severe` 영역에 렌더링합니다.
 * - 참고: `#section-severe`에는 `style.css`의 `.detail-section-deferred`(`content-visibility: auto`)가 적용되어 있어
 *   사용자가 스크롤해 내려올 때 레이아웃·페인트를 수행하므로 상세 시트 초기 열림 속도가 매우 빠릅니다.
 */
function renderSevere(hospital) {
    const target = byId('severe');
    if (!target) {
        return;
    }
    target.replaceChildren();

    byId('severe-time').textContent = '보고 시각 미정보';

    if (!state.sources.severe) {
        empty(target, '미정보 · 중증질환 정보 조회에 실패했습니다.');
        return;
    }

    const fields = hospital.severe || {};

    const card = node('article', 'egen-table-card');
    const header = node('div', 'egen-header-bar severe');
    header.append(
        node('span', 'egen-header-icon', '❤️'),
        node('span', '', '중증응급질환')
    );

    const scrollArea = node('div', 'egen-scroll-area');
    const matrix = node('div', 'severe-matrix-grid');

    const now = Date.now();
    const activeMessages = (hospital.messages || []).filter(item => {
        const start = parseApiDate(item.symblksttdtm);
        const end = parseApiDate(item.symblkenddtm);
        return (!start || start.timestamp <= now) && (!end || end.timestamp > now);
    });

    for (const group of SEVERE_DISEASE_GROUPS) {
        const box = node('div', 'severe-cat-box');
        box.append(node('div', 'severe-cat-header', group.title));

        for (const item of group.items) {
            const row = node('div', 'severe-sub-item');
            const topRow = node('div', 'severe-sub-top');
            const label = node('span', 'severe-sub-label', item.label);
            const valWrap = node('div', 'severe-sub-val');

            const rawVal = fields[item.key];
            const rawMsg = fields[`${item.key}msg`];
            const cleanVal = isBlankOrNull(rawVal) ? '-' : String(rawVal).trim();
            const cleanMsg = isBlankOrNull(rawMsg) || rawMsg === '정보미제공' ? '' : String(rawMsg).trim();

            const status = availability(rawVal, '가능', '불가능');
            const isBlockedByNotice = isDiseaseSpecificallyBlocked(activeMessages, item.key);

            if (cleanMsg) {
                valWrap.append(node('span', 'egen-badge-avail-yellow', '확인 필요'));
            } else if (status.className === 'no' || isBlockedByNotice) {
                valWrap.append(node('span', 'egen-badge-avail-red', '불가능'));
            } else if (status.className === 'yes') {
                valWrap.append(node('span', 'egen-badge-avail-green', '가능'));
            } else {
                valWrap.append(node('span', 'egen-badge-dash', '-'));
            }

            topRow.append(label, valWrap);

            const paramText = `${item.key}\u00A0\u00A0\u00A0수신값 [${cleanVal}]`;
            const paramEl = createMarqueeEl('div', 'egen-cell-param', paramText, paramText);

            row.append(topRow, paramEl);

            if (cleanMsg) {
                const remarkText = `비고: ${cleanMsg}`;
                const remarkEl = createMarqueeEl('div', 'severe-remark-box', remarkText, remarkText);
                row.append(remarkEl);
            }

            box.append(row);
        }

        matrix.append(box);
    }

    scrollArea.append(matrix);
    card.append(header, scrollArea);
    target.append(card);

    const reported = parseApiDate(firstReported(fields.hvidate, fields.mkioskdate));
    if (reported) {
        byId('severe-time').textContent = `보고 ${reported.display}`;
    }
}

/* --------------------------------------------------------------------------
   [14. 실시간 가용 병상 렌더링 엔진 (현장 실사 도메인 규칙 엄수)]
   - 도메인 절대 규칙:
     1) 음수 병상 값(`< 0`)은 오류가 아니라 정원을 넘어 환자를 받고 있는 상태이므로 **'초과수용'**으로 표기합니다.
     2) 빈 값(`null`/`""`)은 0석으로 왜곡하지 않고 **`'-'` / `'미정보'`**로 엄격 분리 표기합니다.
   -------------------------------------------------------------------------- */

/**
 * [함수 역할]
 * 지도 위 병원 카드(`.hospital-card`), 클러스터 목록, 실도로 최단시간 추천 배지에서
 * 응급실 일반병상(`hvec`) 수치를 짧은 한글 요약 문자열(`"5석"`, `"초과수용 2석"`, `"미정보"`)로 변환합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `updateMarkers()`, `openCluster()`, `loadEtaRanking()`, `populateWaypointSelect()`
 */
function bedValue(raw) {
    if (isMissing(raw)) {
        return '미정보';
    }
    const text = String(raw).trim();
    if (!/^-?\d+$/.test(text)) {
        return '미정보';
    }
    const value = Number(text);
    if (!Number.isSafeInteger(value)) {
        return '미정보';
    }
    return value < 0
        ? `초과수용 ${Math.abs(value)}석`
        : `${value}석`;
}

const EMERGENCY_BED_ITEMS = [
    { label: '일반', current: 'hvec', baseline: 'hvs01' },
    { label: '소아', current: 'hv28', baseline: 'hvs02' },
    { label: '외상소생실', current: 'hv60', baseline: 'hvs60' },
    { label: '외상환자진료구역', current: 'hv61', baseline: 'hvs61' },
    { label: '일반격리', current: 'hv30', baseline: 'hvs04' },
    { label: '음압격리', current: 'hv29', baseline: 'hvs03' },
    { label: '격리진료구역 일반격리', current: 'hv14', baseline: 'hvs47' },
    { label: '격리진료구역 음압격리', current: 'hv13', baseline: 'hvs46' },
    { label: '소아일반격리', current: 'hv16', baseline: 'hvs49' },
    { label: '소아음압격리', current: 'hv15', baseline: 'hvs48' },
    { label: '코호트격리', current: 'hv27', baseline: 'hvs59' }
];

const INPATIENT_BED_SECTIONS = [
    {
        title: '중환자실',
        items: [
            { label: '일반', current: 'hvicc', baseline: 'hvs17' },
            { label: '음압격리', current: 'hv35', baseline: 'hvs18' },
            { label: '소아', current: 'hv32', baseline: 'hvs09' },
            { label: '신생아', current: 'hvncc', baseline: 'hvs08' },
            { label: '내과', current: 'hv2', baseline: 'hvs06' },
            { label: '심장내과', current: 'hv34', baseline: 'hvs15' },
            { label: '신경과', current: 'hvcc', baseline: 'hvs11' },
            { label: '화상', current: 'hv8', baseline: 'hvs13' },
            { label: '외과', current: 'hv3', baseline: 'hvs07' },
            { label: '신경외과', current: 'hv6', baseline: 'hvs12' },
            { label: '흉부외과', current: 'hvccc', baseline: 'hvs16' }
        ]
    },
    {
        title: '응급전용',
        items: [
            { label: '입원실', current: 'hv36', baseline: 'hvs19' },
            { label: '입원실 음압격리', current: 'hv19', baseline: 'hvs52' },
            { label: '입원실 일반격리', current: 'hv21', baseline: 'hvs53' },
            { label: '중환자실', current: 'hv31', baseline: 'hvs05' },
            { label: '중환자실 음압격리', current: 'hv17', baseline: 'hvs50' },
            { label: '중환자실 일반격리', current: 'hv18', baseline: 'hvs51' },
            { label: '소아입원실', current: 'hv37', baseline: 'hvs20' },
            { label: '소아중환자실', current: 'hv33', baseline: 'hvs10' }
        ]
    },
    {
        title: '외상전용',
        items: [
            { label: '중환자실', current: 'hv9', baseline: 'hvs14' },
            { label: '입원실', current: 'hv38', baseline: 'hvs21' },
            { label: '수술실', current: 'hv39', baseline: 'hvs23' }
        ]
    },
    {
        title: '감염병 전담병상',
        items: [
            { label: '중환자실', current: 'hv22', baseline: 'hvs54' },
            { label: '중환자실 음압격리', current: 'hv23', baseline: 'hvs55' },
            { label: '[감염] 중증 병상', current: 'hv24', baseline: 'hvs56' },
            { label: '[감염] 준·중증 병상', current: 'hv25', baseline: 'hvs57' },
            { label: '[감염] 중등증 병상', current: 'hv26', baseline: 'hvs58' }
        ]
    },
    {
        title: '입원실',
        items: [
            { label: '일반', current: 'hvgc', baseline: 'hvs38' },
            { label: '외과입원실(정형외과)', current: 'hv4', baseline: null },
            { label: '음압격리', current: 'hv41', baseline: 'hvs25' },
            { label: '정신과 폐쇄병동', current: 'hv40', baseline: 'hvs24' }
        ]
    },
    {
        title: '기타',
        items: [
            { label: '수술실', current: 'hvoc', baseline: 'hvs22' },
            { label: '분만실', current: 'hv42', baseline: 'hvs26', type: 'delivery' },
            { label: '화상전용처치실', current: 'hv43', baseline: 'hvs36' }
        ]
    }
];

function isBlankOrNull(val) {
    return val === null || val === undefined || String(val).trim() === '';
}

function formatParamLabel(currentKey, baselineKey) {
    if (currentKey && baselineKey) {
        return `${currentKey} / ${baselineKey} (가용 / 기준)`;
    }
    if (currentKey) {
        return `${currentKey} (가용)`;
    }
    if (baselineKey) {
        return `${baselineKey} (기준)`;
    }
    return '';
}

/**
 * [함수 역할]
 * 병상 가용 수치(`current`)와 기준 병상 수치(`baseline`)의 비율을 계산하여
 * `원활`(초록) / `보통`(주황) / `혼잡`(빨강) / `초과수용`(빨강 강조) / `-`(미보고) 배지 스타일을 반환합니다.
 *
 * [수정 시 영향 및 예시]
 * - 현재는 가용률이 35% 이하(`ratio <= 0.35`)일 때 `'혼잡'`으로 표시합니다.
 *   만약 20% 이하일 때만 `'혼잡'`으로 표시하고 싶다면:
 *   변경 전: if (current === 0 || ratio <= 0.35)
 *   변경 후: if (current === 0 || ratio <= 0.20)
 */
function getBedStatusBadge(fields, def) {
    const rawVal = fields[def.current];
    const rawBase = def.baseline ? fields[def.baseline] : null;

    if (def.type === 'delivery') {
        if (isBlankOrNull(rawVal) && isBlankOrNull(rawBase)) {
            return { className: 'egen-badge-dash', text: '-' };
        }
        const valStr = String(rawVal ?? '').trim().toUpperCase();
        const baseNum = integerValue(rawBase);
        const currNum = integerValue(rawVal);
        const isY = valStr === 'Y' || (currNum !== null && currNum > 0);
        const count = baseNum !== null ? baseNum : currNum;

        if (isY) {
            return {
                className: 'egen-badge-box-green',
                text: count !== null && count >= 0 ? `Y / ${count}` : 'Y'
            };
        }
        return { className: 'egen-badge-dash', text: '-' };
    }

    if (isBlankOrNull(rawVal)) {
        return { className: 'egen-badge-dash', text: '-' };
    }

    const current = integerValue(rawVal);
    const baseline = integerValue(rawBase);

    if (current === null) {
        return { className: 'egen-badge-dash', text: '-' };
    }

    // 음수 병상 값은 현장 실사 기준 '초과수용' 상태입니다.
    if (current < 0) {
        const overflow = Math.abs(current);
        const textVal = (baseline !== null && baseline > 0)
            ? `초과수용 ${overflow}/${baseline}`
            : `초과수용 ${overflow}`;
        return {
            className: 'egen-badge-pill-red egen-badge-overflow',
            text: textVal
        };
    }

    if (baseline !== null && baseline > 0) {
        const ratio = current / baseline;
        const textVal = `${current}/${baseline}`;
        if (current === 0 || ratio <= 0.35) {
            return { className: 'egen-badge-pill-red', text: `혼잡 ${textVal}` };
        }
        if (ratio < 0.70) {
            return { className: 'egen-badge-pill-orange', text: `보통 ${textVal}` };
        }
        return { className: 'egen-badge-pill-green', text: `원활 ${textVal}` };
    }

    if (current > 0) {
        return { className: 'egen-badge-pill-green', text: `원활 ${current}` };
    }
    return { className: 'egen-badge-pill-red', text: '혼잡 0' };
}

/**
 * [함수 역할]
 * E-Gen 병상 매트릭스 보드의 개별 셀 1칸(`.egen-cell`)을 생성합니다.
 */
function createBedCell(fields, item) {
    const cell = node('div', 'egen-cell');
    const topRow = node('div', 'egen-cell-top');
    const badge = getBedStatusBadge(fields, item);

    topRow.append(
        node('span', 'egen-cell-label', item.label),
        node('span', badge.className, badge.text)
    );

    const currRaw = isBlankOrNull(fields[item.current]) ? '-' : String(fields[item.current]).trim();
    const baseRaw = item.baseline
        ? (isBlankOrNull(fields[item.baseline]) ? '-' : String(fields[item.baseline]).trim())
        : null;

    let paramText = formatParamLabel(item.current, item.baseline);
    if (baseRaw !== null) {
        paramText += `\u00A0\u00A0\u00A0수신값 [${currRaw} / ${baseRaw}]`;
    } else {
        paramText += `\u00A0\u00A0\u00A0수신값 [${currRaw}]`;
    }

    const paramEl = createMarqueeEl('div', 'egen-cell-param', paramText, paramText);
    cell.append(topRow, paramEl);
    return cell;
}

/**
 * [함수 역할]
 * 응급실 병상 현황판(`EMERGENCY_BED_ITEMS`)을 `index.html`의 `#er-beds-board`에 렌더링합니다.
 */
function renderErBeds(hospital) {
    const target = byId('er-beds-board') || byId('beds-board');
    if (!target) {
        return;
    }
    target.replaceChildren();

    if (!state.sources.beds) {
        empty(target, '미정보 · 병상 정보 조회에 실패했습니다.');
        return;
    }

    const fields = hospital.beds || {};
    const card = node('article', 'egen-table-card');
    const header = node('div', 'egen-header-bar emergency');
    header.append(
        node('span', 'egen-header-icon', '🚨'),
        node('span', '', '응급실병상')
    );

    const grid = node('div', 'egen-matrix-grid');
    for (const item of EMERGENCY_BED_ITEMS) {
        grid.append(createBedCell(fields, item));
    }

    card.append(header, grid);
    target.append(card);
}

/**
 * [함수 역할]
 * 중환자실·응급전용·외상전용·감염병·일반입원실 병상 현황판(`INPATIENT_BED_SECTIONS`)을
 * `index.html`의 `#inpatient-beds-board`에 렌더링합니다.
 */
function renderInpatientBeds(hospital) {
    const target = byId('inpatient-beds-board');
    if (!target) {
        return;
    }
    target.replaceChildren();

    if (!state.sources.beds) {
        empty(target, '미정보 · 병상 정보 조회에 실패했습니다.');
        return;
    }

    const fields = hospital.beds || {};
    const card = node('article', 'egen-table-card');
    const header = node('div', 'egen-header-bar inpatient');
    header.append(
        node('span', 'egen-header-icon', '🛏️'),
        node('span', '', '입원병상')
    );

    card.append(header);

    for (const section of INPATIENT_BED_SECTIONS) {
        const subHeader = node('div', 'egen-subgroup-header', section.title);
        const subGrid = node('div', 'egen-matrix-grid');

        for (const item of section.items) {
            subGrid.append(createBedCell(fields, item));
        }

        card.append(subHeader, subGrid);
    }

    target.append(card);
}

/**
 * [함수 역할]
 * 응급실 병상(`renderErBeds`), 입원병상(`renderInpatientBeds`), 개발자 모드 원본 병상(`renderRawBeds`)과
 * 병상 보고 시각(`#bed-time`)을 한꺼번에 갱신합니다.
 */
function renderBeds(hospital) {
    const bedTimeEl = byId('bed-time');
    if (bedTimeEl) {
        bedTimeEl.textContent = '보고 시각 미정보';
    }

    renderErBeds(hospital);
    renderInpatientBeds(hospital);

    const fields = hospital.beds || {};
    const reported = parseApiDate(fields.hvidate);
    if (reported && bedTimeEl) {
        bedTimeEl.textContent = `보고 ${reported.display}`;
    }
}

/**
 * [함수 역할]
 * 상세 시트 하단 `<details id="developer-mode">`가 실제로 펼쳐졌을 때만
 * 수백 개의 API 원본 코드·값 비교 DOM 노드를 지연 생성(Lazy DOM Rendering)하여 카드 클릭 반응성(INP)을 보호합니다.
 */
function renderDeveloperInspector(hospital) {
    if (!hospital) {
        return;
    }
    renderRawBeds(hospital);
    renderBasic(hospital);
    renderRawSevere(hospital);
    renderRawMessages(hospital);
    state.renderedDevHospitalRef = hospital;
}

/* --------------------------------------------------------------------------
   [15. 개발자 모드: API 원본 데이터(Raw Fields) 인스펙터 (`index.html` #developer-mode)]
   - 상세 시트 최하단의 '개발자 모드 · API 수신 원본 코드 대조' 아코디언을 열었을 때 보이는 표를 생성합니다.
   -------------------------------------------------------------------------- */

function renderRawBeds(hospital) {
    const all = byId('all-beds');
    all.replaceChildren();

    if (!state.sources.beds) {
        empty(all, '미정보 · 세부 항목을 표시할 수 없습니다.');
        return;
    }

    const fields = hospital.beds;
    if (!fields) {
        empty(all, '미정보 · 세부 항목이 없습니다.');
        return;
    }

    const groups = [
        {
            title: '실시간 세부 항목 · hv (원본값)',
            keys: [
                ...new Set([
                    ...Object.keys(HV_LABELS),
                    ...Object.keys(fields).filter(key => /^hv\d+$/.test(key))
                ])
            ]
        },
        {
            title: '병상·장비 기준 항목 · hvs (원본값)',
            keys: [
                ...new Set([
                    ...Object.keys(HVS_LABELS),
                    ...Object.keys(fields).filter(key => /^hvs\d+$/.test(key))
                ])
            ]
        },
        {
            title: '그 밖의 병상 관련 항목 (원본값)',
            keys: ['hvec', 'hvoc', 'hvcc', 'hvncc', 'hvccc', 'hvicc', 'hvgc', 'hvdnm', 'hvidate']
        }
    ];

    for (const group of groups) {
        if (!group.keys.length) {
            continue;
        }

        const section = node('section', 'raw-group');
        section.append(node('h3', '', group.title));

        const list = node('dl', 'raw-grid');
        group.keys.sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));

        for (const key of group.keys) {
            const pair = node('div', 'raw-pair');
            pair.append(
                node('dt', 'field-code', fieldLabel(key)),
                node('dd', '', displayValue(fields[key]))
            );
            list.append(pair);
        }

        section.append(list);
        all.append(section);
    }

    if (!all.childElementCount) {
        empty(all, '미정보 · 세부 항목이 없습니다.');
    }
}

const MKIOSKTY_METADATA = {
    mkioskty1: '[재관류중재술] 심근경색',
    mkioskty2: '[재관류중재술] 뇌경색',
    mkioskty3: '[뇌출혈수술] 거미막하출혈',
    mkioskty4: '[뇌출혈수술] 거미막하출혈 외',
    mkioskty5: '[대동맥응급] 흉부',
    mkioskty6: '[대동맥응급] 복부',
    mkioskty7: '[담낭담관질환] 담낭질환',
    mkioskty8: '[담낭담관질환] 담도포함질환',
    mkioskty9: '[복부응급수술] 비외상',
    mkioskty10: '[장중첩/폐색] 영유아',
    mkioskty11: '[응급내시경] 성인 위장관',
    mkioskty12: '[응급내시경] 영유아 위장관',
    mkioskty13: '[응급내시경] 성인 기관지',
    mkioskty14: '[응급내시경] 영유아 기관지',
    mkioskty15: '[저체중출생아] 집중치료',
    mkioskty16: '[산부인과응급] 분만',
    mkioskty17: '[산부인과응급] 산과수술',
    mkioskty18: '[산부인과응급] 부인과수술',
    mkioskty19: '[중증화상] 전문치료',
    mkioskty20: '[사지접합] 수족지접합',
    mkioskty21: '[사지접합] 수족지접합 외',
    mkioskty22: '[응급투석] HD',
    mkioskty23: '[응급투석] CRRT',
    mkioskty24: '[정신과적응급] 폐쇄병동입원',
    mkioskty25: '[안과적수술] 응급',
    mkioskty26: '[영상의학혈관중재] 성인',
    mkioskty27: '[영상의학혈관중재] 영유아',
    mkioskty28: '응급실 (Emergency gate keeper)',
    mkioskty10msg: '장중첩/폐색(영유아) 가능연령 비고',
    mkioskty12msg: '위장관 응급내시경(영유아) 가능연령 비고',
    mkioskty14msg: '기관지 응급내시경(영유아) 가능연령 비고',
    mkioskty15msg: '저체중 출생아 가능연령 비고',
    mkioskty27msg: '영상의학 혈관 중재적 시술(영유아) 가능연령 비고',
    mkioskdate: '키오스크 정보 보고일시 (mkioskdate)',
    hvidate: '중증질환 정보 보고일시 (hvidate)'
};

function renderRawSevere(hospital) {
    const all = byId('all-severe');
    if (!all) {
        return;
    }
    all.replaceChildren();

    if (!state.sources.severe) {
        empty(all, '미정보 · 중증질환 원본 정보를 조회하지 못했습니다.');
        return;
    }

    const fields = hospital.severe;
    if (!fields) {
        empty(all, '미정보 · 해당 병원의 중증질환 수용정보 데이터가 없습니다.');
        return;
    }

    const section = node('section', 'raw-group');
    section.append(node('h3', '', '중증응급질환 수용가능 정보 · getSrsillDissAceptncPosblInfoInqire (원본값)'));

    const list = node('dl', 'raw-grid');
    const standardKeys = [
        ...Array.from({ length: 28 }, (_, i) => `mkioskty${i + 1}`),
        'mkioskty10msg', 'mkioskty12msg', 'mkioskty14msg', 'mkioskty15msg', 'mkioskty27msg',
        'mkioskdate', 'hvidate'
    ];

    const extraKeys = Object.keys(fields).filter(
        k => !standardKeys.includes(k) && k !== 'dutyname' && k !== 'hpid'
    );

    for (const key of [...standardKeys, ...extraKeys]) {
        const val = fields[key];
        const label = MKIOSKTY_METADATA[key] || key;
        const pair = node('div', 'raw-pair');
        pair.append(
            node('dt', 'field-code', `${label} (${key})`),
            node('dd', '', isBlankOrNull(val) ? '-' : String(val).trim())
        );
        list.append(pair);
    }

    section.append(list);
    all.append(section);
}

function renderRawMessages(hospital) {
    const all = byId('all-messages');
    if (!all) {
        return;
    }
    all.replaceChildren();

    if (!state.sources.messages) {
        empty(all, '미정보 · 실시간 진료공지 정보를 조회하지 못했습니다.');
        return;
    }

    const msgs = hospital.messages || [];
    if (!msgs.length) {
        empty(all, '해당 병원에 등록된 실시간 진료 공지가 없습니다.');
        return;
    }

    const section = node('section', 'raw-group');
    section.append(node('h3', '', `실시간 진료 공지 목록 (총 ${msgs.length}건)`));

    for (let i = 0; i < msgs.length; i++) {
        const item = msgs[i];
        const card = node('div', 'raw-msg-card');
        const dl = node('dl', 'raw-grid');

        const fields = [
            ['순번 (rnum)', item.rnum],
            ['구분코드 (symTypCod)', item.symtypcod],
            ['구분명 (symTypCodMag)', item.symtypcodmag],
            ['진료과 (trtPrtCodMag)', item.trtprtcodmag],
            ['표출여부 (symOutDspYon)', item.symoutdspyon],
            ['표출방법 (symOutDspMth)', item.symoutdspmth],
            ['메시지구분 (symBlkMsgTyp)', item.symblkmsgtyp],
            ['시작일시 (symBlkSttDtm)', item.symblksttdtm],
            ['종료일시 (symBlkEndDtm)', item.symblkenddtm],
            ['공지내용 (symBlkMsg)', item.symblkmsg]
        ];

        for (const [dt, dd] of fields) {
            const pair = node('div', 'raw-pair');
            pair.append(
                node('dt', 'field-code', dt),
                node('dd', '', isBlankOrNull(dd) ? '-' : String(dd).trim())
            );
            dl.append(pair);
        }

        const titleText = `공지 #${i + 1} · ${item.trtprtcodmag || item.symtypcodmag || item.symtypcod || '공지'}`;
        card.append(node('h4', 'raw-msg-title', titleText), dl);
        section.append(card);
    }

    all.append(section);
}

/* --------------------------------------------------------------------------
   [16. 가용 장비 현황 (11대 핵심 응급의료 장비 보드 — `index.html` #equipment 연동)]
   -------------------------------------------------------------------------- */

const EQUIPMENT_TABLE_ITEMS = [
    { label: '인공호흡기 일반', key: 'hvventiayn', baseline: 'hvs30' },
    { label: '인공호흡기 조산아', key: 'hvventisoayn', baseline: 'hvs31' },
    { label: '인큐베이터', key: 'hvincuayn', baseline: 'hvs32' },
    { label: 'CRRT', key: 'hvcrrtayn', baseline: 'hvs33' },
    { label: 'ECMO', key: 'hvecmoayn', baseline: 'hvs34' },
    { label: '중심체온조절유도기', key: 'hvhypoayn', baseline: 'hvs35' },
    { label: '고압산소치료기', key: 'hvoxyayn', baseline: 'hvs37' },
    { label: 'CT', key: 'hvctayn', baseline: 'hvs27' },
    { label: 'MRI', key: 'hvmriayn', baseline: 'hvs28' },
    { label: '혈관촬영기', key: 'hvangioayn', baseline: 'hvs29' },
    { label: '구급차 가용여부', key: 'hvamyn', baseline: null }
];

function createEquipmentCell(fields, item) {
    const cell = node('div', 'egen-cell equipment-cell');
    const topRow = node('div', 'egen-cell-top');

    const rawVal = fields[item.key];
    const rawBase = item.baseline ? fields[item.baseline] : null;

    let badgeClass = 'egen-badge-dash';
    let badgeText = '-';

    if (!isBlankOrNull(rawVal)) {
        const valStr = String(rawVal).trim().toUpperCase();
        const numVal = integerValue(rawVal);
        const isAvail = valStr === 'Y' || (numVal !== null && numVal > 0);
        const baseline = integerValue(rawBase);

        if (isAvail) {
            badgeClass = 'egen-badge-box-green';
            badgeText = (baseline !== null && baseline >= 0) ? `Y / ${baseline}` : 'Y';
        } else if (valStr.startsWith('N') || numVal === 0) {
            badgeClass = 'egen-badge-dash';
            badgeText = '-';
        }
    }

    topRow.append(
        node('span', 'egen-cell-label', item.label),
        node('span', badgeClass, badgeText)
    );

    const currRaw = isBlankOrNull(rawVal) ? '-' : String(rawVal).trim();
    const baseRaw = item.baseline
        ? (isBlankOrNull(rawBase) ? '-' : String(fields[item.baseline]).trim())
        : null;

    let paramText = formatParamLabel(item.key, item.baseline);
    if (baseRaw !== null) {
        paramText += `\u00A0\u00A0\u00A0수신값 [${currRaw} / ${baseRaw}]`;
    } else {
        paramText += `\u00A0\u00A0\u00A0수신값 [${currRaw}]`;
    }

    const paramEl = createMarqueeEl('div', 'egen-cell-param', paramText, paramText);
    cell.append(topRow, paramEl);
    return cell;
}

/**
 * [함수 역할]
 * 11대 핵심 응급의료 장비 가용 현황 보드를 `index.html`의 `#equipment` 영역에 렌더링합니다.
 */
function renderEquipment(hospital) {
    const target = byId('equipment');
    if (!target) {
        return;
    }
    target.replaceChildren();

    if (!state.sources.beds) {
        empty(target, '미정보 · 장비 정보 조회에 실패했습니다.');
        return;
    }

    const fields = hospital.beds || {};
    const card = node('article', 'egen-table-card');
    const header = node('div', 'egen-header-bar equipment');
    header.append(
        node('span', 'egen-header-icon', '⚙️'),
        node('span', '', '가용 장비')
    );

    const grid = node('div', 'equipment-matrix-grid');
    for (const item of EQUIPMENT_TABLE_ITEMS) {
        grid.append(createEquipmentCell(fields, item));
    }

    card.append(header, grid);
    target.append(card);
}

/* --------------------------------------------------------------------------
   [17. 기관 기본정보 및 외상센터 정보 렌더링 (`index.html` #basic-info 연동)]
   -------------------------------------------------------------------------- */

function renderBasic(hospital) {
    const target = byId('basic-info');
    target.replaceChildren();

    const blocks = [
        ['응급의료기관 기본정보', hospital.basic, state.sources.basic],
        ['외상센터 기본정보', hospital.trauma, state.sources.traumaBasic]
    ];

    for (const [heading, fields, loaded] of blocks) {
        const section = node('details', 'raw-details');
        section.append(node('summary', '', heading));

        if (!loaded) {
            section.append(node('p', 'empty-state', '미정보 · 조회에 실패했습니다.'));
        } else if (!fields || !Object.keys(fields).length) {
            section.append(node('p', 'empty-state', '미정보 · 보고된 정보가 없습니다.'));
        } else {
            const list = node('dl', 'raw-grid');

            for (const [key, value] of Object.entries(fields)) {
                if (['hpid', 'rnum', 'phpid'].includes(key)) {
                    continue;
                }
                const pair = node('div', 'raw-pair');
                pair.append(
                    node('dt', 'field-code', fieldLabel(key)),
                    node('dd', '', displayValue(value))
                );
                list.append(pair);
            }

            section.append(list);
        }

        target.append(section);
    }
}

/* --------------------------------------------------------------------------
   [18. 긴급 전화 직통 연결(`tel:`) 및 3대 내비게이션 앱 딥링크 연동]
   -------------------------------------------------------------------------- */

function contactLink(text, href, className) {
    const link = node('a', className, text);
    link.href = href;
    return link;
}

/**
 * [함수 역할]
 * 현재 기기가 iPhone / iPad / iPod 등 Apple 터치 디바이스인지 판별합니다.
 * - iPadOS 데스크톱 모드(`Macintosh` + `maxTouchPoints > 1`)까지 정확히 감지합니다.
 */
function isAppleTouchDevice() {
    const userAgent = navigator.userAgent || '';
    return (
        /iPad|iPhone|iPod/.test(userAgent) ||
        (/Macintosh/.test(userAgent) && navigator.maxTouchPoints > 1)
    );
}

/**
 * [함수 역할]
 * 모바일(Android `intent://` / iOS 커스텀 스킴)에서는 네이티브 내비게이션 앱(네이버지도·카카오맵·T맵)을 즉시 실행하고,
 * 앱이 설치되어 있지 않거나 데스크톱 PC 환경이면 웹 지도/앱스토어 폴백 URL로 안전하게 연결합니다.
 */
function openMobileApp(scheme, androidPackage, fallback) {
    if (/Android/i.test(navigator.userAgent)) {
        const intent = scheme.replace(/^[a-z]+:\/\//, 'intent://');
        const protocol = scheme.split(':')[0];

        window.location.href =
            `${intent}#Intent;scheme=${protocol};` +
            'action=android.intent.action.VIEW;' +
            `package=${androidPackage};` +
            `S.browser_fallback_url=${encodeURIComponent(fallback)};end`;
        return;
    }

    if (!isAppleTouchDevice()) {
        window.open(fallback, '_blank', 'noopener,noreferrer');
        return;
    }

    const started = Date.now();
    let timer;

    const cleanup = () => {
        window.clearTimeout(timer);
        document.removeEventListener('visibilitychange', onVisibility);
        window.removeEventListener('pagehide', cleanup);
    };

    const onVisibility = () => {
        if (document.visibilityState === 'hidden') {
            cleanup();
        }
    };

    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('pagehide', cleanup);

    timer = window.setTimeout(() => {
        cleanup();
        if (document.visibilityState === 'visible' && Date.now() - started < 3000) {
            window.location.href = fallback;
        }
    }, 1800);

    window.location.href = scheme;
}

/**
 * [함수 역할]
 * 내비게이션 서비스별(`'naver'`, `'kakao'`, `'tmap'`) 실행 스킴·안드로이드 패키지명·폴백 URL을 생성합니다.
 */
function navigationLink(provider, hospital) {
    const name = encodeURIComponent(hospital.name);
    const lat = hospital.lat;
    const lng = hospital.lng;

    const mobile = /Android/i.test(navigator.userAgent) || isAppleTouchDevice();

    if (provider === 'naver') {
        const appname = encodeURIComponent(window.location.origin);
        let fallback;

        if (mobile) {
            fallback = isAppleTouchDevice()
                ? 'https://apps.apple.com/kr/app/id311867728'
                : 'https://play.google.com/store/apps/details?id=com.nhn.android.nmap';
        } else {
            fallback =
                'https://map.naver.com/p/directions/-/' +
                `${lng},${lat},${name},,/-/car?c=15.00,0,0,0,dh`;
        }

        return {
            scheme: `nmap://navigation?dlat=${lat}&dlng=${lng}&dname=${name}&appname=${appname}`,
            package: 'com.nhn.android.nmap',
            fallback
        };
    }

    if (provider === 'kakao') {
        const start = state.locationKnown ? `sp=${state.origin.lat},${state.origin.lng}&` : '';
        return {
            scheme: `kakaomap://route?${start}ep=${lat},${lng}&by=car`,
            package: 'net.daum.android.map',
            fallback: `https://map.kakao.com/link/to/${name},${lat},${lng}`
        };
    }

    return {
        scheme: isAppleTouchDevice()
            ? `tmap://route?rGoName=${name}&rGoX=${lng}&rGoY=${lat}`
            : `tmap://route?goalname=${name}&goalx=${lng}&goaly=${lat}`,
        package: 'com.skt.tmap.ku',
        fallback: isAppleTouchDevice()
            ? 'https://apps.apple.com/kr/app/id431589174'
            : 'https://play.google.com/store/apps/details?id=com.skt.tmap.ku'
    };
}

function launchNavigation(provider, hospital) {
    const link = navigationLink(provider, hospital);
    openMobileApp(link.scheme, link.package, link.fallback);
}

/**
 * [함수 역할]
 * 상세 시트 상단(`index.html`의 `#contact-actions`)에 응급실/대표전화 직통 발신 버튼(`tel:`)과
 * 네이버지도·카카오맵·T맵 길찾기 실행 버튼을 렌더링합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `openHospital()`
 * - 짝을 이루는 CSS: `frontend/style.css`의 `.contact-actions`, `.phone-actions`, `.navigation-actions`
 *   (모바일에서도 최소 터치 높이 `44px` 이상 보장)
 */
function renderContacts(hospital) {
    const target = byId('contact-actions');
    const phones = node('div', 'phone-actions');
    const maps = node('div', 'navigation-actions');

    phones.setAttribute('aria-label', '병원 전화');
    maps.setAttribute('aria-label', '지도 길찾기');

    for (const [label, raw] of [
        ['응급실', hospital.erTel],
        ['대표전화', hospital.tel]
    ]) {
        const number = isMissing(raw) ? '' : String(raw).trim();
        if (/^[0-9+()\s-]+$/.test(number) && /\d/.test(number)) {
            phones.append(
                contactLink(
                    `${label} ${number}`,
                    `tel:${number.replace(/[^\d+]/g, '')}`,
                    'phone-button'
                )
            );
        } else {
            phones.append(node('span', 'phone-missing', `${label} 미정보`));
        }
    }

    for (const [provider, label] of [
        ['naver', '네이버지도'],
        ['kakao', '카카오맵'],
        ['tmap', 'T맵']
    ]) {
        const button = node('button', `map-button ${provider}`, label);
        button.type = 'button';
        button.addEventListener('click', () => launchNavigation(provider, hospital));
        maps.append(button);
    }

    target.replaceChildren(phones, maps);
}

/* --------------------------------------------------------------------------
   [19. 병원 상세정보 시트(`index.html` #sheet) 인터랙션, 반응형 전환 & 접근성]
   -------------------------------------------------------------------------- */

/**
 * [함수 역할]
 * 특정 병원(`hospital`)의 종합 상세 정보창(`index.html`의 `#sheet`)을 열고,
 * 연락처·실시간 공지·응급실/입원 병상·가용 장비·중증질환·기본정보·자동차 경로(ETA)를 일괄 렌더링합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming):
 *   1) 지도 위 `.hospital-card` 클릭 (`bindHospitalCard`)
 *   2) 클러스터 모달 목록 클릭 (`openCluster`)
 *   3) 검색창 자동완성 결과 클릭 (`updateSearch`)
 *   4) 실도로 최단시간 추천 순위 클릭 (`loadEtaRanking`)
 *   5) 백그라운드 새로고침 시 열려 있는 병원 데이터 갱신 (`fetchData(..., false)`)
 * - 호출하는 하위 렌더러(Outgoing):
 *   `renderContacts()`, `renderMessages()`, `renderBeds()`, `renderEquipment()`, `renderSevere()`,
 *   `renderBasic()`, `renderRawSevere()`, `renderRawMessages()`, `loadDirectionsForHospital()`(`GET /api/directions`)
 *
 * @param {Object} hospital 열 대상 병원 객체 (`HospitalDto`)
 * @param {boolean} [focus=true] 시트 열림 시 스크롤을 맨 위로 올리고 닫기 버튼으로 포커스를 옮길지 여부
 * @param {{ skipDirections?: boolean }} [options={}] 백그라운드 병상 데이터 갱신 시 길찾기 API 재호출을 생략할지 여부
 */
function openHospital(hospital, focus = true, options = {}) {
    closeRadius();
    byId('search-results').hidden = true;

    state.diagnostics.lastHospitalOpened = {
        id: hospital.id,
        name: hospital.name,
        erBeds: hospital.beds?.hvec ?? null,
        timestamp: Date.now()
    };

    if (focus) {
        state.previousFocus = document.activeElement;
    }

    const isDifferentHospital = state.selected?.id !== hospital.id;
    if (isDifferentHospital) {
        byId('developer-mode').open = false;
        state.renderedDevHospitalRef = null;
        state.sheetRouteData = null;
    }

    state.selected = hospital;
    const dirClearBtn = byId('directions-clear-btn');
    if (dirClearBtn) {
        dirClearBtn.hidden = !state.activeRoute;
    }
    byId('hospital-name').textContent = hospital.name;

    if (state.lastUpdated) {
        const updated = state.lastUpdated.toLocaleString('ko-KR', {
            timeZone: 'Asia/Seoul',
            year: 'numeric',
            month: 'numeric',
            day: 'numeric',
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit'
        });
        byId('sheet-updated').textContent = `마지막 새로고침 · ${updated} KST`;
    } else {
        byId('sheet-updated').textContent = '전체 조회 시각을 확인할 수 없습니다.';
    }

    byId('hospital-type').textContent = hospitalLabel(hospital);
    byId('hospital-address').textContent = `주소 ${displayValue(hospital.address)}`;

    const distance = distanceKm(state.origin, hospital).toFixed(1);
    const originName = getOriginDisplayLabel();
    byId('hospital-distance').textContent = `${originName}에서 직선거리 ${distance} km`;

    if (isDifferentHospital) {
        populateWaypointSelect(hospital);
    }

    renderContacts(hospital);
    renderMessages(hospital);
    renderBeds(hospital);
    renderEquipment(hospital);
    renderSevere(hospital);

    if (byId('developer-mode')?.open) {
        renderDeveloperInspector(hospital);
    } else {
        state.renderedDevHospitalRef = null;
    }

    // 네이버 Directions 5 / 15 자동차 경로 및 예상 소요 시간(ETA) 조회 (단순 병상 스냅샷 갱신 시에는 중복 호출 생략)
    if (!options.skipDirections) {
        loadDirectionsForHospital(hospital, false);
    }

    const failed = Object.entries(state.sources)
        .filter(([, loaded]) => !loaded)
        .map(([name]) => name);

    byId('data-state').textContent = failed.length
        ? `일부 데이터 조회 실패: ${failed.join(', ')}. 재조회하거나 병원에 문의하세요.`
        : '공공데이터의 보고 시점과 현장 상황은 다를 수 있습니다.';

    byId('sheet').inert = false;
    setBackgroundInert(true);
    byId('sheet-dim').hidden = false;
    byId('sheet').classList.add('open');
    byId('sheet').setAttribute('aria-hidden', 'false');

    if (focus) {
        byId('sheet').querySelector('.sheet-scroll').scrollTop = 0;
        byId('sheet-close').focus();
    }

    setTimeout(updateMarquees, 100);
    setTimeout(updateMarquees, 350);
}

/**
 * [함수 역할]
 * 병원 상세 시트(`#sheet`)가 열려 있는 동안 뒤쪽 배경 요소(`#map`, `#search-panel`, `#active-route-banner`, `#mobile-top-left-menu`)에
 * 표준 `inert` 속성을 설정하여 스크린 리더·키보드 탭 포커스가 시트 밖으로 새어나가지 않도록 보호합니다.
 */
function setBackgroundInert(value) {
    for (const selector of ['#map', '#search-panel', '#active-route-banner', '#mobile-top-left-menu']) {
        const el = document.querySelector(selector);
        if (el) {
            el.inert = value;
        }
    }
}

/**
 * [함수 역할]
 * 열려 있는 병원 상세 시트(`#sheet`)를 닫고 배경 인터랙션 및 이전 포커스를 복원합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `#sheet-close`(× 버튼), `#sheet-dim`(어두운 배경 클릭), `Escape` 키,
 *   모바일 드래그 핸들 아래로 쓸어내리기(`initSheetDrag`), `'지도에 경로 표시'` 버튼 클릭 직후(`initDirectionsControls`)
 */
function closeSheet() {
    byId('sheet').classList.remove('open');
    byId('sheet').style.transform = '';
    byId('sheet').style.transition = '';
    byId('sheet').setAttribute('aria-hidden', 'true');
    byId('sheet-dim').hidden = true;

    state.selected = null;
    byId('sheet').inert = true;
    setBackgroundInert(false);

    const previous = state.previousFocus;
    if (
        previous?.isConnected &&
        !previous.closest('[hidden], [inert], dialog:not([open])') &&
        previous !== byId('keyword') &&
        previous.getClientRects().length > 0
    ) {
        previous.focus();
    } else {
        byId('search-toggle').focus({ preventScroll: true });
    }
}

/**
 * [상수 정의 - 화면 크기별 동적 상세창(#sheet) 레이아웃 기준점]
 * - LARGE_MONITOR_MIN_WIDTH: 중앙 둥근모서리 사각 모달로 전환되는 최소 가로 픽셀(기본 1200px)
 * - LARGE_MONITOR_MIN_HEIGHT: 중앙 둥근모서리 사각 모달로 전환되는 최소 세로 픽셀(기본 680px)
 * - MOBILE_MAX_WIDTH: 모바일 바텀시트로 동작하는 최대 가로 픽셀(기본 640px)
 *
 * [수정 시 영향 및 예시]
 * - QHD 이상 모니터에서만 중앙 모달이 뜨게 하고 싶다면:
 *   변경 전: const LARGE_MONITOR_MIN_WIDTH = 1200;
 *   변경 후: const LARGE_MONITOR_MIN_WIDTH = 1600; (`frontend/style.css`의 `@media (min-width: 1200px)`도 함께 변경)
 */
const LARGE_MONITOR_MIN_WIDTH = 1200;
const LARGE_MONITOR_MIN_HEIGHT = 680;
const MOBILE_MAX_WIDTH = 640;

/**
 * [함수 역할]
 * 브라우저 뷰포트 크기와 포인터 특성(터치 전용 vs 마우스 겸용)을 판별하여
 * 병원 상세창(`#sheet`)을 3단계 레이아웃(`mobile` 바텀시트 / `slider` 우측 드로어 / `modal` 중앙 모달)으로 전환합니다.
 *
 * [코드의 경로성]
 * - 짝을 이루는 CSS: `frontend/style.css`의 `.touch-device .sheet` 및 `@media (min-width: 1200px) and (min-height: 680px)`
 */
function syncResponsiveSheetLayout() {
    const isCoarseOnlyMobile =
        matchMedia('(pointer: coarse)').matches &&
        !matchMedia('(any-pointer: fine)').matches;
    const isMobileEnv =
        window.innerWidth <= MOBILE_MAX_WIDTH ||
        isAppleTouchDevice() ||
        isCoarseOnlyMobile;

    document.body.classList.toggle('touch-device', isMobileEnv);

    let layoutMode = 'slider';
    if (isMobileEnv) {
        layoutMode = 'mobile';
    } else if (
        window.innerWidth >= LARGE_MONITOR_MIN_WIDTH &&
        window.innerHeight >= LARGE_MONITOR_MIN_HEIGHT
    ) {
        layoutMode = 'modal';
    }

    if (document.body.dataset.sheetLayout !== layoutMode) {
        document.body.dataset.sheetLayout = layoutMode;
        const sheet = byId('sheet');
        if (sheet) {
            sheet.style.transform = '';
            sheet.style.transition = '';
        }
    }
}

/**
 * [함수 역할]
 * 아이폰·안드로이드 터치 기기에서 상세 바텀시트 상단 손잡이(`#sheet-drag-handle`)를 아래로 `90px` 넘게
 * 끌어내리면 시트가 자연스럽게 닫히도록 터치 스와이프 제스처를 바인딩합니다.
 *
 * [수정 시 영향 및 예시]
 * - 살짝만 내려도 닫히게 민감도를 높이고 싶다면 `if (delta > 90)`을 `if (delta > 60)`으로 줄이세요.
 */
function initSheetDrag() {
    const handle = byId('sheet-drag-handle');
    const sheet = byId('sheet');

    let startY = null;
    let delta = 0;

    handle.addEventListener(
        'touchstart',
        event => {
            if (!document.body.classList.contains('touch-device') || event.touches.length !== 1) {
                return;
            }
            startY = event.touches[0].clientY;
            delta = 0;
            sheet.style.transition = 'none';
        },
        { passive: true }
    );

    handle.addEventListener(
        'touchmove',
        event => {
            if (startY === null || event.touches.length !== 1) {
                return;
            }
            delta = Math.max(0, event.touches[0].clientY - startY);
            if (delta > 0) {
                event.preventDefault();
                sheet.style.transform = `translate3d(0, ${delta}px, 0)`;
            }
        },
        { passive: false }
    );

    const finish = () => {
        if (startY === null) {
            return;
        }
        startY = null;
        sheet.style.transition = '';

        if (delta > 90) {
            closeSheet();
        } else {
            sheet.style.transform = '';
        }
        delta = 0;
    };

    handle.addEventListener('touchend', finish, { passive: true });
    handle.addEventListener('touchcancel', finish, { passive: true });
}

/* --------------------------------------------------------------------------
   [20. 네이버 지도 뷰포트 경계 계산 및 상·하단 UI 침범 방지 피팅]
   -------------------------------------------------------------------------- */

/**
 * [함수 역할]
 * 상단 활성 경로 배너(`#active-route-banner`)와 하단 검색 패널(`.search-panel`)의 실제 높이를 계산하여,
 * 경로선이나 반경 원이 플로팅 UI에 가려지지 않도록 여백(Padding)을 두고 네이버 지도 영역(`bounds`)을 맞춥니다.
 *
 * @param {Object} bounds 피팅할 위경도 경계(`naver.maps.LatLngBounds`) 객체
 */
function fitBounds(bounds) {
    if (!state.map || !bounds) {
        return;
    }

    if (!isMapReadyForOverlays()) {
        naver.maps.Event.once(state.map, 'glReady', () => fitBounds(bounds));
        return;
    }

    const height = byId('map').clientHeight || window.innerHeight;
    const searchPanel = document.querySelector('.search-panel');
    const activeBanner = byId('active-route-banner');

    const bannerBottom = (activeBanner && !activeBanner.hidden)
        ? activeBanner.getBoundingClientRect().bottom + 16
        : 0;
    const top = Math.max(32, Math.min(Math.round(bannerBottom), Math.round(height * 0.22)));
    const bottom = Math.min((searchPanel?.offsetHeight || 120) + 24, height * 0.25);

    try {
        state.map.fitBounds(bounds, {
            top,
            right: 32,
            bottom,
            left: 32
        });
    } catch {
        // GL 전환 직전 fitBounds 예외 방어
    }
}

/**
 * [함수 역할]
 * 중심 좌표(`origin`)로부터 `radiusKm` 반경을 감싸는 남·북·서·동 위경도 사각 경계(Bounding Box)를 계산합니다.
 * - `fitRadius()`의 지도 줌 계산뿐만 아니라 `updateMarkers()`의 초고속 사전 필터링에도 함께 사용됩니다!
 */
function radiusExtent(origin, radiusKm) {
    const angle = radiusKm / 6371;
    const latDelta = angle * 180 / Math.PI;
    const lngDelta =
        Math.asin(
            Math.sin(angle) /
            Math.cos(origin.lat * Math.PI / 180)
        ) * 180 / Math.PI;

    return {
        south: origin.lat - latDelta,
        north: origin.lat + latDelta,
        west: origin.lng - lngDelta,
        east: origin.lng + lngDelta
    };
}

/**
 * [함수 역할]
 * 현재 선택된 반경(`5 km`, `10 km`, `20 km`, `전국` 등)이 한눈에 들어오도록 지도 카메라를 조정합니다.
 */
function fitRadius() {
    if (!state.map) {
        return;
    }

    let bounds;
    if (byId('radius').value === 'all') {
        if (!state.hospitals.length) {
            bounds = new naver.maps.LatLngBounds(
                new naver.maps.LatLng(33.1, 124.5),
                new naver.maps.LatLng(38.7, 131.9)
            );
        } else {
            const firstPos = getHospitalLatLng(state.hospitals[0]);
            bounds = new naver.maps.LatLngBounds(firstPos, firstPos);
            for (let i = 1; i < state.hospitals.length; i++) {
                bounds.extend(getHospitalLatLng(state.hospitals[i]));
            }
        }
    } else {
        const box = radiusExtent(state.origin, Number(byId('radius').value));
        bounds = new naver.maps.LatLngBounds(
            new naver.maps.LatLng(box.south, box.west),
            new naver.maps.LatLng(box.north, box.east)
        );
    }

    fitBounds(bounds);
    updateCircle();
}

/* --------------------------------------------------------------------------
   [21. 응급의료 서비스 이용 안내 모달(`#notice-dialog`) 및 검색 패널 컨트롤러]
   -------------------------------------------------------------------------- */

function noticeHidden() {
    try {
        return localStorage.getItem(NOTICE_KEY) === '1';
    } catch {
        return false;
    }
}

function openNotice() {
    byId('notice-skip').textContent = noticeHidden()
        ? '접속할 때 안내 다시 보기'
        : '이 브라우저에서 다시 보지 않기';

    byId('notice-dialog').showModal();
}

function initNotice() {
    const dialog = byId('notice-dialog');

    for (const id of ['notice-close', 'notice-confirm']) {
        byId(id).addEventListener('click', () => dialog.close());
    }

    byId('notice-skip').addEventListener('click', () => {
        try {
            if (noticeHidden()) {
                localStorage.removeItem(NOTICE_KEY);
            } else {
                localStorage.setItem(NOTICE_KEY, '1');
            }
        } catch {
            setStatus('이 브라우저에서는 안내 숨김 설정을 저장할 수 없습니다.');
        }
        dialog.close();
    });

    byId('notice-open')?.addEventListener('click', openNotice);

    const previewMode = new URLSearchParams(window.location.search).get('preview');
    if (!noticeHidden() && (!previewMode || previewMode === 'notice')) {
        openNotice();
    }
}

/**
 * [함수 역할]
 * 하단 검색 패널(`#search-panel`)의 접기/펼치기 버튼(`#search-toggle`)과 높이 측정(`--search-panel-height`)을 관리합니다.
 * - 모바일 주소창 변화 등으로 `resize`가 연속 발생할 때 `requestAnimationFrame`으로 프레임당 1회만 높이를 재측정합니다.
 */
function initPanels() {
    let heightRafId = 0;
    const updateHeight = () => {
        const panelEl = byId('search-panel');
        if (!panelEl) {
            return;
        }
        const height = panelEl.offsetHeight;
        document.documentElement.style.setProperty('--search-panel-height', `${height}px`);
    };

    const scheduleUpdateHeight = () => {
        if (heightRafId) {
            return;
        }
        heightRafId = requestAnimationFrame(() => {
            heightRafId = 0;
            updateHeight();
        });
    };

    const button = byId('search-toggle');
    const content = byId('search-content');
    const panel = byId('search-panel');

    if (button && content && panel) {
        button.addEventListener('click', () => {
            const collapsed = button.getAttribute('aria-expanded') === 'true';

            content.hidden = collapsed;
            panel.classList.toggle('is-collapsed', collapsed);
            button.setAttribute('aria-expanded', String(!collapsed));
            button.setAttribute('aria-label', `검색 ${collapsed ? '펼치기' : '접기'}`);
            button.textContent = collapsed ? '펼치기' : '접기';

            document.body.classList.toggle('search-collapsed', collapsed);
            if (collapsed) {
                byId('search-results').hidden = true;
            } else {
                updateSearch();
            }

            updateHeight();
        });
    }

    if ('ResizeObserver' in window && panel) {
        new ResizeObserver(scheduleUpdateHeight).observe(panel);
    }

    window.addEventListener('resize', scheduleUpdateHeight, { passive: true });
    updateHeight();
}

function blurKeyword() {
    const keyword = byId('keyword');
    if (document.activeElement === keyword) {
        keyword.blur();
    }
}

/**
 * [함수 역할]
 * 모바일 핀치 줌·지도 드래그 시 가상 키보드가 자동으로 내려가도록 포커스를 제어하고,
 * 한글(Hangul) IME 자모 조합 중 `Enter` 키 중복 제출 버그(`event.isComposing`)를 차단합니다.
 */
function initSearchFocus() {
    const keyword = byId('keyword');
    let multiTouch = false;

    const options = {
        capture: true,
        passive: true
    };

    document.addEventListener('pointerdown', event => {
        if (event.target !== keyword || (event.pointerType === 'touch' && !event.isPrimary)) {
            blurKeyword();
        }
    }, options);

    document.addEventListener('touchstart', event => {
        multiTouch = event.touches.length > 1;
        if (multiTouch || event.target !== keyword) {
            blurKeyword();
        }
    }, options);

    const updateTouches = event => {
        multiTouch = event.touches.length > 1;
    };

    document.addEventListener('touchend', updateTouches, options);
    document.addEventListener('touchcancel', updateTouches, options);

    keyword.addEventListener('focus', () => {
        if (multiTouch) {
            keyword.blur();
        }
    });

    keyword.addEventListener('keydown', event => {
        if (event.key === 'Enter' && (event.isComposing || event.keyCode === 229)) {
            event.preventDefault();
        }
    });

    document.addEventListener('wheel', event => {
        if (event.ctrlKey || event.target !== keyword) {
            blurKeyword();
        }
    }, options);

    byId('search-form').addEventListener('submit', blurKeyword);
}

/* --------------------------------------------------------------------------
   [22. 애플리케이션 진입점(`init`), 모바일 메뉴 원자적 이동 & 네이버 지도 마운트]
   -------------------------------------------------------------------------- */

/**
 * [함수 역할]
 * 모바일 좌측 상단 `☰` 햄버거 버튼(`index.html`의 `#mobile-menu-btn`) 클릭 시 도구 서랍(`#mobile-menu-drawer`)을 열고 닫습니다.
 */
function toggleMobileMenu(forceValue) {
    const drawer = byId('mobile-menu-drawer');
    const btn = byId('mobile-menu-btn');
    if (!drawer || !btn) {
        return;
    }
    state.mobileMenuOpen = typeof forceValue === 'boolean' ? forceValue : !state.mobileMenuOpen;
    drawer.hidden = !state.mobileMenuOpen;
    btn.setAttribute('aria-expanded', String(state.mobileMenuOpen));
    btn.setAttribute('aria-label', state.mobileMenuOpen ? '지도 도구 메뉴 닫기' : '지도 도구 메뉴 열기');
    if (!state.mobileMenuOpen) {
        closeRadius();
    }
}

/**
 * [함수 역할]
 * 최신 웹 표준 `Element.prototype.moveBefore()` API를 사용하여 DOM 요소를 포커스·CSS 트랜지션 상태 손실 없이
 * 원자적으로 이동시키고, 미지원 브라우저에서는 `insertBefore` / `appendChild`로 안전하게 폴백합니다.
 */
function moveElementPreservingState(parent, child, referenceNode = null) {
    if (!parent || !child) {
        return;
    }
    if (typeof parent.moveBefore === 'function' && parent.isConnected && child.isConnected) {
        try {
            parent.moveBefore(child, referenceNode);
            return;
        } catch {
            // 구형 엔진 또는 예외 상황 시 표준 DOM 이동 메서드로 폴백
        }
    }
    if (referenceNode) {
        parent.insertBefore(child, referenceNode);
    } else {
        parent.appendChild(child);
    }
}

/**
 * [함수 역할]
 * 화면 가로폭 `640px` 이하(아이폰·안드로이드 스마트폰)에서는 도구 모음(`#map-actions`)을
 * 좌측 상단 `☰` 드로어(`#mobile-menu-drawer`) 안으로 옮겨 하단 검색창이 지도를 가리지 않게 하고,
 * `641px` 이상(태블릿·데스크톱)에서는 다시 검색 패널 헤더(`#search-panel-heading`) 안으로 복귀시킵니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `init()` 초기 실행 및 `resize` rAF 병합 콜백
 * - 가리키는 DOM(Outgoing): `frontend/index.html`의 `#map-actions`, `#mobile-menu-drawer`, `#search-panel-heading`
 */
function syncMobileMenuPlacement() {
    const mapActions = byId('map-actions');
    const mobileDrawer = byId('mobile-menu-drawer');
    const searchHeading = byId('search-panel-heading');
    const searchToggle = byId('search-toggle');
    if (!mapActions || !mobileDrawer || !searchHeading) {
        return;
    }

    const isMobile = window.matchMedia('(max-width: 640px)').matches;
    if (isMobile) {
        if (mapActions.parentElement !== mobileDrawer) {
            moveElementPreservingState(mobileDrawer, mapActions, null);
        }
    } else {
        if (mapActions.parentElement !== searchHeading) {
            moveElementPreservingState(searchHeading, mapActions, searchToggle || null);
        }
        if (state.mobileMenuOpen) {
            toggleMobileMenu(false);
        }
    }
}

/**
 * [함수 역할]
 * 상세 시트 내 길찾기 엔진 선택(`#directions-api`), 경유지 선택(`#directions-waypoint`),
 * `'지도에 경로 표시'`(`#directions-draw-btn`), `'경로 지우기'`(`#directions-clear-btn` / `#active-route-clear`),
 * 그리고 점+경로 통합 `'초기화'`(`#active-route-reset` / `#origin-reset-btn`) 버튼 이벤트를 바인딩합니다.
 */
function initDirectionsControls() {
    byId('directions-api')?.addEventListener('change', () => {
        if (state.selected) {
            const isSelectedActive = Boolean(
                state.activeRoute && state.activeRoute.hospital?.id === state.selected.id
            );
            loadDirectionsForHospital(state.selected, isSelectedActive, { fitMap: false });
        }
    });

    byId('directions-waypoint')?.addEventListener('change', () => {
        if (state.selected) {
            const isSelectedActive = Boolean(
                state.activeRoute && state.activeRoute.hospital?.id === state.selected.id
            );
            loadDirectionsForHospital(state.selected, isSelectedActive, { fitMap: true });
        }
    });

    byId('directions-draw-btn')?.addEventListener('click', () => {
        if (!state.selected) {
            return;
        }
        const apiType = byId('directions-api')?.value || 'directions5';
        const option = state.selectedDirectionOption || 'trafast';
        const rawWaypoint = byId('directions-waypoint')?.value || '';

        if (state.sheetRouteData && state.sheetRouteData.code === 0 && state.sheetRouteData.path?.length) {
            drawRouteOnMap(state.selected, state.sheetRouteData, {
                fitMap: true,
                apiType,
                option,
                rawWaypoint
            });
            closeSheet();
        } else {
            loadDirectionsForHospital(state.selected, true, { fitMap: true }).then(() => {
                if (hasRenderedRoute()) {
                    closeSheet();
                }
            });
        }
    });

    byId('directions-clear-btn')?.addEventListener('click', clearRouteOnMap);
    byId('active-route-clear')?.addEventListener('click', clearRouteOnMap);
    byId('active-route-reset')?.addEventListener('click', resetOriginAndRoute);
    byId('origin-reset-btn')?.addEventListener('click', resetOriginAndRoute);
}

/**
 * [함수 역할]
 * 현재 테마(`state.darkMode`)에 맞춰 네이버 WebGL 벡터 지도(`naver.maps.Map`)를 `#map` 안에 마운트(또는 재마운트)합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `init()` 앱 최초 시작 시, 그리고 `applyTheme()`로 다크/라이트 테마를 전환할 때
 * - 바인딩하는 핵심 지도 이벤트:
 *   1) `'glReady'` : WebGL 스타일 로드 직후 반경 원·내 위치 점·마커·교통소통·활성 경로를 즉시 복원
 *   2) `'idle'`    : 카메라 이동이 멈췄을 때 `updateMarkers()`(Key-Diffing 마커 갱신) 실행
 *   3) `'rightclick'` / `'longtap'` : 데스크톱 우클릭 또는 모바일 롱탭 위치를 `setCustomOrigin()`으로 전달해
 *      출발점을 옮기고 활성 경로가 있으면 실시간 재탐색!
 */
function mountNaverMap() {
    if (typeof naver === 'undefined' || !naver.maps) {
        return;
    }

    const mapContainer = byId('map');
    let prevCenter = new naver.maps.LatLng(state.origin.lat, state.origin.lng);
    let prevZoom = 12;

    try {
        const currentCenter = state.map?.getCenter?.();
        if (currentCenter) {
            prevCenter = currentCenter;
        }
        const currentZoom = state.map?.getZoom?.();
        if (Number.isFinite(currentZoom)) {
            prevZoom = currentZoom;
        }
    } catch {
        // 이전 지도 좌표 조회 중 예외 무시
    }

    const savedActiveRoute = (
        state.activeRoute?.hospital &&
        state.activeRoute?.data?.code === 0 &&
        Array.isArray(state.activeRoute.data.path) &&
        state.activeRoute.data.path.length
    ) ? { ...state.activeRoute } : null;

    clearMarkers();
    try {
        state.circle?.setMap(null);
    } catch {
        // 무시
    }
    state.circle = null;
    state.circleKey = null;
    state.circleMap = null;

    try {
        state.locationMarker?.setMap(null);
    } catch {
        // 무시
    }
    state.locationMarker = null;

    removeRouteOverlays();
    try {
        state.trafficLayer?.setMap(null);
    } catch {
        // 무시
    }
    state.trafficLayer = null;

    try {
        state.map?.destroy?.();
    } catch {
        // 기존 맵 인스턴스 해제 중 예외 무시
    }

    const canvasHost = document.createElement('div');
    canvasHost.className = 'naver-map-canvas';
    canvasHost.style.width = '100%';
    canvasHost.style.height = '100%';
    mapContainer.replaceChildren(canvasHost);

    const baseOptions = {
        center: prevCenter,
        zoom: prevZoom,
        zoomControl: false,
        mapDataControl: false
    };

    if (!naver.maps.glEnabled && !state._glWaitFinished) {
        if (!state._glCallbackBound) {
            state._glCallbackBound = true;
            const finishGlWait = () => {
                if (state._glWaitFinished) {
                    return;
                }
                state._glWaitFinished = true;
                mountNaverMap();
            };
            const prevCallback = naver.maps.onJSContentLoaded;
            naver.maps.onJSContentLoaded = (...args) => {
                if (typeof prevCallback === 'function') {
                    prevCallback(...args);
                }
                finishGlWait();
            };
            setTimeout(finishGlWait, 1500);
        }
        return;
    }

    const glSubmoduleReady = Boolean(
        naver.maps.glEnabled &&
        (typeof naver.maps.Util?.glSupported !== 'function' || naver.maps.Util.glSupported())
    );

    let usingGlEngine = false;
    if (glSubmoduleReady) {
        try {
            const glOptions = {
                ...baseOptions,
                gl: true
            };
            if (state.darkMode && state.naverDarkStyleId) {
                glOptions.customStyleId = state.naverDarkStyleId;
            }
            state.map = new naver.maps.Map(canvasHost, glOptions);
            // 소프트웨어 렌더러·저사양 GPU 등에서 SDK가 예외 없이 내부적으로 2D 모드(renderMode=1)로 자동 폴백한 경우까지 정확히 판별
            usingGlEngine = state.map?.get?.('renderMode') === 2;
        } catch (err) {
            console.warn('네이버 GL 벡터 지도 초기화 실패 — 기본 2D 지도로 폴백합니다:', err);
            state.diagnostics.lastError = String(err?.message || err);
            state.map = new naver.maps.Map(canvasHost, baseOptions);
        }
    } else {
        state.map = new naver.maps.Map(canvasHost, baseOptions);
    }

    const syncMapOverlays = () => {
        if (!state.hasFitted && state.hospitals.length > 0) {
            state.hasFitted = true;
            fitRadius();
        }
        updateCircle();
        updateLocationMarker();
        updateMarkers();
        if (state.trafficEnabled) {
            syncTrafficLayer();
        }
    };

    if (usingGlEngine) {
        naver.maps.Event.addListener(state.map, 'glReady', () => {
            syncMapOverlays();
            if (savedActiveRoute) {
                drawRouteOnMap(savedActiveRoute.hospital, savedActiveRoute.data, {
                    fitMap: false,
                    apiType: savedActiveRoute.apiType,
                    option: savedActiveRoute.option,
                    rawWaypoint: savedActiveRoute.rawWaypoint
                });
            }
        });
    } else {
        naver.maps.Event.addListener(state.map, 'init', syncMapOverlays);
        syncMapOverlays();
        if (savedActiveRoute) {
            drawRouteOnMap(savedActiveRoute.hospital, savedActiveRoute.data, {
                fitMap: false,
                apiType: savedActiveRoute.apiType,
                option: savedActiveRoute.option,
                rawWaypoint: savedActiveRoute.rawWaypoint
            });
        }
    }

    naver.maps.Event.addListener(state.map, 'idle', () => {
        state.diagnostics.idleEventCount += 1;
        updateMarkers();
    });

    // 지도 우클릭(데스크톱) 또는 롱탭(모바일 터치)으로 즉시 임의 출발 기준점 지정 + 활성 경로 실시간 갱신
    naver.maps.Event.addListener(state.map, 'rightclick', event => {
        const coord = event?.coord || event?.latlng;
        if (coord && typeof coord.lat === 'function' && typeof coord.lng === 'function') {
            setCustomOrigin(coord.lat(), coord.lng(), '우클릭 지정 출발지');
        }
    });

    naver.maps.Event.addListener(state.map, 'longtap', event => {
        const coord = event?.coord || event?.latlng;
        if (coord && typeof coord.lat === 'function' && typeof coord.lng === 'function') {
            setCustomOrigin(coord.lat(), coord.lng(), '지정 출발지');
        }
    });

    for (const eventName of ['dragstart', 'zoom_changed']) {
        naver.maps.Event.addListener(state.map, eventName, blurKeyword);
    }

    state.diagnostics.getState = () => state;
    state.diagnostics.hasRenderedRoute = hasRenderedRoute;
    state.diagnostics.glRouteSourceId = GL_ROUTE_SOURCE_ID;
    state.diagnostics.getSchemas = () => ({
        EMERGENCY_BED_ITEMS,
        INPATIENT_BED_SECTIONS,
        EQUIPMENT_TABLE_ITEMS,
        SEVERE_DISEASE_GROUPS
    });
    window.__YONSEI119_DIAGNOSTICS__ = state.diagnostics;
}

/**
 * [함수 역할]
 * 다크 모드 / 라이트 모드 테마를 전환하고 `<meta name="color-scheme">`, `<html data-theme>`,
 * `<body class="dark-mode">`, 토글 버튼 아이콘, 네이버 지도 스타일을 한 번에 동기화합니다.
 *
 * [코드의 경로성]
 * - 호출하는 곳(Incoming): `initThemeToggle()` 초기 실행 및 `#theme-toggle` 버튼 클릭
 * - 연동되는 파일(Outgoing):
 *   1) `frontend/index.html`의 `<meta name="color-scheme">` 및 `<html data-theme>`
 *   2) `frontend/style.css`의 `:root:has(> head > meta[name="color-scheme"]...)` 및 `body.dark-mode`
 *   3) `mountNaverMap()` (다크/라이트 GL 지도 재마운트 및 활성 경로·마커 자동 복원)
 *
 * [수정 시 영향 및 예시]
 * - 최초 접속 시 기본 테마를 다크(`true`)가 아닌 라이트(`false`)로 시작하고 싶다면:
 *   `state.darkMode = false`와 아래 `initThemeToggle()`의 `applyTheme(false, false)`(및 `index.html` 초기 속성)를 변경하세요.
 *
 * @param {boolean} isDark 다크 모드 여부
 * @param {boolean} [remountMap=true] 네이버 지도를 새 테마로 재마운트할지 여부
 */
function applyTheme(isDark, remountMap = true) {
    state.darkMode = Boolean(isDark);
    const themeValue = state.darkMode ? 'dark' : 'light';

    document.documentElement.dataset.theme = themeValue;
    const colorSchemeMeta = document.querySelector('meta[name="color-scheme"]');
    if (colorSchemeMeta) {
        colorSchemeMeta.content = themeValue;
    }

    document.body.classList.toggle('dark-mode', state.darkMode);
    document.body.dataset.theme = themeValue;

    const toggleBtn = byId('theme-toggle');
    const moonIcon = byId('theme-icon-moon');
    const sunIcon = byId('theme-icon-sun');

    if (toggleBtn) {
        toggleBtn.setAttribute('aria-pressed', String(state.darkMode));
        const label = state.darkMode
            ? '라이트 모드로 전환 (현재 다크 모드)'
            : '다크 모드로 전환 (현재 라이트 모드)';
        toggleBtn.setAttribute('aria-label', label);
        toggleBtn.title = state.darkMode ? '라이트 모드로 전환' : '다크 모드로 전환';
    }

    if (moonIcon && sunIcon) {
        moonIcon.hidden = !state.darkMode;
        sunIcon.hidden = state.darkMode;
    }

    if (remountMap && state.map) {
        state.diagnostics.themeSwitchCount += 1;
        mountNaverMap();
    }
}

/**
 * [함수 역할]
 * 테마 토글 버튼(`index.html`의 `#theme-toggle`) 클릭 이벤트를 초기화합니다.
 */
function initThemeToggle() {
    let initialDark = true;
    try {
        const saved = localStorage.getItem('yonsei119-theme');
        if (saved === 'light') {
            initialDark = false;
        } else if (saved === 'dark') {
            initialDark = true;
        }
    } catch {
        // 스토리지 제한 환경에서는 기본 다크 테마 유지
    }
    applyTheme(initialDark, false);
    byId('theme-toggle')?.addEventListener('click', () => {
        const nextDark = !state.darkMode;
        try {
            localStorage.setItem('yonsei119-theme', nextDark ? 'dark' : 'light');
        } catch {
            // 스토리지 쓰기 실패 무시
        }
        applyTheme(nextDark, true);
    });
}

/**
 * [함수 역할]
 * 프론트엔드 애플리케이션의 **최초 실행 진입점(Main Entry Point)**입니다.
 * - 파이썬에 비유하면 `if __name__ == "__main__": main()`과 같은 역할로,
 *   `index.html` 파싱이 끝나자마자 `<script src="app.js" defer>`에 의해 파일 맨 마지막 줄에서 딱 1번 호출됩니다.
 * - 테마·검색·반경·길찾기·모바일 반응형 레이아웃·안내 모달을 초기화한 뒤,
 *   네이버 WebGL 지도를 마운트(`mountNaverMap`)하고 백엔드 병원 스냅샷(`loadData`), 역지오코딩(`updateOriginAddress`),
 *   현재 GPS 위치(`locate`)를 비동기로 구동합니다.
 */
function init() {
    initThemeToggle();
    initSearchFocus();
    initPanels();
    initRadius();
    initDirectionsControls();
    updateSummary();

    syncResponsiveSheetLayout();
    syncMobileMenuPlacement();
    initSheetDrag();
    initNotice();

    byId('mobile-menu-btn')?.addEventListener('click', () => {
        toggleMobileMenu();
    });

    byId('status-toast-close')?.addEventListener('click', () => {
        setStatus('');
    });

    byId('developer-mode').addEventListener('toggle', () => {
        if (byId('developer-mode').open && state.selected) {
            if (state.renderedDevHospitalRef !== state.selected) {
                renderDeveloperInspector(state.selected);
            }
        }
    });

    byId('cluster-close').addEventListener('click', () => {
        byId('cluster-dialog').close();
    });

    byId('cluster-zoom').addEventListener('click', () => {
        if (!state.clusterItems.length) {
            byId('cluster-dialog').close();
            return;
        }
        const firstPos = getHospitalLatLng(state.clusterItems[0]);
        const bounds = new naver.maps.LatLngBounds(firstPos, firstPos);
        for (let i = 1; i < state.clusterItems.length; i++) {
            bounds.extend(getHospitalLatLng(state.clusterItems[i]));
        }
        byId('cluster-dialog').close();
        fitBounds(bounds);
    });

    updateRadiusUI();

    byId('search-form').addEventListener('submit', event => {
        event.preventDefault();
        updateSearch();
        byId('search-results').querySelector('button')?.click();
    });

    byId('keyword').addEventListener('input', updateSearch);
    byId('keyword').addEventListener('focus', updateSearch);
    byId('refresh-button').addEventListener('click', () => loadData(true));
    byId('location-button').addEventListener('click', locate);
    byId('traffic-toggle')?.addEventListener('click', toggleTrafficLayer);
    byId('eta-rank-btn')?.addEventListener('click', () => {
        const box = byId('eta-rank-box');
        if (state.etaRankOpen && box && !box.hidden) {
            state.etaRankOpen = false;
            box.hidden = true;
            byId('eta-rank-btn')?.setAttribute('aria-expanded', 'false');
        } else {
            loadEtaRanking();
        }
    });

    for (const id of [
        'filter-regional',
        'filter-tertiary',
        'filter-medical',
        'filter-other'
    ]) {
        byId(id).addEventListener('change', () => {
            updateMarkers();
            if (state.etaRankOpen) {
                loadEtaRanking();
            }
        });
    }

    byId('sheet-close').addEventListener('click', closeSheet);
    byId('sheet-dim').addEventListener('click', closeSheet);

    document.addEventListener('keydown', event => {
        if (event.key === 'Escape') {
            if (document.querySelector('dialog[open]')) {
                return;
            }
            if (state.mobileMenuOpen) {
                toggleMobileMenu(false);
                return;
            }
            if (!byId('radius-popover').hidden) {
                closeRadius(true);
                return;
            }
            if (!byId('search-results').hidden) {
                byId('search-results').hidden = true;
                byId('keyword').focus();
                return;
            }
            if (state.selected) {
                closeSheet();
            }
        }

        if (
            event.key === 'Tab' &&
            state.selected &&
            !document.querySelector('dialog[open]')
        ) {
            const items = [
                ...byId('sheet').querySelectorAll(
                    'button:not(:disabled), a[href], input, select, summary, [tabindex="0"]'
                )
            ].filter(item =>
                item.getClientRects().length &&
                getComputedStyle(item).visibility !== 'hidden'
            );

            const first = items[0];
            const last = items.at(-1);

            if (event.shiftKey && document.activeElement === first) {
                event.preventDefault();
                last?.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault();
                first?.focus();
            }
        }
    });

    byId('location-button').disabled = true;
    byId('refresh-button').disabled = true;

    if (typeof naver === 'undefined' || !naver.maps) {
        setStatus('네이버 지도 SDK를 불러오지 못했습니다. 네트워크 또는 API 키 설정을 확인하세요.');
        return;
    }

    // 네이버 지도(NAVER Maps v3 GL) 인스턴스 초기화 (최초 기본: 다크 모드 customStyleId 적용)
    mountNaverMap();

    // 모바일 주소창 숨김/노출 및 창 크기 조절 시 프레임당 1회만 레이아웃을 동기화하도록 rAF 병합
    let resizeRafId = 0;
    window.addEventListener('resize', () => {
        if (resizeRafId) {
            return;
        }
        resizeRafId = requestAnimationFrame(() => {
            resizeRafId = 0;
            syncResponsiveSheetLayout();
            syncMobileMenuPlacement();
            updateMarkers();
            updateMarquees();
        });
    }, { passive: true });

    byId('location-button').disabled = false;
    byId('refresh-button').disabled = false;

    syncFrontendConfig();
    loadData(false);
    if (navigator.geolocation) {
        locate();
    } else {
        updateOriginAddress();
    }

    const previewMode = new URLSearchParams(window.location.search).get('preview');
    if (previewMode) {
        handlePreviewMode(previewMode);
    }
}

/**
 * [함수 역할]
 * URL 쿼리스트링(`?preview=detail`, `?preview=cluster`, `?preview=radius`, `?preview=notice`)을 통한
 * UI 미리보기·디버깅 모드를 지원합니다.
 */
function handlePreviewMode(mode) {
    if (mode === 'notice') {
        return;
    }

    byId('notice-dialog')?.close();

    if (mode === 'radius') {
        setTimeout(() => {
            byId('radius-button')?.click();
        }, 1500);
    } else if (mode === 'detail' || mode === 'hospital') {
        const checkAndOpen = setInterval(() => {
            if (state.hospitals.length > 0) {
                clearInterval(checkAndOpen);
                const target = state.hospitals.find(h => h.regional || h.type?.group === 'tertiary') || state.hospitals[0];
                openHospital(target);
            }
        }, 300);
        setTimeout(() => clearInterval(checkAndOpen), 12000);
    } else if (mode === 'cluster') {
        const checkAndCluster = setInterval(() => {
            if (state.hospitals.length > 2) {
                clearInterval(checkAndCluster);
                openCluster(state.hospitals.slice(0, 3));
            }
        }, 300);
        setTimeout(() => clearInterval(checkAndCluster), 12000);
    }
}

init();
