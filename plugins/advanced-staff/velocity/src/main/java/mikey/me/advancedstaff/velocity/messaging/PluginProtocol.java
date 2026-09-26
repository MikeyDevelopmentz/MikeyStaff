package mikey.me.advancedstaff.velocity.messaging;

import java.util.List;
import java.util.Optional;

public final class PluginProtocol {

    public static final int VERSION = mikey.me.core.communication.protocol.PluginProtocol.VERSION;
    public static final long MAX_MESSAGE_AGE_MILLIS = mikey.me.core.communication.protocol.PluginProtocol.MAX_MESSAGE_AGE_MILLIS;

    public static final String CH_HELLO = mikey.me.core.communication.protocol.PluginProtocol.CH_HELLO;
    public static final String CH_STAFFCHAT = mikey.me.core.communication.protocol.PluginProtocol.CH_STAFFCHAT;
    public static final String CH_KICK = mikey.me.core.communication.protocol.PluginProtocol.CH_KICK;
    public static final String CH_BAN_NOTIFY = mikey.me.core.communication.protocol.PluginProtocol.CH_BAN_NOTIFY;
    public static final String CH_MUTE = mikey.me.core.communication.protocol.PluginProtocol.CH_MUTE;
    public static final String CH_VANISH = mikey.me.core.communication.protocol.PluginProtocol.CH_VANISH;
    public static final String CH_FREEZE = mikey.me.core.communication.protocol.PluginProtocol.CH_FREEZE;
    public static final String CH_PLAYER_LIST = mikey.me.core.communication.protocol.PluginProtocol.CH_PLAYER_LIST;

    public static final List<String> ALL_CHANNELS = mikey.me.core.communication.protocol.PluginProtocol.ALL_CHANNELS;

    private PluginProtocol() {}

    public static Optional<String> wrap(String channel, String payload, String secret) {
        return mikey.me.core.communication.protocol.PluginProtocol.wrap(channel, payload, secret);
    }

    public static Optional<String> unwrap(String channel, String envelope, String secret) {
        return mikey.me.core.communication.protocol.PluginProtocol.unwrap(channel, envelope, secret);
    }
}
