/**
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fabric8.kubernetes.client;

import io.fabric8.kubernetes.api.model.APIGroup;
import io.fabric8.kubernetes.api.model.APIGroupList;
import io.fabric8.kubernetes.api.model.APIResourceList;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.RootPaths;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.dsl.internal.HasMetadataOperationsImpl;
import io.fabric8.kubernetes.client.dsl.internal.OperationSupport;
import io.fabric8.kubernetes.client.extension.ExtensionAdapter;
import io.fabric8.kubernetes.client.extension.SupportTestingClient;
import io.fabric8.kubernetes.client.http.HttpClient;
import io.fabric8.kubernetes.client.utils.Utils;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.function.Predicate;

public abstract class BaseClient implements Client {

  public static final String APIS = "/apis";

  private URL masterUrl;
  private String apiVersion;
  private String namespace;
  private Predicate<String> matchingGroupPredicate;
  private final Adapters adapters;
  private final Handlers handlers;
  protected Config config;
  protected HttpClient httpClient;
  private OperationSupport operationSupport;

  BaseClient(Config config, BaseClient baseClient) {
    this.config = config;
    this.httpClient = baseClient.httpClient;
    this.adapters = baseClient.adapters;
    this.handlers = baseClient.handlers;
    this.matchingGroupPredicate = baseClient.matchingGroupPredicate;
    setDerivedFields();
  }

  BaseClient(final HttpClient httpClient, Config config) {
    this.config = config;
    this.httpClient = httpClient;
    this.handlers = new Handlers();
    this.adapters = new Adapters(this.handlers);
    setDerivedFields();
  }

  protected void setDerivedFields() {
    this.namespace = config.getNamespace();
    this.apiVersion = config.getApiVersion();
    if (config.getMasterUrl() == null) {
      throw new KubernetesClientException("Unknown Kubernetes master URL - " +
          "please set with the builder, or set with either system property \"" + Config.KUBERNETES_MASTER_SYSTEM_PROPERTY
          + "\"" +
          " or environment variable \"" + Utils.convertSystemPropertyNameToEnvVar(Config.KUBERNETES_MASTER_SYSTEM_PROPERTY)
          + "\"");
    }
    try {
      this.masterUrl = new URL(config.getMasterUrl());
    } catch (MalformedURLException e) {
      throw KubernetesClientException.launderThrowable(e);
    }
  }

  @Override
  public void close() {
    httpClient.close();
  }

  @Override
  public URL getMasterUrl() {
    return masterUrl;
  }

  @Override
  public String getApiVersion() {
    return apiVersion;
  }

  @Override
  public String getNamespace() {
    return namespace;
  }

  public void setMatchingGroupPredicate(Predicate<String> unsupportedApiGroups) {
    this.matchingGroupPredicate = unsupportedApiGroups;
  }

  @Override
  public boolean hasApiGroup(String apiGroup, boolean exact) {
    if (matchingGroupPredicate != null) {
      return matchingGroupPredicate.test(apiGroup);
    }
    if (exact) {
      return getApiGroup(apiGroup) != null;
    }
    APIGroupList apiGroups = getApiGroups();
    if (apiGroups == null) {
      return false;
    }
    return apiGroups
        .getGroups()
        .stream()
        .anyMatch(g -> g.getName().endsWith(apiGroup));
  }

  @Override
  public <C extends Client> Boolean isAdaptable(Class<C> type) {
    // if type is an instanceof SupportTestingClient, then it's a proper
    // test, otherwise it could be legacy support on an extension client
    C toTest = adapt(type);
    if (toTest instanceof SupportTestingClient) {
      return ((SupportTestingClient) toTest).isSupported();
    }
    return true;
  }

  @Override
  public <R extends KubernetesResource> boolean supports(Class<R> type) {
    String typeApiVersion = HasMetadata.getApiVersion(type);

    if (matchingGroupPredicate != null) {
      return matchingGroupPredicate.test(typeApiVersion);
    }

    String kind = HasMetadata.getKind(type);
    return handlers.getResourceDefinitionContext(typeApiVersion, kind, this) != null;
  }

  @Override
  public <C extends Client> C adapt(Class<C> type) {
    if (type.isAssignableFrom(this.getClass())) {
      return (C) this;
    }
    ExtensionAdapter<C> adapter = adapters.get(type);
    if (adapter == null) {
      throw new IllegalStateException("No adapter available for type:" + type);
    }
    return adapter.adapt(this);
  }

  @Override
  public RootPaths rootPaths() {
    return getOperationSupport().restCall(RootPaths.class);
  }

  @Override
  public boolean supportsApiPath(String apiPath) {
    RootPaths rootPaths = rootPaths();
    if (rootPaths != null) {
      List<String> paths = rootPaths.getPaths();
      if (paths != null) {
        for (String path : paths) {
          if (path.equals(apiPath)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  @Override
  public APIGroupList getApiGroups() {
    return getOperationSupport().restCall(APIGroupList.class, APIS);
  }

  @Override
  public APIGroup getApiGroup(String name) {
    return getOperationSupport().restCall(APIGroup.class, APIS, name);
  }

  private OperationSupport getOperationSupport() {
    if (operationSupport == null) {
      this.operationSupport = new OperationSupport(this);
    }
    return this.operationSupport;
  }

  @Override
  public APIResourceList getApiResources(String groupVersion) {
    return getOperationSupport().restCall(APIResourceList.class, APIS, groupVersion);
  }

  protected VersionInfo getVersionInfo(String path) {
    return getOperationSupport().restCall(VersionInfo.class, path);
  }

  @Override
  public <T extends HasMetadata, L extends KubernetesResourceList<T>, R extends Resource<T>> MixedOperation<T, L, R> resources(
      Class<T> resourceType, Class<L> listClass, Class<R> resourceClass) {
    if (GenericKubernetesResource.class.equals(resourceType)) {
      throw new KubernetesClientException("resources cannot be called with a generic type");
    }
    try {
      // TODO: check the Resource class type
      return handlers.getOperation(resourceType, listClass, this);
    } catch (Exception e) {
      //may be the wrong list type, try more general - may still fail if the resource is not properly annotated
      if (resourceClass == null || Resource.class.equals(resourceClass)) {
        return (MixedOperation<T, L, R>) newHasMetadataOperation(ResourceDefinitionContext.fromResourceType(resourceType),
            resourceType, listClass);
      }
      throw KubernetesClientException.launderThrowable(e);
    }
  }

  public <T extends HasMetadata, L extends KubernetesResourceList<T>> HasMetadataOperationsImpl<T, L> newHasMetadataOperation(
      ResourceDefinitionContext rdContext, Class<T> resourceType, Class<L> listClass) {
    return new HasMetadataOperationsImpl<>(this, rdContext, resourceType, listClass);
  }

  @Override
  public Config getConfiguration() {
    return config;
  }

  @Override
  public HttpClient getHttpClient() {
    return httpClient;
  }

  public Adapters getAdapters() {
    return adapters;
  }

  public Handlers getHandlers() {
    return handlers;
  }

}
