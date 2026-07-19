package com.sbp.dunning;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "app.dunning")
public class DunningPolicy {
    private List<Duration> retryOffsets = List.of(
            Duration.ofDays(1), Duration.ofDays(3), Duration.ofDays(5), Duration.ofDays(7));
    private Duration gracePeriod = Duration.ofDays(3);

    public List<Duration> getRetryOffsets() { return retryOffsets; }
    public void setRetryOffsets(List<Duration> retryOffsets) { this.retryOffsets = retryOffsets; }
    public int getMaxAttempts() { return retryOffsets.size(); }
    public Duration getGracePeriod() { return gracePeriod; }
    public void setGracePeriod(Duration gracePeriod) { this.gracePeriod = gracePeriod; }
}
