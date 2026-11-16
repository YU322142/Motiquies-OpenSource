package com.mapsyncer.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;

final class MotiquiesRegionPolicy {
    static final String SHARED_FOLDER = "Multiplayer_Any Address";
    private static final Pattern SHARED_SETTING = Pattern.compile(
        "(?m)^\\s*differentiate_by_server_address\\s*=\\s*false\\s*(?:#.*)?$");

    private MotiquiesRegionPolicy() {}

    static boolean usesSharedDirectory(Path gameDir) {
        Path config = gameDir.resolve("config/xaero/world-map/client.cfg");
        try {
            return Files.isRegularFile(config) && SHARED_SETTING.matcher(Files.readString(config)).find();
        } catch (IOException error) {
            LoggerFactory.getLogger(MotiquiesRegionPolicy.class).debug("Cannot read Xaero storage setting", error);
            return false;
        }
    }

    static void preserveBeforeReplace(Path target, byte[] published) throws IOException {
        if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to replace a non-regular map file: " + target);
        }
        byte[] previous = Files.readAllBytes(target);
        if (Arrays.equals(previous, published)) return;

        Path history = Files.createDirectories(target.getParent().resolve(".motiquies-local-history"));
        Path copy = history.resolve(target.getFileName() + "." + sha256(previous) + ".bak");
        if (Files.exists(copy, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(copy, LinkOption.NOFOLLOW_LINKS)
                    || !Arrays.equals(previous, Files.readAllBytes(copy))) {
                throw new IOException("Local map history does not match the original: " + copy);
            }
            return;
        }
        Path staging = Files.createTempFile(history, "region-", ".tmp");
        try {
            Files.write(staging, previous);
            Files.move(staging, copy, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(staging, copy);
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
