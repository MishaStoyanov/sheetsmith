package com.ap0stole.sheetsmith.services;

import com.ap0stole.sheetsmith.domain.dto.ExcelSchemaDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers the schema of a session revision so the workbook is opened once instead of on every
 * refresh — a large sheet costs seconds of CPU and hundreds of MB per parse.
 * <p>
 * Safe because a revision file is immutable once written: the key is session id <em>plus</em>
 * revision, so the entry a new revision would invalidate is simply never looked up again. Handing
 * out a schema of a revision that has moved on would make the chat reason about a sheet that no
 * longer exists, which is why nothing here is keyed on the session alone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSchemaCache {

    /** A few live sessions with a couple of revisions each — enough to never re-parse in practice. */
    private static final int MAX_ENTRIES = 64;

    private final SchemaExtractorService schemaExtractorService;

    private final Map<Key, ExcelSchemaDto> entries = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Key, ExcelSchemaDto> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    private record Key(String sessionId, int revision) {}

    /**
     * The schema of one revision, extracted at most once. Extraction runs outside the map's lock:
     * a racing miss costs a duplicate parse, whereas holding the lock would stall every other
     * session behind one big workbook.
     */
    public ExcelSchemaDto get(String sessionId, int revision, String path) {
        Key key = new Key(sessionId, revision);
        ExcelSchemaDto cached = entries.get(key);
        if (cached != null) return cached;

        ExcelSchemaDto schema = schemaExtractorService.extract(path);
        entries.put(key, schema);
        log.debug("Cached schema for session {} revision {}", sessionId, revision);
        return schema;
    }

    /** Called when a session dies — its revisions are gone, so nothing may keep pointing at them. */
    public void evictSession(String sessionId) {
        synchronized (entries) {
            entries.keySet().removeIf(key -> key.sessionId().equals(sessionId));
        }
    }
}
