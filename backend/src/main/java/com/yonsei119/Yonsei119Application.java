package com.yonsei119;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ============================================================================
 * [클래스 역할]
 * 연세 119 응급실 정보 시스템(YONSEI 119 Emergency Information System)의
 * Spring Boot 메인 애플리케이션 진입점(Entry Point) 클래스입니다.
 *
 * [담당 기능]
 * 1. 내장 Tomcat 웹 서버를 구동하고 REST 컨트롤러(`/api/**`) 및 정적 프론트엔드를 초기화합니다.
 * 2. {@link ConfigurationPropertiesScan}을 통해 `application.yml`의 `yonsei119.*` 설정을
 *    타입 안전한 불변(Immutable) Record 객체로 바인딩합니다.
 * 3. {@link EnableScheduling}을 통해 백그라운드에서 주기적으로 전국 530여 개 응급의료기관 데이터를
 *    자동 갱신(`@Scheduled`)하여 최초 접속자도 지연 없이 즉시 조회할 수 있도록 지원합니다.
 *
 * [수정 시 영향 및 예시]
 * - 백그라운드 주기적 스케줄링을 완전히 끄고 싶다면 `@EnableScheduling`을 제거하거나
 *   `application.yml`의 `yonsei119.nemc.auto-refresh-enabled`를 `false`로 변경합니다.
 * ============================================================================
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class Yonsei119Application {

    /**
     * [메서드 역할]
     * JVM이 애플리케이션을 시작할 때 호출하는 표준 메인 함수입니다.
     *
     * @param args 커맨드라인 인자 (예: `--server.port=9090`)
     */
    public static void main(String[] args) {
        SpringApplication.run(Yonsei119Application.class, args);
    }
}
