package io.github.hellices.agentframework.engine.session;

import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.spi.session.SessionSnapshotCodec;
import io.github.hellices.agentframework.spi.session.SessionStore;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class FileSessionStore implements SessionStore {

  private static final Object[] OPERATION_LOCKS = createOperationLocks();

  private final Path root;
  private final boolean directoryForceSupported;
  private final SessionSnapshotCodec codec;

  public FileSessionStore(Path root, SessionSnapshotCodec codec) {
    this.codec = Objects.requireNonNull(codec, "codec must not be null");
    Path configuredRoot = Objects.requireNonNull(root, "root must not be null");
    try {
      Files.createDirectories(configuredRoot);
      this.root = configuredRoot.toRealPath();
      this.directoryForceSupported = File.separatorChar != '\\';
    } catch (IOException failure) {
      throw new FileSessionStoreException("failed to initialize session store root", failure);
    }
  }

  @Override
  public CompletionStage<Optional<SessionSnapshot>> load(String sessionId) {
    synchronized (operationLock(sessionId)) {
      return loadLocked(sessionId);
    }
  }

  private CompletionStage<Optional<SessionSnapshot>> loadLocked(String sessionId) {
    try {
      Path target = target(sessionId);
      verifyRoot();
      rejectSymbolicLink(target);
      try {
        return CompletableFuture.completedFuture(Optional.of(codec.decode(readNoFollow(target))));
      } catch (NoSuchFileException failure) {
        verifyRoot();
        return CompletableFuture.completedFuture(Optional.empty());
      } catch (SessionSnapshotParseException failure) {
        quarantine(target, fileName(sessionId), failure);
        return CompletableFuture.failedFuture(failure);
      }
    } catch (IOException | RuntimeException failure) {
      return CompletableFuture.failedFuture(classify("failed to load session snapshot", failure));
    }
  }

  @Override
  public CompletionStage<Void> save(SessionSnapshot snapshot) {
    String sessionId = snapshot == null ? null : snapshot.sessionId();
    synchronized (operationLock(sessionId)) {
      return saveLocked(snapshot);
    }
  }

  private CompletionStage<Void> saveLocked(SessionSnapshot snapshot) {
    Path temporary = null;
    try {
      SessionSnapshot value = Objects.requireNonNull(snapshot, "snapshot must not be null");
      String targetFileName = fileName(value.sessionId());
      Path target = targetFile(targetFileName);
      byte[] encoded = codec.encode(value);
      verifyRoot();
      rejectSymbolicLink(target);
      temporary = root.resolve(targetFileName + "." + UUID.randomUUID() + ".temporary");
      writeDurably(temporary, encoded);
      try {
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException failure) {
        throw new FileSessionStoreException(
            "atomic replacement is not supported for the session store root", failure);
      }
      forceDirectory(root);
      return CompletableFuture.completedFuture(null);
    } catch (IOException | RuntimeException failure) {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
      }
      return CompletableFuture.failedFuture(classify("failed to save session snapshot", failure));
    }
  }

  @Override
  public CompletionStage<Void> delete(String sessionId) {
    synchronized (operationLock(sessionId)) {
      return deleteLocked(sessionId);
    }
  }

  private CompletionStage<Void> deleteLocked(String sessionId) {
    try {
      Path target = target(sessionId);
      verifyRoot();
      rejectSymbolicLink(target);
      if (Files.deleteIfExists(target)) {
        forceDirectory(root);
      } else {
        verifyRoot();
      }
      return CompletableFuture.completedFuture(null);
    } catch (IOException | RuntimeException failure) {
      return CompletableFuture.failedFuture(classify("failed to delete session snapshot", failure));
    }
  }

  private Path target(String sessionId) {
    return targetFile(fileName(sessionId));
  }

  private Path targetFile(String targetFileName) {
    Path target = root.resolve(targetFileName).normalize();
    if (!target.startsWith(root)) {
      throw new FileSessionStoreException("session path escapes the configured root");
    }
    return target;
  }

  private static String fileName(String sessionId) {
    String value = requireSessionId(sessionId);
    try {
      return HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(value.getBytes(StandardCharsets.UTF_8)))
          + ".session.json";
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is not available", failure);
    }
  }

  private static String requireSessionId(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    for (int index = 0; index < sessionId.length(); index++) {
      char character = sessionId.charAt(index);
      if (Character.isHighSurrogate(character)) {
        if (index + 1 >= sessionId.length()
            || !Character.isLowSurrogate(sessionId.charAt(index + 1))) {
          throw new IllegalArgumentException("sessionId must contain valid Unicode");
        }
        index++;
      } else if (Character.isLowSurrogate(character)) {
        throw new IllegalArgumentException("sessionId must contain valid Unicode");
      }
    }
    return sessionId;
  }

  private static void rejectSymbolicLink(Path target) {
    if (Files.isSymbolicLink(target)) {
      throw new FileSessionStoreException("session snapshot path must not be a symbolic link");
    }
  }

  private void verifyRoot() throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
      throw new FileSessionStoreException(
          "session store root must be a directory and not a symbolic link");
    }
  }

  private static byte[] readNoFollow(Path target) throws IOException {
    Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    try (SeekableByteChannel channel = Files.newByteChannel(target, options);
        InputStream input = Channels.newInputStream(channel)) {
      byte[] encoded = input.readNBytes(SessionSnapshotLimits.MAX_BYTES + 1);
      if (encoded.length > SessionSnapshotLimits.MAX_BYTES) {
        throw new SessionSnapshotSchemaException("session snapshot exceeds the 1 MiB limit");
      }
      return encoded;
    }
  }

  private static void writeDurably(Path target, byte[] encoded) throws IOException {
    try (FileChannel channel =
        FileChannel.open(
            target,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS)) {
      ByteBuffer buffer = ByteBuffer.wrap(encoded);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }
  }

  private void forceDirectory(Path directory) throws IOException {
    if (!directoryForceSupported) {
      return;
    }
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  private void quarantine(
      Path target, String targetFileName, SessionSnapshotParseException parseFailure) {
    try {
      Path quarantined = root.resolve(targetFileName + "." + UUID.randomUUID() + ".corrupt");
      try {
        Files.move(target, quarantined, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException failure) {
        Files.move(target, quarantined);
      }
      forceDirectory(root);
    } catch (IOException | FileSessionStoreException failure) {
      parseFailure.addSuppressed(
          new FileSessionStoreException("failed to quarantine corrupt session snapshot", failure));
    }
  }

  private static RuntimeException classify(String operation, Exception failure) {
    if (failure instanceof SessionSnapshotParseException
        || failure instanceof SessionSnapshotSchemaException
        || failure instanceof FileSessionStoreException
        || failure instanceof IllegalArgumentException) {
      return (RuntimeException) failure;
    }
    return new FileSessionStoreException(operation, failure);
  }

  private Object operationLock(String sessionId) {
    int hash = 31 * root.hashCode() + Objects.hashCode(sessionId);
    return OPERATION_LOCKS[Math.floorMod(hash, OPERATION_LOCKS.length)];
  }

  private static Object[] createOperationLocks() {
    Object[] locks = new Object[64];
    for (int index = 0; index < locks.length; index++) {
      locks[index] = new Object();
    }
    return locks;
  }
}
