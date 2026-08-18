package io.github.hellices.agentframework.build.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TypedPublicContractPolicyTest {

  private static final Path REPOSITORY = RepositoryPaths.root();

  private static final Set<String> REVIEWED_FIXED_RECORDS =
      Set.of(
          "io.github.hellices.agentframework.api.message.MessageAttribution",
          "io.github.hellices.agentframework.api.message.Usage");

  @Test
  void publicContractsUseRecordsOnlyForReviewedFixedValues() throws Exception {
    PublicContractSurface.Report report = PublicContractSurface.inspect(REPOSITORY);

    assertThat(report.records())
        .filteredOn(record -> !REVIEWED_FIXED_RECORDS.contains(record.typeName()))
        .withFailMessage(
            "Public contracts may use records only for the reviewed fixed values %s, but these"
                + " public declarations are still records: %s",
            REVIEWED_FIXED_RECORDS, report.records())
        .isEmpty();
  }

  @Test
  void publicContractsDoNotExposeRawObjectMapsInPrimarySignatures() throws Exception {
    PublicContractSurface.Report report = PublicContractSurface.inspect(REPOSITORY);

    assertThat(report.rawMapSignatures())
        .withFailMessage(
            "Primary public API/SPI signatures must not expose Map<String, Object>. Use typed"
                + " values or JsonObject instead. Offending signatures: %s",
            report.rawMapSignatures())
        .isEmpty();
  }
}
