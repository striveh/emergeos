package io.emergeos.api;

import io.emergeos.adapters.inmemory.DefaultReflectionProposer;
import io.emergeos.adapters.inmemory.DeterministicActionPolicy;
import io.emergeos.adapters.inmemory.InMemoryJourneyStore;
import io.emergeos.adapters.inmemory.LocalDraftActionExecutor;
import io.emergeos.adapters.inmemory.LocalWorkingSelfProjector;
import io.emergeos.adapters.inmemory.TemplateArtifactGenerator;
import io.emergeos.adapters.inmemory.UuidIdGenerator;
import io.emergeos.core.application.ManifestationService;
import io.emergeos.core.port.IdGenerator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
