package org.foxred.kage.spike;

import androidx.test.platform.app.InstrumentationRegistry;
import com.fsck.k9.mail.*;
import com.fsck.k9.mail.internet.MimeMessage;
import com.fsck.k9.mail.ssl.TrustedSocketFactory;
import com.fsck.k9.mail.store.imap.*;
import com.fsck.k9.mail.transport.smtp.SmtpTransport;
import org.foxred.kage.core.testkit.*;
import org.junit.Test;
import static org.junit.Assert.*;
import javax.net.ssl.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import kotlin.Unit;

/** Bounded candidate evaluation, independent of Kage production adapters. */
public class ThunderbirdRuntimeTest {
    @org.junit.Before public void initializeCandidateGlobals() {
        // No-op sink avoids logging fixture envelopes or credentials in this comparison.
        Class<?> logger = net.thunderbird.core.logging.Logger.class;
        net.thunderbird.legacy.logging.Log.INSTANCE.setLogger(
            (net.thunderbird.core.logging.Logger) java.lang.reflect.Proxy.newProxyInstance(
                logger.getClassLoader(), new Class<?>[] { logger }, (proxy, method, args) -> null));
        com.fsck.k9.mail.internet.BinaryTempFileBody.setTempDirectory(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
    }

    private SSLContext context() throws Exception {
        return LoopbackServerKt.testTlsContext(InstrumentationRegistry.getInstrumentation()
            .getContext().getAssets().open("localhost.p12"));
    }

    private TrustedSocketFactory sockets(SSLContext context) {
        return (socket, host, port, alias) -> {
            SSLSocket secured = (SSLSocket) (socket == null
                ? context.getSocketFactory().createSocket()
                : context.getSocketFactory().createSocket(socket, host, port, true));
            SSLParameters parameters = secured.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            secured.setSSLParameters(parameters);
            if (socket != null) secured.startHandshake();
            return secured;
        };
    }

    private ServerSettings settings(String type, int port, ConnectionSecurity security) {
        return new ServerSettings(type, "localhost", port, security, AuthType.PLAIN, "user", "password", null);
    }

    @Test public void mimeAndSmtpStartTls() throws Exception {
        SSLContext context = context();
        SmtpTranscript transcript = new SmtpTranscript(context, null, false);
        try (LoopbackServer server = new LoopbackServer(null, socket -> {
            transcript.serve(socket); return Unit.INSTANCE;
        })) {
            MimeMessage message = MimeMessage.parseMimeMessage(new ByteArrayInputStream((
                "From: sender@example.net\r\nTo: sink@example.net\r\nSubject: Runtime\r\n" +
                "Content-Type: text/plain; charset=UTF-8\r\n\r\nHello\r\n").getBytes(StandardCharsets.UTF_8)), true);
            assertEquals("Runtime", message.getSubject());
            new SmtpTransport(settings("smtp", server.getPort(), ConnectionSecurity.STARTTLS_REQUIRED), sockets(context), null)
                .sendMessage(message);
            server.awaitCompletion();
            assertTrue(transcript.getCommands().contains("DATA"));
        }
    }

    @Test public void imapTlsAuthentication() throws Exception {
        SSLContext context = context();
        ImapTranscript transcript = new ImapTranscript(true, true, false);
        try (LoopbackServer server = new LoopbackServer(context, socket -> {
            transcript.serve(socket); return Unit.INSTANCE;
        })) {
            ImapStoreConfig config = new ImapStoreConfig() {
                public String getLogLabel() { return "fixture"; }
                public boolean isSubscribedFoldersOnly() { return false; }
                public boolean isExpungeImmediately() { return false; }
                public ImapClientInfo clientInfo() { return new ImapClientInfo("Kage spike", "1"); }
            };
            ImapStore store = ImapStore.Companion.create(
                settings("imap", server.getPort(), ConnectionSecurity.SSL_TLS_REQUIRED), config, sockets(context), null);
            try { store.checkSettings(); } finally { store.closeAllConnections(); }
            server.awaitCompletion();
            assertTrue(transcript.getCommands().contains("AUTHENTICATE"));
        }
    }
}
