package io.emergeos.adapters.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.emergeos.core.application.GraphAttemptCoordinator;
import io.emergeos.core.domain.GraphOperatorApproval;
import io.emergeos.core.port.GraphAttemptStore;
import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

class OwnerTtyGraphAuthoritySurfaceTest {

  @Test
  void exposesOnlyRealTtyApprovalAndOpaqueSingleUsePermit()
      throws Exception {
    Class<?> authority =
        Class.forName(
            "io.emergeos.adapters.postgres.OwnerTtyGraphAuthority");
    assertTrue(Modifier.isPublic(authority.getModifiers()));
    assertTrue(Modifier.isFinal(authority.getModifiers()));
    for (Constructor<?> constructor :
        authority.getDeclaredConstructors()) {
      assertFalse(Modifier.isPublic(constructor.getModifiers()));
      assertFalse(Modifier.isProtected(constructor.getModifiers()));
    }

    List<Method> publicMethods =
        Arrays.stream(authority.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .toList();
    assertEquals(
        List.of(
            "adopt",
            "approve",
            "bindTerminal",
            "claimChildTerminal",
            "claimParentTerminal",
            "claimProviderSessionIntent",
            "claimTerminal",
            "consumeEgress",
            "consumeProviderSessionIntent",
            "coordinator",
            "requireProviderSessionFresh",
            "takeAuthorized"),
        publicMethods.stream().map(Method::getName).sorted().toList());
    Method approve =
        publicMethods.stream()
            .filter(method -> method.getName().equals("approve"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        List.of(OwnerTtyGraphAuthority.Pack010Revision.class),
        List.of(approve.getParameterTypes()));

    Class<?> permit = approve.getReturnType();
    assertEquals("MutationPermit", permit.getSimpleName());
    assertTrue(Modifier.isPublic(permit.getModifiers()));
    assertTrue(Modifier.isFinal(permit.getModifiers()));
    for (Constructor<?> constructor : permit.getDeclaredConstructors()) {
      assertTrue(Modifier.isPrivate(constructor.getModifiers()));
    }
    assertTrue(
        Arrays.stream(permit.getDeclaredFields())
            .noneMatch(field ->
                javax.sql.DataSource.class.isAssignableFrom(
                    field.getType())));
    assertTrue(
        Arrays.stream(permit.getDeclaredMethods())
            .noneMatch(method -> Modifier.isPublic(method.getModifiers())));

    Method adopt =
        publicMethods.stream()
            .filter(method -> method.getName().equals("adopt"))
            .findFirst()
            .orElseThrow();
    assertEquals(List.of(permit), List.of(adopt.getParameterTypes()));
    Class<?> handoff = adopt.getReturnType();
    assertEquals("ApprovedHandoff", handoff.getSimpleName());
    assertTrue(Modifier.isPublic(handoff.getModifiers()));
    assertTrue(Modifier.isFinal(handoff.getModifiers()));
    for (Constructor<?> constructor : handoff.getDeclaredConstructors()) {
      assertTrue(Modifier.isPrivate(constructor.getModifiers()));
    }
    assertTrue(
        Arrays.stream(handoff.getDeclaredMethods())
            .noneMatch(method -> Modifier.isPublic(method.getModifiers())));

    Method coordinator =
        publicMethods.stream()
            .filter(method -> method.getName().equals("coordinator"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        List.of(handoff), List.of(coordinator.getParameterTypes()));
    assertEquals(
        GraphAttemptCoordinator.class, coordinator.getReturnType());

    Method takeAuthorized =
        publicMethods.stream()
            .filter(method -> method.getName().equals("takeAuthorized"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        List.of(handoff, GraphAttemptCoordinator.class),
        List.of(takeAuthorized.getParameterTypes()));
    assertEquals(
        GraphAttemptCoordinator.Authorized.class,
        takeAuthorized.getReturnType());

    Method consumeEgress =
        publicMethods.stream()
            .filter(method -> method.getName().equals("consumeEgress"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        List.of(
            handoff,
            GraphAttemptCoordinator.class,
            GraphAttemptCoordinator.EgressAuthority.class),
        List.of(consumeEgress.getParameterTypes()));
    assertEquals(
        OwnerTtyGraphAuthority.Pack010Revision.class,
        consumeEgress.getReturnType());

    for (Class<?> terminalType :
        List.of(
            OwnerTtyGraphAuthority.ProviderSessionIntent.class,
            OwnerTtyGraphAuthority.TerminalCapability.class,
            OwnerTtyGraphAuthority.ChildTerminalClaim.class,
            OwnerTtyGraphAuthority.ParentTerminalClaim.class)) {
      assertTrue(Modifier.isPublic(terminalType.getModifiers()));
      assertTrue(Modifier.isFinal(terminalType.getModifiers()));
      for (Constructor<?> constructor :
          terminalType.getDeclaredConstructors()) {
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));
      }
      assertTrue(
          Arrays.stream(terminalType.getDeclaredMethods())
              .noneMatch(method ->
                  Modifier.isPublic(method.getModifiers())));
      assertTrue(
          Arrays.stream(terminalType.getDeclaredFields())
              .noneMatch(field ->
                  javax.sql.DataSource.class.isAssignableFrom(
                      field.getType())));
    }

    List<Class<?>> forbidden =
        List.of(
            javax.sql.DataSource.class,
            GraphAttemptStore.class,
            PostgresGraphAttemptStore.class,
            io.emergeos.core.domain.GraphAttemptCursor.class,
            GraphAttemptCoordinator.InteractiveConsole.class,
            Console.class,
            Clock.class,
            GraphOperatorApproval.class);
    for (Method method : authority.getDeclaredMethods()) {
      for (Class<?> forbiddenType : forbidden) {
        assertFalse(
            forbiddenType.isAssignableFrom(method.getReturnType()));
        assertTrue(
            Arrays.stream(method.getParameterTypes())
                .noneMatch(forbiddenType::isAssignableFrom));
      }
    }
  }

  @Test
  void samePackageCodeCannotConstructMutationPermit()
      throws Exception {
    Path directory = Files.createTempDirectory("permit-forge-negative-");
    Path source =
        directory.resolve(
            "io/emergeos/adapters/postgres/PermitForge.java");
    Files.createDirectories(source.getParent());
    Files.writeString(
        source,
        """
        package io.emergeos.adapters.postgres;
        final class PermitForge {
          Object forge() {
            return new OwnerTtyGraphAuthority.MutationPermit(
                null, null, null, null, null, null);
          }
        }
        """,
        StandardCharsets.UTF_8);
    ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
    int exit =
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                diagnostics,
                diagnostics,
                "--release",
                "21",
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                directory.toString(),
                source.toString());
    assertNotEquals(0, exit, diagnostics.toString(StandardCharsets.UTF_8));
  }

  @Test
  void authorityBytecodeOwnsConsoleButCannotConstructProviderEffects()
      throws Exception {
    String resource =
        "/"
            + OwnerTtyGraphAuthority.class.getName().replace('.', '/')
            + ".class";
    byte[] bytecode;
    try (InputStream input =
        OwnerTtyGraphAuthority.class.getResourceAsStream(resource)) {
      bytecode = input.readAllBytes();
    }
    String constantPool =
        new String(bytecode, StandardCharsets.ISO_8859_1);
    assertTrue(constantPool.contains("java/lang/System"));
    assertTrue(constantPool.contains("console"));
    for (String forbidden :
        List.of(
            "java/net/",
            "java/net/http/",
            "io/emergeos/adapters/openai/",
            "OpenAiResponsesModel",
            "ReviewedOpenAiClient",
            "getenv",
            "getProperty")) {
      assertFalse(constantPool.contains(forbidden), forbidden);
    }
  }

  @Test
  void coordinatorUsesTheAuthorityBoundStore() throws Exception {
    Path source =
        Path.of(System.getProperty("user.dir"))
            .resolve(
                "src/main/java/io/emergeos/adapters/postgres/"
                    + "OwnerTtyGraphAuthority.java");
    String java = Files.readString(source, StandardCharsets.UTF_8);
    assertTrue(
        java.contains(
            "GraphAttemptCoordinator.ownerAdoptionOnly(this.store)"),
        "Coordinator must not retain the unbound supplied Store");
  }
}
