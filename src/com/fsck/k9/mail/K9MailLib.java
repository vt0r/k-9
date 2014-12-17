package com.fsck.k9.mail;

import com.fsck.k9.K9;

public class K9MailLib {
    private K9MailLib() {}

    public static final String LOG_TAG = K9.LOG_TAG;

    public static final int PUSH_WAKE_LOCK_TIMEOUT = K9.PUSH_WAKE_LOCK_TIMEOUT;
    public static final String IDENTITY_HEADER     = K9.IDENTITY_HEADER;

    /**
     * Should K-9 log the conversation it has over the wire with
     * SMTP servers?
     */
    public static boolean DEBUG_PROTOCOL_SMTP = true;

    /**
     * Should K-9 log the conversation it has over the wire with
     * IMAP servers?
     */
    public static boolean DEBUG_PROTOCOL_IMAP = true;

    /**
     * Should K-9 log the conversation it has over the wire with
     * POP3 servers?
     */
    public static boolean DEBUG_PROTOCOL_POP3 = true;

    /**
     * Should K-9 log the conversation it has over the wire with
     * WebDAV servers?
     */
    public static boolean DEBUG_PROTOCOL_WEBDAV = true;

    public static boolean isDebug() {
        return K9.DEBUG;
    }

    public static boolean isDebugSensitive() {
        return K9.DEBUG_SENSITIVE;
    }
}
