package io.emergeos.grapheval;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Test-only JDBC cutpoint immediately before a real transaction commit. */
final class Pack010CommitBeforeDelegateHardKillDataSource
    extends DelegatingDataSource {

  private final String receiptPrefix;
  private final AtomicReference<ArmedCutpoint> armed =
      new AtomicReference<>();

  Pack010CommitBeforeDelegateHardKillDataSource(
      javax.sql.DataSource targetDataSource) {
    this(targetDataSource, "PACK010_V12_PRECOMMIT_HARD_KILL");
  }

  Pack010CommitBeforeDelegateHardKillDataSource(
      javax.sql.DataSource targetDataSource, String receiptPrefix) {
    super(Objects.requireNonNull(targetDataSource, "targetDataSource"));
    if (receiptPrefix == null
        || !receiptPrefix.matches("[A-Z0-9_]{1,96}")) {
      throw new IllegalArgumentException("CUTPOINT_RECEIPT_INVALID");
    }
    this.receiptPrefix = receiptPrefix;
  }

  void arm(Path marker, String actor, int haltCode) {
    Objects.requireNonNull(marker, "marker");
    if (actor == null
        || !actor.matches("[a-z0-9-]{1,64}")
        || haltCode < 1
        || haltCode > 255
        || !armed.compareAndSet(
            null, new ArmedCutpoint(marker, actor, haltCode))) {
      throw new IllegalArgumentException("CUTPOINT_INVALID");
    }
  }

  @Override
  public Connection getConnection() throws SQLException {
    return intercept(super.getConnection());
  }

  @Override
  public Connection getConnection(String username, String password)
      throws SQLException {
    return intercept(super.getConnection(username, password));
  }

  private Connection intercept(Connection delegate) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              if (method.getName().equals("commit")
                  && method.getParameterCount() == 0) {
                ArmedCutpoint cutpoint = armed.getAndSet(null);
                if (cutpoint != null) {
                  if (delegate.getAutoCommit()) {
                    throw new SQLException("CUTPOINT_AUTOCOMMIT_DRIFT");
                  }
                  BackendReceipt backend;
                  try (var statement = delegate.createStatement();
                      var result =
                          statement.executeQuery(
                              "SELECT pg_backend_pid(), "
                                  + "pg_current_xact_id()::text")) {
                    if (!result.next()) {
                      throw new SQLException("CUTPOINT_BACKEND_ABSENT");
                    }
                    backend =
                        new BackendReceipt(
                            result.getLong(1), result.getString(2));
                  }
                  String receipt =
                      receiptPrefix
                          + " actor=" + cutpoint.actor()
                          + " pid=" + ProcessHandle.current().pid()
                          + " backendPid=" + backend.pid()
                          + " transactionId=" + backend.transactionId();
                  Pack009ProcessSupport.writeDurableCreateNew(
                      cutpoint.marker(), receipt);
                  System.out.println(receipt);
                  System.out.flush();
                  Runtime.getRuntime().halt(cutpoint.haltCode());
                  throw new AssertionError("Runtime.halt returned");
                }
              }
              try {
                return method.invoke(delegate, args);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  private record ArmedCutpoint(Path marker, String actor, int haltCode) {}

  private record BackendReceipt(long pid, String transactionId) {}
}
