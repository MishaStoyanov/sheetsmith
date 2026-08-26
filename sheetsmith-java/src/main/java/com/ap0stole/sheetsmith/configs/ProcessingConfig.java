package com.ap0stole.sheetsmith.configs;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Semaphore;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "sheetsmith.processing")
public class ProcessingConfig {

    private int maxConcurrentJobs = 1;

    /**
     * Cells one AUTOSIZE_COLUMNS step may measure, counted as columns x rows. Sizing a column renders
     * every value in it through AWT text measurement, so it is the one action whose cost is set by how
     * big the sheet is rather than by how big a range the user named.
     * <p>
     * Half a million cells is roughly five seconds of measurement — which on a 50 000-row sheet buys
     * about ten columns, not the whole sheet. Past it the step sizes the columns it can afford and
     * reports the remainder as skipped: refusing outright bounded nothing, because the budget is per
     * step and two half-ranges cost the same as one whole one.
     * <p>
     * It lives here rather than under {@code sheetsmith.chat} because both the planner and the chat run
     * actions, and a budget only the chat honoured would leave the improve flow unbounded.
     */
    private int maxAutosizeCells = 500_000;

    @Bean
    public Semaphore jobSemaphore() {
        return new Semaphore(maxConcurrentJobs);
    }
}
