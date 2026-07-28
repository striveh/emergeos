package io.emergeos.api;

import io.emergeos.adapters.inmemory.DefaultReflectionProposer;
import io.emergeos.adapters.inmemory.DeterministicActionPolicy;
import io.emergeos.adapters.inmemory.InMemoryJourneyStore;
import io.emergeos.adapters.inmemory.LocalDraftActionExecutor;
import io.emergeos.adapters.inmemory.LocalWorkingSelfProjector;
import io.emergeos.adapters.inmemory.TemplateArtifactGenerator;
import io.emergeos.adapters.inmemory.UuidIdGenerator;
import io.emergeos.adapters.postgres.PostgresActionAttemptStore;
import io.emergeos.adapters.postgres.PostgresArtifactLineageStore;
import io.emergeos.adapters.postgres.PostgresCaptureStore;
import io.emergeos.contracts.RiskLevel;
import io.emergeos.core.application.ArtifactLineageService;
import io.emergeos.core.application.CaptureService;
import io.emergeos.core.application.LocalActionAuthority;
import io.emergeos.core.application.ManifestationService;
import io.emergeos.core.application.RecoverableActionService;
import io.emergeos.core.port.IdGenerator;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
class ApiConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  IdGenerator idGenerator() {
    return new UuidIdGenerator();
  }

  @Bean
  PostgresCaptureStore captureStore(
      DataSource dataSource, PlatformTransactionManager transactionManager) {
    return new PostgresCaptureStore(dataSource, transactionManager);
  }

  @Bean
  CaptureService captureService(
      PostgresCaptureStore captureStore, IdGenerator idGenerator, Clock clock) {
    return new CaptureService(captureStore, idGenerator, clock);
  }

  @Bean
  PostgresArtifactLineageStore artifactLineageStore(
      DataSource dataSource, PlatformTransactionManager transactionManager) {
    return new PostgresArtifactLineageStore(dataSource, transactionManager);
  }

  @Bean
  ArtifactLineageService artifactLineageService(
      PostgresArtifactLineageStore artifactLineageStore,
      PostgresCaptureStore captureStore,
      IdGenerator idGenerator,
      Clock clock) {
    return new ArtifactLineageService(
        artifactLineageStore, captureStore, idGenerator, clock);
  }

  @Bean
  PostgresActionAttemptStore actionAttemptStore(
      DataSource dataSource, PlatformTransactionManager transactionManager) {
    return new PostgresActionAttemptStore(dataSource, transactionManager);
  }

  @Bean
  HttpSimulatedActionProvider simulatedActionProvider(
      @Value("${emerge.simulated-provider.base-url}") URI baseUrl,
      @Value("${emerge.simulated-provider.request-timeout}") Duration requestTimeout) {
    return new HttpSimulatedActionProvider(baseUrl, requestTimeout);
  }

  @Bean
  LocalActionAuthority localActionAuthority(
      @Value("${emerge.prototype.principal-id}") String principalId,
      @Value("${emerge.simulated-provider.connector}") String connector,
      @Value("${emerge.simulated-provider.audience}") String audience,
      @Value("${emerge.simulated-provider.account-ref}") String accountRef,
      @Value("${emerge.simulated-provider.capability-ttl}") Duration capabilityTtl,
      @Value("${emerge.simulated-provider.max-provider-calls}") int maxProviderCalls) {
    return new LocalActionAuthority(
        principalId,
        connector,
        audience,
        accountRef,
        "CREATE_LOCAL_DRAFT",
        "local://drafts",
        RiskLevel.REVERSIBLE,
        "local-action-v1",
        capabilityTtl,
        maxProviderCalls);
  }

  @Bean
  RecoverableActionService recoverableActionService(
      PostgresActionAttemptStore actionAttemptStore,
      PostgresArtifactLineageStore artifactLineageStore,
      HttpSimulatedActionProvider simulatedActionProvider,
      IdGenerator idGenerator,
      Clock clock,
      LocalActionAuthority localActionAuthority) {
    return new RecoverableActionService(
        actionAttemptStore,
        artifactLineageStore,
        simulatedActionProvider,
        idGenerator,
        clock,
        localActionAuthority);
  }

  @Bean
  InMemoryJourneyStore journeyStore() {
    return new InMemoryJourneyStore();
  }

  @Bean
  LocalDraftActionExecutor actionExecutor(IdGenerator idGenerator) {
    return new LocalDraftActionExecutor(idGenerator);
  }

  @Bean
  ManifestationService manifestationService(
      InMemoryJourneyStore store,
      LocalDraftActionExecutor actionExecutor,
      IdGenerator idGenerator,
      Clock clock) {
    return new ManifestationService(
        store,
        store,
        store,
        store,
        store,
        new LocalWorkingSelfProjector(),
        new TemplateArtifactGenerator(),
        new DeterministicActionPolicy(),
        actionExecutor,
        new DefaultReflectionProposer(),
        idGenerator,
        clock);
  }
}
