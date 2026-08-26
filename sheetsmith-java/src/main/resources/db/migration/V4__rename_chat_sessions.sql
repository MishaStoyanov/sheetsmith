-- The table follows the class: this row is the shared working copy of a document, read and written
-- by the improve flow as much as by the chat, and the old name said otherwise.
--
-- A rename, not a new table — the whole reason the schema moved to Flyway first. Under
-- `ddl-auto: update` Hibernate would have created `document_sessions` alongside `chat_sessions` and
-- left every existing session stranded in the old one.

alter table chat_sessions rename to document_sessions;
