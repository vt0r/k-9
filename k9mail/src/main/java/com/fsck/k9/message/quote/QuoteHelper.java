package com.fsck.k9.message.quote;


import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

import android.content.res.Resources;

import com.fsck.k9.mail.Message;


class QuoteHelper {
    // amount of extra buffer to allocate to accommodate quoting headers or prefixes
    static final int QUOTE_BUFFER_LENGTH = 512;


    /**
     * Extract the date from a message and convert it into a locale-specific
     * date string suitable for use in a header for a quoted message.
     *
     * @return A string with the formatted date/time
     */
    static String getSentDateText(Resources resources, Message message) {
        try {
            final int dateStyle = DateFormat.LONG;
            final int timeStyle = DateFormat.LONG;
            Date date = message.getSentDate();
            Locale locale = resources.getConfiguration().locale;
            return DateFormat.getDateTimeInstance(dateStyle, timeStyle, locale)
                    .format(date);
        } catch (Exception e) {
            return "";
        }
    }
}
