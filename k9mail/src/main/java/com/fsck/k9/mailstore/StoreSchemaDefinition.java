package com.fsck.k9.mailstore;


import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.text.TextUtils;
import android.util.Log;

import com.fsck.k9.Account;
import com.fsck.k9.K9;
import com.fsck.k9.R;
import com.fsck.k9.helper.Utility;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.internet.MimeHeader;
import com.fsck.k9.mail.internet.MimeUtility;
import com.fsck.k9.mailstore.LocalFolder.DataLocation;
import com.fsck.k9.mailstore.LocalFolder.MessagePartType;
import org.apache.james.mime4j.codec.QuotedPrintableOutputStream;
import org.apache.james.mime4j.util.MimeUtil;


class StoreSchemaDefinition implements LockableDatabase.SchemaDefinition {
    private final LocalStore localStore;

    StoreSchemaDefinition(LocalStore localStore) {
        this.localStore = localStore;
    }

    @Override
    public int getVersion() {
        return LocalStore.DB_VERSION;
    }

    @Override
    public void doDbUpgrade(final SQLiteDatabase db) {
        try {
            upgradeDatabase(db);
        } catch (Exception e) {
            Log.e(K9.LOG_TAG, "Exception while upgrading database. Resetting the DB to v0", e);
            db.setVersion(0);
            upgradeDatabase(db);
        }
    }

    private void upgradeDatabase(final SQLiteDatabase db) {
        Log.i(K9.LOG_TAG, String.format(Locale.US, "Upgrading database from version %d to version %d",
                                        db.getVersion(), LocalStore.DB_VERSION));

        db.beginTransaction();
        try {
            // schema version 29 was when we moved to incremental updates
            // in the case of a new db or a < v29 db, we blow away and start from scratch
            if (db.getVersion() < 29) {
                dbCreateDatabaseFromScratch(db);
            } else {
                switch (db.getVersion()) {
                    // in the case that we're starting out at 29 or newer, run all the needed updates
                    case 29:
                        db30AddDeletedColumn(db);
                    case 30:
                        db31ChangeMsgFolderIdDeletedDateIndex(db);
                    case 31:
                        db32UpdateDeletedColumnFromFlags(db);
                    case 32:
                        db33AddPreviewColumn(db);
                    case 33:
                        db34AddFlaggedCountColumn(db);
                    case 34:
                        db35UpdateRemoveXNoSeenInfoFlag(db);
                    case 35:
                        db36AddAttachmentsContentIdColumn(db);
                    case 36:
                        db37AddAttachmentsContentDispositionColumn(db);
                    case 37:
                        // Database version 38 is solely to prune cached attachments now that we clear them better
                    case 38:
                        db39HeadersPruneOrphans(db);
                    case 39:
                        db40AddMimeTypeColumn(db);
                    case 40:
                        db41FoldersAddClassColumns(db);
                        db41UpdateFolderMetadata(db, localStore);
                    case 41:
                        boolean notUpdatingFromEarlierThan41 = db.getVersion() == 41;
                        if (notUpdatingFromEarlierThan41) {
                            db42From41MoveFolderPreferences(localStore);
                        }
                    case 42:
                        db43FixOutboxFolders(db, localStore);
                    case 43:
                        db44AddMessagesThreadingColumns(db);
                    case 44:
                        db45ChangeThreadingIndexes(db);
                    case 45:
                        db46AddMessagesFlagColumns(db);
                    case 46:
                        db47CreateThreadsTable(db);
                    case 47:
                        db48UpdateThreadsSetRootWhereNull(db);
                    case 48:
                        db49CreateMsgCompositeIndex(db);
                    case 49:
                        db50FoldersAddNotifyClassColumn(db, localStore);
                    case 50:
                        db51MigrateMessageFormat(db, localStore);
                    case 51:
                        db52AddMoreMessagesColumnToFoldersTable(db);
                    case 52:
                        db53RemoveNullValuesFromEmptyColumnInMessagesTable(db);
                    case 53:
                        db54AddPreviewTypeColumn(db);
                }
            }

            db.setVersion(LocalStore.DB_VERSION);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        if (db.getVersion() != LocalStore.DB_VERSION) {
            throw new RuntimeException("Database upgrade failed!");
        }
    }

    /** Objects of this class hold immutable information on a database position for
     * one part of the mime structure of a message.
     *
     * An object of this class must be passed to and returned by every operation
     * which inserts mime parts into the database. Each mime part which is inserted
     * must call the {#applyValues()} method on its ContentValues, then obtain the
     * next state object by calling the appropriate next*() method.
     *
     * While the data carried by this object is immutable, it contains some state
     * to ensure that the operations are called correctly and in order.
     *
     * Because the insertion operations required for the database migration are
     * strictly linear, we do not require a more complex stack-based data structure
     * here.
     */
    @VisibleForTesting
    static class MimeStructureState {
        private final Long rootPartId;
        private final Long prevParentId;
        private final long parentId;
        private final int nextOrder;

        // just some diagnostic state to make sure all operations are called in order
        private boolean isValuesApplied;
        private boolean isStateAdvanced;


        private MimeStructureState(Long rootPartId, Long prevParentId, long parentId, int nextOrder) {
            this.rootPartId = rootPartId;
            this.prevParentId = prevParentId;
            this.parentId = parentId;
            this.nextOrder = nextOrder;
        }

        public static MimeStructureState getNewRootState() {
            return new MimeStructureState(null, null, -1, 0);
        }

        public MimeStructureState nextChild(long newPartId) {
            if (!isValuesApplied || isStateAdvanced) {
                throw new IllegalStateException("next* methods must only be called once");
            }
            isStateAdvanced = true;

            if (rootPartId == null) {
                return new MimeStructureState(newPartId, null, -1, nextOrder+1);
            }
            return new MimeStructureState(rootPartId, prevParentId, parentId, nextOrder+1);
        }

        public MimeStructureState nextMultipartChild(long newPartId) {
            if (!isValuesApplied || isStateAdvanced) {
                throw new IllegalStateException("next* methods must only be called once");
            }
            isStateAdvanced = true;

            if (rootPartId == null) {
                return new MimeStructureState(newPartId, parentId, newPartId, nextOrder+1);
            }
            return new MimeStructureState(rootPartId, parentId, newPartId, nextOrder+1);
        }

        public void applyValues(ContentValues cv) {
            if (isValuesApplied || isStateAdvanced) {
                throw new IllegalStateException("applyValues must be called exactly once, after a call to next*");
            }
            if (rootPartId != null && parentId == -1L) {
                throw new IllegalStateException("applyValues must not be called after a root nextChild call");
            }
            isValuesApplied = true;

            if (rootPartId != null) {
                cv.put("root", rootPartId);
            }
            cv.put("parent", parentId);
            cv.put("seq", nextOrder);
        }

        public MimeStructureState popParent() {
            if (prevParentId == null) {
                throw new IllegalStateException("popParent must only be called if parent depth is >= 2");
            }
            return new MimeStructureState(rootPartId, null, prevParentId, nextOrder);
        }
    }

    /** This method converts from the old message table structure to the new one.
     *
     * This is a complex migration, and ultimately we do not have enough
     * information to recreate the mime structure of the original mails.
     * What we have:
     *  - general mail info
     *  - html_content and text_content data, which is the squashed readable content of the mail
     *  - a table with message headers
     *  - attachments
     *
     * What we need to do:
     *  - migrate general mail info as-is
     *  - flag mails as migrated for re-download
     *  - for each message, recreate a mime structure from its message content and attachments:
     *    + insert one or both of textContent and htmlContent, depending on mimeType
     *    + if mimeType is text/plain, text/html or multipart/alternative and no
     *      attachments are present, just insert that.
     *    + otherwise, use multipart/mixed, adding attachments after textual content
     *    + revert content:// URIs in htmlContent to original cid: URIs.
     *
     */
    private static void db51MigrateMessageFormat(SQLiteDatabase db, LocalStore localStore) {

        renameOldMessagesTableAndCreateNew(db);

        copyMessageMetadataToNewTable(db);

        File attachmentDirNew, attachmentDirOld;
        Account account = localStore.getAccount();
        attachmentDirNew = StorageManager.getInstance(K9.app).getAttachmentDirectory(
                account.getUuid(), account.getLocalStorageProviderId());
        attachmentDirOld = renameOldAttachmentDirAndCreateNew(account, attachmentDirNew);

        Cursor msgCursor = db.query("messages_old",
                new String[] { "id", "flags", "html_content", "text_content", "mime_type", "attachment_count" },
                null, null, null, null, null);
        try {
            Log.d(K9.LOG_TAG, "migrating " + msgCursor.getCount() + " messages");
            ContentValues cv = new ContentValues();
            while (msgCursor.moveToNext()) {
                long messageId = msgCursor.getLong(0);
                String messageFlags = msgCursor.getString(1);
                String htmlContent = msgCursor.getString(2);
                String textContent = msgCursor.getString(3);
                String mimeType = msgCursor.getString(4);
                int attachmentCount = msgCursor.getInt(5);

                try {
                    updateFlagsForMessage(db, messageId, messageFlags);
                    MimeHeader mimeHeader = loadHeaderFromHeadersTable(db, messageId);

                    MimeStructureState structureState = MimeStructureState.getNewRootState();

                    boolean messageHadSpecialFormat = false;

                    // we do not rely on the protocol parameter here but guess by the multipart structure
                    boolean isMaybePgpMimeEncrypted = attachmentCount == 2
                            && MimeUtil.isSameMimeType(mimeType, "multipart/encrypted");
                    if (isMaybePgpMimeEncrypted) {
                        MimeStructureState maybeStructureState =
                                migratePgpMimeEncryptedContent(db, messageId, attachmentDirOld, attachmentDirNew,
                                        mimeHeader, structureState);
                        if (maybeStructureState != null) {
                            structureState = maybeStructureState;
                            messageHadSpecialFormat = true;
                        }
                    }

                    if (!messageHadSpecialFormat) {
                        boolean isSimpleStructured = attachmentCount == 0 &&
                                Utility.isAnyMimeType(mimeType, "text/plain", "text/html", "multipart/alternative");
                        if (isSimpleStructured) {
                            structureState = migrateSimpleMailContent(db, htmlContent, textContent,
                                    mimeType, mimeHeader, structureState);
                        } else {
                            mimeType = "multipart/mixed";
                            structureState =
                                    migrateComplexMailContent(db, attachmentDirOld, attachmentDirNew, messageId,
                                            htmlContent, textContent, mimeHeader, structureState);
                        }
                    }

                    cv.clear();
                    cv.put("mime_type", mimeType);
                    cv.put("message_part_id", structureState.rootPartId);
                    cv.put("attachment_count", attachmentCount);
                    db.update("messages", cv, "id = ?", new String[] { Long.toString(messageId) });
                } catch (IOException e) {
                    Log.e(K9.LOG_TAG, "error inserting into database", e);
                }
            }

        } finally {
            msgCursor.close();
        }

        cleanUpOldAttachmentDirectory(attachmentDirOld);

        dropOldMessagesTable(db);
    }

    @NonNull
    private static File renameOldAttachmentDirAndCreateNew(Account account, File attachmentDirNew) {
        File attachmentDirOld = new File(attachmentDirNew.getParent(),
                account.getUuid() + ".old_attach-" + System.currentTimeMillis());
        boolean moveOk = attachmentDirNew.renameTo(attachmentDirOld);
        if (!moveOk) {
            // TODO escalate?
            Log.e(K9.LOG_TAG, "Error moving attachment dir! All attachments might be lost!");
        }
        boolean mkdirOk = attachmentDirNew.mkdir();
        if (!mkdirOk) {
            // TODO escalate?
            Log.e(K9.LOG_TAG, "Error creating new attachment dir!");
        }
        return attachmentDirOld;
    }

    private static void dropOldMessagesTable(SQLiteDatabase db) {
        Log.d(K9.LOG_TAG, "Migration succeeded, dropping old tables.");
        db.execSQL("DROP TABLE messages_old");
        db.execSQL("DROP TABLE attachments");
        db.execSQL("DROP TABLE headers");
    }

    private static void cleanUpOldAttachmentDirectory(File attachmentDirOld) {
        for (File file : attachmentDirOld.listFiles()) {
            Log.d(K9.LOG_TAG, "deleting stale attachment file: " + file.getName());
            file.delete();
        }
        Log.d(K9.LOG_TAG, "deleting old attachment directory");
        attachmentDirOld.delete();
    }

    private static void copyMessageMetadataToNewTable(SQLiteDatabase db) {
        db.execSQL("INSERT INTO messages (" +
                "id, deleted, folder_id, uid, subject, date, sender_list, " +
                "to_list, cc_list, bcc_list, reply_to_list, attachment_count, " +
                "internal_date, message_id, preview, mime_type, " +
                "normalized_subject_hash, empty, read, flagged, answered" +
                ") SELECT " +
                "id, deleted, folder_id, uid, subject, date, sender_list, " +
                "to_list, cc_list, bcc_list, reply_to_list, attachment_count, " +
                "internal_date, message_id, preview, mime_type, " +
                "normalized_subject_hash, empty, read, flagged, answered " +
                "FROM messages_old");
    }

    private static void renameOldMessagesTableAndCreateNew(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE messages RENAME TO messages_old");

        db.execSQL("CREATE TABLE messages (" +
            "id INTEGER PRIMARY KEY, " +
            "deleted INTEGER default 0, " +
            "folder_id INTEGER, " +
            "uid TEXT, " +
            "subject TEXT, " +
            "date INTEGER, " +
            "flags TEXT, " +
            "sender_list TEXT, " +
            "to_list TEXT, " +
            "cc_list TEXT, " +
            "bcc_list TEXT, " +
            "reply_to_list TEXT, " +
            "attachment_count INTEGER, " +
            "internal_date INTEGER, " +
            "message_id TEXT, " +
            "preview TEXT, " +
            "mime_type TEXT, "+
            "normalized_subject_hash INTEGER, " +
            "empty INTEGER default 0, " +
            "read INTEGER default 0, " +
            "flagged INTEGER default 0, " +
            "answered INTEGER default 0, " +
            "forwarded INTEGER default 0, " +
            "message_part_id INTEGER" +
            ")");

        db.execSQL("CREATE TABLE message_parts (" +
            "id INTEGER PRIMARY KEY, " +
            "type INTEGER NOT NULL, " +
            "root INTEGER, " +
            "parent INTEGER NOT NULL, " +
            "seq INTEGER NOT NULL, " +
            "mime_type TEXT, " +
            "decoded_body_size INTEGER, " +
            "display_name TEXT, " +
            "header TEXT, " +
            "encoding TEXT, " +
            "charset TEXT, " +
            "data_location INTEGER NOT NULL, " +
            "data BLOB, " +
            "preamble TEXT, " +
            "epilogue TEXT, " +
            "boundary TEXT, " +
            "content_id TEXT, " +
            "server_extra TEXT" +
            ")");

        db.execSQL("CREATE TRIGGER set_message_part_root " +
                "AFTER INSERT ON message_parts " +
                "BEGIN " +
                "UPDATE message_parts SET root=id WHERE root IS NULL AND ROWID = NEW.ROWID; " +
                "END");
    }

    @Nullable
    private static MimeStructureState migratePgpMimeEncryptedContent(SQLiteDatabase db, long messageId,
            File attachmentDirOld, File attachmentDirNew, MimeHeader mimeHeader, MimeStructureState structureState) {

        Log.d(K9.LOG_TAG, "Attempting to migrate multipart/encrypted as pgp/mime");

        // we only handle attachment count == 2 here, so simply sorting application/pgp-encrypted
        // to the front (and application/octet-stream second) should suffice.
        String orderBy = "(mime_type LIKE 'application/pgp-encrypted') DESC";
        Cursor cursor = db.query("attachments",
                new String[] {
                        "id", "size", "name", "mime_type", "store_data",
                        "content_uri", "content_id", "content_disposition"
                },
                "message_id = ?", new String[] { Long.toString(messageId) }, null, null, orderBy);

        try {
            if (cursor.getCount() != 2) {
                Log.e(K9.LOG_TAG, "Found multipart/encrypted but bad number of attachments, handling as regular mail");
                return null;
            }

            cursor.moveToFirst();

            long firstPartId = cursor.getLong(0);
            int firstPartSize = cursor.getInt(1);
            String firstPartName = cursor.getString(2);
            String firstPartMimeType = cursor.getString(3);
            String firstPartStoreData = cursor.getString(4);
            String firstPartContentUriString = cursor.getString(5);

            if (!MimeUtil.isSameMimeType(firstPartMimeType, "application/pgp-encrypted")) {
                Log.e(K9.LOG_TAG,
                        "First part in multipart/encrypted wasn't application/pgp-encrypted, not handling as pgp/mime");
                return null;
            }

            cursor.moveToNext();

            long secondPartId = cursor.getLong(0);
            int secondPartSize = cursor.getInt(1);
            String secondPartName = cursor.getString(2);
            String secondPartMimeType = cursor.getString(3);
            String secondPartStoreData = cursor.getString(4);
            String secondPartContentUriString = cursor.getString(5);

            if (!MimeUtil.isSameMimeType(secondPartMimeType, "application/octet-stream")) {
                Log.e(K9.LOG_TAG,
                        "First part in multipart/encrypted wasn't application/octet-stream, not handling as pgp/mime");
                return null;
            }

            String boundary = MimeUtility.getHeaderParameter(
                    mimeHeader.getFirstHeader(MimeHeader.HEADER_CONTENT_TYPE), "boundary");
            if (TextUtils.isEmpty(boundary)) {
                boundary = MimeUtil.createUniqueBoundary();
            }
            mimeHeader.setHeader(MimeHeader.HEADER_CONTENT_TYPE,
                    String.format("multipart/encrypted; boundary=\"%s\"; protocol=\"application/pgp-encrypted\"", boundary));

            ContentValues cv = new ContentValues();
            cv.put("type", MessagePartType.UNKNOWN);
            cv.put("data_location", DataLocation.IN_DATABASE);
            cv.put("mime_type", "multipart/encrypted");
            cv.put("header", mimeHeader.toString());
            cv.put("boundary", boundary);
            structureState.applyValues(cv);

            long rootMessagePartId = db.insertOrThrow("message_parts", null, cv);
            structureState = structureState.nextMultipartChild(rootMessagePartId);

            structureState =
                    insertMimeAttachmentPart(db, attachmentDirOld, attachmentDirNew, structureState, firstPartId,
                            firstPartSize, firstPartName, "application/pgp-encrypted", firstPartStoreData,
                            firstPartContentUriString, null, null);

            structureState =
                    insertMimeAttachmentPart(db, attachmentDirOld, attachmentDirNew, structureState, secondPartId,
                            secondPartSize, secondPartName, "application/octet-stream", secondPartStoreData,
                            secondPartContentUriString, null, null);
        } finally {
            cursor.close();
        }

        return structureState;

    }

    private static MimeStructureState migrateComplexMailContent(SQLiteDatabase db,
            File attachmentDirOld, File attachmentDirNew, long messageId, String htmlContent, String textContent,
            MimeHeader mimeHeader, MimeStructureState structureState) throws IOException {
        Log.d(K9.LOG_TAG, "Processing mail with complex data structure as multipart/mixed");

        String boundary = MimeUtility.getHeaderParameter(
                mimeHeader.getFirstHeader(MimeHeader.HEADER_CONTENT_TYPE), "boundary");
        if (TextUtils.isEmpty(boundary)) {
            boundary = MimeUtil.createUniqueBoundary();
        }
        mimeHeader.setHeader(MimeHeader.HEADER_CONTENT_TYPE,
                String.format("multipart/mixed; boundary=\"%s\";", boundary));

        ContentValues cv = new ContentValues();
        cv.put("type", MessagePartType.UNKNOWN);
        cv.put("data_location", DataLocation.IN_DATABASE);
        cv.put("mime_type", "multipart/mixed");
        cv.put("header", mimeHeader.toString());
        cv.put("boundary", boundary);
        structureState.applyValues(cv);

        long rootMessagePartId = db.insertOrThrow("message_parts", null, cv);
        structureState = structureState.nextMultipartChild(rootMessagePartId);

        if (htmlContent != null) {
            htmlContent = replaceContentUriWithContentIdInHtmlPart(db, messageId, htmlContent);
        }

        if (textContent != null && htmlContent != null) {
            structureState = insertBodyAsMultipartAlternative(db, structureState, null, textContent, htmlContent);
            structureState = structureState.popParent();
        } else if (textContent != null) {
            structureState = insertTextualPartIntoDatabase(db, structureState, null, textContent, false);
        } else if (htmlContent != null) {
            structureState = insertTextualPartIntoDatabase(db, structureState, null, htmlContent, true);
        }

        structureState = insertAttachments(db, attachmentDirOld, attachmentDirNew, messageId, structureState);

        return structureState;
    }

    private static String replaceContentUriWithContentIdInHtmlPart(
            SQLiteDatabase db, long messageId, String htmlContent) {
        Cursor cursor = db.query("attachments", new String[] { "content_uri", "content_id" },
                "content_id IS NOT NULL AND message_id = ?", new String[] { Long.toString(messageId) }, null, null, null);

        try {
            while (cursor.moveToNext()) {
                String contentUriString = cursor.getString(0);
                String contentId = cursor.getString(1);
                // this is not super efficient, but occurs only once or twice
                htmlContent = htmlContent.replaceAll(Pattern.quote(contentUriString), "cid:" + contentId);
            }
        } finally {
            cursor.close();
        }

        return htmlContent;
    }

    private static MimeStructureState migrateSimpleMailContent(SQLiteDatabase db, String htmlContent,
            String textContent, String mimeType, MimeHeader mimeHeader, MimeStructureState structureState)
            throws IOException {
        Log.d(K9.LOG_TAG, "Processing mail with simple structure");

        if (MimeUtil.isSameMimeType(mimeType, "text/plain")) {
            return insertTextualPartIntoDatabase(db, structureState, mimeHeader, textContent, false);
        } else if (MimeUtil.isSameMimeType(mimeType, "text/html")) {
            return insertTextualPartIntoDatabase(db, structureState, mimeHeader, htmlContent, true);
        } else if (MimeUtil.isSameMimeType(mimeType, "multipart/alternative")) {
            return insertBodyAsMultipartAlternative(db, structureState, mimeHeader, textContent, htmlContent);
        } else {
            throw new IllegalStateException("migrateSimpleMailContent cannot handle mimeType " + mimeType);
        }
    }

    private static MimeStructureState insertAttachments(SQLiteDatabase db, File attachmentDirOld, File attachmentDirNew,
            long messageId, MimeStructureState structureState) {
        Cursor cursor = db.query("attachments",
                new String[] {
                        "id", "size", "name", "mime_type", "store_data",
                        "content_uri", "content_id", "content_disposition"
                },
                "message_id = ?", new String[] { Long.toString(messageId) }, null, null, null);

        try {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                int size = cursor.getInt(1);
                String name = cursor.getString(2);
                String mimeType = cursor.getString(3);
                String storeData = cursor.getString(4);
                String contentUriString = cursor.getString(5);
                String contentId = cursor.getString(6);
                String contentDisposition = cursor.getString(7);

                structureState =
                        insertMimeAttachmentPart(db, attachmentDirOld, attachmentDirNew, structureState, id, size, name,
                                mimeType, storeData, contentUriString, contentId, contentDisposition);

            }
        } finally {
            cursor.close();
        }

        return structureState;
    }

    private static MimeStructureState insertMimeAttachmentPart(SQLiteDatabase db, File attachmentDirOld,
            File attachmentDirNew, MimeStructureState structureState, long id, int size, String name, String mimeType,
            String storeData, String contentUriString, String contentId, String contentDisposition) {
        if (K9.DEBUG) {
            Log.d(K9.LOG_TAG, "processing attachment " + id + ", " + name + ", "
                    + mimeType + ", " + storeData + ", " + contentUriString);
        }

        if (contentDisposition == null) {
            contentDisposition = "attachment";
        }

        MimeHeader mimeHeader = new MimeHeader();
        mimeHeader.setHeader(MimeHeader.HEADER_CONTENT_TYPE,
                String.format("%s;\r\n name=\"%s\"", mimeType, name));
        mimeHeader.setHeader(MimeHeader.HEADER_CONTENT_DISPOSITION,
                String.format(Locale.US, "%s;\r\n filename=\"%s\";\r\n size=%d",
                        contentDisposition, name, size)); // TODO: Should use encoded word defined in RFC 2231.
        if (contentId != null) {
            mimeHeader.setHeader(MimeHeader.HEADER_CONTENT_ID, contentId);
        }

        boolean hasData = contentUriString != null;
        File attachmentFileToMove;
        if (hasData) {
            try {
                Uri contentUri = Uri.parse(contentUriString);
                List<String> pathSegments = contentUri.getPathSegments();
                String attachmentId = pathSegments.get(1);
                boolean isMatchingAttachmentId = Long.parseLong(attachmentId) == id;

                File attachmentFile = new File(attachmentDirOld, attachmentId);
                boolean isExistingAttachmentFile = attachmentFile.exists();

                if (!isMatchingAttachmentId) {
                    Log.e(K9.LOG_TAG, "mismatched attachment id. mark as missing");
                    attachmentFileToMove = null;
                } else if (!isExistingAttachmentFile) {
                    Log.e(K9.LOG_TAG, "attached file doesn't exist. mark as missing");
                    attachmentFileToMove = null;
                } else {
                    attachmentFileToMove = attachmentFile;
                }
            } catch (Exception e) {
                // anything here fails, conservatively assume the data doesn't exist
                attachmentFileToMove = null;
            }
        } else {
            attachmentFileToMove = null;
        }
        if (K9.DEBUG && attachmentFileToMove == null) {
            Log.d(K9.LOG_TAG, "matching attachment is in local cache");
        }

        boolean hasContentTypeAndIsInline = !TextUtils.isEmpty(contentId) && "inline".equalsIgnoreCase(contentDisposition);
        int messageType = hasContentTypeAndIsInline ? MessagePartType.HIDDEN_ATTACHMENT : MessagePartType.UNKNOWN;

        ContentValues cv = new ContentValues();
        cv.put("type", messageType);
        cv.put("mime_type", mimeType);
        cv.put("decoded_body_size", size);
        cv.put("display_name", name);
        cv.put("header", mimeHeader.toString());
        cv.put("encoding", MimeUtil.ENC_BINARY);
        cv.put("data_location", attachmentFileToMove != null ? DataLocation.ON_DISK : DataLocation.MISSING);
        cv.put("content_id", contentId);
        cv.put("server_extra", storeData);
        structureState.applyValues(cv);

        long partId = db.insertOrThrow("message_parts", null, cv);
        structureState = structureState.nextChild(partId);

        if (attachmentFileToMove != null) {
            boolean moveOk = attachmentFileToMove.renameTo(new File(attachmentDirNew, Long.toString(partId)));
            if (!moveOk) {
                Log.e(K9.LOG_TAG, "Moving attachment to new dir failed!");
            }
        }
        return structureState;
    }

    private static void updateFlagsForMessage(SQLiteDatabase db, long messageId, String messageFlags) {
        List<Flag> extraFlags = new ArrayList<>();
        if (messageFlags != null && messageFlags.length() > 0) {
            String[] flags = messageFlags.split(",");

            for (String flagStr : flags) {
                try {
                    Flag flag = Flag.valueOf(flagStr);
                    extraFlags.add(flag);
                } catch (Exception e) {
                    // Ignore bad flags
                }
            }
        }
        extraFlags.add(Flag.X_MIGRATED_FROM_V50);

        String flagsString = LocalStore.serializeFlags(extraFlags);
        db.execSQL("UPDATE messages SET flags = ? WHERE id = ?", new Object[] { flagsString, messageId } );
    }

    private static MimeStructureState insertBodyAsMultipartAlternative(SQLiteDatabase db,
            MimeStructureState structureState, MimeHeader mimeHeader,
            String textContent, String htmlContent) throws IOException {
        if (mimeHeader == null) {
            mimeHeader = new MimeHeader();
        }
        String boundary = MimeUtility.getHeaderParameter(
                mimeHeader.getFirstHeader(MimeHeader.HEADER_CONTENT_TYPE), "boundary");
        if (TextUtils.isEmpty(boundary)) {
            boundary = MimeUtil.createUniqueBoundary();
        }
        mimeHeader.setHeader(MimeHeader.HEADER_CONTENT_TYPE,
                String.format("multipart/alternative; boundary=\"%s\";", boundary));

        ContentValues cv = new ContentValues();
        cv.put("type", MessagePartType.UNKNOWN);
        cv.put("data_location", DataLocation.IN_DATABASE);
        cv.put("mime_type", "multipart/alternative");
        cv.put("header", mimeHeader.toString());
        cv.put("boundary", boundary);
        structureState.applyValues(cv);

        long multipartAlternativePartId = db.insertOrThrow("message_parts", null, cv);
        structureState = structureState.nextMultipartChild(multipartAlternativePartId);

        if (!TextUtils.isEmpty(textContent)) {
            structureState = insertTextualPartIntoDatabase(db, structureState, null, textContent, false);
        }

        if (!TextUtils.isEmpty(htmlContent)) {
            structureState = insertTextualPartIntoDatabase(db, structureState, null, htmlContent, true);
        }

        return structureState;
    }

    private static MimeStructureState insertTextualPartIntoDatabase(SQLiteDatabase db, MimeStructureState structureState,
            MimeHeader mimeHeader, String content, boolean isHtml) throws IOException {
        if (mimeHeader == null) {
            mimeHeader = new MimeHeader();
        }
        mimeHeader.setHeader(MimeHeader.HEADER_CONTENT_TYPE,
                isHtml ? "text/html; charset=\"utf-8\"" : "text/plain; charset=\"utf-8\"");
        mimeHeader.setHeader(MimeHeader.HEADER_CONTENT_TRANSFER_ENCODING, MimeUtil.ENC_QUOTED_PRINTABLE);

        ByteArrayOutputStream contentOutputStream = new ByteArrayOutputStream();
        QuotedPrintableOutputStream quotedPrintableOutputStream =
                new QuotedPrintableOutputStream(contentOutputStream, false);
        quotedPrintableOutputStream.write(content.getBytes());
        quotedPrintableOutputStream.flush();
        byte[] contentBytes = contentOutputStream.toByteArray();

        ContentValues cv = new ContentValues();
        cv.put("type", MessagePartType.UNKNOWN);
        cv.put("data_location", DataLocation.IN_DATABASE);
        cv.put("mime_type", isHtml ? "text/html" : "text/plain");
        cv.put("header", mimeHeader.toString());
        cv.put("data", contentBytes);
        cv.put("decoded_body_size", content.length());
        cv.put("encoding", MimeUtil.ENC_QUOTED_PRINTABLE);
        cv.put("charset", "utf-8");
        structureState.applyValues(cv);

        long partId = db.insertOrThrow("message_parts", null, cv);
        return structureState.nextChild(partId);
    }

    private static MimeHeader loadHeaderFromHeadersTable(SQLiteDatabase db, long messageId) {
        Cursor headersCursor = db.query("headers",
                new String[] { "name", "value" },
                "message_id = ?", new String[] { Long.toString(messageId) }, null, null, null);
        try {
            MimeHeader mimeHeader = new MimeHeader();
            while (headersCursor.moveToNext()) {
                String name = headersCursor.getString(0);
                String value = headersCursor.getString(1);
                mimeHeader.addHeader(name, value);
            }
            return mimeHeader;
        } finally {
            headersCursor.close();
        }
    }

    private static void dbCreateDatabaseFromScratch(SQLiteDatabase db) {

        db.execSQL("DROP TABLE IF EXISTS folders");
        db.execSQL("CREATE TABLE folders (" +
                "id INTEGER PRIMARY KEY," +
                "name TEXT, " +
                "last_updated INTEGER, " +
                "unread_count INTEGER, " +
                "visible_limit INTEGER, " +
                "status TEXT, " +
                "push_state TEXT, " +
                "last_pushed INTEGER, " +
                "flagged_count INTEGER default 0, " +
                "integrate INTEGER, " +
                "top_group INTEGER, " +
                "poll_class TEXT, " +
                "push_class TEXT, " +
                "display_class TEXT, " +
                "notify_class TEXT, " +
                "more_messages TEXT default \"unknown\"" +
                ")");

        db.execSQL("CREATE INDEX IF NOT EXISTS folder_name ON folders (name)");
        db.execSQL("DROP TABLE IF EXISTS messages");
        db.execSQL("CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY, " +
                "deleted INTEGER default 0, " +
                "folder_id INTEGER, " +
                "uid TEXT, " +
                "subject TEXT, " +
                "date INTEGER, " +
                "flags TEXT, " +
                "sender_list TEXT, " +
                "to_list TEXT, " +
                "cc_list TEXT, " +
                "bcc_list TEXT, " +
                "reply_to_list TEXT, " +
                "attachment_count INTEGER, " +
                "internal_date INTEGER, " +
                "message_id TEXT, " +
                "preview_type TEXT default \"none\", " +
                "preview TEXT, " +
                "mime_type TEXT, "+
                "normalized_subject_hash INTEGER, " +
                "empty INTEGER default 0, " +
                "read INTEGER default 0, " +
                "flagged INTEGER default 0, " +
                "answered INTEGER default 0, " +
                "forwarded INTEGER default 0, " +
                "message_part_id INTEGER" +
                ")");

        db.execSQL("CREATE TABLE message_parts (" +
                "id INTEGER PRIMARY KEY, " +
                "type INTEGER NOT NULL, " +
                "root INTEGER, " +
                "parent INTEGER NOT NULL, " +
                "seq INTEGER NOT NULL, " +
                "mime_type TEXT, " +
                "decoded_body_size INTEGER, " +
                "display_name TEXT, " +
                "header TEXT, " +
                "encoding TEXT, " +
                "charset TEXT, " +
                "data_location INTEGER NOT NULL, " +
                "data BLOB, " +
                "preamble TEXT, " +
                "epilogue TEXT, " +
                "boundary TEXT, " +
                "content_id TEXT, " +
                "server_extra TEXT" +
                ")");

        db.execSQL("CREATE TRIGGER set_message_part_root " +
                "AFTER INSERT ON message_parts " +
                "BEGIN " +
                "UPDATE message_parts SET root=id WHERE root IS NULL AND ROWID = NEW.ROWID; " +
                "END");

        db.execSQL("CREATE INDEX IF NOT EXISTS msg_uid ON messages (uid, folder_id)");
        db.execSQL("DROP INDEX IF EXISTS msg_folder_id");
        db.execSQL("DROP INDEX IF EXISTS msg_folder_id_date");
        db.execSQL("CREATE INDEX IF NOT EXISTS msg_folder_id_deleted_date ON messages (folder_id,deleted,internal_date)");

        db.execSQL("DROP INDEX IF EXISTS msg_empty");
        db.execSQL("CREATE INDEX IF NOT EXISTS msg_empty ON messages (empty)");

        db.execSQL("DROP INDEX IF EXISTS msg_read");
        db.execSQL("CREATE INDEX IF NOT EXISTS msg_read ON messages (read)");

        db.execSQL("DROP INDEX IF EXISTS msg_flagged");
        db.execSQL("CREATE INDEX IF NOT EXISTS msg_flagged ON messages (flagged)");

        db.execSQL("DROP INDEX IF EXISTS msg_composite");
        db.execSQL("CREATE INDEX IF NOT EXISTS msg_composite ON messages (deleted, empty,folder_id,flagged,read)");


        db.execSQL("DROP TABLE IF EXISTS threads");
        db.execSQL("CREATE TABLE threads (" +
                "id INTEGER PRIMARY KEY, " +
                "message_id INTEGER, " +
                "root INTEGER, " +
                "parent INTEGER" +
                ")");

        db.execSQL("DROP INDEX IF EXISTS threads_message_id");
        db.execSQL("CREATE INDEX IF NOT EXISTS threads_message_id ON threads (message_id)");

        db.execSQL("DROP INDEX IF EXISTS threads_root");
        db.execSQL("CREATE INDEX IF NOT EXISTS threads_root ON threads (root)");

        db.execSQL("DROP INDEX IF EXISTS threads_parent");
        db.execSQL("CREATE INDEX IF NOT EXISTS threads_parent ON threads (parent)");

        db.execSQL("DROP TRIGGER IF EXISTS set_thread_root");
        db.execSQL("CREATE TRIGGER set_thread_root " +
                "AFTER INSERT ON threads " +
                "BEGIN " +
                "UPDATE threads SET root=id WHERE root IS NULL AND ROWID = NEW.ROWID; " +
                "END");

        db.execSQL("DROP TABLE IF EXISTS pending_commands");
        db.execSQL("CREATE TABLE pending_commands " +
                "(id INTEGER PRIMARY KEY, command TEXT, arguments TEXT)");

        db.execSQL("DROP TRIGGER IF EXISTS delete_folder");
        db.execSQL("CREATE TRIGGER delete_folder BEFORE DELETE ON folders BEGIN DELETE FROM messages WHERE old.id = folder_id; END;");

        db.execSQL("DROP TRIGGER IF EXISTS delete_message");
        db.execSQL("CREATE TRIGGER delete_message " +
                "BEFORE DELETE ON messages " +
                "BEGIN " +
                "DELETE FROM message_parts WHERE root = OLD.message_part_id;" +
                "END");
    }

    private static void db30AddDeletedColumn(SQLiteDatabase db) {
        try {
            db.execSQL("ALTER TABLE messages ADD deleted INTEGER default 0");
        } catch (SQLiteException e) {
            if (! e.toString().startsWith("duplicate column name: deleted")) {
                throw e;
            }
        }
    }

    private static void db31ChangeMsgFolderIdDeletedDateIndex(SQLiteDatabase db) {
        db.execSQL("DROP INDEX IF EXISTS msg_folder_id_date");
        db.execSQL("CREATE INDEX IF NOT EXISTS msg_folder_id_deleted_date ON messages (folder_id,deleted,internal_date)");
    }

    private static void db32UpdateDeletedColumnFromFlags(SQLiteDatabase db) {
        db.execSQL("UPDATE messages SET deleted = 1 WHERE flags LIKE '%DELETED%'");
    }

    private static void db33AddPreviewColumn(SQLiteDatabase db) {
        try {
            db.execSQL("ALTER TABLE messages ADD preview TEXT");
        } catch (SQLiteException e) {
            if (! e.toString().startsWith("duplicate column name: preview")) {
                throw e;
            }
        }
    }

    private static void db34AddFlaggedCountColumn(SQLiteDatabase db) {
        try {
            db.execSQL("ALTER TABLE folders ADD flagged_count INTEGER default 0");
        } catch (SQLiteException e) {
            if (! e.getMessage().startsWith("duplicate column name: flagged_count")) {
                throw e;
            }
        }
    }

    private static void db35UpdateRemoveXNoSeenInfoFlag(SQLiteDatabase db) {
        try {
            db.execSQL("update messages set flags = replace(flags, 'X_NO_SEEN_INFO', 'X_BAD_FLAG')");
        } catch (SQLiteException e) {
            Log.e(K9.LOG_TAG, "Unable to get rid of obsolete flag X_NO_SEEN_INFO", e);
        }
    }

    private static void db36AddAttachmentsContentIdColumn(SQLiteDatabase db) {
        try {
            db.execSQL("ALTER TABLE attachments ADD content_id TEXT");
        } catch (SQLiteException e) {
            Log.e(K9.LOG_TAG, "Unable to add content_id column to attachments");
        }
    }

    private static void db37AddAttachmentsContentDispositionColumn(SQLiteDatabase db) {
        try {
            db.execSQL("ALTER TABLE attachments ADD content_disposition TEXT");
        } catch (SQLiteException e) {
            Log.e(K9.LOG_TAG, "Unable to add content_disposition column to attachments");
        }
    }

    private static void db39HeadersPruneOrphans(SQLiteDatabase db) {
        try {
            db.execSQL("DELETE FROM headers WHERE id in (SELECT headers.id FROM headers LEFT JOIN messages ON headers.message_id = messages.id WHERE messages.id IS NULL)");
        } catch (SQLiteException e) {
            Log.e(K9.LOG_TAG, "Unable to remove extra header data from the database");
        }
    }

    private static void db40AddMimeTypeColumn(SQLiteDatabase db) {
        try {
            db.execSQL("ALTER TABLE messages ADD mime_type TEXT");
        } catch (SQLiteException e) {
            Log.e(K9.LOG_TAG, "Unable to add mime_type column to messages");
        }
    }

    private static void db41UpdateFolderMetadata(SQLiteDatabase db, LocalStore localStore) {
        Cursor cursor = null;
        try {
            SharedPreferences prefs = localStore.getPreferences();
            cursor = db.rawQuery("SELECT id, name FROM folders", null);
            while (cursor.moveToNext()) {
                try {
                    int id = cursor.getInt(0);
                    String name = cursor.getString(1);
                    update41Metadata(db, localStore, prefs, id, name);
                } catch (Exception e) {
                    Log.e(K9.LOG_TAG, " error trying to ugpgrade a folder class", e);
                }
            }
        } catch (SQLiteException e) {
            Log.e(K9.LOG_TAG, "Exception while upgrading database to v41. folder classes may have vanished", e);
        } finally {
            Utility.closeQuietly(cursor);
        }
    }

    private static void update41Metadata(SQLiteDatabase db, LocalStore localStore, SharedPreferences prefs,
            int id, String name) {

        Folder.FolderClass displayClass = Folder.FolderClass.NO_CLASS;
        Folder.FolderClass syncClass = Folder.FolderClass.INHERITED;
        Folder.FolderClass pushClass = Folder.FolderClass.SECOND_CLASS;
        boolean inTopGroup = false;
        boolean integrate = false;
        if (localStore.getAccount().getInboxFolderName().equals(name)) {
            displayClass = Folder.FolderClass.FIRST_CLASS;
            syncClass =  Folder.FolderClass.FIRST_CLASS;
            pushClass =  Folder.FolderClass.FIRST_CLASS;
            inTopGroup = true;
            integrate = true;
        }

        try {
            displayClass = Folder.FolderClass.valueOf(prefs.getString(localStore.uUid + "." + name + ".displayMode", displayClass.name()));
            syncClass = Folder.FolderClass.valueOf(prefs.getString(localStore.uUid + "." + name + ".syncMode", syncClass.name()));
            pushClass = Folder.FolderClass.valueOf(prefs.getString(localStore.uUid + "." + name + ".pushMode", pushClass.name()));
            inTopGroup = prefs.getBoolean(localStore.uUid + "." + name + ".inTopGroup", inTopGroup);
            integrate = prefs.getBoolean(localStore.uUid + "." + name + ".integrate", integrate);
        } catch (Exception e) {
            Log.e(K9.LOG_TAG, " Throwing away an error while trying to upgrade folder metadata", e);
        }

        if (displayClass == Folder.FolderClass.NONE) {
            displayClass = Folder.FolderClass.NO_CLASS;
        }
        if (syncClass == Folder.FolderClass.NONE) {
            syncClass = Folder.FolderClass.INHERITED;
        }
        if (pushClass == Folder.FolderClass.NONE) {
            pushClass = Folder.FolderClass.INHERITED;
        }

        db.execSQL("UPDATE folders SET integrate = ?, top_group = ?, poll_class=?, push_class =?, display_class = ? WHERE id = ?",
                   new Object[] { integrate, inTopGroup, syncClass, pushClass, displayClass, id });

    }

    private static void db41FoldersAddClassColumns(SQLiteDatabase db) {
        try {
            db.execSQL("ALTER TABLE folders ADD integrate INTEGER");
            db.execSQL("ALTER TABLE folders ADD top_group INTEGER");
            db.execSQL("ALTER TABLE folders ADD poll_class TEXT");
            db.execSQL("ALTER TABLE folders ADD push_class TEXT");
            db.execSQL("ALTER TABLE folders ADD display_class TEXT");
        } catch (SQLiteException e) {
            if (! e.getMessage().startsWith("duplicate column name:")) {
                throw e;
            }
        }
    }

    private static void db42From41MoveFolderPreferences(LocalStore localStore) {
        try {
            long startTime = System.currentTimeMillis();
            SharedPreferences.Editor editor = localStore.getPreferences().edit();

            List<? extends Folder > folders = localStore.getPersonalNamespaces(true);
            for (Folder folder : folders) {
                if (folder instanceof LocalFolder) {
                    LocalFolder lFolder = (LocalFolder)folder;
                    lFolder.save(editor);
                }
            }

            editor.commit();
            long endTime = System.currentTimeMillis();
            Log.i(K9.LOG_TAG, "Putting folder preferences for " + folders.size() +
                    " folders back into Preferences took " + (endTime - startTime) + " ms");
        } catch (Exception e) {
            Log.e(K9.LOG_TAG, "Could not replace Preferences in upgrade from DB_VERSION 41", e);
        }
    }

    private static void db43FixOutboxFolders(SQLiteDatabase db, LocalStore localStore) {
        try {
            // If folder "OUTBOX" (old, v3.800 - v3.802) exists, rename it to
            // "K9MAIL_INTERNAL_OUTBOX" (new)
            LocalFolder oldOutbox = new LocalFolder(localStore, "OUTBOX");
            if (oldOutbox.exists()) {
                ContentValues cv = new ContentValues();
                cv.put("name", Account.OUTBOX);
                db.update("folders", cv, "name = ?", new String[] { "OUTBOX" });
                Log.i(K9.LOG_TAG, "Renamed folder OUTBOX to " + Account.OUTBOX);
            }

            // Check if old (pre v3.800) localized outbox folder exists
            String localizedOutbox = localStore.context.getString(R.string.special_mailbox_name_outbox);
            LocalFolder obsoleteOutbox = new LocalFolder(localStore, localizedOutbox);
            if (obsoleteOutbox.exists()) {
                // Get all messages from the localized outbox ...
                List<? extends Message> messages = obsoleteOutbox.getMessages(null, false);

                if (messages.size() > 0) {
                    // ... and move them to the drafts folder (we don't want to
                    // surprise the user by sending potentially very old messages)
                    LocalFolder drafts = new LocalFolder(localStore, localStore.getAccount().getDraftsFolderName());
                    obsoleteOutbox.moveMessages(messages, drafts);
                }

                // Now get rid of the localized outbox
                obsoleteOutbox.delete();
                obsoleteOutbox.delete(true);
            }
        } catch (Exception e) {
            Log.e(K9.LOG_TAG, "Error trying to fix the outbox folders", e);
        }
    }

    private static void db44AddMessagesThreadingColumns(SQLiteDatabase db) {
        try {
            db.execSQL("ALTER TABLE messages ADD thread_root INTEGER");
            db.execSQL("ALTER TABLE messages ADD thread_parent INTEGER");
            db.execSQL("ALTER TABLE messages ADD normalized_subject_hash INTEGER");
            db.execSQL("ALTER TABLE messages ADD empty INTEGER");
        } catch (SQLiteException e) {
            if (! e.getMessage().startsWith("duplicate column name:")) {
                throw e;
            }
        }
    }

    private static void db45ChangeThreadingIndexes(SQLiteDatabase db) {
        try {
            db.execSQL("DROP INDEX IF EXISTS msg_empty");
            db.execSQL("CREATE INDEX IF NOT EXISTS msg_empty ON messages (empty)");

            db.execSQL("DROP INDEX IF EXISTS msg_thread_root");
            db.execSQL("CREATE INDEX IF NOT EXISTS msg_thread_root ON messages (thread_root)");

            db.execSQL("DROP INDEX IF EXISTS msg_thread_parent");
            db.execSQL("CREATE INDEX IF NOT EXISTS msg_thread_parent ON messages (thread_parent)");
        } catch (SQLiteException e) {
            if (! e.getMessage().startsWith("duplicate column name:")) {
                throw e;
            }
        }
    }

    private static void db46AddMessagesFlagColumns(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE messages ADD read INTEGER default 0");
        db.execSQL("ALTER TABLE messages ADD flagged INTEGER default 0");
        db.execSQL("ALTER TABLE messages ADD answered INTEGER default 0");
        db.execSQL("ALTER TABLE messages ADD forwarded INTEGER default 0");

        String[] projection = { "id", "flags" };

        ContentValues cv = new ContentValues();
        List<Flag> extraFlags = new ArrayList<>();

        Cursor cursor = db.query("messages", projection, null, null, null, null, null);
        try {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String flagList = cursor.getString(1);

                boolean read = false;
                boolean flagged = false;
                boolean answered = false;
                boolean forwarded = false;

                if (flagList != null && flagList.length() > 0) {
                    String[] flags = flagList.split(",");

                    for (String flagStr : flags) {
                        try {
                            Flag flag = Flag.valueOf(flagStr);

                            switch (flag) {
                                case ANSWERED: {
                                    answered = true;
                                    break;
                                }
                                case DELETED: {
                                    // Don't store this in column 'flags'
                                    break;
                                }
                                case FLAGGED: {
                                    flagged = true;
                                    break;
                                }
                                case FORWARDED: {
                                    forwarded = true;
                                    break;
                                }
                                case SEEN: {
                                    read = true;
                                    break;
                                }
                                case DRAFT:
                                case RECENT:
                                case X_DESTROYED:
                                case X_DOWNLOADED_FULL:
                                case X_DOWNLOADED_PARTIAL:
                                case X_REMOTE_COPY_STARTED:
                                case X_SEND_FAILED:
                                case X_SEND_IN_PROGRESS: {
                                    extraFlags.add(flag);
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            // Ignore bad flags
                        }
                    }
                }


                cv.put("flags", LocalStore.serializeFlags(extraFlags));
                cv.put("read", read);
                cv.put("flagged", flagged);
                cv.put("answered", answered);
                cv.put("forwarded", forwarded);

                db.update("messages", cv, "id = ?", new String[] { Long.toString(id) });

                cv.clear();
                extraFlags.clear();
            }
        } finally {
            cursor.close();
        }

        db.execSQL("CREATE INDEX IF NOT EXISTS msg_read ON messages (read)");
        db.execSQL("CREATE INDEX IF NOT EXISTS msg_flagged ON messages (flagged)");
    }

    private static void db47CreateThreadsTable(SQLiteDatabase db) {
        // Create new 'threads' table
        db.execSQL("DROP TABLE IF EXISTS threads");
        db.execSQL("CREATE TABLE threads (" +
                "id INTEGER PRIMARY KEY, " +
                "message_id INTEGER, " +
                "root INTEGER, " +
                "parent INTEGER" +
                ")");

        // Create indices for new table
        db.execSQL("DROP INDEX IF EXISTS threads_message_id");
        db.execSQL("CREATE INDEX IF NOT EXISTS threads_message_id ON threads (message_id)");

        db.execSQL("DROP INDEX IF EXISTS threads_root");
        db.execSQL("CREATE INDEX IF NOT EXISTS threads_root ON threads (root)");

        db.execSQL("DROP INDEX IF EXISTS threads_parent");
        db.execSQL("CREATE INDEX IF NOT EXISTS threads_parent ON threads (parent)");

        // Create entries for all messages in 'threads' table
        db.execSQL("INSERT INTO threads (message_id) SELECT id FROM messages");

        // Copy thread structure from 'messages' table to 'threads'
        Cursor cursor = db.query("messages",
                new String[] { "id", "thread_root", "thread_parent" },
                null, null, null, null, null);
        try {
            ContentValues cv = new ContentValues();
            while (cursor.moveToNext()) {
                cv.clear();
                long messageId = cursor.getLong(0);

                if (!cursor.isNull(1)) {
                    long threadRootMessageId = cursor.getLong(1);
                    db.execSQL("UPDATE threads SET root = (SELECT t.id FROM " +
                                    "threads t WHERE t.message_id = ?) " +
                                    "WHERE message_id = ?",
                            new String[] {
                                    Long.toString(threadRootMessageId),
                                    Long.toString(messageId)
                            });
                }

                if (!cursor.isNull(2)) {
                    long threadParentMessageId = cursor.getLong(2);
                    db.execSQL("UPDATE threads SET parent = (SELECT t.id FROM " +
                                    "threads t WHERE t.message_id = ?) " +
                                    "WHERE message_id = ?",
                            new String[] {
                                    Long.toString(threadParentMessageId),
                                    Long.toString(messageId)
                            });
                }
            }
        } finally {
            cursor.close();
        }

        // Remove indices for old thread-related columns in 'messages' table
        db.execSQL("DROP INDEX IF EXISTS msg_thread_root");
        db.execSQL("DROP INDEX IF EXISTS msg_thread_parent");

        // Clear out old thread-related columns in 'messages'
        ContentValues cv = new ContentValues();
        cv.putNull("thread_root");
        cv.putNull("thread_parent");
        db.update("messages", cv, null, null);
    }

    private static void db48UpdateThreadsSetRootWhereNull(SQLiteDatabase db) {
        db.execSQL("UPDATE threads SET root=id WHERE root IS NULL");

        db.execSQL("CREATE TRIGGER set_thread_root " +
                "AFTER INSERT ON threads " +
                "BEGIN " +
                "UPDATE threads SET root=id WHERE root IS NULL AND ROWID = NEW.ROWID; " +
                "END");
    }

    private static void db49CreateMsgCompositeIndex(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS msg_composite ON messages (deleted, empty,folder_id,flagged,read)");
    }

    private static void db50FoldersAddNotifyClassColumn(SQLiteDatabase db, LocalStore localStore) {
        try {
            db.execSQL("ALTER TABLE folders ADD notify_class TEXT default '" +
                    Folder.FolderClass.INHERITED.name() + "'");
        } catch (SQLiteException e) {
            if (! e.getMessage().startsWith("duplicate column name:")) {
                throw e;
            }
        }

        ContentValues cv = new ContentValues();
        cv.put("notify_class", Folder.FolderClass.FIRST_CLASS.name());

        db.update("folders", cv, "name = ?",
                new String[] { localStore.getAccount().getInboxFolderName() });
    }

    private static void db52AddMoreMessagesColumnToFoldersTable(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE folders ADD more_messages TEXT default \"unknown\"");
    }

    private static void db53RemoveNullValuesFromEmptyColumnInMessagesTable(SQLiteDatabase db) {
        db.execSQL("UPDATE messages SET empty = 0 WHERE empty IS NULL");
    }

    private static void db54AddPreviewTypeColumn(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE messages ADD preview_type TEXT default \"none\"");
        db.execSQL("UPDATE messages SET preview_type = 'text' WHERE preview IS NOT NULL");
    }

}
