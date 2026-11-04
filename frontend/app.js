/* global naver */
'use strict';

/**
 * ==============================================================================
 * [파일 역할]
 * 연세 119 응급실 정보 시스템 프론트엔드 메인 애플리케이션 (frontend/app.js)
 *
 * [모노레포 아키텍처 및 리팩토링 내역]
 * 1. 백엔드(Java Spring Boot) 이관 및 레거시 JS 코드 정리 (요구사항 13, 14 반영):
 *    - 기존 브라우저에 노출되어 있던 공공데이터포털 `API_KEY`, `API_ROOT`, XML 파서(`readXml`, `fieldMap`),
 *      다중 페이지 수집기(`requestPage`, `requestAll`), 데이터 병합기(`groupById`),
 *      상급종합병원 분류 사전(`TERTIARY_2024_2026`, `REGION_PREFIX`, `classify`, `isRegional`) 및
 *      미사용 상수(`DISEASES`, `BED_GROUPS`, `EQUIPMENT_BASELINES`)를 프론트엔드에서 완전히 제거했습니다.
 *    - 대신 백엔드의 단일 통합 엔드포인트(`GET /api/hospitals`)로부터 정제된 JSON 스냅샷을 수신합니다.
 * 2. 카카오맵 폐기 및 네이버 지도(NAVER Maps JS v3) 전환 (요구사항 8 반영):
 *    - `naver.maps.Map`, `naver.maps.Circle`, `naver.maps.LatLngBounds`, `naver.maps.Polyline`,
 *      커스텀 `naver.maps.OverlayView` 서브클래스를 사용하여 지도 렌더링을 전면 재구축했습니다.
 * 3. 네이버 Directions 5 및 Directions 15 자동차 경로·예상 소요 시간(ETA) 연동 (요구사항 10 반영):
 *    - 백엔드 `GET /api/directions`를 호출하여 실시간 자동차 주행 시간, 실도로 거리, 택시비·통행료·유류비,
 *      턴바이턴(Turn-by-Turn) 경로 안내 및 지도 위 폴리라인(Polyline) 렌더링을 제공합니다.
 * 4. 국립중앙의료원 OpenAPI 설명서(V13) 더블체크 반영 (요구사항 3 반영):
 *    - 현장 실사 도메인 규칙 보존: 음수 병상 값(`< 0`)은 **'초과수용'**, 빈 값(`null`/`""`)은 **`'-'`**로 엄격 분리 표기.
 *    - 누락되어 있던 실시간 병상(`hv13`, `hv14`, `hv61`, `hv4`, `hv22`~`hv26`), 구급차 가용여부(`hvamyn`),
 *      응급실 게이트키퍼(`mkioskty28`), `N1` 상태값 처리를 모두 보완했습니다.
 * ==============================================================================
 */

/** 지도 위 병원 오버레이 카드의 가로/세로 크기 (충돌 클러스터링 계산 기준) */
const CARD_WIDTH = 168;
const CARD_HEIGHT = 60;

/** 이용 안내 모달 '다시 보지 않기' LocalStorage 저장 키 */
const NOTICE_KEY = 'emergency-notice-hidden-v1';

/**
 * 네이버 지도 Style Editor 다크 모드(야간 관제) Custom Style ID
 *
 * [수정 시 영향 및 예시]
 * - 네이버 클라우드 플랫폼 Style Editor에서 다른 다크 테마 스타일 ID로 교체하고 싶다면:
 *   변경 전: const DEFAULT_NAVER_DARK_STYLE_ID = '4b12b380-f70c-4d42-85df-ecf7961ff041';
 *   변경 후: const DEFAULT_NAVER_DARK_STYLE_ID = '새로운-스타일-ID';
 */
const DEFAULT_NAVER_DARK_STYLE_ID = '4b12b380-f70c-4d42-85df-ecf7961ff041';

/**
 * 기본 기준점 좌표 (사용자 GPS 미수신 시 서울시청 기준 폴백)
 *
 * [수정 시 영향 및 예시]
 * - 초기 폴백 좌표를 연세대학교 미래캠퍼스로 변경하고 싶다면:
 *   변경 전: const DEFAULT_ORIGIN = { lat: 37.5668, lng: 126.9786, label: '서울시청' };
 *   변경 후: const DEFAULT_ORIGIN = { lat: 37.31904, lng: 127.90123, label: '연세대 미래캠퍼스' };
 */
const DEFAULT_ORIGIN = {
    lat: 37.5668,
    lng: 126.9786,
    label: '서울시청'
};

/* --------------------------------------------------------------------------
   [1. 필수 특수 의료 장비 11종 필드 매핑 (EQUIPMENT)]
   - 국립중앙의료원 OpenAPI 명세서(V13) 기준 장비 가용 여부(Y/N/N1) 플래그
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
   - 국립중앙의료원 OpenAPI 공식 활용가이드(V13) 전수 대조를 통해 구축한 매핑 사전입니다.
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
   - 가용률(가용수 / 기준수) 계산 시 분모(기준) 데이터로 활용되는 코드 사전입니다.
   - OpenAPI 설명서(V13) 17페이지 대조 반영: hvs31을 '인공호흡기 조산아 기준'으로 통일했습니다.
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
 * [담당 기능]
 * - 네이버 지도 인스턴스(`map`), 반경 원(`circle`), 내 위치 마커(`locationMarker`),
 *   자동차 경로 폴리라인(`routePolyline`, `routeOutlinePolyline`), 현재 선택 병원(`selected`) 등을 관리합니다.
 */
const state = {
    map: null,
    circle: null,
    locationMarker: null,
    routePolyline: null,
    routeOutlinePolyline: null,
    routeSectionPolylines: [],
    activeRouteData: null,
    locating: false,
    origin: { lat: DEFAULT_ORIGIN.lat, lng: DEFAULT_ORIGIN.lng },
    locationKnown: false,
    locationAccuracy: null,
    locationUpdated: null,
    lastUpdated: null,
    hospitals: [],
    markers: [],
    selected: null,
    sources: {},
    controller: null,
    directionsController: null,
    loading: false,
    previousFocus: null,
    inflight: null,
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
        idleEventCount: 0,
        themeSwitchCount: 0,
        lastHospitalOpened: null,
        lastError: null
    }
};

const byId = id => document.getElementById(id);

/* --------------------------------------------------------------------------
   [5. 데이터 정제 및 결측치 방어 유틸리티]
   -------------------------------------------------------------------------- */

/**
 * [함수 역할]
 * 주어진 값이 결측치(null, undefined, 공백, 'null', '-' 등)인지 판별합니다.
 *
 * [수정 시 영향 및 예시]
 * - 숫자 `0`은 결측치가 아니므로 `false`를 반환합니다.
 * - 이를 수정하여 `0`까지 결측치로 취급하면 병상 0석(만실)이 미보고(`-`)로 왜곡되므로 절대 주의해야 합니다.
 *
 * @param {*} value 검사할 값
 * @returns {boolean} 결측치 여부
 */
function isMissing(value) {
    return value == null ||
        /^(?:\s*|null|undefined|none|n\/?a|nan|[-−–—]+)$/i.test(
            String(value).trim()
        );
}

/**
 * [함수 역할]
 * 결측치인 경우 `'미정보'`를 반환하고, 값이 있으면 양끝 공백을 제거한 문자열을 반환합니다.
 *
 * @param {*} value 원본 값
 * @returns {string} 표시용 문자열
 */
function displayValue(value) {
    return isMissing(value) ? '미정보' : String(value).trim();
}

/**
 * [함수 역할]
 * 여러 후보 값 중 결측치가 아닌 첫 번째 값을 반환합니다.
 *
 * @param {...*} values 후보 값 목록
 * @returns {*} 첫 번째 유효 값
 */
function firstReported(...values) {
    return values.find(value => !isMissing(value));
}

/**
 * [함수 역할]
 * OpenAPI 필드 코드(예: `hvec`)를 `'hvec(일반(응급실 일반병상))'` 형태의 한글 설명 포함 라벨로 변환합니다.
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
 * 중증질환 및 장비의 가용 여부(`Y`, `N`, `N1`, `가능`, `불가능` 등)를 판별합니다.
 *
 * [OpenAPI 설명서 더블체크 반영]
 * 실제 NEMC OpenAPI 응답에는 `'N'` 외에도 `'N1'`(장비 미보유/불가 코드)이 반환되는 경우가 있으므로,
 * `upper.startsWith('N')` 조건을 추가하여 `'N1'`도 정확히 `'불가능'`(`no`)으로 분류합니다.
 *
 * @param {*} raw 원본 상태값
 * @param {string} [yes='가능'] 가능 시 표시 텍스트
 * @param {string} [no='불가능'] 불가능 시 표시 텍스트
 * @returns {{text: string, className: string}} 상태 객체
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
 * 문자열을 부호 있는 정수(음수 포함)로 안전하게 파싱합니다.
 * 정수가 아니면 `null`을 반환합니다.
 *
 * @param {*} raw 원본 문자열/숫자
 * @returns {number|null} 파싱된 정수 또는 null
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
 * 경량 DOM 엘리먼트 생성 헬퍼 함수입니다.
 *
 * @param {string} tag 생성할 HTML 태그명
 * @param {string} [className=''] 적용할 CSS 클래스명
 * @param {string|number} [content=undefined] 삽입할 텍스트 콘텐츠
 * @returns {HTMLElement} 생성된 DOM 노드
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
 * 전광판(Ticker/Marquee) 애니메이션을 위한 래퍼 엘리먼트를 생성합니다.
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
 * 전광판(Marquee) 오버플로우 계산 및 CSS 애니메이션 변수를 동적으로 주입합니다.
 * `requestAnimationFrame` 내부에서 읽기(Read)와 쓰기(Write) 단계를 분리하여
 * 레이아웃 스래싱(Layout Thrashing)을 방지합니다.
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

function setStatus(message) {
    const statusEl = byId('status');
    if (!statusEl) {
        return;
    }
    statusEl.textContent = message;
    statusEl.hidden = !message;
}

function empty(container, message) {
    container.replaceChildren(node('p', 'empty-state', message));
}

/* --------------------------------------------------------------------------
   [6. 갱신 시간 및 반경 설정 UI 제어 (요구사항 12 반영)]
   -------------------------------------------------------------------------- */

/**
 * [함수 역할]
 * 우측 하단 검색 패널(`#search-panel`)의 새로고침 버튼 우측에 통합된
 * `갱신 시간`(`#updated-time`) 텍스트를 최신 동기화 시각으로 업데이트합니다.
 *
 * [요구사항 12 반영]
 * - 기존 좌측 상단에 있던 `'530개 기관'`(`#institution-count`) 업데이트 코드는 제거되었습니다.
 *
 * [수정 시 영향 및 예시]
 * - 시간 표시에 '초(second)' 단위까지 함께 보여주고 싶다면:
 *   예시(변경 전):
 *     hour: '2-digit', minute: '2-digit', hour12: false
 *   예시(변경 후):
 *     hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
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
 * 탐색 반경 버튼(`#radius-button`)과 팝오버 안내 문구를 현재 선택된 반경 값으로 갱신합니다.
 */
function updateRadiusUI() {
    const value = byId('radius').value;
    const label = value === 'all' ? '전국' : `${value} km`;

    byId('radius-value').textContent = label;
    byId('radius-button').setAttribute('aria-label', `탐색 반경 설정, 현재 ${label}`);
    byId('radius-button').title = `탐색 반경: ${label}`;

    byId('radius-help').textContent = value === 'all'
        ? '전국 기관을 표시합니다. 선택하면 바로 적용됩니다.'
        : `${state.locationKnown ? '내 위치' : DEFAULT_ORIGIN.label} 기준 · 선택하면 바로 적용됩니다.`;
}

function closeRadius(restoreFocus = false) {
    byId('radius-popover').hidden = true;
    byId('radius-button').setAttribute('aria-expanded', 'false');
    if (restoreFocus) {
        byId('radius-button').focus();
    }
}

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
   [7. 백엔드 Spring Boot API 연동 파이프라인 (요구사항 2, 13, 14 반영)]
   -------------------------------------------------------------------------- */

/**
 * [함수 역할]
 * 중복 요청 방지(In-flight Deduplication)를 적용한 데이터 로드 진입점입니다.
 *
 * @param {boolean} [forceRefresh=false] 캐시를 무시하고 강제 새로고침할지 여부
 * @returns {Promise<void>}
 */
function loadData(forceRefresh = false) {
    if (state.inflight) {
        return state.inflight;
    }

    state.inflight = fetchData(forceRefresh).finally(() => {
        state.inflight = null;
    });

    return state.inflight;
}

/**
 * [함수 역할]
 * Java Spring Boot 백엔드(`GET /api/hospitals`)로부터 전국 응급의료기관 통합 스냅샷을 수신합니다.
 *
 * [리팩토링 설명]
 * - 기존 브라우저에서 9개 XML 엔드포인트를 직접 호출하고 파싱하던 무거운 작업이
 *   Spring Boot 백엔드로 이관되어, 프론트엔드는 단일 JSON 응답만 받아 즉시 렌더링합니다.
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
    state.controller = new AbortController();

    setStatus('정보 확인 중…');

    try {
        const endpointUrl = forceRefresh ? '/api/hospitals?refresh=true' : '/api/hospitals';
        const response = await fetch(endpointUrl, {
            signal: state.controller.signal,
            cache: 'no-store',
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

        state.hospitals = Array.isArray(snapshot.hospitals) ? snapshot.hospitals : [];
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
                openHospital(fresh, false);
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
        state.loading = false;
        byId('refresh-button').disabled = false;
        document.body.classList.remove('is-loading');
        byId('developer-spinner').hidden = true;
    }
}

/* --------------------------------------------------------------------------
   [8. 네이버 지도(NAVER Maps v3) GIS 연동 및 지리 연산 (요구사항 8 반영)]
   -------------------------------------------------------------------------- */

/**
 * [함수 역할]
 * 두 위경도 좌표 간의 구면 직선 거리(Haversine Formula)를 km 단위로 계산합니다.
 *
 * @param {{lat: number, lng: number}} a 시작점 좌표
 * @param {{lat: number, lng: number}} b 도착점 좌표
 * @returns {number} 직선 거리 (km)
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
 * 현재 네이버 지도 인스턴스(`state.map`)가 오버레이(`Circle`, `Polyline`, `OverlayView`) 및
 * 좌표 투영(`Projection`)을 처리할 준비가 완료되었는지 확인합니다.
 *
 * [동작 원리]
 * - 일반 2D 지도 모드(`renderMode !== 2`)에서는 인스턴스 생성 즉시 준비 완료(`true`) 상태입니다.
 * - GL 커스텀 다크 스타일 모드(`renderMode === 2`)에서는 비동기 스타일(`style.json`) 로드가 끝나
 *   `isGLReady()`가 `true`가 된 시점부터 투영(`mapbox.project`) 및 도형 매니저(`overlayManager`)가 활성화됩니다.
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
 * 네이버 지도(`naver.maps.OverlayView`)를 상속하여 임의의 DOM 엘리먼트(병원 카드, 내 위치 마커)를
 * 특정 위경도 좌표 위에 렌더링하는 커스텀 오버레이 생성자입니다.
 *
 * [담당 기능]
 * - 기존 카카오맵의 `kakao.maps.CustomOverlay`를 네이버 지도 v3 방식으로 완벽히 대체합니다.
 * - `xAnchor`(기본 0.5), `yAnchor`(병원 카드는 1.0, 내 위치 점은 0.5) 비율에 맞춰
 *   CSS `transform: translate(...)`로 정확한 앵커 포인트를 맞춥니다.
 *
 * @param {Object} options 오버레이 옵션 (`position`, `content`, `map`, `xAnchor`, `yAnchor`, `zIndex`)
 * @returns {Object} 생성된 네이버 커스텀 오버레이(`naver.maps.OverlayView`) 인스턴스
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
            this.content.style.zIndex = String(this.zIndex);
            this.content.style.transform = `translate(${-this.xAnchor * 100}%, ${-this.yAnchor * 100}%)`;
            targetPane.appendChild(this.content);
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
                    this.content.style.left = `${Math.round(offset.x)}px`;
                    this.content.style.top = `${Math.round(offset.y)}px`;
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
 * GL 도형 오버레이(`Circle`, `Polyline`)가 지도 이동(pan/zoom) 중 `_triggerRedraw()`에 의해
 * 매 프레임 동일한 GeoJSON 좌표를 WebWorker로 재전송(`updateGeojsonSource`)하며 깜빡이거나
 * Mapbox GL의 `idle` 이벤트를 무한 재호출하는 현상을 원천 차단합니다.
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
 * 네이버 지도 위에 현재 기준점(`state.origin`) 중심의 탐색 반경 원(`naver.maps.Circle`)을 그립니다.
 *
 * [깜빡임(Flicker) 원천 차단 설계]
 * - 네이버 지도 GL 모드(`gl: true`)에서 `naver.maps.Circle`은 내부 Mapbox GL의 GeoJSON Source/Layer로 등록됩니다.
 * - 만약 `idle` 이벤트마다 기존 `Circle`을 `setMap(null)`로 지우고 새로 생성하면,
 *   Mapbox GL이 Source 변경을 감지하여 리페인트 후 다시 `idle` 이벤트를 발생시키는 무한 루프(깜빡임)가 일어납니다.
 * - 이를 방지하기 위해 `(지도 인스턴스, 기준 위도, 기준 경도, 반경 값, 다크모드 여부)`를 조합한
 *   `state.circleKey`가 동일할 때는 기존 `Circle`을 절대 파괴·재생성하지 않고 즉시 반환(No-op)합니다.
 *
 * [수정 시 영향 및 예시]
 * - 반경 원의 테두리 색상을 파란색(`#2873df`)에서 초록색(`#03c75a`)으로 바꾸고 싶다면
 *   아래 `strokeColor`와 `fillColor` 속성값을 변경하면 됩니다.
 *
 * @param {boolean} [force=false] 강제로 반경 원을 재생성할지 여부
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
 * 사용자의 현재 GPS 위치(`state.origin`)에 파란색 맥동 마커(`.location-marker`)를 표시합니다.
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
 * 브라우저 Geolocation API를 통해 사용자의 현재 GPS 위치를 측정하고 지도 중심과 반경을 갱신합니다.
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

            state.locationKnown = true;
            state.locationAccuracy = position.coords.accuracy;
            state.locationUpdated = new Date(position.timestamp || Date.now());

            updateRadiusUI();
            updateLocationMarker();

            if (!state.selected) {
                fitRadius();

                if (byId('radius').value === 'all') {
                    state.map.panTo(new naver.maps.LatLng(state.origin.lat, state.origin.lng));
                }
            } else {
                updateCircle();
            }

            updateMarkers();
            finish();
        },
        error => {
            const reason = error.code === 1
                ? '위치 권한이 허용되지 않았습니다.'
                : '현재 위치를 확인하지 못했습니다.';

            updateRadiusUI();
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

/* --------------------------------------------------------------------------
   [9. 병원 지도 카드 렌더링 및 클러스터링]
   -------------------------------------------------------------------------- */

function visualGroup(hospital) {
    return hospital.regional ? 'regional' : hospital.type.group;
}

function hospitalLabel(hospital) {
    return hospital.regional
        ? `권역응급의료센터 · ${hospital.type.label}`
        : hospital.type.label;
}

function matchesFilters(hospital, enabled) {
    return Boolean(
        enabled[hospital.type.group] ||
        (hospital.regional && enabled.regional)
    );
}

function groupCards(points) {
    const groups = [];
    for (const point of points) {
        const near = groups.find(group =>
            Math.abs(group.x - point.x) < CARD_WIDTH + 10 &&
            Math.abs(group.y - point.y) < CARD_HEIGHT + 14
        );

        if (near) {
            near.items.push(point.hospital);
        } else {
            groups.push({
                ...point,
                items: [point.hospital]
            });
        }
    }
    return groups;
}

function bindHospitalCard(card, open) {
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
        open();
    });
}

/**
 * [함수 역할]
 * 위경도 좌표를 화면 픽셀 좌표(`{x, y}`)로 변환합니다.
 * 네이버 지도 `Projection.fromCoordToOffset()`을 우선 사용하며,
 * 투영 객체가 아직 준비되지 않은 초기 프레임에서는 줌 레벨 기반 웹 메르카토르 픽셀 근사식을 사용합니다.
 */
function coordToScreenPoint(lat, lng) {
    const latLng = new naver.maps.LatLng(lat, lng);
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
 * 네이버 지도 위에 렌더링된 모든 병원 카드 커스텀 오버레이(`state.markers`)를 안전하게 제거하고 초기화합니다.
 */
function clearMarkers() {
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
 * 현재 지도 뷰포트 영역과 활성화된 필터(`권역응급`, `상급종합`, `의료원`, `일반·기타`) 및 탐색 반경에 맞춰
 * 네이버 지도 위에 병원 카드 오버레이를 렌더링합니다.
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

    clearMarkers();
    state.diagnostics.markersUpdatedCount += 1;

    const radius = byId('radius').value;
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

    const candidates = state.hospitals
        .filter(hospital => {
            const isSelected = Boolean(state.selected && hospital.id === state.selected.id);
            if (!isSelected) {
                if (!matchesFilters(hospital, enabled)) {
                    return false;
                }
                if (radius !== 'all' && distanceKm(state.origin, hospital) > Number(radius)) {
                    return false;
                }
            }
            if (!viewport) {
                return true;
            }
            const pos = new naver.maps.LatLng(hospital.lat, hospital.lng);
            return typeof viewport.hasLatLng === 'function'
                ? viewport.hasLatLng(pos)
                : viewport.hasPoint(pos);
        })
        .sort((a, b) =>
            rank[visualGroup(a)] - rank[visualGroup(b)] ||
            distanceKm(state.origin, a) - distanceKm(state.origin, b)
        );

    const points = candidates.map(hospital => {
        const point = coordToScreenPoint(hospital.lat, hospital.lng);
        return {
            x: point.x,
            y: point.y,
            hospital
        };
    });

    const groups = groupCards(points);

    for (const group of groups) {
        const hospital = group.items[0];
        const multiple = group.items.length > 1;

        const card = node('button', `hospital-card ${visualGroup(hospital)}`);
        card.type = 'button';

        card.title = multiple
            ? group.items.map(item => item.name).join(' · ')
            : `${hospital.name} · ${hospitalLabel(hospital)}`;

        card.setAttribute(
            'aria-label',
            multiple
                ? `주변 ${group.items.length}개 병원 목록 열기`
                : `${hospital.name} 상세 정보 열기`
        );

        const shortType = hospital.regional
            ? '권역응급'
            : hospital.type.label;

        card.append(
            node(
                'strong',
                '',
                multiple ? `주변 ${group.items.length}개 병원` : hospital.name
            ),
            node(
                'small',
                '',
                multiple
                    ? '목록에서 병원 선택 ›'
                    : `${shortType} · 응급실 ${bedValue(hospital.beds?.hvec)}`
            )
        );

        bindHospitalCard(card, () => {
            if (multiple) {
                openCluster(group.items);
            } else {
                openHospital(hospital);
            }
        });

        const overlay = createNaverDomOverlay({
            map: state.map,
            position: new naver.maps.LatLng(hospital.lat, hospital.lng),
            content: card,
            xAnchor: 0.5,
            yAnchor: 1.0,
            zIndex: 5
        });

        state.markers.push(overlay);
    }

    byId('map-count').textContent =
        `현재 화면 ${candidates.length}개 기관 · 겹치는 카드는 묶어서 표시`;
}

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
   [10. 실시간 병원 검색 및 자동완성]
   -------------------------------------------------------------------------- */

/**
 * [함수 역할]
 * 검색창 등에서 특정 병원을 선택했을 때 지도의 중심 좌표(Center)와 줌 레벨(Zoom)을
 * 단일 원자적(Atomic) 카메라 트랜지션(`morph`)으로 이동시킵니다.
 *
 * [버그 수정 배경 — 최초 검색 시 지도 미이동 현상 해결]
 * - 네이버 지도 WebGL 엔진(`maps-gl.js`)에서 `panTo(coord)` 직후 동기적으로 `setZoom(15)`를 호출하면,
 *   첫 번째 검색 시(현재 줌이 15가 아닐 때) `setZoom(15)` 내부의 `zoomBy()`가
 *   아직 `panTo`가 시작되기 전의 '이전 지도 중심점(`_mapModel.getCenterPixel()`)'을 기준으로
 *   `easeTo({ around: 이전중심점, zoom: 15 })`를 덮어써 버려 `panTo` 애니메이션이 취소됩니다.
 * - 반면 두 번째 검색 시에는 이미 줌이 15이므로 `setZoom(15)`가 `if (i === n) return false;`로 조기 종료되어
 *   `panTo`가 취소되지 않고 정상 이동했던 것입니다.
 * - 이를 해결하기 위해 중심 이동과 줌 변경을 하나의 `easeTo({ center, zoom })`로 결합 수행하는
 *   `state.map.morph(targetLatLng, targetZoom)`를 사용하여 첫 번째 검색부터 100% 정확히 이동시킵니다.
 *
 * @param {Object} hospital 이동할 대상 병원 객체 (`lat`, `lng` 포함)
 * @param {number} [targetZoom=15] 이동 후 목표 줌 레벨
 */
function focusHospitalOnMap(hospital, targetZoom = 15) {
    if (!state.map || !hospital || !Number.isFinite(hospital.lat) || !Number.isFinite(hospital.lng)) {
        return;
    }

    // 사용자가 명시적으로 병원을 검색·선택했으므로 초기 반경 자동 맞춤(fitRadius)이 덮어쓰지 않도록 고정
    state.hasFitted = true;

    const targetLatLng = new naver.maps.LatLng(hospital.lat, hospital.lng);

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

function updateSearch() {
    closeRadius();

    const list = byId('search-results');
    const keywordInput = byId('keyword');
    const query = keywordInput
        .value
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
                .includes(query)
        )
        .slice(0, 25);

    if (!matches.length) {
        list.append(node('li', 'empty-state', '일치하는 기관이 없습니다.'));
        return;
    }

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

    list.append(fragment);
}

/* --------------------------------------------------------------------------
   [11. 네이버 Directions 5 & Directions 15 자동차 경로 탐색 및 ETA (요구사항 10 반영)]
   -------------------------------------------------------------------------- */

/**
 * [함수 역할]
 * 지도 위에 그려진 네이버 자동차 경로 폴리라인(`routePolyline`, `routeOutlinePolyline`,
 * 구간별 혼잡도 폴리라인 `routeSectionPolylines`)과 상단 플로팅 배너(`#active-route-banner`)를 제거합니다.
 */
function clearRouteOnMap() {
    state.routePolyline?.setMap(null);
    state.routePolyline = null;
    state.routeOutlinePolyline?.setMap(null);
    state.routeOutlinePolyline = null;

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
 * 네이버 Directions API의 실시간 혼잡도 코드(`congestion`: 1 원활, 2 서행, 3 정체)에 대응하는
 * 폴리라인 색상·외곽선 색상·한글 상태명을 반환합니다.
 *
 * @param {number} congestion 혼잡도 코드 (`0`: 정보없음, `1`: 원활, `2`: 서행, `3`: 정체)
 * @returns {{label: string, badgeClass: string, strokeColor: string, outlineColor: string, zIndex: number}}
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
 * 네이버 Directions 5 / 15 API가 반환한 경로 좌표 배열(`data.path`)과 구간별 실시간 혼잡도(`data.sections`)를
 * 사용하여 네이버 지도(`state.map`) 위에 실시간 교통 소통 색상(원활: 초록 `#03c75a`, 서행: 주황 `#f59e0b`,
 * 정체: 빨강 `#ef4444`)이 반영된 다중 폴리라인을 그리고, 출발지와 도착 병원이 한눈에 보이도록 피팅합니다.
 *
 * [수정 시 영향 및 예시]
 * - 정체 구간 색상을 빨간색(`#ef4444`)에서 자주색(`#be123c`)으로 바꾸고 싶다면
 *   위 `getCongestionStyle(3)`의 `strokeColor` 값을 수정하면 됩니다.
 *
 * @param {Object} hospital 목적지 병원 객체
 * @param {Object} data `/api/directions` 응답 DTO 객체
 */
function drawRouteOnMap(hospital, data) {
    if (!state.map || !data || !Array.isArray(data.path) || !data.path.length) {
        return;
    }

    if (!isMapReadyForOverlays()) {
        naver.maps.Event.once(state.map, 'glReady', () => drawRouteOnMap(hospital, data));
        return;
    }

    clearRouteOnMap();

    const latLngPath = data.path.map(([lng, lat]) => new naver.maps.LatLng(lat, lng));

    // 1. 전체 경로 기본 외곽선 폴리라인 (지도 배경과 명확히 구분되도록 진한 테두리 역할)
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

    // 2. 전체 경로 기본 베이스 라인 (section 미포함 일반 도로 구간까지 끊김 없이 원활 그린으로 연결)
    state.routePolyline = guardGlShapeRedraw(new naver.maps.Polyline({
        map: state.map,
        path: latLngPath,
        strokeColor: '#03c75a',
        strokeWeight: 5,
        strokeOpacity: 0.95,
        strokeLineCap: 'round',
        strokeLineJoin: 'round',
        zIndex: 95
    }));

    // 3. 구간별 실시간 혼잡도(data.sections: 서행 2 -> 정체 3 순서로 레이어링하여 정체 빨간색이 최상단에 표시되도록 함)
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

    // 4. 상단 플로팅 활성 경로 배너 표시
    const banner = byId('active-route-banner');
    if (banner) {
        byId('active-route-api').textContent = data.apiLabel || 'Directions 5';
        byId('active-route-name').textContent = `${hospital.name}`;
        byId('active-route-meta').textContent = `${data.durationText} · ${data.distanceKm} km`;
        banner.hidden = false;
    }

    const clearBtn = byId('directions-clear-btn');
    if (clearBtn) {
        clearBtn.hidden = false;
    }

    // 5. 경로 전체가 보이도록 지도 영역 피팅
    const bounds = new naver.maps.LatLngBounds(latLngPath[0], latLngPath[0]);
    for (const pt of latLngPath) {
        bounds.extend(pt);
    }
    fitBounds(bounds);
}

/**
 * [함수 역할]
 * 백엔드 `/api/directions` 엔드포인트를 호출하여 현재 기준 위치(`state.origin`)에서
 * 선택된 병원(`hospital`)까지의 네이버 **Directions 5** 또는 **Directions 15**
 * 자동차 주행 경로 및 예상 소요 시간(ETA)을 조회하고 바텀시트에 렌더링합니다.
 *
 * @param {Object} hospital 목적지 병원 객체
 * @param {boolean} [autoDraw=false] 조회 완료 즉시 지도 위에 폴리라인을 그릴지 여부
 */
async function loadDirectionsForHospital(hospital, autoDraw = false) {
    const container = byId('directions-result');
    const statusBadge = byId('directions-status-badge');
    if (!container || !hospital) {
        return;
    }

    const apiType = byId('directions-api')?.value || 'directions5';
    const option = byId('directions-option')?.value || 'trafast';

    state.directionsController?.abort();
    state.directionsController = new AbortController();

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

        const response = await fetch(`/api/directions?${params.toString()}`, {
            signal: state.directionsController.signal,
            headers: { Accept: 'application/json' }
        });

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
        state.activeRouteData = data;

        if (data.code !== 0) {
            if (statusBadge) {
                statusBadge.textContent = '탐색 불가';
            }
            container.replaceChildren(
                node('p', 'empty-state', `자동차 경로 안내 불가: ${data.message || '경로를 찾을 수 없습니다.'}`)
            );
            return;
        }

        if (statusBadge) {
            statusBadge.textContent = `${data.apiLabel} · ${data.optionLabel}`;
        }

        // 바텀시트 상단 거리 요약 텍스트에 자동차 예상 소요 시간과 실도로 거리도 함께 병기
        const straightKm = distanceKm(state.origin, hospital).toFixed(1);
        const originName = state.locationKnown ? '내 위치' : DEFAULT_ORIGIN.label;
        byId('hospital-distance').textContent =
            `${originName}에서 직선 ${straightKm} km · 자동차 ${data.durationText} (실도로 ${data.distanceKm} km)`;

        // KPI 3열 그리드 생성 (예상 소요 시간 / 실도로 주행 거리 / 예상 요금)
        const kpiGrid = node('div', 'directions-kpi-grid');

        const etaCard = node('div', 'directions-kpi-card');
        etaCard.append(
            node('span', 'directions-kpi-label', '예상 소요 시간 (ETA)'),
            node('strong', 'directions-kpi-value highlight', data.durationText),
            node('span', 'directions-kpi-sub', `${data.apiLabel} · ${data.optionLabel}`)
        );

        const distCard = node('div', 'directions-kpi-card');
        distCard.append(
            node('span', 'directions-kpi-label', '실도로 주행 거리'),
            node('strong', 'directions-kpi-value', `${data.distanceKm} km`),
            node('span', 'directions-kpi-sub', `직선거리 ${straightKm} km`)
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
        container.replaceChildren(kpiGrid);

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
            // 정체(3)·서행(2) 구간은 모두 우선 포함하고, 원활(1) 구간은 주요 도로 위주로 선별 후 주행 순서대로 정렬
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
            drawRouteOnMap(hospital, data);
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
   [12. API 시각 파싱 및 실시간 진료 공지 렌더링]
   -------------------------------------------------------------------------- */

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
   [13. 중증응급질환 28종 분류 체계 및 공지 교차 검증 (OpenAPI V13 더블체크 반영)]
   - 요구사항 3 반영: OpenAPI 명세서 21페이지의 `MKioskTy28` (응급실 Emergency gate keeper)을
     누락 없이 `SEVERE_DISEASE_GROUPS`에 추가했습니다.
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
   [14. 실시간 가용 병상 렌더링 엔진 (현장 실사 도메인 규칙 & OpenAPI V13 더블체크 반영)]
   - 음수 값(< 0)은 '초과수용', 공란/null은 '-'로 엄격 분리 표기합니다.
   - OpenAPI 설명서 대조 결과 누락되어 있던 격리진료구역(hv13/hv14), 외상환자진료구역(hv61),
     정형외과 입원실(hv4), 감염병 전담병상(hv22~hv26)을 모두 보드에 반영했습니다.
   -------------------------------------------------------------------------- */

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
 * 병상 가용 수치(`current`)와 기준 수치(`baseline`)를 비교하여 상태 배지를 반환합니다.
 *
 * [현장 실사 도메인 규칙 (요구사항 3)]
 * 1. `isBlankOrNull(rawVal)` -> `'-'` (결측치를 0으로 왜곡하지 않음)
 * 2. `current < 0` -> `'초과수용 {overflow}/{baseline}'` (음수 값은 기준 병상을 초과하여 수용 중임을 뜻함)
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

function renderBeds(hospital) {
    const bedTimeEl = byId('bed-time');
    if (bedTimeEl) {
        bedTimeEl.textContent = '보고 시각 미정보';
    }

    renderRawBeds(hospital);
    renderErBeds(hospital);
    renderInpatientBeds(hospital);

    const fields = hospital.beds || {};
    const reported = parseApiDate(fields.hvidate);
    if (reported && bedTimeEl) {
        bedTimeEl.textContent = `보고 ${reported.display}`;
    }
}

/* --------------------------------------------------------------------------
   [15. 개발자 모드: API 원본 데이터(Raw Fields) 인스펙터]
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
   [16. 가용 장비 현황 (11대 핵심 응급의료 장비 보드 - OpenAPI V13 더블체크 반영)]
   - 요구사항 3 반영: OpenAPI 명세서의 `hvamyn` (구급차 가용여부)를 보드에 추가했습니다.
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
        ? (isBlankOrNull(rawBase) ? '-' : String(rawBase).trim())
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
   [17. 기관 기본정보 및 외상센터 정보 렌더링]
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
   [18. 긴급 연락처 연결 및 3대 내비게이션 딥링크 연동]
   -------------------------------------------------------------------------- */

function contactLink(text, href, className) {
    const link = node('a', className, text);
    link.href = href;
    return link;
}

function isAppleTouchDevice() {
    const userAgent = navigator.userAgent || '';
    return (
        /iPad|iPhone|iPod/.test(userAgent) ||
        (/Macintosh/.test(userAgent) && navigator.maxTouchPoints > 1)
    );
}

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
   [19. 병원 상세정보 바텀시트(Bottom Sheet) 인터랙션 및 접근성]
   -------------------------------------------------------------------------- */

function openHospital(hospital, focus = true) {
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

    if (state.selected?.id !== hospital.id) {
        byId('developer-mode').open = false;
    }

    state.selected = hospital;
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
    const originName = state.locationKnown ? '내 위치' : DEFAULT_ORIGIN.label;
    byId('hospital-distance').textContent = `${originName}에서 직선거리 ${distance} km`;

    renderContacts(hospital);
    renderMessages(hospital);
    renderBeds(hospital);
    renderEquipment(hospital);
    renderSevere(hospital);
    renderBasic(hospital);
    renderRawSevere(hospital);
    renderRawMessages(hospital);

    // 네이버 Directions 5 / 15 자동차 경로 및 예상 소요 시간(ETA) 자동 조회
    loadDirectionsForHospital(hospital, false);

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

function setBackgroundInert(value) {
    for (const selector of ['#map', '#search-panel']) {
        const el = document.querySelector(selector);
        if (el) {
            el.inert = value;
        }
    }
}

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
 *   변경 후: const LARGE_MONITOR_MIN_WIDTH = 1600; (style.css의 @media (min-width: 1200px)도 함께 변경)
 */
const LARGE_MONITOR_MIN_WIDTH = 1200;
const LARGE_MONITOR_MIN_HEIGHT = 680;
const MOBILE_MAX_WIDTH = 640;

/**
 * [함수 역할]
 * 현재 브라우저 뷰포트 크기 및 입력 장치 특성에 따라 병원 상세창(`#sheet`)의 레이아웃 모드를
 * 동적으로 판별하고 `document.body`의 상태 속성(`data-sheet-layout`, `.touch-device`)을 동기화합니다.
 *
 * [3단계 동적 레이아웃]
 * 1. `mobile` (모바일 바텀시트):
 *    - 화면 가로 `640px` 이하이거나, 마우스(`any-pointer: fine`) 없이 터치(`pointer: coarse`)만 사용하는 스마트폰/태블릿 환경
 *    - 하단에서 위로 올라오는 기존 바텀시트 형태를 그대로 유지합니다.
 * 2. `modal` (대화면 모니터 중앙 둥근모서리 사각 모달):
 *    - 데스크톱/노트북 모니터 환경에서 화면 가로 `1200px` 이상 & 세로 `680px` 이상일 때
 *    - 화면 정중앙에 둥근 모서리 사각 카드가 나타나고 내부 콘텐츠는 스크롤 처리됩니다.
 * 3. `slider` (작은 모니터 / 창 축소 시 우측 슬라이더 카드):
 *    - 데스크톱/노트북 환경에서 창 너비가 `1200px` 미만이거나 높이가 `680px` 미만일 때
 *    - 기존처럼 우측에서 좌측으로 나오는 슬라이더 드로어 형태로 동작합니다.
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
   [20. 네이버 지도 뷰포트 영역 계산 및 반경 피팅]
   -------------------------------------------------------------------------- */

/**
 * [함수 역할]
 * 상하단 오버레이 패널 높이를 감안하여 네이버 지도 뷰포트 패딩을 적용하고 경계 영역(`bounds`)을 피팅합니다.
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

    const top = 32;
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
            const first = state.hospitals[0];
            bounds = new naver.maps.LatLngBounds(
                new naver.maps.LatLng(first.lat, first.lng),
                new naver.maps.LatLng(first.lat, first.lng)
            );
            for (const hospital of state.hospitals) {
                bounds.extend(new naver.maps.LatLng(hospital.lat, hospital.lng));
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
   [21. 응급의료 서비스 이용 안내 모달 및 패널 컨트롤러]
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

function initPanels() {
    const updateHeight = () => {
        const height = byId('search-panel').offsetHeight;
        document.documentElement.style.setProperty('--search-panel-height', `${height}px`);
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

    if ('ResizeObserver' in window) {
        new ResizeObserver(updateHeight).observe(byId('search-panel'));
    }

    window.addEventListener('resize', updateHeight);
    updateHeight();
}

function blurKeyword() {
    const keyword = byId('keyword');
    if (document.activeElement === keyword) {
        keyword.blur();
    }
}

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

    // Modern Web Guidance: 한글(Hangul) IME 조합 중 Enter 키가 중복 제출을 일으키지 않도록 방어
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
   [22. 애플리케이션 진입점 및 네이버 지도 초기화]
   -------------------------------------------------------------------------- */

function initDirectionsControls() {
    byId('directions-api')?.addEventListener('change', () => {
        if (state.selected) {
            loadDirectionsForHospital(state.selected, Boolean(state.routePolyline));
        }
    });

    byId('directions-option')?.addEventListener('change', () => {
        if (state.selected) {
            loadDirectionsForHospital(state.selected, Boolean(state.routePolyline));
        }
    });

    byId('directions-draw-btn')?.addEventListener('click', () => {
        if (!state.selected) {
            return;
        }
        if (state.activeRouteData && state.activeRouteData.code === 0 && state.activeRouteData.path?.length) {
            drawRouteOnMap(state.selected, state.activeRouteData);
            closeSheet();
        } else {
            loadDirectionsForHospital(state.selected, true).then(() => {
                if (state.routePolyline) {
                    closeSheet();
                }
            });
        }
    });

    byId('directions-clear-btn')?.addEventListener('click', clearRouteOnMap);
    byId('active-route-clear')?.addEventListener('click', clearRouteOnMap);
}

/**
 * [함수 역할]
 * 현재 테마(`state.darkMode`)에 맞춰 네이버 지도(`naver.maps.Map`) 인스턴스를 마운트하거나 재마운트합니다.
 *
 * [동작 원리]
 * - 다크 모드(`state.darkMode === true`)일 때:
 *   네이버 지도 Style Editor의 GL 커스텀 스타일(`gl: true`, `customStyleId: '4b12b380-f70c-4d42-85df-ecf7961ff041'`)을
 *   적용하여 야간 관제용 다크 지도를 렌더링합니다.
 * - 라이트 모드(`state.darkMode === false`)일 때:
 *   표준 라이트 지도를 렌더링하며, 전환 직전의 중심 좌표(`center`), 줌 레벨(`zoom`),
 *   탐색 반경 원(`circle`), 내 위치 마커(`locationMarker`), 병원 마커 카드(`markers`),
 *   자동차 주행 경로 폴리라인(`routePolyline`)을 그대로 보존·복원합니다.
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

    const hadActiveRoute = Boolean(
        state.routePolyline &&
        state.selected &&
        state.activeRouteData?.code === 0 &&
        Array.isArray(state.activeRouteData.path) &&
        state.activeRouteData.path.length
    );

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

    try {
        state.routeOutlinePolyline?.setMap(null);
        state.routePolyline?.setMap(null);
    } catch {
        // 무시
    }
    state.routeOutlinePolyline = null;
    state.routePolyline = null;

    try {
        state.map?.destroy?.();
    } catch {
        // 기존 맵 인스턴스 해제 중 예외 무시
    }

    // 이전 MapView.destroy()로 인한 잔여 속성/이벤트 충돌을 원천 차단하기 위해 새 내부 캔버스 호스트 생성
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

    // 다크 모드와 라이트 모드 모두 동일한 WebGL 벡터 엔진(gl: true)을 사용하여
    // 마우스 스크롤 휠 확대/축소량(1/200 소수점 단위 줌)과 60fps 애니메이션 부드러움을 완벽히 통일합니다.
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
            usingGlEngine = true;
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
    };

    if (usingGlEngine) {
        naver.maps.Event.addListener(state.map, 'glReady', () => {
            syncMapOverlays();
            if (hadActiveRoute && state.selected && state.activeRouteData) {
                drawRouteOnMap(state.selected, state.activeRouteData);
            }
        });
    } else {
        naver.maps.Event.addListener(state.map, 'init', syncMapOverlays);
        syncMapOverlays();
        if (hadActiveRoute && state.selected && state.activeRouteData) {
            drawRouteOnMap(state.selected, state.activeRouteData);
        }
    }

    naver.maps.Event.addListener(state.map, 'idle', () => {
        state.diagnostics.idleEventCount += 1;
        updateMarkers();
    });

    for (const eventName of ['dragstart', 'zoom_changed']) {
        naver.maps.Event.addListener(state.map, eventName, blurKeyword);
    }

    state.diagnostics.getState = () => state;
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
 * 다크 모드 / 라이트 모드 테마 상태를 전환하고 UI 클래스, 좌측 하단 버튼 아이콘, 네이버 지도 스타일을 동기화합니다.
 *
 * [수정 시 영향 및 예시]
 * - 최초 접속 시 기본 테마를 다크(`true`)가 아닌 라이트(`false`)로 바꾸고 싶다면
 *   `state.darkMode` 초기값과 `init()` 내부의 `applyTheme(true, false)`를 `false`로 변경하면 됩니다.
 *
 * @param {boolean} isDark 다크 모드 활성화 여부
 * @param {boolean} [remountMap=true] 네이버 지도 인스턴스를 새 테마로 재마운트할지 여부
 */
function applyTheme(isDark, remountMap = true) {
    state.darkMode = Boolean(isDark);
    document.body.classList.toggle('dark-mode', state.darkMode);
    document.body.dataset.theme = state.darkMode ? 'dark' : 'light';
    document.documentElement.style.colorScheme = state.darkMode ? 'dark' : 'light';

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
 * 검색 패널 내 다크모드 동그라미 토글 버튼(`#theme-toggle`) 클릭 이벤트를 초기화합니다.
 */
function initThemeToggle() {
    applyTheme(true, false);
    byId('theme-toggle')?.addEventListener('click', () => {
        applyTheme(!state.darkMode, true);
    });
}

function init() {
    initThemeToggle();
    initSearchFocus();
    initPanels();
    initRadius();
    initDirectionsControls();
    updateSummary();

    syncResponsiveSheetLayout();
    initSheetDrag();
    initNotice();

    byId('developer-mode').addEventListener('toggle', () => {
        if (byId('developer-mode').open && state.selected) {
            loadData(false);
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
        const first = state.clusterItems[0];
        const bounds = new naver.maps.LatLngBounds(
            new naver.maps.LatLng(first.lat, first.lng),
            new naver.maps.LatLng(first.lat, first.lng)
        );
        for (const hospital of state.clusterItems) {
            bounds.extend(new naver.maps.LatLng(hospital.lat, hospital.lng));
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

    for (const id of [
        'filter-regional',
        'filter-tertiary',
        'filter-medical',
        'filter-other'
    ]) {
        byId(id).addEventListener('change', updateMarkers);
    }

    byId('sheet-close').addEventListener('click', closeSheet);
    byId('sheet-dim').addEventListener('click', closeSheet);

    document.addEventListener('keydown', event => {
        if (event.key === 'Escape') {
            if (document.querySelector('dialog[open]')) {
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

    // 네이버 지도(NAVER Maps v3) 인스턴스 초기화 (최초 기본: 다크 모드 customStyleId 적용)
    mountNaverMap();

    window.addEventListener('resize', () => {
        syncResponsiveSheetLayout();
        updateMarkers();
        updateMarquees();
    });

    byId('location-button').disabled = false;
    byId('refresh-button').disabled = false;

    loadData(false);
    locate();

    const previewMode = new URLSearchParams(window.location.search).get('preview');
    if (previewMode) {
        handlePreviewMode(previewMode);
    }
}

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
