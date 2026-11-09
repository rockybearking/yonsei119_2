package com.yonsei119.dto;

import java.util.List;
import java.util.Map;

/**
 * ============================================================================
 * [레코드 역할 — DTO]
 * {@code /api/hospitals} 엔드포인트가 프론트엔드에 내려보내는 **전국 응급의료기관 실시간 통합 스냅샷** 최상위 응답 DTO입니다.
 * <p>
 * [파이썬 장고(Django) 비유]
 * - 장고 {@code views.py}에서 {@code return JsonResponse({"updatedAt": ..., "totalCount": ..., "hospitals": [...]})}로 내려보낼
 *   최상위 응답 스키마(DRF {@code EmergencySnapshotSerializer})를 타입 안전하게 정의한 파일입니다.
 * <p>
 * [코드의 경로성 (어디서 오고 어디로 가는가?)]
 * - [누가 만드는가 (Incoming Producer)] :
 *   {@code service/EmergencyDataService.java}의 {@code fetchFreshSnapshot()} 및 {@code getSnapshot(boolean)} 메서드
 * - [어느 API로 나가는가 (Via)] :
 *   {@code controller/EmergencyApiController.java}의 {@code GET /api/hospitals} ({@code getHospitals()})
 *   (또한 {@code /api/directions/rank}와 {@code /api/geocode}도 내부적으로 이 스냅샷의 {@code hospitals()} 리스트를 참조합니다!)
 * - [프론트엔드 어디서 쓰는가 (Outgoing Consumer)] :
 *   {@code frontend/app.js}의 {@code fetchData()} → {@code loadData()}:
 *   - {@code updatedAt}, {@code cached} → 상단 갱신 시각 표시 및 상태 배너 업데이트
 *   - {@code hospitals} → {@code state.hospitals} 배열에 저장되어 지도 마커({@code updateMarkers()}), 검색({@code renderSearchResults()}),
 *     상세 시트({@code openHospital()})의 원본 데이터로 사용됨
 *   - {@code sources}, {@code endpointCounts}, {@code *Count} → 상세 시트 하단 {@code '개발자 모드'}({@code #developer-mode}) 진단 정보에 표시됨
 * <p>
 * [수정 시 영향 및 예시]
 * - 백엔드가 공공데이터 API 7개를 긁어오는 데 걸린 소요 시간({@code fetchElapsedMs})을 JSON에 추가하고 싶다면:
 *   예시(변경 전): {@code ..., List<HospitalDto> hospitals)}
 *   예시(변경 후): {@code ..., List<HospitalDto> hospitals, long fetchElapsedMs)}
 *   이후 {@code EmergencyDataService.java}에서 {@code new EmergencySnapshotDto(..., elapsedMs)}로 생성해 주면 됩니다.
 * ============================================================================
 *
 * @param updatedAt      스냅샷 생성 시각 (ISO-8601 문자열, 예: {@code "2026-11-10T02:00:00Z"})
 * @param cached         인메모리 캐시 히트 여부 ({@code true}: 캐시에서 즉시 반환, {@code false}: 방금 공공데이터포털에서 새로 수집)
 * @param totalCount     유효 좌표를 가진 전국 전체 응급의료기관 수 (약 520~540개)
 * @param tertiaryCount  상급종합병원 수
 * @param regionalCount  권역응급의료센터 수
 * @param medicalCount   의료원 수
 * @param traumaCount    권역외상센터 수
 * @param otherCount     일반·기타 응급의료기관 수
 * @param sources        NEMC 7개 OpenAPI 오퍼레이션별 정상 수신 여부 ({@code list}, {@code basic}, {@code beds}, {@code messages}, {@code severe}, {@code traumaList}, {@code traumaBasic})
 * @param endpointCounts NEMC 7개 OpenAPI 오퍼레이션별 수신된 원본 XML 아이템 개수
 * @param hospitals      기관 고유코드({@code HPID}) 기준으로 교차 병합된 전체 병원 목록 ({@link HospitalDto})
 */
public record EmergencySnapshotDto(
        String updatedAt,
        boolean cached,
        int totalCount,
        int tertiaryCount,
        int regionalCount,
        int medicalCount,
        int traumaCount,
        int otherCount,
        Map<String, Boolean> sources,
        Map<String, Integer> endpointCounts,
        List<HospitalDto> hospitals
) {
}
