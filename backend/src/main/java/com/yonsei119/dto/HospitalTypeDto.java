package com.yonsei119.dto;

/**
 * ============================================================================
 * [레코드 역할 — DTO]
 * 개별 응급의료기관의 종별 분류(상급종합병원 / 의료원 / 일반·기타 응급의료기관) 메타데이터를 담는 DTO입니다.
 * <p>
 * [파이썬 장고(Django) 비유]
 * - {@code HospitalDto} 안에 중첩(Nested)되어 포함되는 작은 읽기 전용 {@code @dataclass} / {@code HospitalTypeSerializer}입니다.
 * <p>
 * [코드의 경로성 (어디서 오고 어디로 가는가?)]
 * - [생성 (Incoming Producer)] : {@code service/HospitalClassifier.java}의 상수({@code TERTIARY}, {@code MEDICAL}, {@code OTHER}) 및 {@code classifyType()} 메서드
 * - [포함 (Embedded In)] : {@code dto/HospitalDto.java}의 {@code type} 필드 → {@code /api/hospitals} JSON 응답의 {@code hospital.type} 객체
 * - [소비 (Outgoing Consumer)] : {@code frontend/app.js}의 아래 로직들과 직접 매칭됩니다:
 *   1) 필터링({@code visibleTypes()}, {@code updateMarkers()}): {@code group} 값({@code "tertiary"}, {@code "medical"}, {@code "other"})으로 체크박스 필터와 대조
 *   2) 지도 마커 색상 및 배지({@code visualGroup()}, {@code createMarkerContent()}): {@code group} 및 {@code label} 표시
 *   3) 정렬 우선순위({@code hospitalPriority()}): 거리순 외 동일 조건일 때 {@code priority}가 낮을수록 먼저 배치
 * <p>
 * [수정 시 영향 및 예시]
 * - 지도나 시트에 찍히는 {@code '상급종합'} 글자를 {@code '3차 상급종합'}으로 바꾸고 싶다면 이 파일이 아니라
 *   이 객체를 실제로 생성하는 {@code service/HospitalClassifier.java}의 {@code TERTIARY} 상수 라벨을 고치면 전체 화면에 일괄 반영됩니다!
 * ============================================================================
 *
 * @param group    프론트엔드 필터 식별 키 ({@code "tertiary"}, {@code "medical"}, {@code "other"})
 * @param label    화면 표시용 한글 종별 라벨 ({@code "상급종합"}, {@code "의료원"}, {@code "일반·기타"})
 * @param accent   종별 대표 강조 HEX 색상 코드 ({@code "#111111"}, {@code "#0284c7"}, {@code "#64748b"})
 * @param priority 정렬 우선순위 정수 (낮을수록 상위 등급: 상급종합 {@code 0}, 의료원 {@code 2}, 일반·기타 {@code 3})
 */
public record HospitalTypeDto(
        String group,
        String label,
        String accent,
        int priority
) {
}
