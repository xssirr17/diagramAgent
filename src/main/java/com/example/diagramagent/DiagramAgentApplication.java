package com.example.diagramagent;

import com.example.diagramagent.config.DiagramProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.net.URI;

@SpringBootApplication
@EnableConfigurationProperties(DiagramProperties.class)
public class DiagramAgentApplication {

    public static void main(String[] args) {
        configureProxyIfPresent();
        SpringApplication.run(DiagramAgentApplication.class, args);
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
