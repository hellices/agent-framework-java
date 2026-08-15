package io.github.hellices.agentframework.engine.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hellices.agentframework.api.session.SessionSnapshot;
import io.github.hellices.agentframework.api.session.SessionStateEntry;
import io.github.hellices.agentframework.spi.session.SessionSnapshotCodec;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSessionStoreTest {

  @TempDir Path temporaryDirectory;

  @Test
  void savesLoadsDeletesAndUsesLastWriterWins() throws Exception {
    FileSessionStore store =
        new FileSessionStore(temporaryDirectory, new JacksonSessionSnapshotCodec());
    SessionSnapshot first = snapshot("../../outside", 1, "first");
    SessionSnapshot second = snapshot("../../outside", 2, "second");

    store.save(first).toCompletableFuture().join();
    store.save(second).toCompletableFuture().join();
    SessionSnapshot loaded = store.load("../../outside").toCompletableFuture().join().orElseThrow();

    assertThat(loaded).isEqualTo(second);
    try (Stream<Path> files = Files.list(temporaryDirectory)) {
      assertThat(files)
          .hasSize(1)
          .allSatisfy(
              path -> {
                assertThat(path.getParent().toRealPath())
                    .isEqualTo(temporaryDirectory.toRealPath());
                assertThat(path.getFileName().toString())
                    .isEqualTo(
                        "e28b700f2449d902a77c46549f66fa06dc5428009311e2c4ee92fe827e9e6441"
                            + ".session.json");
              });
    }

    store.delete("../../outside").toCompletableFuture().join();
    assertThat(store.load("../../outside").toCompletableFuture().join()).isEmpty();
  }

  @Test
  void rejectsMalformedUnicodeSessionIdsBeforeCreatingFiles() throws Exception {
    FileSessionStore store =
        new FileSessionStore(temporaryDirectory, new JacksonSessionSnapshotCodec());
    String malformed = "\uD800";

    assertThatThrownBy(
            () -> store.save(snapshot(malformed, 0, "value")).toCompletableFuture().join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.load(malformed).toCompletableFuture().join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.delete(malformed).toCompletableFuture().join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
    try (Stream<Path> files = Files.list(temporaryDirectory)) {
      assertThat(files).isEmpty();
    }

    SessionSnapshot valid = snapshot("?", 0, "distinct");
    store.save(valid).toCompletableFuture().join();
    assertThat(store.load("?").toCompletableFuture().join()).contains(valid);
  }

  @Test
  void corruptionKeepsParseAndSchemaFailuresClassified() throws Exception {
    FileSessionStore store =
        new FileSessionStore(temporaryDirectory, new JacksonSessionSnapshotCodec());
    store.save(snapshot("session-1", 0, "value")).toCompletableFuture().join();
    Path stored;
    try (Stream<Path> files = Files.list(temporaryDirectory)) {
      stored = files.findFirst().orElseThrow();
    }
    Files.writeString(stored, "{");

    assertThatThrownBy(() -> store.load("session-1").toCompletableFuture().join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(SessionSnapshotParseException.class);
    assertThat(stored).doesNotExist();
    try (Stream<Path> files = Files.list(temporaryDirectory)) {
      assertThat(files.filter(path -> fileName(path).endsWith(".corrupt")))
          .hasSize(1)
          .allSatisfy(path -> assertThat(path.getParent()).isEqualTo(temporaryDirectory));
    }

    store.save(snapshot("session-1", 1, "value")).toCompletableFuture().join();
    try (Stream<Path> files = Files.list(temporaryDirectory)) {
      stored =
          files.filter(path -> fileName(path).endsWith(".session.json")).findFirst().orElseThrow();
    }
    Files.writeString(stored, "{\"type\":\"session\",\"version\":\"2.0\"}");

    assertThatThrownBy(() -> store.load("session-1").toCompletableFuture().join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(SessionSnapshotSchemaException.class);
    assertThat(stored).exists();
  }

  @Test
  void oversizedSnapshotsFailBeforeDecodingAndRemainAvailableForRecovery() throws Exception {
    FileSessionStore store =
        new FileSessionStore(temporaryDirectory, new JacksonSessionSnapshotCodec());
    store.save(snapshot("session-1", 0, "value")).toCompletableFuture().join();
    Path stored;
    try (Stream<Path> files = Files.list(temporaryDirectory)) {
      stored = files.findFirst().orElseThrow();
    }
    Files.write(stored, new byte[SessionSnapshotLimits.MAX_BYTES + 1]);

    assertThatThrownBy(() -> store.load("session-1").toCompletableFuture().join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(SessionSnapshotSchemaException.class);
    assertThat(stored).exists();
    try (Stream<Path> files = Files.list(temporaryDirectory)) {
      assertThat(files.filter(path -> fileName(path).endsWith(".corrupt"))).isEmpty();
    }
  }

  @Test
  void noContentSnapshotsAreQuarantinedAsParseCorruption() throws Exception {
    for (String content : new String[] {"", " \n\t"}) {
      Path root = temporaryDirectory.resolve("case-" + content.length());
      FileSessionStore store = new FileSessionStore(root, new JacksonSessionSnapshotCodec());
      store.save(snapshot("session-1", 0, "value")).toCompletableFuture().join();
      Path stored;
      try (Stream<Path> files = Files.list(root)) {
        stored = files.findFirst().orElseThrow();
      }
      Files.writeString(stored, content);

      assertThatThrownBy(() -> store.load("session-1").toCompletableFuture().join())
          .isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(SessionSnapshotParseException.class);
      assertThat(stored).doesNotExist();
      try (Stream<Path> files = Files.list(root)) {
        assertThat(files.filter(path -> fileName(path).endsWith(".corrupt"))).hasSize(1);
      }
    }
  }

  @Test
  void concurrentStoreInstanceSaveCannotBeQuarantinedByAnEarlierLoad() throws Exception {
    BlockingDecodeCodec codec = new BlockingDecodeCodec();
    FileSessionStore readerStore = new FileSessionStore(temporaryDirectory, codec);
    FileSessionStore writerStore =
        new FileSessionStore(temporaryDirectory, new JacksonSessionSnapshotCodec());
    readerStore.save(snapshot("session-1", 0, "corrupt")).toCompletableFuture().join();
    Path stored;
    try (Stream<Path> files = Files.list(temporaryDirectory)) {
      stored = files.findFirst().orElseThrow();
    }
    Files.writeString(stored, "{");
    codec.blockNextDecode();

    AtomicReference<RuntimeException> loadFailure = new AtomicReference<>();
    Thread load =
        new Thread(
            () -> {
              try {
                readerStore.load("session-1").toCompletableFuture().join();
              } catch (RuntimeException failure) {
                loadFailure.set(failure);
              }
            });
    load.start();
    assertThat(codec.awaitDecodeStarted()).isTrue();
    SessionSnapshot replacement = snapshot("session-1", 1, "replacement");
    AtomicReference<RuntimeException> saveFailure = new AtomicReference<>();
    Thread save =
        new Thread(
            () -> {
              try {
                writerStore.save(replacement).toCompletableFuture().join();
              } catch (RuntimeException failure) {
                saveFailure.set(failure);
              }
            });
    save.start();
    try {
      awaitBlocked(save);
    } finally {
      codec.releaseDecode();
    }
    load.join(5_000);
    save.join(5_000);
    assertThat(load.isAlive()).isFalse();
    assertThat(save.isAlive()).isFalse();
    assertThat(loadFailure.get())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(SessionSnapshotParseException.class);
    assertThat(saveFailure.get()).isNull();
    assertThat(writerStore.load("session-1").toCompletableFuture().join()).contains(replacement);
  }

  @Test
  void fileSystemErrorsAreNotReportedAsMissingSessions() throws Exception {
    Path root = temporaryDirectory.resolve("store");
    FileSessionStore store = new FileSessionStore(root, new JacksonSessionSnapshotCodec());
    Files.delete(root);
    Files.writeString(root, "not a directory");

    assertThatThrownBy(() -> store.load("session-1").toCompletableFuture().join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(FileSessionStoreException.class);
    assertThatThrownBy(() -> store.delete("session-1").toCompletableFuture().join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(FileSessionStoreException.class);

    Files.delete(root);
    assertThatThrownBy(() -> store.load("session-1").toCompletableFuture().join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(FileSessionStoreException.class);
    assertThatThrownBy(() -> store.delete("session-1").toCompletableFuture().join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(FileSessionStoreException.class);
  }

  @Test
  void symlinkSquattingFailsLoadSaveAndDelete() throws Exception {
    FileSessionStore store =
        new FileSessionStore(temporaryDirectory, new JacksonSessionSnapshotCodec());
    store.save(snapshot("session-1", 0, "value")).toCompletableFuture().join();
    Path stored;
    try (Stream<Path> files = Files.list(temporaryDirectory)) {
      stored = files.filter(Files::isRegularFile).findFirst().orElseThrow();
    }
    Files.delete(stored);
    Path outside =
        Objects.requireNonNull(temporaryDirectory.getParent())
            .resolve("outside-session-" + UUID.randomUUID() + ".json");
    Files.writeString(outside, "outside");

    try {
      try {
        Files.createSymbolicLink(stored, outside);
      } catch (FileSystemException | UnsupportedOperationException failure) {
        Assumptions.assumeTrue(false, () -> "symbolic links are unavailable: " + failure);
      }
      assertThatThrownBy(() -> store.load("session-1").toCompletableFuture().join())
          .isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(FileSessionStoreException.class);
      assertThatThrownBy(
              () ->
                  store.save(snapshot("session-1", 1, "replacement")).toCompletableFuture().join())
          .isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(FileSessionStoreException.class);
      assertThatThrownBy(() -> store.delete("session-1").toCompletableFuture().join())
          .isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(FileSessionStoreException.class);
      assertThat(Files.readString(outside)).isEqualTo("outside");
    } finally {
      Files.deleteIfExists(stored);
      Files.deleteIfExists(outside);
    }
  }

  private static SessionSnapshot snapshot(String sessionId, long revision, String value) {
    return new SessionSnapshot(
        "session",
        "1.0",
        sessionId,
        null,
        revision,
        Instant.EPOCH,
        Map.of("value", new SessionStateEntry("test.string", 1, value)));
  }

  private static String fileName(Path path) {
    return Objects.requireNonNull(path.getFileName()).toString();
  }

  private static void awaitBlocked(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    Thread.State state = thread.getState();
    while (thread.isAlive() && !isWaiting(state) && System.nanoTime() < deadline) {
      Thread.sleep(10);
      state = thread.getState();
    }
    assertThat(state).isIn(Thread.State.BLOCKED, Thread.State.WAITING, Thread.State.TIMED_WAITING);
  }

  private static boolean isWaiting(Thread.State state) {
    return state == Thread.State.BLOCKED
        || state == Thread.State.WAITING
        || state == Thread.State.TIMED_WAITING;
  }

  private static final class BlockingDecodeCodec implements SessionSnapshotCodec {

    private final JacksonSessionSnapshotCodec delegate = new JacksonSessionSnapshotCodec();
    private final CountDownLatch decodeStarted = new CountDownLatch(1);
    private final CountDownLatch releaseDecode = new CountDownLatch(1);
    private volatile boolean blockDecode;

    @Override
    public byte[] encode(SessionSnapshot snapshot) {
      return delegate.encode(snapshot);
    }

    @Override
    public SessionSnapshot decode(byte[] encoded) {
      if (blockDecode) {
        blockDecode = false;
        decodeStarted.countDown();
        try {
          releaseDecode.await();
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new AssertionError(failure);
        }
      }
      return delegate.decode(encoded);
    }

    private void blockNextDecode() {
      blockDecode = true;
    }

    private boolean awaitDecodeStarted() throws InterruptedException {
      return decodeStarted.await(5, TimeUnit.SECONDS);
    }

    private void releaseDecode() {
      releaseDecode.countDown();
    }
  }
}
