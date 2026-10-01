package com.chy.zhikexing.catalog;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties("app.catalog-cache")
public class CatalogCacheProperties {
    public enum Mode { DB, REDIS, TWO_LEVEL }

    /** Same catalog queries and authorization; only their read-through cache changes. */
    private Mode mode = Mode.TWO_LEVEL;
    /** Separate benchmark keys without flushing a Redis database. Default preserves existing keys. */
    private String keyPrefix = "catalog:";
    private Duration localTtl = Duration.ofSeconds(10);
    private Duration sharedTtl = Duration.ofSeconds(60);
    private long maxEntries = 1000;
    private Duration lockWait = Duration.ofSeconds(1);
}
