package com.collarmc.protocol;

import com.collarmc.api.identity.Identity;
import com.collarmc.io.ByteBufferInputStream;
import com.collarmc.io.IO;
import com.collarmc.security.messages.Cipher;
import com.collarmc.security.messages.CipherException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Encodes and decodes packets for/from the wire, handling encryption and different types of signal messages
 * Packet format is int(0x22)+int(version)+int(ENCRYPTEDMODE)+CiphertextMessage()
 */
public final class PacketIO {

    private static final Logger LOGGER = LogManager.getLogger(PacketIO.class);

    /** UwU **/
    private static final int PACKET_MARKER = 0x22;
    private static final int VERSION = 2;
    private static final int MODE_PLAIN = 0xc001;
    private static final int MODE_ENCRYPTED = 0xba5ed;
    public static final short MAX_PACKET_SIZE = Short.MAX_VALUE;

    @Nonnull
    private final ObjectMapper mapper;
    @Nullable
    private final Cipher cipher;

    public PacketIO(@Nonnull ObjectMapper mapper, @Nullable Cipher cipher) {
        this.mapper = mapper;
        this.cipher = cipher;
    }

    public <T> Optional<T> decode(Identity sender, InputStream is, Class<T> type) throws IOException, CipherException {
        return decode(sender, IO.toByteBuffer(is), type);
    }

    public <T> Optional<T> decode(Identity sender, ByteBuffer buffer, Class<T> type) throws IOException, CipherException {
        T decoded;
        int packetType;
        try (DataInputStream objectStream = new DataInputStream(new ByteBufferInputStream(buffer))) {
            int packetMarker = objectStream.readInt();
            if (packetMarker != PACKET_MARKER) {
                throw new IllegalStateException("not a collar packet " + Integer.toHexString(packetMarker));
            }
            int version = objectStream.readInt();
            if (version != VERSION) {
                throw new IllegalStateException("unknown packet version " + version);
            }
            packetType = objectStream.readInt();
            byte[] remainingBytes = IO.toByteArray(objectStream);
            if (packetType == MODE_PLAIN) {
                checkPacketSize(remainingBytes);
                decoded = mapper.readValue(remainingBytes, type);
            } else if (packetType == MODE_ENCRYPTED) {
                if (cipher == null) {
                    throw new IllegalStateException("cipher was not set when mode is expecting encrypted");
                }
                if (sender == null) {
                    LOGGER.error("Cannot read encrypted packets with no sender");
                    decoded = null;
                } else {
                    remainingBytes = cipher.decrypt(remainingBytes, sender);
                    checkPacketSize(remainingBytes);
                    decoded = mapper.readValue(remainingBytes, type);
                }
            } else {
                LOGGER.error("unknown packet type " + packetType);
                decoded = null;
            }
        }
        return Optional.ofNullable(decoded);
    }

    public byte[] encodePlain(Object object) throws IOException {
        byte[] rawBytes = mapper.writeValueAsBytes(object);
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            try (DataOutputStream objectStream = new DataOutputStream(outputStream)) {
                objectStream.writeInt(PACKET_MARKER);
                objectStream.writeInt(VERSION);
                objectStream.writeInt(MODE_PLAIN);
                objectStream.write(rawBytes);
            }
            byte[] bytes = outputStream.toByteArray();
            checkPacketSize(bytes);
            return bytes;
        }
    }

    public byte[] encodeEncrypted(Identity recipient, Object object) throws IOException, CipherException {
        if (cipher == null) {
            throw new IllegalStateException("cipher was not set when mode is expecting encrypted");
        }
        byte[] rawBytes = mapper.writeValueAsBytes(object);
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            try (DataOutputStream objectStream = new DataOutputStream(outputStream)) {
                objectStream.writeInt(PACKET_MARKER);
                objectStream.writeInt(VERSION);
                objectStream.writeInt(MODE_ENCRYPTED);
                if (recipient == null) {
                    throw new IllegalArgumentException("recipient cannot be null when sending MODE_ENCRYPTED packets");
                }
                objectStream.write(cipher.encrypt(rawBytes, recipient));
            }
            byte[] bytes = outputStream.toByteArray();
            checkPacketSize(bytes);
            return bytes;
        }
    }

    private void checkPacketSize(byte[] bytes) {
        if (bytes.length > PacketIO.MAX_PACKET_SIZE) {
            throw new IllegalStateException("Packet is too large. Size is " + bytes.length + " bytes when maximum is " + MAX_PACKET_SIZE);
        }
    }
}
