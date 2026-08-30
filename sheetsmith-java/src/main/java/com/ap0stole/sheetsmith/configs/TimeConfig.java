package com.ap0stole.sheetsmith.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Where "now" comes from.
 * <p>
 * The services that reason about time — a monthly spend window, a retention cutoff, how long a run
 * took — take this rather than calling {@code LocalDateTime.now()}, so a test can decide what day
 * it is instead of arranging for the machine to be on the right side of a month boundary. The
 * ones that only stamp a row keep calling {@code now(ZoneId)} directly: a created-at is a fact
 * about when the row was written, not a decision anything is made on.
 * <p>
 * System default zone, because the instance runs where its files and its readers are, and the
 * alternative — UTC everywhere — would print times nobody at the keyboard recognises.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
