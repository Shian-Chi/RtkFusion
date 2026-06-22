package com.example.rtkgnss.ntrip;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses RTCM3 byte stream into discrete messages.
 *
 * RTCM3 frame structure:
 *   Byte 0:    0xD3 (preamble)
 *   Bytes 1-2: 6 reserved bits + 10-bit message length
 *   Bytes 3..N+2: message data (N bytes)
 *   Bytes N+3..N+5: 24-bit CRC
 */
public class RtcmParser {

    private static final String TAG = "RtcmParser";
    private static final int PREAMBLE = 0xD3;
    private static final int MAX_MSG_LEN = 1023;

    // CRC-24Q polynomial
    private static final int CRC24Q_POLY = 0x1864CFB;

    public interface MessageListener {
        void onRtcmMessage(int messageType, byte[] payload, int length);
    }

    private final List<MessageListener> listeners = new ArrayList<>();
    private final byte[] buffer = new byte[4096];
    private int bufferLen = 0;

    public void addListener(MessageListener listener) {
        listeners.add(listener);
    }

    /**
     * Feed raw bytes from the NTRIP stream. Messages are emitted via listeners.
     */
    public void feed(byte[] data, int length) {
        // Append to internal buffer
        int available = buffer.length - bufferLen;
        int toCopy = Math.min(length, available);
        System.arraycopy(data, 0, buffer, bufferLen, toCopy);
        bufferLen += toCopy;

        processBuffer();
    }

    private void processBuffer() {
        int pos = 0;

        while (pos < bufferLen) {
            // Find preamble
            if ((buffer[pos] & 0xFF) != PREAMBLE) {
                pos++;
                continue;
            }

            // Need at least 3 bytes for header
            if (pos + 3 > bufferLen) break;

            int msgLen = ((buffer[pos + 1] & 0x03) << 8) | (buffer[pos + 2] & 0xFF);
            if (msgLen > MAX_MSG_LEN) {
                // Invalid length, skip preamble
                pos++;
                continue;
            }

            int frameLen = 3 + msgLen + 3; // header + payload + CRC
            if (pos + frameLen > bufferLen) break; // wait for more data

            // Verify CRC
            if (!verifyCrc(buffer, pos, 3 + msgLen)) {
                Log.w(TAG, "CRC mismatch at pos=" + pos);
                pos++;
                continue;
            }

            // Extract message type from first 12 bits of payload
            int msgType = 0;
            if (msgLen >= 2) {
                msgType = ((buffer[pos + 3] & 0xFF) << 4) | ((buffer[pos + 4] & 0xFF) >> 4);
            }

            byte[] payload = new byte[msgLen];
            System.arraycopy(buffer, pos + 3, payload, 0, msgLen);

            Log.d(TAG, "RTCM3 msg type=" + msgType + " len=" + msgLen);
            for (MessageListener listener : listeners) {
                listener.onRtcmMessage(msgType, payload, msgLen);
            }

            pos += frameLen;
        }

        // Compact buffer
        if (pos > 0) {
            int remaining = bufferLen - pos;
            System.arraycopy(buffer, pos, buffer, 0, remaining);
            bufferLen = remaining;
        }
    }

    private boolean verifyCrc(byte[] data, int offset, int length) {
        int crc = crc24q(data, offset, length);
        int expected = ((data[offset + length] & 0xFF) << 16)
                | ((data[offset + length + 1] & 0xFF) << 8)
                | (data[offset + length + 2] & 0xFF);
        return crc == expected;
    }

    private int crc24q(byte[] data, int offset, int length) {
        int crc = 0;
        for (int i = 0; i < length; i++) {
            crc ^= (data[offset + i] & 0xFF) << 16;
            for (int j = 0; j < 8; j++) {
                crc <<= 1;
                if ((crc & 0x1000000) != 0) crc ^= CRC24Q_POLY;
            }
        }
        return crc & 0xFFFFFF;
    }
}
