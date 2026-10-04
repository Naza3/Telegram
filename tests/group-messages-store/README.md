# Local deleted-message store checks

Run `bash tests/group-messages-store/run.sh` with JDK 8 or later. The suite compiles the actual Java store and uses real temporary files and AES-GCM keys. No Android, JSON library, storage/crypto stubs, or Gradle download is required.

Coverage includes per-owner authenticated encryption, owner/group identity collisions, user/channel sender and topic cleanup, duplicate deletion events, independent cleanup and key revocation failures, inclusive original-message date ranges, restart round trips, count/UTF-8 ciphertext bounds, failure preservation, invalid schemas and Unicode, and concurrent store instances.

Android Keystore availability and lifecycle, Telegram deletion capture/eligibility, and UI integration need their separate integration checks. The store's process-local lock serializes instances inside the Telegram process; this is not a multi-process database. Atomic replacement uses a synced temporary ciphertext plus a same-directory rename on Android/Linux.
