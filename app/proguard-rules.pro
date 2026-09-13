# Release rules for Secure WhatsApp AI.
#
# R8 is currently disabled (see gradle.properties: securewa.release.minifyEnabled).
# Rules are added together with the release hardening milestone, which also
# verifies that a minified release build still passes the message pipeline
# tests. Until then this file only documents the intent.
#
# -keep class com.securewa.core.** { *; }   # deterministic domain model, keep names for audit logs
# -dontobfuscate is intentionally NOT set: the release build must be obfuscated.
