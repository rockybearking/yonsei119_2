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
 * 모노레포의 {@code frontend/} 정적 리소스(HTML/CSS/JS/이미지) 서빙, 루트({@code /}) 접속 라우팅,
 * 그리고 {@code /api/**} CORS(교차 출처 리소스 공유) 보안 정책을 담당하는 Spring Web MVC 설정 클래스입니다.
 * <p>
 * [파이썬 장고(Django) 비유]
 * - 장고에서 아래 3가지 설정을 한 파일에 깔끔하게 모아둔 것과 같습니다:
 *   1) {@code settings.py}의 {@code STATIC_URL}, {@code STATICFILES_DIRS} (정적 파일 경로 연결 — {@link #addResourceHandlers})
 *   2) {@code urls.py}의 {@code path("", TemplateView.as_view(template_name="index.html"))} (홈 화면 연결 — {@link #addViewControllers})
 *   3) {@code django-cors-headers} 미들웨어의 {@code CORS_ALLOW_ALL_ORIGINS} / {@code CORS_ALLOWED_ORIGINS} 설정 ({@link #addCorsMappings})
 * <p>
 * [코드의 경로성 (어디서 오고 어디로 가는가?)]
 * - [어디서 왔는가 (Incoming)] :
 *   {@code Yonsei119Application.java} 구동 시 {@code @Configuration} 어노테이션을 보고 Spring MVC 엔진이 이 클래스의 메서드 3개를 자동 호출합니다.
 * - [어디로 가는가 (Outgoing)] :
 *   1) 사용자가 브라우저에서 {@code http://localhost:8080/} 접속 → {@link #addViewControllers}가 {@code /index.html}로 포워딩 →
 *      {@link #addResourceHandlers}가 {@code frontend/index.html}을 찾아 브라우저로 전송합니다!
 *   2) {@code frontend/index.html}이 로드되면서 요청하는 {@code /style.css}, {@code /app.js}, {@code /Assets/...} 파일들도 모두 이 클래스를 거쳐 브라우저에 전달됩니다.
 *   3) 반면 {@code /api/...}로 시작하는 요청은 정적 파일이 아니라 {@code controller/EmergencyApiController.java}가 먼저 가로채어 JSON 데이터를 반환합니다.
 * ============================================================================
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * [메서드 역할]
     * 정적 웹 리소스({@code index.html}, {@code style.css}, {@code app.js}, {@code Assets/**})를 어느 폴더에서 찾을지 우선순위를 등록합니다.
     * <p>
     * [탐색 우선순위 설명]
     * - 1순위 {@code file:./frontend/}  : 모노레포 루트({@code yonsei119/})에서 서버를 켰을 때 실제 디스크의 {@code frontend/} 폴더를 바로 읽습니다.
     * - 2순위 {@code file:../frontend/} : {@code yonsei119/backend/} 하위 폴더에서 서버를 켰을 때 상위 폴더의 {@code frontend/}를 읽습니다.
     * - 3순위 {@code classpath:/static/}: Render/Docker에 단일 JAR({@code yonsei119-backend.jar})로 배포했을 때 JAR 내부({@code backend/build.gradle}의
     *                              {@code copyFrontendToStatic} 태스크가 복사해 넣은 폴더)에서 읽습니다.
     * <p>
     * [수정 시 영향 및 예시]
     * - 현재는 {@code CacheControl.noCache()}로 되어 있어 HTML/CSS/JS를 고치고 새로고침하면 옛날 캐시 없이 즉시 반영됩니다.
     * - 만약 이미지 폴더({@code /Assets/**})만 브라우저에 7일간 캐시되도록 별도 분리하고 싶다면 아래처럼 위에 추가하면 됩니다:
     *   예시(변경 후 추가 코드):
     *   {@code
     *   registry.addResourceHandler("/Assets/**")
     *           .addResourceLocations("file:./frontend/Assets/", "file:../frontend/Assets/", "classpath:/static/Assets/")
     *           .setCacheControl(CacheControl.maxAge(java.time.Duration.ofDays(7)));
     *   }
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
     * 사용자가 주소창에 파일명 없이 루트 경로({@code http://localhost:8080/})만 입력했을 때,
     * 내부적으로 {@code /index.html}({@code frontend/index.html}) 화면을 보여주도록 연결합니다.
     * (장고 {@code urls.py}의 {@code path("", ...)} 루트 뷰 매핑과 동일한 역할입니다.)
     * <p>
     * [수정 시 영향 및 예시]
     * - 만약 {@code /map} 주소로 접속했을 때도 똑같이 {@code index.html}을 띄워주고 싶다면 한 줄만 추가하면 됩니다:
     *   예시(변경 후):
     *   {@code
     *   registry.addViewController("/").setViewName("forward:/index.html");
     *   registry.addViewController("/map").setViewName("forward:/index.html");
     *   }
     *
     * @param registry 뷰 컨트롤러 레지스트리
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    /**
     * [메서드 역할]
     * 프론트엔드를 별도 개발 서버(예: VS Code / WebStorm Live Server {@code localhost:5500})로 띄워서
     * 백엔드({@code localhost:8080/api/**})를 호출할 때 브라우저가 차단하지 않도록 CORS 헤더를 허용합니다.
     * <p>
     * [수정 시 영향 및 예시]
     * - 상용 배포 시 특정 도메인(예: {@code https://yonsei119.onrender.com})에서만 API 호출을 허용하도록 보안을 강화하려면:
     *   예시(변경 전): {@code .allowedOriginPatterns("*")}
     *   예시(변경 후): {@code .allowedOrigins("https://yonsei119.onrender.com", "http://localhost:8080")}
     * - 향후 {@code POST} 요청 API를 새로 만든다면 {@code .allowedMethods(...)}에 {@code "POST"}를 추가해야 외부 출처에서 호출됩니다:
     *   예시(변경 전): {@code .allowedMethods("GET", "OPTIONS")}
     *   예시(변경 후): {@code .allowedMethods("GET", "POST", "OPTIONS")}
     *
     * @param registry CORS 정책 레지스트리
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
