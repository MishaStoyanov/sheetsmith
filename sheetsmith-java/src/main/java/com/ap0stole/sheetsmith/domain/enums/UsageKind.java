package com.ap0stole.sheetsmith.domain.enums;

/** Which flow a model call belonged to. The two spend differently and are worth telling apart. */
public enum UsageKind {
    /** Planning or repairing an improve run. */
    IMPROVE,
    /** One step of a chat turn. */
    CHAT
}
