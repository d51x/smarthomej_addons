/**
 * Copyright (c) 2021-2023 Contributors to the SmartHome/J project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.smarthomej.binding.tuya.internal.local.handlers;

import static org.smarthomej.binding.tuya.internal.local.CommandType.DP_QUERY;
import static org.smarthomej.binding.tuya.internal.local.CommandType.DP_QUERY_NEW;
import static org.smarthomej.binding.tuya.internal.local.CommandType.DP_REFRESH;
import static org.smarthomej.binding.tuya.internal.local.CommandType.HEART_BEAT;
import static org.smarthomej.binding.tuya.internal.local.CommandType.SESS_KEY_NEG_FINISH;
import static org.smarthomej.binding.tuya.internal.local.CommandType.SESS_KEY_NEG_START;
import static org.smarthomej.binding.tuya.internal.local.ProtocolVersion.V3_3;
import static org.smarthomej.binding.tuya.internal.local.ProtocolVersion.V3_4;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.jose4j.base64url.Base64;
import org.openhab.core.util.HexUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smarthomej.binding.tuya.internal.local.CommandType;
import org.smarthomej.binding.tuya.internal.local.MessageWrapper;
import org.smarthomej.binding.tuya.internal.local.ProtocolVersion;
import org.smarthomej.binding.tuya.internal.local.TuyaDevice;
import org.smarthomej.binding.tuya.internal.util.CryptoUtil;

import com.google.gson.Gson;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * The {@link TuyaEncoder} is a Netty Encoder for encoding Tuya Local messages
 *
 * Parts of this code are inspired by the TuyAPI project (see notice file)
 *
 * @author Jan N. Klug - Initial contribution
 */
@NonNullByDefault
public class TuyaEncoder extends MessageToByteEncoder<MessageWrapper<?>> {
    private final Logger logger = LoggerFactory.getLogger(TuyaEncoder.class);

    private final TuyaDevice.KeyStore keyStore;
    private final ProtocolVersion version;
    private final String deviceId;
    private final Gson gson;

    private int sequenceNo = 0;

    public TuyaEncoder(Gson gson, String deviceId, TuyaDevice.KeyStore keyStore, ProtocolVersion version) {
        this.gson = gson;
        this.deviceId = deviceId;
        this.keyStore = keyStore;
        this.version = version;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void encode(@NonNullByDefault({}) ChannelHandlerContext ctx, MessageWrapper<?> msg,
            @NonNullByDefault({}) ByteBuf out) throws Exception {
        byte[] payloadBytes;

        // prepare payload
        if (msg.content == null || msg.content instanceof Map<?, ?>) {
            Map<String, Object> content = (Map<String, Object>) msg.content;
            Map<String, Object> payload = new HashMap<>();
            if (version == V3_4) {
                payload.put("protocol", 5);
                payload.put("t", System.currentTimeMillis() / 1000);
                Map<String, Object> data = new HashMap<>();
                data.put("cid", deviceId);
                data.put("ctype", 0);
                if (content != null) {
                    data.putAll(content);
                }
                payload.put("data", data);
            } else {
                payload.put("devId", deviceId);
                payload.put("gwId", deviceId);
                payload.put("uid", deviceId);
                payload.put("t", System.currentTimeMillis() / 1000);
                if (content != null) {
                    payload.putAll(content);
                }
            }

            logger.debug("{}{}: Sending {}, payload {}", deviceId,
                    Objects.requireNonNullElse(ctx.channel().remoteAddress(), ""), msg.commandType, payload);

            String json = gson.toJson(payload);
            payloadBytes = json.getBytes(StandardCharsets.UTF_8);
        } else if (msg.content instanceof byte[]) {
            byte[] contentBytes = Objects.requireNonNull((byte[]) msg.content);
            if (logger.isDebugEnabled()) {
                logger.debug("{}{}: Sending payload {}", deviceId,
                        Objects.requireNonNullElse(ctx.channel().remoteAddress(), ""),
                        HexUtils.bytesToHex(contentBytes));
            }
            payloadBytes = contentBytes.clone();
        } else {
            logger.warn("Can't determine payload type for '{}', discarding.", msg.content);
            return;
        }

        Optional<byte[]> bufferOptional = version == V3_4 ? encode34(msg.commandType, payloadBytes)
                : encodePre34(msg.commandType, payloadBytes);

        bufferOptional.ifPresentOrElse(buffer -> {
            if (logger.isTraceEnabled()) {
                logger.trace("{}{}: Sending encoded '{}'", deviceId, ctx.channel().remoteAddress(),
                        HexUtils.bytesToHex(buffer));
            }

            out.writeBytes(buffer);
        }, () -> logger.debug("{}{}: Encoding returned an empty buffer", deviceId, ctx.channel().remoteAddress()));
    }

    private Optional<byte[]> encodePre34(CommandType commandType, byte[] payload) {
        byte[] payloadBytes = payload;
        if (version == V3_3) {
            // Always encrypted
            payloadBytes = CryptoUtil.encryptAesEcb(payloadBytes, keyStore.getDeviceKey(), true);
            if (payloadBytes == null) {
                return Optional.empty();
            }

            if (commandType != DP_QUERY && commandType != CommandType.DP_REFRESH) {
                // Add 3.3 header
                ByteBuffer buffer = ByteBuffer.allocate(payloadBytes.length + 15);
                buffer.put("3.3".getBytes(StandardCharsets.UTF_8));
                buffer.position(15);
                buffer.put(payloadBytes);
                payloadBytes = buffer.array();
            }
        } else if (CommandType.CONTROL.equals(commandType)) {
            // Protocol 3.1 and below, only encrypt data if necessary
            byte[] encryptedPayload = CryptoUtil.encryptAesEcb(payloadBytes, keyStore.getDeviceKey(), true);
            if (encryptedPayload == null) {
                return Optional.empty();
            }
            String payloadStr = Base64.encode(encryptedPayload);
            String hash = CryptoUtil.md5(
                    "data=" + payloadStr + "||lpv=" + version.getString() + "||" + new String(keyStore.getDeviceKey()));

            // Create byte buffer from hex data
            payloadBytes = (version + hash.substring(8, 24) + payloadStr).getBytes(StandardCharsets.UTF_8);
        }

        // Allocate buffer with room for payload + 24 bytes for
        // prefix, sequence, command, length, crc, and suffix
        ByteBuffer buffer = ByteBuffer.allocate(payloadBytes.length + 24);

        // Add prefix, command, and length
        buffer.putInt(0x000055AA);
        buffer.putInt(++sequenceNo);
        buffer.putInt(commandType.getCode());
        buffer.putInt(payloadBytes.length + 8);

        // Add payload
        buffer.put(payloadBytes);

        // Calculate and add checksum
        int calculatedCrc = CryptoUtil.calculateChecksum(buffer.array(), 0, payloadBytes.length + 16);
        buffer.putInt(calculatedCrc);

        // Add postfix
        buffer.putInt(0x0000AA55);

        return Optional.of(buffer.array());
    }

    private Optional<byte[]> encode34(CommandType commandType, byte[] payloadBytes) {
        byte[] rawPayload = payloadBytes;

        if (commandType != DP_QUERY && commandType != HEART_BEAT && commandType != DP_QUERY_NEW
                && commandType != SESS_KEY_NEG_START && commandType != SESS_KEY_NEG_FINISH
                && commandType != DP_REFRESH) {
            rawPayload = new byte[payloadBytes.length + 15];
            System.arraycopy("3.4".getBytes(StandardCharsets.UTF_8), 0, rawPayload, 0, 3);
            System.arraycopy(payloadBytes, 0, rawPayload, 15, payloadBytes.length);
        }

        byte padding = (byte) (0x10 - (rawPayload.length & 0xf));
        byte[] padded = new byte[rawPayload.length + padding];
        Arrays.fill(padded, padding);
        System.arraycopy(rawPayload, 0, padded, 0, rawPayload.length);

        byte[] encryptedPayload = CryptoUtil.encryptAesEcb(padded, keyStore.getSessionKey(), false);
        if (encryptedPayload == null) {
            return Optional.empty();
        }

        ByteBuffer buffer = ByteBuffer.allocate(encryptedPayload.length + 52);

        // Add prefix, command, and length
        buffer.putInt(0x000055AA);
        buffer.putInt(++sequenceNo);
        buffer.putInt(commandType.getCode());
        buffer.putInt(encryptedPayload.length + 0x24);

        // Add payload
        buffer.put(encryptedPayload);

        // Calculate and add checksum
        byte[] checksumContent = new byte[encryptedPayload.length + 16];
        System.arraycopy(buffer.array(), 0, checksumContent, 0, encryptedPayload.length + 16);
        byte[] checksum = CryptoUtil.hmac(checksumContent, this.keyStore.getSessionKey());
        if (checksum == null) {
            return Optional.empty();
        }
        buffer.put(checksum);

        // Add postfix
        buffer.putInt(0x0000AA55);

        return Optional.of(buffer.array());
    }
}
