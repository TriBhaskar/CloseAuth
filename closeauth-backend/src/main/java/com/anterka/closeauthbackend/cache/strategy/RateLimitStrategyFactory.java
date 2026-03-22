package com.anterka.closeauthbackend.cache.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Factory for obtaining rate limit strategies by action name.
 * Auto-discovers all RateLimitStrategy beans and maps them by action.
 */
@Component
@Slf4j
public class RateLimitStrategyFactory {

    private final Map<String, RateLimitStrategy> strategies;

    public RateLimitStrategyFactory(List<RateLimitStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(
                        RateLimitStrategy::getAction,
                        strategy -> strategy
                ));
        log.info("Loaded {} rate limit strategies: {}", strategies.size(), strategies.keySet());
    }

    /**
     * Gets the strategy for the given action.
     * Returns a default strategy if no specific strategy is found.
     */
    public RateLimitStrategy getStrategy(String action) {
        return strategies.getOrDefault(action, new DefaultRateLimitStrategy(action));
    }

    /**
     * Checks if a strategy exists for the given action.
     */
    public boolean hasStrategy(String action) {
        return strategies.containsKey(action);
    }
}

