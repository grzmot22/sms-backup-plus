/*
 * Copyright (c) 2009 Christoph Studer <chstuder@gmail.com>
 * Copyright (c) 2010 Jan Berkel <jan.berkel@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zegoggles.smssync.mail;

import android.annotation.TargetApi;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Build;
import android.provider.CallLog;
import android.provider.Contacts.ContactMethods;
import android.provider.Contacts.People;
import android.provider.Contacts.Phones;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.text.TextUtils;
import android.util.Log;
import com.fsck.k9.mail.Address;
import com.fsck.k9.mail.BodyPart;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.Message.RecipientType;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.internet.MimeBodyPart;
import com.fsck.k9.mail.internet.MimeMessage;
import com.fsck.k9.mail.internet.MimeMultipart;
import com.fsck.k9.mail.internet.TextBody;
import com.github.jberkel.whassup.model.WhatsAppMessage;
import com.zegoggles.smssync.Consts;
import com.zegoggles.smssync.MmsConsts;
import com.zegoggles.smssync.R;
import com.zegoggles.smssync.SmsConsts;
import com.zegoggles.smssync.contacts.ContactAccessor;
import com.zegoggles.smssync.preferences.AddressStyle;
import com.zegoggles.smssync.preferences.PrefStore;
import com.zegoggles.smssync.utils.ThreadHelper;
import org.apache.commons.io.IOUtils;
import org.apache.james.mime4j.codec.EncoderUtil;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static com.zegoggles.smssync.App.LOCAL_LOGV;
import static com.zegoggles.smssync.App.TAG;
import static com.zegoggles.smssync.mail.Attachment.*;

public class CursorToMessage {
    //ContactsContract.CommonDataKinds.Email.CONTENT_URI
    public static final Uri ECLAIR_CONTENT_URI =
            Uri.parse("content://com.android.contacts/data/emails");

    // PhoneLookup.CONTENT_FILTER_URI
    public static final Uri ECLAIR_CONTENT_FILTER_URI =
            Uri.parse("content://com.android.contacts/phone_lookup");

    private static final String REFERENCE_UID_TEMPLATE = "<%s.%s@sms-backup-plus.local>";
    private static final String MSG_ID_TEMPLATE = "<%s@sms-backup-plus.local>";

    private static final boolean NEW_CONTACT_API = Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.ECLAIR;

    private static final String[] PHONE_PROJECTION = getPhoneProjection();

    @TargetApi(Build.VERSION_CODES.ECLAIR)
    private static String[] getPhoneProjection() {
        return NEW_CONTACT_API ?
                new String[]{Contacts._ID, Contacts.DISPLAY_NAME} :
                new String[]{Phones.PERSON_ID, People.NAME, Phones.NUMBER};
    }

    // only query for needed fields
    // http://stackoverflow.com/questions/12033234/get-calls-provider-internal-structure
    public static final String[] CALLLOG_PROJECTION = {
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.DURATION,
            CallLog.Calls.DATE,
            CallLog.Calls.TYPE
    };

    private static final String UNKNOWN_NUMBER = "unknown.number";
    private static final String UNKNOWN_EMAIL = "unknown.email";

    private static final int MAX_PEOPLE_CACHE_SIZE = 500;
    private final AddressStyle mStyle;

    private final Context mContext;
    private final Address mUserAddress;
    private final ThreadHelper threadHelper = new ThreadHelper();

    // simple LRU cache
    @SuppressWarnings("serial")
    private final Map<String, PersonRecord> mPeopleCache =
            new LinkedHashMap<String, PersonRecord>(MAX_PEOPLE_CACHE_SIZE + 1, .75F, true) {
                @Override
                public boolean removeEldestEntry(Map.Entry<String, PersonRecord> eldest) {
                    return size() > MAX_PEOPLE_CACHE_SIZE;
                }
            };

    private String mReferenceValue;
    private final boolean mMarkAsRead;
    private final boolean mPrefix;

    /**
     * used for whitelisting specific contacts
     */
    private final ContactAccessor.GroupContactIds allowedIds;

    public CursorToMessage(Context ctx, String userEmail) {
        mContext = ctx;
        mUserAddress = new Address(userEmail);
        mMarkAsRead = PrefStore.getMarkAsRead(ctx);
        mReferenceValue = PrefStore.getReferenceUid(ctx);
        mPrefix = PrefStore.getMailSubjectPrefix(mContext);
        mStyle = PrefStore.getEmailAddressStyle(ctx);

        if (mReferenceValue == null) {
            mReferenceValue = generateReferenceValue();
            PrefStore.setReferenceUid(ctx, mReferenceValue);
        }

        switch (PrefStore.getBackupContactGroup(ctx).type) {
            case EVERYBODY:
                allowedIds = null;
                break;
            default:
                allowedIds = ContactAccessor.Get.instance().getGroupContactIds(ctx, PrefStore.getBackupContactGroup(ctx));
                if (LOCAL_LOGV) Log.v(TAG, "whitelisted ids for backup: " + allowedIds);
        }

        Log.d(TAG, String.format(Locale.ENGLISH, "using %s contacts API", NEW_CONTACT_API ? "new" : "old"));
    }

    public ConversionResult cursorToMessages(final Cursor cursor, final int maxEntries,
                                             DataType dataType) throws MessagingException {
        final String[] columns = cursor.getColumnNames();
        final ConversionResult result = new ConversionResult(dataType);
        do {
            final Map<String, String> msgMap = new HashMap<String, String>(columns.length);
            for (int i = 0; i < columns.length; i++) {
                String value;
                try {
                    value = cursor.getString(i);
                } catch (SQLiteException ignored) {
                    // this can happen in case of BLOBS in the DB
                    // column type checking is API level >= 11
                    value = "[BLOB]";
                }
                msgMap.put(columns[i], value);
            }

            Message m = null;
            switch (dataType) {
                case SMS:
                    m = messageFromMapSms(msgMap);
                    break;
                case MMS:
                    m = messageFromMapMms(msgMap);
                    break;
                case CALLLOG:
                    m = messageFromMapCallLog(msgMap);
                    break;
                case WHATSAPP:
                    m = messageFromMapWhatsApp(cursor);
                    break;
            }
            if (m != null) {
                result.messageList.add(m);
                result.mapList.add(msgMap);

                String dateHeader = Headers.get(m, Headers.DATE);
                if (dateHeader != null) {
                    final long date = Long.parseLong(dateHeader);
                    if (date > result.maxDate) {
                        result.maxDate = date;
                    }
                }
            }
        } while (result.messageList.size() < maxEntries && cursor.moveToNext());

        return result;
    }

    public ContentValues messageToContentValues(final Message message)
            throws IOException, MessagingException {
        if (message == null) throw new MessagingException("message is null");

        final ContentValues values = new ContentValues();
        switch (getDataType(message)) {
            case SMS:
                if (message.getBody() == null) throw new MessagingException("body is null");

                InputStream is = message.getBody().getInputStream();
                if (is == null) {
                    throw new MessagingException("body.getInputStream() is null for " + message.getBody());
                }
                final String body = IOUtils.toString(is);
                final String address = Headers.get(message, Headers.ADDRESS);
                values.put(SmsConsts.BODY, body);
                values.put(SmsConsts.ADDRESS, address);
                values.put(SmsConsts.TYPE, Headers.get(message, Headers.TYPE));
                values.put(SmsConsts.PROTOCOL, Headers.get(message, Headers.PROTOCOL));
                values.put(SmsConsts.SERVICE_CENTER, Headers.get(message, Headers.SERVICE_CENTER));
                values.put(SmsConsts.DATE, Headers.get(message, Headers.DATE));
                values.put(SmsConsts.STATUS, Headers.get(message, Headers.STATUS));
                values.put(SmsConsts.THREAD_ID, threadHelper.getThreadId(mContext, address));
                values.put(SmsConsts.READ,
                        PrefStore.getMarkAsReadOnRestore(mContext) ? "1" : Headers.get(message, Headers.READ));
                break;
            case CALLLOG:
                values.put(CallLog.Calls.NUMBER, Headers.get(message, Headers.ADDRESS));
                values.put(CallLog.Calls.TYPE, Integer.valueOf(Headers.get(message, Headers.TYPE)));
                values.put(CallLog.Calls.DATE, Headers.get(message, Headers.DATE));
                values.put(CallLog.Calls.DURATION, Long.valueOf(Headers.get(message, Headers.DURATION)));
                values.put(CallLog.Calls.NEW, 0);

                PersonRecord record = lookupPerson(Headers.get(message, Headers.ADDRESS));
                if (!record.unknown) {
                    values.put(CallLog.Calls.CACHED_NAME, record.name);
                    values.put(CallLog.Calls.CACHED_NUMBER_TYPE, -2);
                }

                break;
            default:
                throw new MessagingException("don't know how to restore " + getDataType(message));
        }

        return values;
    }

    public DataType getDataType(Message message) {
        final String dataTypeHeader = Headers.get(message, Headers.DATATYPE);
        final String typeHeader = Headers.get(message, Headers.TYPE);
        //we have two possible header sets here
        //legacy:  there is no CursorToMessage.Headers.DATATYPE. CursorToMessage.Headers.TYPE
        //         contains either the string "mms" or an integer which is the internal type of the sms
        //current: there IS a Headers.DATATYPE containing a string representation of CursorToMessage.DataType
        //         CursorToMessage.Headers.TYPE then contains the type of the sms, mms or calllog entry
        //The current header set was introduced in version 1.2.00
        if (dataTypeHeader == null) {
            return MmsConsts.LEGACY_HEADER.equalsIgnoreCase(typeHeader) ? DataType.MMS : DataType.SMS;
        } else {
            try {
                return DataType.valueOf(dataTypeHeader.toUpperCase(Locale.ENGLISH));
            } catch (IllegalArgumentException e) {
                return DataType.SMS; // whateva
            }
        }
    }

    public static String formattedDuration(int duration) {
        return String.format(Locale.ENGLISH, "%02d:%02d:%02d",
                duration / 3600,
                duration % 3600 / 60,
                duration % 3600 % 60);
    }

    public String callTypeString(int callType, String name) {
        if (name == null) {
            return mContext.getString(
                    callType == CallLog.Calls.OUTGOING_TYPE ? R.string.call_outgoing :
                            callType == CallLog.Calls.INCOMING_TYPE ? R.string.call_incoming :
                                    R.string.call_missed);
        } else {
            return mContext.getString(
                    callType == CallLog.Calls.OUTGOING_TYPE ? R.string.call_outgoing_text :
                            callType == CallLog.Calls.INCOMING_TYPE ? R.string.call_incoming_text :
                                    R.string.call_missed_text,
                    name);
        }
    }

    /* Look up a person */
    public PersonRecord lookupPerson(final String address) {
        if (TextUtils.isEmpty(address)) {
            final PersonRecord record = new PersonRecord(mStyle);
            record.number = "-1";
            record.email = getUnknownEmail(null);
            record.unknown = true;
            return record;
        } else if (!mPeopleCache.containsKey(address)) {
            Uri personUri = Uri.withAppendedPath(NEW_CONTACT_API ? ECLAIR_CONTENT_FILTER_URI :
                    Phones.CONTENT_FILTER_URL, Uri.encode(address));

            Cursor c = mContext.getContentResolver().query(personUri, PHONE_PROJECTION, null, null, null);
            final PersonRecord record = new PersonRecord(mStyle);
            if (c != null && c.moveToFirst()) {
                record._id = c.getLong(c.getColumnIndex(PHONE_PROJECTION[0]));
                record.name = sanitize(c.getString(c.getColumnIndex(PHONE_PROJECTION[1])));
                record.number = sanitize(NEW_CONTACT_API ? address :
                        c.getString(c.getColumnIndex(PHONE_PROJECTION[2])));
                record.email = getPrimaryEmail(record._id, record.number);
            } else {
                if (LOCAL_LOGV) Log.v(TAG, "Looked up unknown address: " + address);

                record.number = sanitize(address);
                record.email = getUnknownEmail(address);
                record.unknown = true;
            }
            mPeopleCache.put(address, record);

            if (c != null) c.close();
        }
        return mPeopleCache.get(address);
    }

    private @Nullable Message messageFromMapSms(Map<String, String> msgMap) throws MessagingException {
        final String address = msgMap.get(SmsConsts.ADDRESS);
        if (address == null || address.trim().length() == 0) {
            return null;
        }
        PersonRecord record = lookupPerson(address);
        if (!backupPerson(record, DataType.SMS)) return null;

        final Message msg = new MimeMessage();
        msg.setSubject(getSubject(DataType.SMS, record));
        msg.setBody(new TextBody(msgMap.get(SmsConsts.BODY)));

        final int messageType = Integer.valueOf(msgMap.get(SmsConsts.TYPE));
        if (SmsConsts.MESSAGE_TYPE_INBOX == messageType) {
            // Received message
            msg.setFrom(record.getAddress());
            msg.setRecipient(RecipientType.TO, mUserAddress);
        } else {
            // Sent message
            msg.setRecipient(RecipientType.TO, record.getAddress());
            msg.setFrom(mUserAddress);
        }

        try {
            final Date then = new Date(Long.valueOf(msgMap.get(SmsConsts.DATE)));
            msg.setSentDate(then);
            msg.setInternalDate(then);
            msg.setHeader("Message-ID", createMessageId(then, address, messageType));
        } catch (NumberFormatException n) {
            Log.e(TAG, "error parsing date", n);
        }

        // Threading by person ID, not by thread ID. I think this value is more stable.
        msg.setHeader("References",
                String.format(REFERENCE_UID_TEMPLATE, mReferenceValue, sanitize(record.getId())));
        msg.setHeader(Headers.ID, msgMap.get(SmsConsts.ID));
        msg.setHeader(Headers.ADDRESS, sanitize(address));
        msg.setHeader(Headers.DATATYPE, DataType.SMS.toString());
        msg.setHeader(Headers.TYPE, msgMap.get(SmsConsts.TYPE));
        msg.setHeader(Headers.DATE, msgMap.get(SmsConsts.DATE));
        msg.setHeader(Headers.THREAD_ID, msgMap.get(SmsConsts.THREAD_ID));
        msg.setHeader(Headers.READ, msgMap.get(SmsConsts.READ));
        msg.setHeader(Headers.STATUS, msgMap.get(SmsConsts.STATUS));
        msg.setHeader(Headers.PROTOCOL, msgMap.get(SmsConsts.PROTOCOL));
        msg.setHeader(Headers.SERVICE_CENTER, msgMap.get(SmsConsts.SERVICE_CENTER));
        msg.setHeader(Headers.BACKUP_TIME, new Date().toGMTString());
        msg.setHeader(Headers.VERSION, PrefStore.getVersion(mContext, true));
        msg.setFlag(Flag.SEEN, mMarkAsRead);

        return msg;
    }


    private @Nullable Message messageFromMapWhatsApp(Cursor cursor) throws MessagingException {
        WhatsAppMessage whatsapp = new WhatsAppMessage(cursor);
        // we don't deal with group messages (yet)

        if (whatsapp.isGroupMessage()) return null;
        final String address = whatsapp.getNumber();
        if (TextUtils.isEmpty(address)) {
            return null;
        }
        PersonRecord record = lookupPerson(address);
        if (!backupPerson(record, DataType.WHATSAPP)) return null;

        final Message msg = new MimeMessage();

        if (whatsapp.hasMediaAttached()) {
            MimeMultipart body = new MimeMultipart();
            if (whatsapp.hasText()) {
                body.addBodyPart(createTextPart(whatsapp.getFilteredText()));
            }
            body.addBodyPart(createPartFromFile(whatsapp.getMedia().getFile(), whatsapp.getMedia().getMimeType()));
            msg.setBody(body);
        } else if (whatsapp.hasText()) {
            msg.setBody(new TextBody(whatsapp.getFilteredText()));
        } else {
            // no media / no text, pointless
            return null;
        }
        msg.setSubject(getSubject(DataType.WHATSAPP, record));

        if (whatsapp.isReceived()) {
            // Received message
            msg.setFrom(record.getAddress());
            msg.setRecipient(RecipientType.TO, mUserAddress);
        } else {
            // Sent message
            msg.setRecipient(RecipientType.TO, record.getAddress());
            msg.setFrom(mUserAddress);
        }

        final Date then = whatsapp.getTimestamp();
        msg.setSentDate(then);
        msg.setInternalDate(then);
        msg.setHeader("Message-ID", createMessageId(then, address, whatsapp.getStatus()));

        // Threading by person ID, not by thread ID. I think this value is more stable.
        msg.setHeader("References",
                String.format(REFERENCE_UID_TEMPLATE, mReferenceValue, sanitize(record.getId())));
        msg.setHeader(Headers.ID, String.valueOf(whatsapp.getId()));
        msg.setHeader(Headers.ADDRESS, sanitize(address));
        msg.setHeader(Headers.DATATYPE, DataType.WHATSAPP.toString());
        msg.setHeader(Headers.DATE, String.valueOf(then.getTime()));
        msg.setHeader(Headers.TYPE, String.valueOf(whatsapp.getStatus()));
        msg.setHeader(Headers.STATUS, String.valueOf(whatsapp.getStatus()));
        msg.setHeader(Headers.BACKUP_TIME, new Date().toGMTString());
        msg.setHeader(Headers.VERSION, PrefStore.getVersion(mContext, true));
        msg.setFlag(Flag.SEEN, mMarkAsRead);
        return msg;
    }

    private @Nullable Message messageFromMapCallLog(Map<String, String> msgMap) throws MessagingException {
        final String address = msgMap.get(CallLog.Calls.NUMBER);
        final int callType = Integer.parseInt(msgMap.get(CallLog.Calls.TYPE));

        if (address == null || address.trim().length() == 0 ||
                !PrefStore.isCallLogTypeEnabled(mContext, callType)) {

            if (LOCAL_LOGV) Log.v(TAG, "ignoring call log entry: " + msgMap);
            return null;
        }

        PersonRecord record = lookupPerson(address);
        if (!backupPerson(record, DataType.CALLLOG)) return null;

        final Message msg = new MimeMessage();
        msg.setSubject(getSubject(DataType.CALLLOG, record));

        switch (callType) {
            case CallLog.Calls.OUTGOING_TYPE:
                msg.setFrom(mUserAddress);
                msg.setRecipient(RecipientType.TO, record.getAddress());
                break;
            case CallLog.Calls.MISSED_TYPE:
            case CallLog.Calls.INCOMING_TYPE:
                msg.setFrom(record.getAddress());
                msg.setRecipient(RecipientType.TO, mUserAddress);
                break;

            default:
                // some weird phones seem to have SMS in their call logs, which is
                // not part of the official API.
                Log.i(TAG, "ignoring unknown call type: " + callType);
                return null;
        }

        final int duration = msgMap.get(CallLog.Calls.DURATION) == null ? 0 :
                Integer.parseInt(msgMap.get(CallLog.Calls.DURATION));
        final StringBuilder text = new StringBuilder();

        if (callType != CallLog.Calls.MISSED_TYPE) {
            text.append(duration)
                    .append("s")
                    .append(" (").append(formattedDuration(duration)).append(")")
                    .append("\n");
        }
        text.append(record.getNumber())
                .append(" (").append(callTypeString(callType, null)).append(")");

        msg.setBody(new TextBody(text.toString()));

        try {
            Date then = new Date(Long.valueOf(msgMap.get(CallLog.Calls.DATE)));
            msg.setSentDate(then);
            msg.setInternalDate(then);
            msg.setHeader("Message-ID", createMessageId(then, address, callType));
        } catch (NumberFormatException n) {
            Log.e(TAG, "error parsing date", n);
        }

        // Threading by person ID, not by thread ID. I think this value is more stable.
        msg.setHeader("References",
                String.format(Locale.ENGLISH, REFERENCE_UID_TEMPLATE, mReferenceValue, sanitize(record.getId())));
        msg.setHeader(Headers.ID, msgMap.get(CallLog.Calls._ID));
        msg.setHeader(Headers.ADDRESS, sanitize(address));
        msg.setHeader(Headers.DATATYPE, DataType.CALLLOG.toString());
        msg.setHeader(Headers.TYPE, msgMap.get(CallLog.Calls.TYPE));
        msg.setHeader(Headers.DATE, msgMap.get(CallLog.Calls.DATE));
        msg.setHeader(Headers.DURATION, msgMap.get(CallLog.Calls.DURATION));
        msg.setHeader(Headers.BACKUP_TIME, new Date().toGMTString());
        msg.setHeader(Headers.VERSION, PrefStore.getVersion(mContext, true));
        msg.setFlag(Flag.SEEN, mMarkAsRead);

        return msg;
    }

    private boolean backupPerson(PersonRecord record, DataType type) {
        switch (type) {
            default:
                final boolean backup = (allowedIds == null || allowedIds.ids.contains(record._id));
                if (LOCAL_LOGV && !backup) Log.v(TAG, "not backing up " + type + " / " + record);
                return backup;
        }
    }

    private String getSubject(DataType type, PersonRecord record) {
        switch (type) {
            case SMS:
                return mPrefix ?
                        String.format(Locale.ENGLISH, "[%s] %s", PrefStore.getImapFolder(mContext), record.getName()) :
                        mContext.getString(R.string.sms_with_field, record.getName());
            case MMS:
                return mPrefix ?
                        String.format(Locale.ENGLISH, "[%s] %s", PrefStore.getImapFolder(mContext), record.getName()) :
                        mContext.getString(R.string.mms_with_field, record.getName());
            case CALLLOG:
                return mPrefix ?
                        String.format(Locale.ENGLISH, "[%s] %s", PrefStore.getCallLogFolder(mContext), record.getName()) :
                        mContext.getString(R.string.call_with_field, record.getName());
            case WHATSAPP:
                return mPrefix ?
                        String.format(Locale.ENGLISH, "[%s] %s", PrefStore.getWhatsAppFolder(mContext), record.getName()) :
                        mContext.getString(R.string.whatsapp_with_field, record.getName());


            default:
                throw new RuntimeException("unknown type:" + type);
        }
    }

    private @Nullable Message messageFromMapMms(Map<String, String> msgMap) throws MessagingException {
        if (LOCAL_LOGV) Log.v(TAG, "messageFromMapMms(" + msgMap + ")");

        final Uri msgRef = Uri.withAppendedPath(Consts.MMS_PROVIDER, msgMap.get(MmsConsts.ID));
        Cursor curAddr = mContext.getContentResolver().query(Uri.withAppendedPath(msgRef, "addr"),
                null, null, null, null);

        // TODO: this is probably not the best way to determine if a message is inbound or outbound
        boolean inbound = true;
        final List<String> recipients = new ArrayList<String>(); // MMS recipients
        while (curAddr != null && curAddr.moveToNext()) {
            final String address = curAddr.getString(curAddr.getColumnIndex("address"));
            //final int type       = curAddr.getInt(curAddr.getColumnIndex("type"));

            if (MmsConsts.INSERT_ADDRESS_TOKEN.equals(address)) {
                inbound = false;
            } else {
                recipients.add(address);
            }
        }
        if (curAddr != null) curAddr.close();
        if (recipients.isEmpty()) {
            Log.w(TAG, "no recipients found");
            return null;
        }

        final String address = recipients.get(0);
        final PersonRecord[] records = new PersonRecord[recipients.size()];
        final Address[] addresses = new Address[recipients.size()];
        for (int i = 0; i < recipients.size(); i++) {
            records[i] = lookupPerson(recipients.get(i));
            addresses[i] = records[i].getAddress();
        }

        boolean backup = false;
        for (PersonRecord r : records) {
            if (backupPerson(r, DataType.MMS)) {
                backup = true;
                break;
            }
        }
        if (!backup) return null;

        final Message msg = new MimeMessage();
        msg.setSubject(getSubject(DataType.MMS, records[0]));
        final int msg_box = Integer.parseInt(msgMap.get("msg_box"));
        if (inbound) {
            // msg_box == MmsConsts.MESSAGE_BOX_INBOX does not work
            msg.setFrom(records[0].getAddress());
            msg.setRecipient(RecipientType.TO, mUserAddress);
        } else {
            msg.setRecipients(RecipientType.TO, addresses);
            msg.setFrom(mUserAddress);
        }

        try {
            Date then = new Date(1000 * Long.valueOf(msgMap.get(MmsConsts.DATE)));
            msg.setSentDate(then);
            msg.setInternalDate(then);
            msg.setHeader("Message-ID", createMessageId(then, address, msg_box));
        } catch (NumberFormatException n) {
            Log.e(TAG, "error parsing date", n);
        }

        // Threading by person ID, not by thread ID. I think this value is more stable.
        msg.setHeader("References", String.format(Locale.ENGLISH, REFERENCE_UID_TEMPLATE, mReferenceValue,
                sanitize(records[0].getId())));
        msg.setHeader(Headers.ID, msgMap.get(MmsConsts.ID));
        msg.setHeader(Headers.ADDRESS, sanitize(address));
        msg.setHeader(Headers.DATATYPE, DataType.MMS.toString());
        msg.setHeader(Headers.TYPE, msgMap.get(MmsConsts.TYPE));
        msg.setHeader(Headers.DATE, msgMap.get(MmsConsts.DATE));
        msg.setHeader(Headers.THREAD_ID, msgMap.get(MmsConsts.THREAD_ID));
        msg.setHeader(Headers.READ, msgMap.get(MmsConsts.READ));
        msg.setHeader(Headers.BACKUP_TIME, new Date().toGMTString());
        msg.setHeader(Headers.VERSION, PrefStore.getVersion(mContext, true));
        msg.setFlag(Flag.SEEN, mMarkAsRead);

        // deal with attachments
        MimeMultipart body = new MimeMultipart();
        for (BodyPart p : getBodyParts(Uri.withAppendedPath(msgRef, "part"))) {
            body.addBodyPart(p);
        }
        msg.setBody(body);
        return msg;
    }

    private List<BodyPart> getBodyParts(final Uri uriPart) throws MessagingException {
        final List<BodyPart> parts = new ArrayList<BodyPart>();
        Cursor curPart = mContext.getContentResolver().query(uriPart, null, null, null, null);

        // _id, mid, seq, ct, name, chset, cd, fn, cid, cl, ctt_s, ctt_t, _data, text
        while (curPart != null && curPart.moveToNext()) {
            final String id = curPart.getString(curPart.getColumnIndex("_id"));
            final String contentType = curPart.getString(curPart.getColumnIndex("ct"));
            final String fileName = curPart.getString(curPart.getColumnIndex("cl"));
            final String text = curPart.getString(curPart.getColumnIndex("text"));

            if (LOCAL_LOGV) Log.v(TAG, String.format(Locale.ENGLISH, "processing part %s, name=%s (%s)", id,
                    fileName, contentType));

            if (!TextUtils.isEmpty(contentType) && contentType.startsWith("text/") && !TextUtils.isEmpty(text)) {
                // text
                parts.add(new MimeBodyPart(new TextBody(text), contentType));
            } else //noinspection StatementWithEmptyBody
                if ("application/smil".equalsIgnoreCase(contentType)) {
                // silently ignore SMIL stuff
            } else {
                // attach everything else
                final Uri partUri = Uri.withAppendedPath(Consts.MMS_PROVIDER, "part/" + id);
                parts.add(createPartFromUri(mContext.getContentResolver(), partUri, fileName, contentType));
            }
        }

        if (curPart != null) curPart.close();
        return parts;
    }

    /**
     * Create a message-id based on message date, phone number and message
     * type.
     *
     * @param sent    email send date
     * @param address the email address
     * @param type    the type
     * @return the message-id
     */
    private String createMessageId(Date sent, String address, int type) {
        try {
            final MessageDigest digest = java.security.MessageDigest.getInstance("MD5");

            digest.update(Long.toString(sent.getTime()).getBytes("UTF-8"));
            digest.update(address.getBytes("UTF-8"));
            digest.update(Integer.toString(type).getBytes("UTF-8"));

            final StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format(Locale.ENGLISH, "%02x", b));
            }
            return String.format(Locale.ENGLISH, MSG_ID_TEMPLATE, sb.toString());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }




    @TargetApi(Build.VERSION_CODES.ECLAIR)
    private String getPrimaryEmail(final long personId, final String number) {
        if (personId <= 0) {
            return getUnknownEmail(number);
        }
        String primaryEmail = null;

        // Get all e-mail addresses for that person.
        Cursor c;
        int columnIndex;
        if (NEW_CONTACT_API) {
            c = mContext.getContentResolver().query(
                    ECLAIR_CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Email.DATA},
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID + " = ?", new String[]{String.valueOf(personId)},
                    ContactsContract.CommonDataKinds.Email.IS_PRIMARY + " DESC");
            columnIndex = c != null ? c.getColumnIndex(ContactsContract.CommonDataKinds.Email.DATA) : -1;
        } else {
            c = mContext.getContentResolver().query(
                    ContactMethods.CONTENT_EMAIL_URI,
                    new String[]{ContactMethods.DATA},
                    ContactMethods.PERSON_ID + " = ?", new String[]{String.valueOf(personId)},
                    ContactMethods.ISPRIMARY + " DESC");
            columnIndex = c != null ? c.getColumnIndex(ContactMethods.DATA) : -1;
        }

        // Loop over cursor and find a Gmail address for that person.
        // If there is none, pick first e-mail address.
        while (c != null && c.moveToNext()) {
            String e = c.getString(columnIndex);
            if (primaryEmail == null) {
                primaryEmail = e;
            }
            if (isGmailAddress(e)) {
                primaryEmail = e;
                break;
            }
        }

        if (c != null) c.close();
        return (primaryEmail != null) ? primaryEmail : getUnknownEmail(number);
    }

    static String sanitize(String s) {
        return s != null ? s.replaceAll("\\p{Cntrl}", "") : null;
    }

    private static String encodeLocal(String s) {
        return (s != null ? EncoderUtil.encodeAddressLocalPart(sanitize(s)) : null);
    }

    private static String getUnknownEmail(String number) {
        final String no = (number == null || "-1".equals(number)) ? UNKNOWN_NUMBER : number;
        return encodeLocal(no.trim()) + "@" + UNKNOWN_EMAIL;
    }

    // Returns whether the given e-mail address is a Gmail address or not.
    private static boolean isGmailAddress(String email) {
        return email != null &&
                (email.toLowerCase(Locale.ENGLISH).endsWith("gmail.com") ||
                 email.toLowerCase(Locale.ENGLISH).endsWith("googlemail.com"));
    }

    private static String generateReferenceValue() {
        final StringBuilder sb = new StringBuilder();
        final Random random = new Random();
        for (int i = 0; i < 24; i++) {
            sb.append(Integer.toString(random.nextInt(35), 36));
        }
        return sb.toString();
    }
}