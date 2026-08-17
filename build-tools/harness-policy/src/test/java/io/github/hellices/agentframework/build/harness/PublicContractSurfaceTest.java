package io.github.hellices.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicContractSurfaceTest {

  @TempDir Path repository;

  @Test
  void findsNestedPublicTypesDeclaredInsidePublicInterfaces() throws Exception {
    write(
        "module/src/main/java/io/github/hellices/agentframework/api/example/Outer.java",
        """
        package io.github.hellices.agentframework.api.example;

        public interface Outer {
          record Bad(String value) {}
        }
        """);

    PublicContractSurface.Report report = PublicContractSurface.inspect(repository);

    assertThat(report.records())
        .extracting(PublicContractSurface.UnexpectedRecord::typeName)
        .containsExactly("io.github.hellices.agentframework.api.example.Outer.Bad");
  }

  @Test
  void detectsFullyQualifiedRawObjectMapSignatures() throws Exception {
    write(
        "module/src/main/java/io/github/hellices/agentframework/api/example/Surface.java",
        """
        package io.github.hellices.agentframework.api.example;

        public interface Surface {
          java.util.Map<java.lang.String, java.lang.Object> metadata();

          void accept(java.util.Map<java.lang.String, java.lang.Object> value);
        }
        """);

    PublicContractSurface.Report report = PublicContractSurface.inspect(repository);

    assertThat(report.rawMapSignatures())
        .extracting(PublicContractSurface.RawMapSignature::member)
        .containsExactlyInAnyOrder("metadata", "accept");
  }

  @Test
  void ignoresPackagePrivateTopLevelTypesInScannedPackages() throws Exception {
    write(
        "module/src/main/java/io/github/hellices/agentframework/api/example/Hidden.java",
        """
        package io.github.hellices.agentframework.api.example;

        record Hidden(String value) {}
        """);

    PublicContractSurface.Report report = PublicContractSurface.inspect(repository);

    assertThat(report.records()).isEmpty();
    assertThat(report.rawMapSignatures()).isEmpty();
  }

  @Test
  void detectsWrappedRawObjectMapsInReturnAndParameterTypes() throws Exception {
    write(
        "module/src/main/java/io/github/hellices/agentframework/api/example/Wrapped.java",
        """
        package io.github.hellices.agentframework.api.example;

        import java.util.List;
        import java.util.Optional;
        import java.util.concurrent.CompletionStage;

        public interface Wrapped {
          CompletionStage<java.util.Map<java.lang.String, java.lang.Object>> metadata();

          void accept(
              List<? extends Optional<java.util.Map<java.lang.String, java.lang.Object>[]>> value);
        }
        """);

    PublicContractSurface.Report report = PublicContractSurface.inspect(repository);

    assertThat(report.rawMapSignatures())
        .extracting(PublicContractSurface.RawMapSignature::member)
        .containsExactlyInAnyOrder("metadata", "accept");
  }

  private void write(String relativePath, String text) throws Exception {
    Path file = repository.resolve(relativePath);
    Files.createDirectories(Objects.requireNonNull(file.getParent(), "file must have a parent"));
    Files.writeString(file, text);
  }
}
