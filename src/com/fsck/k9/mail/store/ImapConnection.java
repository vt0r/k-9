package com.fsck.k9.mail.store;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import com.fsck.k9.mail.Authentication;
import com.fsck.k9.mail.AuthenticationFailedException;
import com.fsck.k9.mail.CertificateValidationException;
import com.fsck.k9.mail.ConnectionSecurity;
import com.fsck.k9.mail.K9MailLib;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.filter.Base64;
import com.fsck.k9.mail.filter.PeekableInputStream;
import com.fsck.k9.mail.ssl.TrustedSocketFactory;
import com.fsck.k9.mail.transport.imap.ImapSettings;
import com.jcraft.jzlib.JZlib;
import com.jcraft.jzlib.ZOutputStream;

import org.apache.commons.io.IOUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.net.ssl.SSLException;

import static com.fsck.k9.mail.K9MailLib.DEBUG_PROTOCOL_IMAP;
import static com.fsck.k9.mail.K9MailLib.LOG_TAG;

/**
 * A cacheable class that stores the details for a single IMAP connection.
 */
class ImapConnection {

    private Socket mSocket;
    private PeekableInputStream mIn;
    private OutputStream mOut;
    private ImapResponseParser mParser;
    private int mNextCommandTag;
    private Set<String> capabilities = new HashSet<String>();
    private ImapSettings mSettings;
    private ConnectivityManager mConnectivityManager;
    private final TrustedSocketFactory mSocketFactory;

    public ImapConnection(ImapSettings settings,
                          TrustedSocketFactory socketFactory,
                          ConnectivityManager connectivityManager) {
        this.mSettings = settings;
        this.mSocketFactory = socketFactory;
        this.mConnectivityManager = connectivityManager;
    }

    public Set<String> getCapabilities() {
        return capabilities;
    }

    public OutputStream getOutputStream() {
        return mOut;
    }

    protected String getLogId() {
        return "conn" + hashCode();
    }

    private List<ImapResponseParser.ImapResponse> receiveCapabilities(List<ImapResponseParser.ImapResponse> responses) {
        for (ImapResponseParser.ImapResponse response : responses) {
            ImapResponseParser.ImapList capabilityList = null;
            if (!response.isEmpty() && ImapResponseParser.equalsIgnoreCase(response.get(0), "OK")) {
                for (Object thisPart : response) {
                    if (thisPart instanceof ImapResponseParser.ImapList) {
                        ImapResponseParser.ImapList thisList = (ImapResponseParser.ImapList)thisPart;
                        if (ImapResponseParser.equalsIgnoreCase(thisList.get(0), ImapCommands.CAPABILITY_CAPABILITY)) {
                            capabilityList = thisList;
                            break;
                        }
                    }
                }
            } else if (response.mTag == null) {
                capabilityList = response;
            }

            if (capabilityList != null && !capabilityList.isEmpty() &&
                    ImapResponseParser.equalsIgnoreCase(capabilityList.get(0), ImapCommands.CAPABILITY_CAPABILITY)) {
                if (K9MailLib.isDebug()) {
                    Log.d(LOG_TAG, "Saving " + capabilityList.size() + " capabilities for " + getLogId());
                }
                for (Object capability : capabilityList) {
                    if (capability instanceof String) {
//                            if (K9MailLib.isDebug())
//                            {
//                                Log.v(LOG_TAG, "Saving capability '" + capability + "' for " + getLogId());
//                            }
                        capabilities.add(((String)capability).toUpperCase(Locale.US));
                    }
                }
            }
        }
        return responses;
    }

    public void open() throws IOException, MessagingException {
        if (isOpen()) {
            return;
        }

        boolean authSuccess = false;

        mNextCommandTag = 1;
        try {
            Security.setProperty("networkaddress.cache.ttl", "0");
        } catch (Exception e) {
            Log.w(LOG_TAG, "Could not set DNS ttl to 0 for " + getLogId(), e);
        }


        try {
            Security.setProperty("networkaddress.cache.negative.ttl", "0");
        } catch (Exception e) {
            Log.w(LOG_TAG, "Could not set DNS negative ttl to 0 for " + getLogId(), e);
        }

        try {
            ConnectionSecurity connectionSecurity = mSettings.getConnectionSecurity();

            // Try all IPv4 and IPv6 addresses of the host
            InetAddress[] addresses = InetAddress.getAllByName(mSettings.getHost());
            for (int i = 0; i < addresses.length; i++) {
                try {
                    if (K9MailLib.isDebug() && DEBUG_PROTOCOL_IMAP) {
                        Log.d(LOG_TAG, "Connecting to " + mSettings.getHost() + " as " +
                                addresses[i]);
                    }

                    SocketAddress socketAddress = new InetSocketAddress(addresses[i],
                            mSettings.getPort());

                    if (connectionSecurity == ConnectionSecurity.SSL_TLS_REQUIRED) {
                        mSocket = mSocketFactory.createSocket(
                                null,
                                mSettings.getHost(),
                                mSettings.getPort(),
                                mSettings.getClientCertificateAlias());
                    } else {
                        mSocket = new Socket();
                    }

                    mSocket.connect(socketAddress, RemoteStore.SOCKET_CONNECT_TIMEOUT);

                    // Successfully connected to the server; don't try any other addresses
                    break;
                } catch (SocketException e) {
                    if (i < (addresses.length - 1)) {
                        // There are still other addresses for that host to try
                        continue;
                    }
                    throw new MessagingException("Cannot connect to host", e);
                }
            }

            setReadTimeout(RemoteStore.SOCKET_READ_TIMEOUT);

            mIn = new PeekableInputStream(new BufferedInputStream(mSocket.getInputStream(),
                                          1024));
            mParser = new ImapResponseParser(mIn);
            mOut = new BufferedOutputStream(mSocket.getOutputStream(), 1024);

            capabilities.clear();
            ImapResponseParser.ImapResponse nullResponse = mParser.readResponse();
            if (K9MailLib.isDebug() && DEBUG_PROTOCOL_IMAP)
                Log.v(LOG_TAG, getLogId() + "<<<" + nullResponse);

            List<ImapResponseParser.ImapResponse> nullResponses = new LinkedList<ImapResponseParser.ImapResponse>();
            nullResponses.add(nullResponse);
            receiveCapabilities(nullResponses);

            if (!hasCapability(ImapCommands.CAPABILITY_CAPABILITY)) {
                if (K9MailLib.isDebug())
                    Log.i(LOG_TAG, "Did not get capabilities in banner, requesting CAPABILITY for " + getLogId());
                List<ImapResponseParser.ImapResponse> responses = receiveCapabilities(executeSimpleCommand(ImapCommands.COMMAND_CAPABILITY));
                if (responses.size() != 2) {
                    throw new MessagingException("Invalid CAPABILITY response received");
                }
            }

            if (mSettings.getConnectionSecurity() == ConnectionSecurity.STARTTLS_REQUIRED) {

                if (hasCapability("STARTTLS")) {
                    // STARTTLS
                    executeSimpleCommand("STARTTLS");

                    mSocket = mSocketFactory.createSocket(
                            mSocket,
                            mSettings.getHost(),
                            mSettings.getPort(),
                            mSettings.getClientCertificateAlias());
                    mSocket.setSoTimeout(RemoteStore.SOCKET_READ_TIMEOUT);
                    mIn = new PeekableInputStream(new BufferedInputStream(mSocket
                                                  .getInputStream(), 1024));
                    mParser = new ImapResponseParser(mIn);
                    mOut = new BufferedOutputStream(mSocket.getOutputStream(), 1024);
                    // Per RFC 2595 (3.1):  Once TLS has been started, reissue CAPABILITY command
                    if (K9MailLib.isDebug())
                        Log.i(LOG_TAG, "Updating capabilities after STARTTLS for " + getLogId());
                    capabilities.clear();
                    List<ImapResponseParser.ImapResponse> responses = receiveCapabilities(executeSimpleCommand(ImapCommands.COMMAND_CAPABILITY));
                    if (responses.size() != 2) {
                        throw new MessagingException("Invalid CAPABILITY response received");
                    }
                } else {
                    /*
                     * This exception triggers a "Certificate error"
                     * notification that takes the user to the incoming
                     * server settings for review. This might be needed if
                     * the account was configured with an obsolete
                     * "STARTTLS (if available)" setting.
                     */
                    throw new CertificateValidationException(
                            "STARTTLS connection security not available");
                }
            }

            switch (mSettings.getAuthType()) {
            case CRAM_MD5:
                if (hasCapability(ImapCommands.CAPABILITY_AUTH_CRAM_MD5)) {
                    authCramMD5();
                } else {
                    throw new MessagingException(
                            "Server doesn't support encrypted passwords using CRAM-MD5.");
                }
                break;

            case PLAIN:
                if (hasCapability(ImapCommands.CAPABILITY_AUTH_PLAIN)) {
                    saslAuthPlain();
                } else if (!hasCapability(ImapCommands.CAPABILITY_LOGINDISABLED)) {
                    login();
                } else {
                    throw new MessagingException(
                            "Server doesn't support unencrypted passwords using AUTH=PLAIN and LOGIN is disabled.");
                }
                break;

            case EXTERNAL:
                if (hasCapability(ImapCommands.CAPABILITY_AUTH_EXTERNAL)) {
                    saslAuthExternal();
                } else {
                    // Provide notification to user of a problem authenticating using client certificates
                    throw new CertificateValidationException(CertificateValidationException.Reason.MissingCapability);
                }
                break;

            default:
                throw new MessagingException(
                        "Unhandled authentication method found in the server settings (bug).");
            }
            authSuccess = true;
            if (K9MailLib.isDebug()) {
                Log.d(LOG_TAG, ImapCommands.CAPABILITY_COMPRESS_DEFLATE + " = " + hasCapability(ImapCommands.CAPABILITY_COMPRESS_DEFLATE));
            }
            if (hasCapability(ImapCommands.CAPABILITY_COMPRESS_DEFLATE)) {
                boolean useCompression = true;

                NetworkInfo netInfo = mConnectivityManager.getActiveNetworkInfo();
                if (netInfo != null) {
                    int type = netInfo.getType();
                    if (K9MailLib.isDebug())
                        Log.d(LOG_TAG, "On network type " + type);
                    useCompression = mSettings.useCompression(type);

                }
                if (K9MailLib.isDebug())
                    Log.d(LOG_TAG, "useCompression " + useCompression);
                if (useCompression) {
                    try {
                        executeSimpleCommand(ImapCommands.COMMAND_COMPRESS_DEFLATE);
                        Inflater inf = new Inflater(true);
                        InflaterInputStream zInputStream = new InflaterInputStream(mSocket.getInputStream(), inf);
                        mIn = new PeekableInputStream(new BufferedInputStream(zInputStream, 1024));
                        mParser = new ImapResponseParser(mIn);
                        ZOutputStream zOutputStream = new ZOutputStream(mSocket.getOutputStream(), JZlib.Z_BEST_SPEED, true);
                        mOut = new BufferedOutputStream(zOutputStream, 1024);
                        zOutputStream.setFlushMode(JZlib.Z_PARTIAL_FLUSH);
                        if (K9MailLib.isDebug()) {
                            Log.i(LOG_TAG, "Compression enabled for " + getLogId());
                        }
                    } catch (Exception e) {
                        Log.e(LOG_TAG, "Unable to negotiate compression", e);
                    }
                }
            }


            if (K9MailLib.isDebug())
                Log.d(LOG_TAG, "NAMESPACE = " + hasCapability(ImapCommands.CAPABILITY_NAMESPACE)
                      + ", mPathPrefix = " + mSettings.getPathPrefix());

            if (mSettings.getPathPrefix() == null) {
                if (hasCapability(ImapCommands.CAPABILITY_NAMESPACE)) {
                    if (K9MailLib.isDebug())
                        Log.i(LOG_TAG, "mPathPrefix is unset and server has NAMESPACE capability");
                    List<ImapResponseParser.ImapResponse> namespaceResponses =
                        executeSimpleCommand(ImapCommands.COMMAND_NAMESPACE);
                    for (ImapResponseParser.ImapResponse response : namespaceResponses) {
                        if (ImapResponseParser.equalsIgnoreCase(response.get(0), ImapCommands.COMMAND_NAMESPACE)) {
                            if (K9MailLib.isDebug())
                                Log.d(LOG_TAG, "Got NAMESPACE response " + response + " on " + getLogId());

                            Object personalNamespaces = response.get(1);
                            if (personalNamespaces != null && personalNamespaces instanceof ImapResponseParser.ImapList) {
                                if (K9MailLib.isDebug())
                                    Log.d(LOG_TAG, "Got personal namespaces: " + personalNamespaces);
                                ImapResponseParser.ImapList bracketed = (ImapResponseParser.ImapList)personalNamespaces;
                                Object firstNamespace = bracketed.get(0);
                                if (firstNamespace != null && firstNamespace instanceof ImapResponseParser.ImapList) {
                                    if (K9MailLib.isDebug())
                                        Log.d(LOG_TAG, "Got first personal namespaces: " + firstNamespace);
                                    bracketed = (ImapResponseParser.ImapList)firstNamespace;
                                    mSettings.setPathPrefix(bracketed.getString(0));
                                    mSettings.setPathDelimeter(bracketed.getString(1));
                                    mSettings.setCombinedPrefix(null);
                                    if (K9MailLib.isDebug())
                                        Log.d(LOG_TAG, "Got path '" + mSettings.getPathPrefix() + "' and separator '" + mSettings.getPathDelimeter() + "'");
                                }
                            }
                        }
                    }
                } else {
                    if (K9MailLib.isDebug())
                        Log.i(LOG_TAG, "mPathPrefix is unset but server does not have NAMESPACE capability");
                    mSettings.setPathPrefix("");
                }
            }
            if (mSettings.getPathDelimeter() == null) {
                try {
                    List<ImapResponseParser.ImapResponse> nameResponses =
                        executeSimpleCommand("LIST \"\" \"\"");
                    for (ImapResponseParser.ImapResponse response : nameResponses) {
                        if (ImapResponseParser.equalsIgnoreCase(response.get(0), "LIST")) {
                            mSettings.setPathDelimeter(response.getString(2));
                            mSettings.setCombinedPrefix(null);
                            if (K9MailLib.isDebug())
                                Log.d(LOG_TAG, "Got path delimeter '" + mSettings.getPathDelimeter() + "' for " + getLogId());
                        }
                    }
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Unable to get path delimeter using LIST", e);
                }
            }

        } catch (SSLException e) {
            if (e.getCause() instanceof CertificateException) {
                throw new CertificateValidationException(e.getMessage(), e);
            } else {
                throw e;
            }
        } catch (GeneralSecurityException gse) {
            throw new MessagingException(
                "Unable to open connection to IMAP server due to security error.", gse);
        } catch (ConnectException ce) {
            String ceMess = ce.getMessage();
            String[] tokens = ceMess.split("-");
            if (tokens != null && tokens.length > 1 && tokens[1] != null) {
                Log.e(LOG_TAG, "Stripping host/port from ConnectionException for " + getLogId(), ce);
                throw new ConnectException(tokens[1].trim());
            } else {
                throw ce;
            }
        } finally {
            if (!authSuccess) {
                Log.e(LOG_TAG, "Failed to login, closing connection for " + getLogId());
                close();
            }
        }
    }

    protected void login() throws IOException, MessagingException {
        /*
         * Use quoted strings which permit spaces and quotes. (Using IMAP
         * string literals would be better, but some servers are broken
         * and don't parse them correctly.)
         */

        // escape double-quotes and backslash characters with a backslash
        Pattern p = Pattern.compile("[\\\\\"]");
        String replacement = "\\\\$0";
        String username = p.matcher(mSettings.getUsername()).replaceAll(
                replacement);
        String password = p.matcher(mSettings.getPassword()).replaceAll(
                replacement);
        try {
            receiveCapabilities(executeSimpleCommand(
                    String.format("LOGIN \"%s\" \"%s\"", username, password), true));
        } catch (ImapStore.ImapException e) {
            throw new AuthenticationFailedException(e.getMessage());
        }
    }

    protected void authCramMD5() throws MessagingException, IOException {
        String command = "AUTHENTICATE CRAM-MD5";
        String tag = sendCommand(command, false);
        ImapResponseParser.ImapResponse response = readContinuationResponse(tag);
        if (response.size() != 1 || !(response.get(0) instanceof String)) {
            throw new MessagingException("Invalid Cram-MD5 nonce received");
        }
        byte[] b64Nonce = response.getString(0).getBytes();
        byte[] b64CRAM = Authentication.computeCramMd5Bytes(
                mSettings.getUsername(), mSettings.getPassword(), b64Nonce);

        mOut.write(b64CRAM);
        mOut.write('\r');
        mOut.write('\n');
        mOut.flush();
        try {
            receiveCapabilities(readStatusResponse(tag, command, null));
        } catch (MessagingException e) {
            throw new AuthenticationFailedException(e.getMessage());
        }
    }

    protected void saslAuthPlain() throws IOException, MessagingException {
        String command = "AUTHENTICATE PLAIN";
        String tag = sendCommand(command, false);
        readContinuationResponse(tag);
        mOut.write(Base64.encodeBase64(("\000" + mSettings.getUsername()
                + "\000" + mSettings.getPassword()).getBytes()));
        mOut.write('\r');
        mOut.write('\n');
        mOut.flush();
        try {
            receiveCapabilities(readStatusResponse(tag, command, null));
        } catch (MessagingException e) {
            throw new AuthenticationFailedException(e.getMessage());
        }
    }

    private void saslAuthExternal() throws IOException, MessagingException {
        try {
            receiveCapabilities(executeSimpleCommand(
                    String.format("AUTHENTICATE EXTERNAL %s",
                            Base64.encode(mSettings.getUsername())), false));
        } catch (ImapStore.ImapException e) {
            /*
             * Provide notification to the user of a problem authenticating
             * using client certificates. We don't use an
             * AuthenticationFailedException because that would trigger a
             * "Username or password incorrect" notification in
             * AccountSetupCheckSettings.
             */
            throw new CertificateValidationException(e.getMessage());
        }
    }

    protected ImapResponseParser.ImapResponse readContinuationResponse(String tag)
            throws IOException, MessagingException {
        ImapResponseParser.ImapResponse response;
        do {
            response = readResponse();
            if (response.mTag != null) {
                if (response.mTag.equalsIgnoreCase(tag)) {
                    throw new MessagingException(
                            "Command continuation aborted: " + response);
                } else {
                    Log.w(LOG_TAG, "After sending tag " + tag
                            + ", got tag response from previous command "
                            + response + " for " + getLogId());
                }
            }
        } while (!response.mCommandContinuationRequested);
        return response;
    }

    protected List<ImapResponseParser.ImapResponse> readStatusResponse(String tag,
            String commandToLog, ImapStore.UntaggedHandler untaggedHandler)
            throws IOException, MessagingException {
        List<ImapResponseParser.ImapResponse> responses = new ArrayList<ImapResponseParser.ImapResponse>();
        ImapResponseParser.ImapResponse response;
        do {
            response = mParser.readResponse();
            if (K9MailLib.isDebug() && DEBUG_PROTOCOL_IMAP)
                Log.v(LOG_TAG, getLogId() + "<<<" + response);

            if (response.mTag != null && !response.mTag.equalsIgnoreCase(tag)) {
                Log.w(LOG_TAG, "After sending tag " + tag + ", got tag response from previous command " + response + " for " + getLogId());
                Iterator<ImapResponseParser.ImapResponse> iter = responses.iterator();
                while (iter.hasNext()) {
                    ImapResponseParser.ImapResponse delResponse = iter.next();
                    if (delResponse.mTag != null || delResponse.size() < 2
                            || (!ImapResponseParser.equalsIgnoreCase(delResponse.get(1), "EXISTS") && !ImapResponseParser.equalsIgnoreCase(delResponse.get(1), "EXPUNGE"))) {
                        iter.remove();
                    }
                }
                response.mTag = null;
                continue;
            }
            if (untaggedHandler != null) {
                untaggedHandler.handleAsyncUntaggedResponse(response);
            }
            responses.add(response);
        } while (response.mTag == null);
        if (response.size() < 1 || !ImapResponseParser.equalsIgnoreCase(response.get(0), "OK")) {
            throw new ImapStore.ImapException("Command: " + commandToLog + "; response: " + response.toString(), response.getAlertText());
        }
        return responses;
    }

    protected void setReadTimeout(int millis) throws SocketException {
        Socket sock = mSocket;
        if (sock != null) {
            sock.setSoTimeout(millis);
        }
    }

    protected boolean isIdleCapable() {
        if (K9MailLib.isDebug())
            Log.v(LOG_TAG, "Connection " + getLogId() + " has " + capabilities.size() + " capabilities");

        return capabilities.contains(ImapCommands.CAPABILITY_IDLE);
    }

    protected boolean hasCapability(String capability) {
        return capabilities.contains(capability.toUpperCase(Locale.US));
    }

    public boolean isOpen() {
        return (mIn != null && mOut != null && mSocket != null && mSocket.isConnected() && !mSocket.isClosed());
    }

    public void close() {
//            if (isOpen()) {
//                try {
//                    executeSimpleCommand("LOGOUT");
//                } catch (Exception e) {
//
//                }
//            }
        IOUtils.closeQuietly(mIn);
        IOUtils.closeQuietly(mOut);
        IOUtils.closeQuietly(mSocket);
        mIn = null;
        mOut = null;
        mSocket = null;
    }

    public ImapResponseParser.ImapResponse readResponse() throws IOException, MessagingException {
        return readResponse(null);
    }

    public ImapResponseParser.ImapResponse readResponse(ImapResponseParser.IImapResponseCallback callback) throws IOException {
        try {
            ImapResponseParser.ImapResponse response = mParser.readResponse(callback);
            if (K9MailLib.isDebug() && DEBUG_PROTOCOL_IMAP)
                Log.v(LOG_TAG, getLogId() + "<<<" + response);

            return response;
        } catch (IOException ioe) {
            close();
            throw ioe;
        }
    }

    public void sendContinuation(String continuation) throws IOException {
        mOut.write(continuation.getBytes());
        mOut.write('\r');
        mOut.write('\n');
        mOut.flush();

        if (K9MailLib.isDebug() && DEBUG_PROTOCOL_IMAP)
            Log.v(LOG_TAG, getLogId() + ">>> " + continuation);

    }

    public String sendCommand(String command, boolean sensitive)
    throws MessagingException, IOException {
        try {
            open();
            String tag = Integer.toString(mNextCommandTag++);
            String commandToSend = tag + " " + command + "\r\n";
            mOut.write(commandToSend.getBytes());
            mOut.flush();

            if (K9MailLib.isDebug() && DEBUG_PROTOCOL_IMAP) {
                if (sensitive && !K9MailLib.isDebugSensitive()) {
                    Log.v(LOG_TAG, getLogId() + ">>> "
                          + "[Command Hidden, Enable Sensitive Debug Logging To Show]");
                } else {
                    Log.v(LOG_TAG, getLogId() + ">>> " + commandToSend);
                }
            }

            return tag;
        } catch (IOException ioe) {
            close();
            throw ioe;
        } catch (ImapStore.ImapException ie) {
            close();
            throw ie;
        } catch (MessagingException me) {
            close();
            throw me;
        }
    }

    public List<ImapResponseParser.ImapResponse> executeSimpleCommand(String command) throws IOException,
            MessagingException {
        return executeSimpleCommand(command, false, null);
    }

    public List<ImapResponseParser.ImapResponse> executeSimpleCommand(String command, boolean sensitive) throws IOException,
            MessagingException {
        return executeSimpleCommand(command, sensitive, null);
    }

    public List<ImapResponseParser.ImapResponse> executeSimpleCommand(String command, boolean sensitive, ImapStore.UntaggedHandler untaggedHandler)
    throws IOException, MessagingException {
        String commandToLog = command;
        if (sensitive && !K9MailLib.isDebugSensitive()) {
            commandToLog = "*sensitive*";
        }


        //if (K9MailLib.isDebug())
        //    Log.v(LOG_TAG, "Sending IMAP command " + commandToLog + " on connection " + getLogId());

        String tag = sendCommand(command, sensitive);
        //if (K9MailLib.isDebug())
        //    Log.v(LOG_TAG, "Sent IMAP command " + commandToLog + " with tag " + tag + " for " + getLogId());

        return readStatusResponse(tag, commandToLog, untaggedHandler);
    }
}
