package com.ap0stole.sheetsmith.configs;

import com.ap0stole.sheetsmith.controller.ChatMessageController;
import com.ap0stole.sheetsmith.domain.dto.CapabilitiesDto;
import com.ap0stole.sheetsmith.services.chat.ChatAgentService;
import com.ap0stole.sheetsmith.services.chat.ChatToolRegistry;
import com.ap0stole.sheetsmith.services.chat.SuggestionService;
import com.ap0stole.sheetsmith.services.excel.query.QueryTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The privacy guarantee, as something a machine checks rather than something a README claims.
 * <p>
 * With {@code xlsxai.chat.enabled=false} the promise is that nothing but a sheet's structure can
 * reach a language model. That is only true if the parts able to send cell values are <em>absent
 * from the context</em>, not merely unreferenced — an unreachable code path is one refactor away
 * from being reachable again, and nobody would notice. So this asserts absence.
 */
class ChatDisabledTest {

    /**
     * Scans the package holding the read-only query tools — the only components that can put a cell
     * value in front of a model. Component scanning is the mechanism under test: annotating a class
     * is what removes it, so importing the classes explicitly would prove nothing.
     */
    @Configuration
    @EnableConfigurationProperties(ChatConfig.class)
    @ComponentScan(basePackages = "com.ap0stole.sheetsmith.services.excel.query")
    static class Slice {
    }

    private final ApplicationContextRunner contexts =
            new ApplicationContextRunner().withUserConfiguration(Slice.class);

    @Test
    @DisplayName("with the chat off, nothing that can read cell values is in the context at all")
    void theModelFacingBeansAreAbsent() {
        contexts.withPropertyValues("xlsxai.chat.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(context.getBeansOfType(QueryTool.class))
                    .as("the query tools are the only way a cell value reaches the model")
                    .isEmpty();
        });
    }

    @Test
    @DisplayName("with the chat on — the default — the same beans are there")
    void theyAreThereByDefault() {
        contexts.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(QueryTool.class))
                    .as("READ_RANGE, AGGREGATE, FIND_ROWS, DESCRIBE_COLUMN, EVAL_FORMULA")
                    .hasSize(5);
        });
    }

    @Test
    @DisplayName("the flag defaults to on, so an existing deployment keeps its chat")
    void theDefaultIsUnchanged() {
        contexts.run(context ->
                assertThat(context.getBean(ChatConfig.class).isEnabled()).isTrue());
    }

    @Test
    @DisplayName("the annotation is what does it — a bean carrying it is conditional on the property")
    void theAnnotationCarriesTheCondition() {
        for (Class<?> type : new Class<?>[]{
                ChatAgentService.class, ChatToolRegistry.class, SuggestionService.class,
                ChatMessageController.class}) {
            ConditionalOnProperty condition =
                    type.getAnnotation(ConditionalOnChatEnabled.class) == null
                            ? null
                            : ConditionalOnChatEnabled.class.getAnnotation(ConditionalOnProperty.class);
            assertThat(condition)
                    .as("%s must be removable, or the flag is a half-measure", type.getSimpleName())
                    .isNotNull();
            assertThat(condition.name()).containsExactly("enabled");
            assertThat(condition.matchIfMissing()).isTrue();
        }
    }

    @Test
    @DisplayName("the capabilities a UI reads say plainly what the instance will and will not send")
    void capabilitiesAreHonest() {
        CapabilitiesDto off = CapabilitiesDto.of(false);
        assertThat(off.chatEnabled()).isFalse();
        assertThat(off.suggestionsEnabled())
                .as("suggestions inspect real cell values, so they go with the chat")
                .isFalse();
        assertThat(off.sendsOnlyStructure()).isTrue();

        CapabilitiesDto on = CapabilitiesDto.of(true);
        assertThat(on.sendsOnlyStructure())
                .as("with a chat present, claiming structure-only would be a lie")
                .isFalse();
    }
}
