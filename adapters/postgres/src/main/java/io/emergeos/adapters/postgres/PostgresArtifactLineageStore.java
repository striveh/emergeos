package io.emergeos.adapters.postgres;

import io.emergeos.core.domain.ArtifactLineage;
import io.emergeos.core.domain.ArtifactLineageEntry;
import io.emergeos.core.port.ArtifactLineageStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class PostgresArtifactLineageStore implements ArtifactLineageStore {

  private static final String OWNED_LINEAGE_SQL =
      """
      SELECT
          a.principal_id,
          a.artifact_id,
          a.source_capture_id,
          a.current_version,
          a.current_hash,
          v.version,
          v.content,
          v.content_hash,
          v.base_version,
          v.base_hash,
          v.created_at
      FROM artifacts a
      JOIN artifact_versions v
        ON v.principal_id = a.principal_id
       AND v.artifact_id = a.artifact_id
      WHERE a.principal_id = :principalId
        AND a.artifact_id = :artifactId
      ORDER BY v.version
      """;

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;

  public PostgresArtifactLineageStore(
      DataSource dataSource, PlatformTransactionManager transactionManager) {
    this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource"));
    this.transactions =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager"));
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  @Override
  public ArtifactLineage create(ArtifactLineage proposed) {
    Objects.requireNonNull(proposed, "proposed");
    if (proposed.versions().size() != 1) {
      throw new IllegalArgumentException("a new Artifact must contain exactly version one");
    }
    return Objects.requireNonNull(
        transactions.execute(
            status -> {
              ArtifactLineageEntry initial = proposed.current();
              jdbc.sql(
                      """
                      INSERT INTO artifacts (
                          principal_id, artifact_id, source_capture_id,
                          current_version, current_hash
                      ) VALUES (
                          :principalId, :artifactId, :sourceCaptureId,
                          :currentVersion, :currentHash
                      )
                      """)
                  .param("principalId", proposed.principalId())
                  .param("artifactId", proposed.artifactId())
                  .param("sourceCaptureId", proposed.sourceCaptureId())
                  .param("currentVersion", initial.version())
                  .param("currentHash", initial.contentHash())
                  .update();
              insertVersion(proposed.principalId(), proposed.artifactId(), initial);
              return requireOwned(proposed.principalId(), proposed.artifactId());
            }),
        "Artifact create transaction result");
  }

  @Override
  public RevisionResult compareAndSwap(
      String principalId,
      String artifactId,
      int expectedBaseVersion,
      String expectedBaseHash,
      ArtifactLineageEntry proposed) {
    Objects.requireNonNull(proposed, "proposed");
    if (proposed.version() != expectedBaseVersion + 1
        || !Objects.equals(proposed.baseVersion(), expectedBaseVersion)
        || !Objects.equals(proposed.baseHash(), expectedBaseHash)) {
      throw new IllegalArgumentException("proposed revision must extend the expected base");
    }
    return Objects.requireNonNull(
        transactions.execute(
            status ->
                compareAndSwapInTransaction(
                    principalId,
                    artifactId,
                    expectedBaseVersion,
                    expectedBaseHash,
                    proposed)),
        "Artifact revision transaction result");
  }

  private RevisionResult compareAndSwapInTransaction(
      String principalId,
      String artifactId,
      int expectedBaseVersion,
      String expectedBaseHash,
      ArtifactLineageEntry proposed) {
    int updated =
        jdbc.sql(
                """
                UPDATE artifacts
                SET current_version = :nextVersion,
                    current_hash = :nextHash
                WHERE principal_id = :principalId
                  AND artifact_id = :artifactId
                  AND current_version = :expectedBaseVersion
                  AND current_hash = :expectedBaseHash
                """)
            .param("nextVersion", proposed.version())
            .param("nextHash", proposed.contentHash())
            .param("principalId", principalId)
            .param("artifactId", artifactId)
            .param("expectedBaseVersion", expectedBaseVersion)
            .param("expectedBaseHash", expectedBaseHash)
            .update();
    if (updated == 1) {
      insertVersion(principalId, artifactId, proposed);
      return new RevisionResult.Revised(requireOwned(principalId, artifactId));
    }
    Optional<Integer> currentVersion =
        jdbc.sql(
                """
                SELECT current_version
                FROM artifacts
                WHERE principal_id = :principalId
                  AND artifact_id = :artifactId
                """)
            .param("principalId", principalId)
            .param("artifactId", artifactId)
            .query(Integer.class)
            .optional();
    return currentVersion
        .<RevisionResult>map(RevisionResult.Conflict::new)
        .orElseGet(RevisionResult.NotFound::new);
  }

  @Override
  public Optional<ArtifactLineage> findOwned(String principalId, String artifactId) {
    List<LineageRow> rows =
        jdbc.sql(OWNED_LINEAGE_SQL)
            .param("principalId", principalId)
            .param("artifactId", artifactId)
            .query(PostgresArtifactLineageStore::mapLineageRow)
            .list();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    LineageRow first = rows.getFirst();
    List<ArtifactLineageEntry> versions = rows.stream().map(LineageRow::entry).toList();
    var lineage =
        new ArtifactLineage(
            first.artifactId(), first.principalId(), first.sourceCaptureId(), versions);
    if (lineage.current().version() != first.currentVersion()
        || !lineage.current().contentHash().equals(first.currentHash())) {
      throw new ArtifactLineageIntegrityException(
          "Artifact head does not match its immutable lineage");
    }
    return Optional.of(lineage);
  }

  private ArtifactLineage requireOwned(String principalId, String artifactId) {
    return findOwned(principalId, artifactId)
        .orElseThrow(
            () ->
                new ArtifactLineageIntegrityException(
                    "Artifact write completed without a committed database lineage"));
  }

  private void insertVersion(
      String principalId, String artifactId, ArtifactLineageEntry version) {
    jdbc.sql(
            """
            INSERT INTO artifact_versions (
                principal_id, artifact_id, version, content, content_hash,
                base_version, base_hash, created_at
            ) VALUES (
                :principalId, :artifactId, :version, :content, :contentHash,
                :baseVersion, :baseHash, :createdAt
            )
            """)
        .param("principalId", principalId)
        .param("artifactId", artifactId)
        .param("version", version.version())
        .param("content", version.content())
        .param("contentHash", version.contentHash())
        .param("baseVersion", version.baseVersion(), java.sql.Types.INTEGER)
        .param("baseHash", version.baseHash(), java.sql.Types.CHAR)
        .param("createdAt", Timestamp.from(version.createdAt()))
        .update();
  }

  private static LineageRow mapLineageRow(ResultSet resultSet, int rowNumber)
      throws SQLException {
    int baseVersionValue = resultSet.getInt("base_version");
    Integer baseVersion = resultSet.wasNull() ? null : baseVersionValue;
    return new LineageRow(
        resultSet.getString("principal_id"),
        resultSet.getString("artifact_id"),
        resultSet.getString("source_capture_id"),
        resultSet.getInt("current_version"),
        resultSet.getString("current_hash"),
        new ArtifactLineageEntry(
            resultSet.getInt("version"),
            resultSet.getString("content"),
            resultSet.getString("content_hash"),
            baseVersion,
            resultSet.getString("base_hash"),
            resultSet.getTimestamp("created_at").toInstant()));
  }

  private record LineageRow(
      String principalId,
      String artifactId,
      String sourceCaptureId,
      int currentVersion,
      String currentHash,
      ArtifactLineageEntry entry) {}

  private static final class ArtifactLineageIntegrityException extends RuntimeException {

    private ArtifactLineageIntegrityException(String message) {
      super(message);
    }
  }
}
