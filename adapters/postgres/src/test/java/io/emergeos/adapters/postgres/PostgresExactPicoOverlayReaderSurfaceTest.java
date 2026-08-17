package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.core.domain.GraphAttemptManifest;
import io.emergeos.core.domain.GraphExactPicoOverlayVerification;
import io.emergeos.core.port.GraphExactPicoOverlayReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class PostgresExactPicoOverlayReaderSurfaceTest {

  private static final Set<String> RELATIONS =
      Set.of(
          "agent_graph_attempts",
          "agent_graph_attempt_heads",
          "agent_graph_attempt_events",
          "agent_graph_attempt_provider_attributions",
          "agent_graph_provider_session_intents",
          "agent_graph_provider_validation_keys",
          "agent_graph_provider_validations",
          "agent_graph_provider_profiles_v14",
          "agent_graph_exact_tx_a_requirements_v15",
          "agent_graph_exact_provider_validations_v16",
          "agent_graph_exact_provider_attributions_v16",
          "agent_graph_exact_attempt_events_v16",
          "agent_graph_exact_attempt_heads_v16");

  @Test
  void exposesOnlyTheTypedReadCapability() throws Exception {
    Class<?> adapter = PostgresExactPicoOverlayReader.class;
    assertTrue(Modifier.isPublic(adapter.getModifiers()));
    assertTrue(Modifier.isFinal(adapter.getModifiers()));
    assertEquals(List.of(GraphExactPicoOverlayReader.class),
        List.of(adapter.getInterfaces()));

    List<Method> publicMethods =
        Arrays.stream(adapter.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .sorted(java.util.Comparator.comparing(Method::getName))
            .toList();
    assertEquals(
        List.of("findVerified", "open"),
        publicMethods.stream().map(Method::getName).toList());
    Method find = publicMethods.getFirst();
    assertFalse(Modifier.isStatic(find.getModifiers()));
    assertEquals(List.of(GraphAttemptManifest.class),
        List.of(find.getParameterTypes()));
    assertEquals(GraphExactPicoOverlayVerification.class,
        find.getReturnType());
    Method open = publicMethods.getLast();
    assertTrue(Modifier.isStatic(open.getModifiers()));
    assertEquals(List.of(DataSource.class), List.of(open.getParameterTypes()));
    assertEquals(GraphExactPicoOverlayReader.class, open.getReturnType());

    Constructor<?>[] constructors = adapter.getDeclaredConstructors();
    assertEquals(1, constructors.length);
    assertTrue(Modifier.isPrivate(constructors[0].getModifiers()));
    assertEquals(List.of(DataSource.class),
        List.of(constructors[0].getParameterTypes()));
    assertTrue(
        Arrays.stream(adapter.getDeclaredClasses())
            .noneMatch(type -> Modifier.isPublic(type.getModifiers())));
  }

  @Test
  void freezesOneReadOnlySnapshotAndThirteenDirectRelations()
      throws Exception {
    String source =
        Files.readString(
            Path.of(
                "src/main/java/io/emergeos/adapters/postgres/"
                    + "PostgresExactPicoOverlayReader.java"));
    assertTrue(source.contains("manager.setEnforceReadOnly(true)"));
    assertTrue(
        source.contains("TransactionDefinition.ISOLATION_REPEATABLE_READ"));
    assertTrue(
        source.contains("TransactionDefinition.PROPAGATION_REQUIRES_NEW"));
    assertTrue(source.contains("this.transactions.setReadOnly(true)"));
    assertTrue(source.contains("current_setting('transaction_read_only')"));
    assertFalse(source.contains("PostgresGraphAttemptAccess"));
    assertFalse(source.contains("openReader("));
    assertFalse(source.contains("agent_graph_stage_exact_tx_a_v16"));
    assertFalse(source.contains("agent_graph_commit_exact_tx_a_v16"));

    Set<String> declared =
        Arrays.stream(
                source.substring(
                        source.indexOf("private static final String[] RELATIONS"),
                        source.indexOf("private static final String RELATION_NAMES_SQL"))
                    .split("[^a-zA-Z0-9_]+"))
            .filter(token -> token.startsWith("agent_graph_"))
            .collect(Collectors.toUnmodifiableSet());
    assertEquals(RELATIONS, declared);
    for (String relation : RELATIONS) {
      assertTrue(source.contains("FROM public." + relation));
    }
  }
}
