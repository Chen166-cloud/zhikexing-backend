package com.chy.zhikexing.catalog;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties("app.catalog-cache")
public class CatalogCacheProperties {
    private Duration localTtl = Duration.ofSeconds(10);
    private Duration sharedTtl = Duration.ofSeconds(60);
    private long maxEntries = 1000;
    private Duration lockWait = Duration.ofSeconds(1);
}
