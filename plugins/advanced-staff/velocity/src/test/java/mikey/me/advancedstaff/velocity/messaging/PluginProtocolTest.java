package mikey.me.advancedstaff.velocity.messaging;

import mikey.me.core.json.JsonUtil;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginProtocolTest {

    private static final String SECRET = "test-secret";
    private static final String PAYLOAD = "{\"message\":\"hello\"}";

    @Test
    void wrapsAndUnwrapsAValidMessage() {
        String envelope = PluginProtocol.wrap(PluginProtocol.CH_PLAYER_LIST, PAYLOAD, SECRET).orElseThrow();

        assertEquals(PAYLOAD, PluginProtocol.unwrap(PluginProtocol.CH_PLAYER_LIST, envelope, SECRET).orElseThrow());
    }

    @Test
    void rejectsReplayedMessages() {
        String envelope = PluginProtocol.wrap(PluginProtocol.CH_MUTE, PAYLOAD, SECRET).orElseThrow();

        assertTrue(PluginProtocol.unwrap(PluginProtocol.CH_MUTE, envelope, SECRET).isPresent());
        assertTrue(PluginProtocol.unwrap(PluginProtocol.CH_MUTE, envelope, SECRET).isEmpty());
    }

    @Test
    void rejectsTamperedMessages() {
        String envelope = PluginProtocol.wrap(PluginProtocol.CH_FREEZE, PAYLOAD, SECRET).orElseThrow();
        String tampered = envelope.replace("hello", "goodbye");

        assertTrue(PluginProtocol.unwrap(PluginProtocol.CH_FREEZE, tampered, SECRET).isEmpty());
    }

    @Test
    void rejectsWrongChannelAndWrongSecret() {
        String envelope = PluginProtocol.wrap(PluginProtocol.CH_VANISH, PAYLOAD, SECRET).orElseThrow();

        assertTrue(PluginProtocol.unwrap(PluginProtocol.CH_FREEZE, envelope, SECRET).isEmpty());
        assertTrue(PluginProtocol.unwrap(PluginProtocol.CH_VANISH, envelope, "wrong-secret").isEmpty());
    }

    @Test
    void rejectsStaleMessages() throws Exception {
        long timestamp = System.currentTimeMillis() - PluginProtocol.MAX_MESSAGE_AGE_MILLIS - 1_000L;
        String nonce = "0123456789abcdef0123456789abcdef";
        String envelope = signedEnvelope(timestamp, nonce, PAYLOAD, SECRET);

        assertTrue(PluginProtocol.unwrap(PluginProtocol.CH_BAN_NOTIFY, envelope, SECRET).isEmpty());
    }

    @Test
    void rejectsBlankSecretsAndUnknownChannels() {
        assertTrue(PluginProtocol.wrap(PluginProtocol.CH_HELLO, PAYLOAD, "").isEmpty());
        assertTrue(PluginProtocol.wrap("advancedstaff:unknown", PAYLOAD, SECRET).isEmpty());
    }

    @Test
    void rejectsFutureDatedMessages() throws Exception {
        long timestamp = System.currentTimeMillis() + PluginProtocol.MAX_MESSAGE_AGE_MILLIS + 1_000L;
        String envelope = signedEnvelope(timestamp, "11111111111111111111111111111111", PAYLOAD, SECRET);

        assertTrue(PluginProtocol.unwrap(PluginProtocol.CH_BAN_NOTIFY, envelope, SECRET).isEmpty());
    }

    @Test
    void rejectsMalformedEnvelopeFieldsAndNonObjectPayloads() throws Exception {
        long timestamp = System.currentTimeMillis();
        String badNonce = signedEnvelope(timestamp, "22222222222222222222222222222222", PAYLOAD, SECRET)
                .replace("\"nonce\":\"22222222222222222222222222222222\"",
                        "\"nonce\":\"2222222222222222222222222222222x\"");
        String arrayPayload = signedEnvelope(timestamp, "33333333333333333333333333333333", "[]", SECRET);
        String plainPayload = signedEnvelope(timestamp, "44444444444444444444444444444444", "text", SECRET);

        assertTrue(PluginProtocol.unwrap(PluginProtocol.CH_BAN_NOTIFY, badNonce, SECRET).isEmpty());
        assertTrue(PluginProtocol.unwrap(PluginProtocol.CH_BAN_NOTIFY, arrayPayload, SECRET).isEmpty());
        assertTrue(PluginProtocol.unwrap(PluginProtocol.CH_BAN_NOTIFY, plainPayload, SECRET).isEmpty());
        assertTrue(PluginProtocol.wrap(PluginProtocol.CH_STAFFCHAT, "text", SECRET).isEmpty());
    }

    @Test
    void invalidMacDoesNotConsumeNonce() throws Exception {
        String nonce = "55555555555555555555555555555555";
        String envelope = signedEnvelope(System.currentTimeMillis(), nonce, PAYLOAD, SECRET);
        String invalidMac = envelope.replaceFirst("\"mac\":\"[0-9a-f]{64}\"",
                "\"mac\":\"" + "0".repeat(64) + "\"");

        assertTrue(PluginProtocol.unwrap(PluginProtocol.CH_BAN_NOTIFY, invalidMac, SECRET).isEmpty());
        assertEquals(PAYLOAD, PluginProtocol.unwrap(PluginProtocol.CH_BAN_NOTIFY, envelope, SECRET).orElseThrow());
    }

    private static String signedEnvelope(long timestamp, String nonce, String payload, String secret)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String value = timestamp + "\n" + PluginProtocol.CH_BAN_NOTIFY + "\n" + nonce + "\n" + payload;
        String signature = HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        return "{\"timestamp\":" + timestamp
                + ",\"nonce\":\"" + nonce
                + "\",\"payload\":\"" + JsonUtil.escape(payload)
                + "\",\"mac\":\"" + signature + "\"}";
    }
}
