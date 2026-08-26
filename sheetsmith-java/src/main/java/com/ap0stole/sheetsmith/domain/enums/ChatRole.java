package com.ap0stole.sheetsmith.domain.enums;

public enum ChatRole {
    USER,
    ASSISTANT,
    /** Emitted by the app itself, e.g. when an improve run replaced the sheet under the chat. */
    SYSTEM
}
