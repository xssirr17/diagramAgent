package com.example.diagramagent;

import com.example.diagramagent.config.DiagramProperties;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.net.URI;

@SpringBootApplication
@EnableConfigurationProperties(DiagramProperties.class)
public class DiagramAgentApplication {

    public static void main(String[] args) {
        configureProxyIfPresent();
        SpringApplication app = new SpringApplication(DiagramAgentApplication.class);
        if (isCliCommand(args)) {
            app.setWebApplicationType(WebApplicationType.NONE);
            app.setBannerMode(Banner.Mode.OFF);
            app.setAdditionalProfiles("cli");
            int exitCode = SpringApplication.exit(app.run(args));
            System.exit(exitCode);
        } else {
            app.run(args);
        }
    }

    public static boolean isCliCommand(String[] args) {
        String profile = System.getProperty("spring.profiles.active");
        if (profile == null) {
            profile = System.getenv("SPRING_PROFILES_ACTIVE");
        }
        if (profile != null && (profile.equals("cli") || profile.contains("cli"))) {
            return true;
        }
        if (args != null && args.length > 0) {
            String first = args[0].trim().toLowerCase();
            return first.equals("generate")
                || first.equals("endpoints")
                || first.equals("diff")
                || first.equals("validate")
                || first.equals("--validate-only")
                || first.equals("--help")
                || first.equals("-h")
                || first.equals("--version")
                || first.equals("-v");
        }
        return false;
    }

    private static void configureProxyIfPresent() {
        String proxy = System.getenv("ALL_PROXY");
        if (proxy == null || proxy.isBlank()) {
            proxy = System.getenv("HTTPS_PROXY");
        }
        if (proxy == null || proxy.isBlank()) {
            proxy = System.getenv("HTTP_PROXY");
        }
        if (proxy != null && !proxy.isBlank()) {
            try {
                URI uri = URI.create(proxy);
                String scheme = uri.getScheme();
                String host = uri.getHost();
                int port = uri.getPort();
                if (host != null && port > 0) {
                    if ("socks5".equalsIgnoreCase(scheme) || "socks".equalsIgnoreCase(scheme)) {
                        System.setProperty("socksProxyHost", host);
                        System.setProperty("socksProxyPort", String.valueOf(port));
                    } else if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                        System.setProperty("http.proxyHost", host);
                        System.setProperty("http.proxyPort", String.valueOf(port));
                        System.setProperty("https.proxyHost", host);
                        System.setProperty("https.proxyPort", String.valueOf(port));
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }
}
