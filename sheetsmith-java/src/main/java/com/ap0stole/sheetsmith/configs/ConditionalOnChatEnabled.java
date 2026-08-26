package com.ap0stole.sheetsmith.configs;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a bean as part of the chat, so that {@code sheetsmith.chat.enabled=false} leaves it
 * <em>unregistered</em> rather than merely unreachable.
 * <p>
 * The distinction is the whole point of the flag. An instance run for its privacy guarantee should
 * not contain a wired-up path that can send cell values to a model and simply happens not to be
 * called; with this, the query tools, the agent and the message endpoints do not exist in the
 * context at all, and {@code ChatDisabledTest} asserts exactly that.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnProperty(prefix = "sheetsmith.chat", name = "enabled", havingValue = "true", matchIfMissing = true)
public @interface ConditionalOnChatEnabled {
}
