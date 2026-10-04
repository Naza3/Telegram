/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.telegram.messenger.ai;

import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Immutable wire snapshots of the entities produced by Telegram's full-text parser. */
public final class SummaryPublishEntities {
    private SummaryPublishEntities() { }

    /**
     * Null entities is Telegram's normal representation for plain text. Other
     * malformed input fails the entire capture; no entity is silently discarded.
     * Non-wire custom-emoji Document caches are deliberately not retained.
     */
    public static Snapshot capture(String parsedText, List<TLRPC.MessageEntity> entities) {
        if (parsedText == null) throw new IllegalArgumentException("Parsed text must not be null");
        ArrayList<byte[]> payloads = new ArrayList<>();
        ArrayList<SummaryPublishPlan.EntityRange> ranges = new ArrayList<>();
        if (entities != null) {
            for (int index = 0; index < entities.size(); index++) {
                TLRPC.MessageEntity entity = entities.get(index);
                if (entity == null || entity.offset < 0 || entity.length <= 0
                        || entity.offset > parsedText.length() - entity.length) {
                    throw new IllegalArgumentException("Invalid entity range");
                }
                byte[] payload = serialize(entity);
                TLRPC.MessageEntity decoded = deserialize(payload);
                // Native serializers may normalize flags, but every emitted byte
                // must survive a complete native decode/encode cycle.
                if (decoded.offset != entity.offset || decoded.length != entity.length
                        || !Arrays.equals(payload, serialize(decoded))) {
                    throw new IllegalArgumentException("Entity cannot be preserved losslessly");
                }
                payloads.add(payload);
                ranges.add(new SummaryPublishPlan.EntityRange(index, entity.offset,
                        entity.offset + entity.length, !canClip(decoded)));
            }
        }
        return new Snapshot(parsedText, payloads, ranges);
    }

    public static final class Snapshot {
        private final String parsedText;
        private final byte[][] payloads;
        private final List<SummaryPublishPlan.EntityRange> ranges;
        private final int hashCode;
        private final String fingerprint;

        private Snapshot(String parsedText, List<byte[]> payloads,
                         List<SummaryPublishPlan.EntityRange> ranges) {
            this.parsedText = parsedText;
            this.payloads = payloads.toArray(new byte[payloads.size()][]);
            this.ranges = Collections.unmodifiableList(new ArrayList<>(ranges));
            this.hashCode = 31 * parsedText.hashCode() + Arrays.deepHashCode(this.payloads);
            this.fingerprint = SummaryPublishEntities.fingerprint(parsedText, this.payloads);
        }

        public List<SummaryPublishPlan.EntityRange> ranges() { return ranges; }

        /** Includes exact text, ordered constructors, ranges, and every wire field. */
        public String fingerprint() { return fingerprint; }

        /**
         * Returns fresh native entities for exactly this part. Validate both the
         * text and every expected slice so a part from an unrelated plan cannot
         * silently omit entities, truncate an atomic entity, or reuse wrong offsets.
         */
        public ArrayList<TLRPC.MessageEntity> materialize(SummaryPublishPlan.Part part) {
            if (part == null || part.start < 0 || part.end <= part.start
                    || part.end > parsedText.length()
                    || !parsedText.substring(part.start, part.end).equals(part.text)) {
                throw new IllegalArgumentException("Part does not match the entity snapshot");
            }
            ArrayList<TLRPC.MessageEntity> result = new ArrayList<>();
            int sliceIndex = 0;
            for (SummaryPublishPlan.EntityRange range : ranges) {
                int start = Math.max(range.start, part.start);
                int end = Math.min(range.end, part.end);
                if (start >= end) continue;
                if (range.atomic && (start != range.start || end != range.end)) {
                    throw new IllegalArgumentException("Atomic entity cannot be split");
                }
                if (sliceIndex >= part.entities.size()) {
                    throw new IllegalArgumentException("Part is missing an entity");
                }
                SummaryPublishPlan.EntitySlice slice = part.entities.get(sliceIndex++);
                if (slice.sourceIndex != range.sourceIndex || slice.offset != start - part.start
                        || slice.length != end - start) {
                    throw new IllegalArgumentException("Part entity does not match the snapshot");
                }
                TLRPC.MessageEntity entity = deserialize(payloads[range.sourceIndex]);
                entity.offset = slice.offset;
                entity.length = slice.length;
                result.add(entity);
            }
            if (sliceIndex != part.entities.size()) {
                throw new IllegalArgumentException("Part contains an unrelated entity");
            }
            return result;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Snapshot)) return false;
            Snapshot snapshot = (Snapshot) other;
            return parsedText.equals(snapshot.parsedText) && Arrays.deepEquals(payloads, snapshot.payloads);
        }

        @Override
        public int hashCode() { return hashCode; }
    }

    private static boolean canClip(TLRPC.MessageEntity entity) {
        return entity instanceof TLRPC.TL_messageEntityBold
                || entity instanceof TLRPC.TL_messageEntityItalic
                || entity instanceof TLRPC.TL_messageEntityUnderline
                || entity instanceof TLRPC.TL_messageEntityStrike
                || entity instanceof TLRPC.TL_messageEntitySpoiler
                || entity instanceof TLRPC.TL_messageEntityCode
                || entity instanceof TLRPC.TL_messageEntityPre
                || entity instanceof TLRPC.TL_messageEntityBlockquote;
    }

    private static byte[] serialize(TLRPC.MessageEntity entity) {
        SerializedData output = new SerializedData();
        int flags = entity.flags;
        try {
            entity.serializeToStream(output);
            return output.toByteArray();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Entity could not be serialized");
        } finally {
            // Some native serializers derive flags from boolean fields in place.
            entity.flags = flags;
            output.cleanup();
        }
    }

    private static TLRPC.MessageEntity deserialize(byte[] payload) {
        SerializedData input = new SerializedData(payload);
        try {
            TLRPC.MessageEntity entity = TLRPC.MessageEntity.TLdeserialize(input, input.readInt32(true), true);
            if (entity == null || input.remaining() != 0) {
                throw new IllegalArgumentException("Entity wire payload was not fully consumed");
            }
            return entity;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Entity wire payload could not be restored");
        } finally {
            input.cleanup();
        }
    }

    private static String fingerprint(String text, byte[][] payloads) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
        updateInt(digest, text.length());
        // Encode UTF-16 units directly, including any unpaired surrogate, so the
        // fingerprint never conflates different Java strings through replacement.
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            digest.update((byte) (value >>> 8));
            digest.update((byte) value);
        }
        updateInt(digest, payloads.length);
        for (byte[] payload : payloads) {
            updateInt(digest, payload.length);
            digest.update(payload);
        }
        char[] hex = new char[64];
        char[] alphabet = "0123456789abcdef".toCharArray();
        byte[] bytes = digest.digest();
        for (int i = 0; i < bytes.length; i++) {
            hex[2 * i] = alphabet[(bytes[i] & 255) >>> 4];
            hex[2 * i + 1] = alphabet[bytes[i] & 15];
        }
        return new String(hex);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
