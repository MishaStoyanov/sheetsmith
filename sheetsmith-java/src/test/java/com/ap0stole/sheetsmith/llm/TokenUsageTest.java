package com.ap0stole.sheetsmith.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole point of this type is telling "reported nothing" apart from "cost nothing", and every
 * provider gets that wrong in its own way — hence a test per shape rather than one happy path.
 */
class TokenUsageTest {

    @Test
    @DisplayName("reads what a paid provider reported")
    void readsReportedUsage() {
        TokenUsage usage = TokenUsage.from(responseWith(new DefaultUsage(1200, 300, 1500)));

        assertThat(usage.promptTokens()).isEqualTo(1200L);
        assertThat(usage.completionTokens()).isEqualTo(300L);
        assertThat(usage.totalTokens()).isEqualTo(1500L);
    }

    @Test
    @DisplayName("Spring AI's EmptyUsage is silence, not a free run")
    void emptyUsageIsRecordedAsNothing() {
        // EmptyUsage answers 0 to every question, so taken at face value a local model would fill
        // the audit with runs that apparently cost nothing at all.
        TokenUsage usage = TokenUsage.from(responseWith(new EmptyUsage()));

        assertThat(usage.isEmpty()).isTrue();
        assertThat(usage.promptTokens()).isNull();
        assertThat(usage.totalTokens()).isNull();
    }

    @Test
    @DisplayName("no metadata at all is silence too")
    void missingMetadataIsRecordedAsNothing() {
        assertThat(TokenUsage.from(null).isEmpty()).isTrue();
        assertThat(TokenUsage.from(responseWith(null)).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("halves without a total are added up rather than left as a hole")
    void derivesTheTotalWhenTheProviderOmitsIt() {
        TokenUsage usage = TokenUsage.from(responseWith(new DefaultUsage(900, 100, null)));

        assertThat(usage.totalTokens()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("two calls of one run add up")
    void addsTwoCalls() {
        TokenUsage total = new TokenUsage(1000L, 200L, 1200L).plus(new TokenUsage(400L, 100L, 500L));

        assertThat(total.promptTokens()).isEqualTo(1400L);
        assertThat(total.completionTokens()).isEqualTo(300L);
        assertThat(total.totalTokens()).isEqualTo(1700L);
    }

    @Test
    @DisplayName("adding silence changes nothing, in either direction")
    void addingNothingKeepsWhatIsKnown() {
        TokenUsage known = new TokenUsage(1000L, 200L, 1200L);

        assertThat(known.plus(TokenUsage.NONE)).isEqualTo(known);
        assertThat(TokenUsage.NONE.plus(known)).isEqualTo(known);
        assertThat(TokenUsage.NONE.plus(TokenUsage.NONE).isEmpty()).isTrue();
    }

    private static ChatResponse responseWith(Usage usage) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().usage(usage).build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))), metadata);
    }
}
