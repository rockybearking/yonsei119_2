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
 * <p>
 * [파이썬 장고(Django) 비유]
 * - 장고의 **{@code manage.py} + {@code wsgi.py}(또는 {@code asgi.py})**를 합친 것과 정확히 같은 출발점 파일입니다!
 * - 장고에서는 터미널에서 {@code python manage.py runserver}를 치면 {@code settings.py}를 읽고 {@code urls.py}를 스캔해 웹 서버를 띄우듯,
 *   Spring Boot에서는 이 클래스의 {@link #main(String[])} 함수가 실행되면서 {@code com.yonsei119} 하위 폴더({@code config}, {@code controller},
 *   {@code service}, {@code dto})에 있는 모든 클래스들을 자동으로 스캔(Component Scan)하여 내장 Tomcat 웹 서버를 띄웁니다.
 * <p>
 * [코드의 경로성 (어디서 오고 어디로 가는가?)]
 * - [어디서 왔는가 (Incoming)] :
 *   1) 개발 환경: {@code ./gradlew bootRun} ({@code backend/build.gradle}) 또는 IntelliJ IDEA의 ▶ Run 버튼
 *   2) 배포 환경: {@code java -jar yonsei119-backend.jar} 실행 시 JVM이 이 클래스의 {@code main()}을 가장 먼저 호출합니다.
 * - [어디로 가는가 (Outgoing — 서버 부팅 순서)] :
 *   1) {@link ConfigurationPropertiesScan}이 {@code src/main/resources/application.yml}을 읽어
 *      {@code config/Yonsei119Properties.java} 객체를 생성합니다.
 *   2) {@link SpringBootApplication}의 컴포넌트 스캔이 아래 핵심 빈(Bean)들을 순서대로 조립(의존성 주입, DI)합니다:
 *      - {@code config/WebConfig.java} (정적 프론트엔드 {@code frontend/} 연결 및 CORS 설정)
 *      - {@code service/HospitalClassifier.java} (응급실 종별 분류기)
 *      - {@code service/NmcOpenApiClient.java} (국립중앙의료원 XML API 호출기)
 *      - {@code service/EmergencyDataService.java} (7종 API 병합 및 캐시 관리 서비스)
 *      - {@code service/NaverDirectionsService.java} (네이버 길찾기·지오코딩 서비스)
 *      - {@code controller/EmergencyApiController.java} ({@code /api/**} URL 엔드포인트 라우터)
 *   3) 서버 준비 완료 직후 {@code EmergencyDataService.warmUpCacheOnStartup()}이 실행되어 전국 병원 데이터를 미리 캐싱하고,
 *      {@link EnableScheduling}에 의해 20분마다 {@code EmergencyDataService.scheduledAutoRefresh()}가 자동 실행됩니다.
 * <p>
 * [수정 시 영향 및 예시]
 * - 백그라운드 주기적 스케줄링({@code @Scheduled}) 기능을 프로젝트 전체에서 완전히 끄고 싶다면:
 *   예시(변경 전):
 *   {@code
 *   @SpringBootApplication
 *   @ConfigurationPropertiesScan
 *   @EnableScheduling
 *   public class Yonsei119Application
 *   }
 *   예시(변경 후 — {@code @EnableScheduling} 한 줄 주석 처리 또는 제거):
 *   {@code
 *   @SpringBootApplication
 *   @ConfigurationPropertiesScan
 *   // @EnableScheduling
 *   public class Yonsei119Application
 *   }
 *   (단, 코드 수정 없이 설정으로만 끄고 싶다면 {@code application.yml}의 {@code yonsei119.nemc.auto-refresh-enabled: false}로 바꾸는 것이 더 안전합니다.)
 * ============================================================================
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class Yonsei119Application {

    /**
     * [메서드 역할]
     * Java 가상머신(JVM)이 프로그램을 시작할 때 가장 먼저 호출하는 표준 진입 함수입니다.
     * (파이썬의 {@code if __name__ == "__main__": execute_from_command_line(sys.argv)}와 동일한 위치입니다.)
     * <p>
     * [수정 시 영향 및 예시]
     * - 실행 시 기본 배너(Spring 글자 로고)를 콘솔에서 숨기고 싶다면 아래와 같이 수정할 수 있습니다:
     *   예시(변경 전):
     *   {@code
     *   SpringApplication.run(Yonsei119Application.class, args);
     *   }
     *   예시(변경 후):
     *   {@code
     *   SpringApplication app = new SpringApplication(Yonsei119Application.class);
     *   app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
     *   app.run(args);
     *   }
     *
     * @param args 실행 시 전달된 커맨드라인 인자 배열 (예: {@code --server.port=9090})
     */
    public static void main(String[] args) {
        SpringApplication.run(Yonsei119Application.class, args);
    }
}
