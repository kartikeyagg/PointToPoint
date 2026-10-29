package com.kartikey.p2pchat.conf.app;

import com.kartikey.p2pchat.conf.Identity;
import com.kartikey.p2pchat.conf.ParticipantId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

/**
 * Keeps this machine's participant identity on disk, so admins see the same person across meetings
 * and restarts. Layout: {@code participantId(88) | private keys}; the file is readable by the owner
 * only. A damaged file is moved aside and a new identity made.
 */
public final class IdentityStore {

    private final Path file;

    public IdentityStore(Path file) {
        this.file = file;
    }

    /** {@code ~/.p2p-conference/identity}, or {@code identity-<port>} for extra instances on other ports. */
    public static IdentityStore forPort(int port, int defaultPort) {
        Path dir = Path.of(System.getProperty("user.home"), ".p2p-conference");
        return new IdentityStore(dir.resolve(port == defaultPort ? "identity" : "identity-" + port));
    }

    public Identity loadOrCreate() throws IOException {
        if (Files.exists(file)) {
            try {
                byte[] data = Files.readAllBytes(file);
                ParticipantId id = ParticipantId.decode(data, 0);
                return Identity.importPrivate(Arrays.copyOfRange(data, ParticipantId.ENCODED_LEN, data.length), id);
            } catch (IllegalArgumentException e) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".bad-" + System.currentTimeMillis()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Identity me = Identity.generate();
        save(me);
        return me;
    }

    private void save(Identity me) throws IOException {
        Files.createDirectories(file.getParent());
        restrict(file.getParent(), "rwx------");
        byte[] id = me.id().encoded(), priv = me.exportPrivate();
        byte[] data = Arrays.copyOf(id, id.length + priv.length);
        System.arraycopy(priv, 0, data, id.length, priv.length);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, data);
        restrict(tmp, "rw-------");
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void restrict(Path p, String perms) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows: the file stays in the user's profile, which is private by default
        }
    }
}
