package io.emergeos.core.port;

import io.emergeos.core.domain.ProviderActionRequest;
import io.emergeos.core.domain.ProviderResult;

public interface ActionProvider {

  ProviderResult executeOrReconcile(ProviderActionRequest request);
}
