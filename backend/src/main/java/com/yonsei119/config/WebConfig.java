package com.yonsei119.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * ============================================================================
 * [클래스 역할]
 * 모노레포의 `frontend/` 정적 리소스 서빙, 루트(`/`) 라우팅, CORS 정책을 담당하는
 * Spring Web MVC 설정 클래스입니다.
 *
 * [담당 기능]
 * 1. 로컬 IntelliJ IDEA에서 실행할 때(`file:./frontend/`, `file:../frontend/`)와
 *    Render 클라우드 Docker/JAR로 배포할 때(`classpath:/static/`) 모두 동일하게
 *    프론트엔드 파일(`index.html`, `style.css`, `app.js`, `Assets/**`)을 서빙합니다.
 * 2. 로컬 개발 중 프론트엔드 파일(JS/CSS/HTML)을 수정하면 서버 재시작 없이
 *    브라우저 새로고침만으로 즉시 반영되도록 파일 시스템 경로를 우선 매핑합니다.
 * 3. 프론트엔드를 별도 포트(예: WebStorm Live Server 등)에서 띄워 백엔드 API를 호출할 때도
 *    CORS 에러가 발생하지 않도록 `/api/**` 엔드포인트에 안전한 CORS를 허용합니다.
 *
 * [수정 시 영향 및 예시]
 * - 정적 이미지(`Assets/**`)에 대해 브라우저 캐시를 7일간 활성화하고 싶다면
 *   별도의 리소스 핸들러를 분리하여 `CacheControl.maxAge(7, TimeUnit.DAYS)`를 지정할 수 있습니다.
 *   예시:
 *   {@code
 *   registry.addResourceHandler("/Assets/**")
 *           .addResourceLocations("file:./frontend/Assets/", "classpath:/static/Assets/")
 *           .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)));
 *   }
 * ============================================================================
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * [메서드 역할]
     * 정적 웹 리소스(`index.html`, `style.css`, `app.js`, `Assets/**`) 탐색 경로를 등록합니다.
     *
     * [탐색 우선순위]
     * 1순위: `file:./frontend/` (모노레포 루트에서 실행 시 실시간 반영)
     * 2순위: `file:../frontend/` (`backend/` 폴더 내부에서 실행 시 실시간 반영)
     * 3순위: `classpath:/static/` (Render 배포용 단일 JAR 내부에 패키징된 리소스)
     *
     * @param registry Spring MVC 리소스 핸들러 레지스트리
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(
                        "file:./frontend/",
                        "file:../frontend/",
                        "classpath:/static/"
                )
                .setCacheControl(CacheControl.noCache());
    }

    /**
     * [메서드 역할]
     * 사용자가 루트 경로(`/`)로 접속했을 때 `index.html`을 기본 문서로 포워딩합니다.
     *
     * @param registry 뷰 컨트롤러 레지스트리
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    /**
     * [메서드 역할]
     * `/api/**` 경로에 대한 CORS(교차 출처 리소스 공유) 정책을 설정합니다.
     *
     * [수정 시 영향 및 예시]
     * - 특정 도메인(예: `https://yonsei119.onrender.com`)만 허용하도록 제한하고 싶다면:
     *   변경 전: `.allowedOriginPatterns("*")`
     *   변경 후: `.allowedOrigins("https://yonsei119.onrender.com")`
     *
     * @param registry CORS 레지스트리
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
