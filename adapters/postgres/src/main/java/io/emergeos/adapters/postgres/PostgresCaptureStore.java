package io.emergeos.adapters.postgres;

import io.emergeos.contracts.DataClass;
import io.emergeos.core.domain.Capture;
import io.emergeos.core.domain.CaptureSourceType;
import io.emergeos.core.port.CaptureStore;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class PostgresCaptureStore implements CaptureStore {

  private static final String COLUMNS =
      """
      principal_id, capture_id, client_nonce, request_hash, content,
      source_type, source_ref, data_class, captured_at
      """;

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;

  public PostgresCaptureStore(
      DataSource dataSource, PlatformTransactionManager transactionManager) {
    this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource"));
    this.transactions =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager"));
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  @Override
  public SaveResult saveOrFindByNonce(Capture proposed) {
    Objects.requireNonNull(proposed, "proposed");
    return Objects.requireNonNull(
        transactions.execute(status -> saveOrFindByNonceInTransaction(proposed)),
        "Capture transaction result");
  }

  private SaveResult saveOrFindByNonceInTransaction(Capture proposed) {
    int inserted =
        jdbc.sql(
                """
                INSERT INTO captures (
                    principal_id, capture_id, client_nonce, request_hash, content,
                    source_type, source_ref, data_class, captured_at
                ) VALUES (
                    :principalId, :captureId, :clientNonce, :requestHash, :content,
                    :sourceType, :sourceRef, :dataClass, :capturedAt
                )
                ON CONFLICT (principal_id, client_nonce) DO NOTHING
                """)
            .param("principalId", proposed.principalId())
            .param("captureId", proposed.captureId())
            .param("clientNonce", proposed.clientNonce())
            .param("requestHash", proposed.requestHash())
            .param("content", proposed.content())
            .param("sourceType", proposed.sourceType().name())
            .param("sourceRef", proposed.sourceRef())
            .param("dataClass", proposed.dataClass().name())
            .param("capturedAt", Timestamp.from(proposed.capturedAt()))
            .update();
    Capture committed =
        findByNonce(proposed.principalId(), proposed.clientNonce())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Capture write completed without a committed database value"));
    return new SaveResult(committed, inserted == 1);
  }

  @Override
  public Optional<Capture> findOwned(String principalId, String captureId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM captures "
            + "WHERE principal_id = :principalId AND capture_id = :captureId")
        .param("principalId", principalId)
        .param("captureId", captureId)
        .query(PostgresCaptureStore::mapCapture)
        .optional();
  }

  private Optional<Capture> findByNonce(String principalId, String clientNonce) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM captures "
            + "WHERE principal_id = :principalId AND client_nonce = :clientNonce")
        .param("principalId", principalId)
        .param("clientNonce", clientNonce)
        .query(PostgresCaptureStore::mapCapture)
        .optional();
  }

  private static Capture mapCapture(java.sql.ResultSet resultSet, int rowNumber)
      throws java.sql.SQLException {
    return new Capture(
        resultSet.getString("capture_id"),
        resultSet.getString("principal_id"),
        resultSet.getString("client_nonce"),
        resultSet.getString("request_hash"),
        resultSet.getString("content"),
        CaptureSourceType.valueOf(resultSet.getString("source_type")),
        resultSet.getString("source_ref"),
        DataClass.valueOf(resultSet.getString("data_class")),
        resultSet.getTimestamp("captured_at").toInstant());
  }
}
