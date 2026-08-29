package com.ap0stole.sheetsmith.configs;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Turns the javadoc this codebase already writes into something Swagger UI renders as prose.
 * <p>
 * The descriptions in this document come from the declarations themselves, which is the only way an
 * API reference stays true — but javadoc is written in HTML, and it arrives with {@code <p>} between
 * every paragraph and {@code <strong>} around every emphasis. Swagger UI renders markdown, so
 * without this pass the page shows a wall with tags in it: correct, and nobody reads it.
 * <p>
 * Deliberately a small, literal translation rather than an HTML parser. The tags this project's
 * javadoc actually uses are five, they are always well formed because the compiler would complain
 * otherwise, and a dependency that can parse arbitrary HTML would be answering a question nobody
 * here asks.
 */
@Component
public class JavadocToMarkdown implements OpenApiCustomizer, Ordered {

    @Override
    public void customise(io.swagger.v3.oas.models.OpenAPI openApi) {
        if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
            openApi.getComponents().getSchemas().values().forEach(this::clean);
        }
        if (openApi.getPaths() != null) {
            openApi.getPaths().values().forEach(this::clean);
        }
    }

    private void clean(PathItem path) {
        path.readOperations().forEach(this::clean);
    }

    private void clean(Operation operation) {
        operation.setSummary(markdown(operation.getSummary()));
        operation.setDescription(markdown(operation.getDescription()));
    }

    @SuppressWarnings("rawtypes")
    private void clean(Schema schema) {
        if (schema == null) {
            return;
        }
        schema.setDescription(markdown(schema.getDescription()));
        Map<String, Schema> properties = schema.getProperties();
        if (properties != null) {
            properties.values().forEach(this::clean);
        }
        clean(schema.getItems());
    }

    private String markdown(String javadoc) {
        if (javadoc == null || javadoc.isBlank()) {
            return javadoc;
        }
        return javadoc
                .replaceAll("(?i)<p>\\s*", "\n\n")
                .replaceAll("(?i)</?(strong|b)>", "**")
                .replaceAll("(?i)</?(em|i)>", "*")
                .replaceAll("(?i)<code>", "`")
                .replaceAll("(?i)</code>", "`")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</?[a-z][^>]*>", "")
                // Javadoc wraps at a hundred characters and Swagger re-wraps to the panel, so the
                // line breaks inside a paragraph are noise — but the blank line between paragraphs
                // is the structure, and has to survive.
                .replaceAll("(?<!\n)\n(?!\n)", " ")
                .replaceAll("[ \t]+", " ")
                .strip();
    }

    /**
     * After the pass that appends the authorization sentence, which is written as markdown already
     * and must not be re-flowed into the paragraph above it.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
