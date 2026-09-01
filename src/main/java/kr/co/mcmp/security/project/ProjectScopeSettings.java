package kr.co.mcmp.security.project;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Component
@ConfigurationProperties(prefix = "app.project-scope")
@Getter
@Setter
public class ProjectScopeSettings {

    private boolean enabled = true;
    private String iamBaseUrl = "http://mc-iam-manager:5000";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(5);
}
