/*
 * Copyright 2018 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */

package com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.distributed.kubernetes.v2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spinnaker.clouddriver.kubernetes.v1.deploy.KubernetesUtil;
import com.netflix.spinnaker.clouddriver.kubernetes.v1.deploy.description.servergroup.KubernetesImageDescription;
import com.netflix.spinnaker.halyard.config.model.v1.node.CustomSizing;
import com.netflix.spinnaker.halyard.config.model.v1.node.DeploymentConfiguration;
import com.netflix.spinnaker.halyard.config.model.v1.node.SidecarConfig;
import com.netflix.spinnaker.halyard.config.model.v1.providers.kubernetes.KubernetesAccount;
import com.netflix.spinnaker.halyard.core.error.v1.HalException;
import com.netflix.spinnaker.halyard.core.problem.v1.Problem;
import com.netflix.spinnaker.halyard.core.registry.v1.Versions;
import com.netflix.spinnaker.halyard.core.resource.v1.JinjaJarResource;
import com.netflix.spinnaker.halyard.core.resource.v1.TemplatedResource;
import com.netflix.spinnaker.halyard.deploy.deployment.v1.AccountDeploymentDetails;
import com.netflix.spinnaker.halyard.deploy.services.v1.ArtifactService;
import com.netflix.spinnaker.halyard.deploy.services.v1.GenerateService;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.SpinnakerArtifact;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.SpinnakerRuntimeSettings;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.profile.Profile;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.ConfigSource;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.HasServiceSettings;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.ServiceSettings;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.SpinnakerMonitoringDaemonService;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.distributed.DistributedService.DeployPriority;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.distributed.SidecarService;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.distributed.kubernetes.KubernetesSharedServiceSettings;
import com.netflix.spinnaker.halyard.deploy.spinnaker.v1.service.distributed.kubernetes.v2.KubernetesV2Utils.SecretMountPair;
import io.fabric8.utils.Strings;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

public interface KubernetesV2Service<T> extends HasServiceSettings<T> {
  String getServiceName();
  String getDockerRegistry(String deploymentName, SpinnakerArtifact artifact);
  String getSpinnakerStagingPath(String deploymentName);
  ArtifactService getArtifactService();
  ServiceSettings defaultServiceSettings(DeploymentConfiguration deploymentConfiguration);
  ObjectMapper getObjectMapper();
  SpinnakerMonitoringDaemonService getMonitoringDaemonService();
  DeployPriority getDeployPriority();

  default boolean runsOnJvm() {
    return true;
  }

  default int terminationGracePeriodSeconds() {
    return 60;
  }

  default String shutdownScriptFile() {
    return "/opt/spinnaker/scripts/shutdown.sh";
  }

  default boolean isEnabled(DeploymentConfiguration deploymentConfiguration) {
    return true;
  }

  default List<String> getReadinessExecCommand(ServiceSettings settings) {
    return Arrays.asList("wget", "--no-check-certificate", "--spider", "-q", settings.getScheme() + "://localhost:" + settings.getPort() + settings.getHealthEndpoint());
  }

  default boolean hasPreStopCommand() {
    return false;
  }

  default List<String> getPreStopCommand(ServiceSettings settings) {
    return hasPreStopCommand() ? Arrays.asList("bash", shutdownScriptFile()): Collections.EMPTY_LIST;
  }

  default String getRootHomeDirectory() {
    return "/root";
  }

  default String getHomeDirectory() {
    return "/home/spinnaker";
  }

  default String getNamespaceYaml(GenerateService.ResolvedConfiguration resolvedConfiguration) {
    ServiceSettings settings = resolvedConfiguration.getServiceSettings(getService());
    String name = getNamespace(settings);
    TemplatedResource namespace = new JinjaJarResource("/kubernetes/manifests/namespace.yml");
    namespace.addBinding("name", name);
    return namespace.toString();
  }

  default String getServiceYaml(GenerateService.ResolvedConfiguration resolvedConfiguration) {
    ServiceSettings settings = resolvedConfiguration.getServiceSettings(getService());
    String namespace = getNamespace(settings);
    TemplatedResource service = new JinjaJarResource("/kubernetes/manifests/service.yml");
    service.addBinding("name", getService().getCanonicalName());
    service.addBinding("namespace", namespace);
    service.addBinding("port", settings.getPort());

    return service.toString();
  }

  default String getVolumeYaml(ConfigSource configSource) {
    TemplatedResource volume;
    switch (configSource.getType()) {
      case secret:
        volume = new JinjaJarResource("/kubernetes/manifests/secretVolume.yml");
        break;
      case emptyDir:
        volume = new JinjaJarResource("/kubernetes/manifests/emptyDirVolume.yml");
        break;
      case configMap:
        volume = new JinjaJarResource("/kubernetes/manifests/configMapVolume.yml");
        break;
      default:
        throw new IllegalStateException("Unknown volume type: " + configSource.getType());
    }

    volume.addBinding("name", configSource.getId());
    return volume.toString();
  }

  default String getResourceYaml(AccountDeploymentDetails<KubernetesAccount> details,
      GenerateService.ResolvedConfiguration resolvedConfiguration) {
    ServiceSettings settings = resolvedConfiguration.getServiceSettings(getService());
    SpinnakerRuntimeSettings runtimeSettings = resolvedConfiguration.getRuntimeSettings();
    String namespace = getNamespace(settings);

    List<ConfigSource> configSources = stageConfig(details, resolvedConfiguration);

    List<SidecarConfig> sidecarConfigs = details.getDeploymentConfiguration()
        .getDeploymentEnvironment()
        .getSidecars()
        .getOrDefault(getService().getServiceName(), new ArrayList<>());

    List<String> initContainers = details.getDeploymentConfiguration()
        .getDeploymentEnvironment()
        .getInitContainers()
        .getOrDefault(getService().getServiceName(), new ArrayList<>())
        .stream()
        .map(o -> {
          try {
            return getObjectMapper().writeValueAsString(o);
          } catch (JsonProcessingException e) {
            throw new HalException(Problem.Severity.FATAL, "Invalid init container format: " + e.getMessage(), e);
          }
        }).collect(Collectors.toList());

    if (initContainers.isEmpty()) {
      initContainers = null;
    }

    List<String> hostAliases = details.getDeploymentConfiguration()
        .getDeploymentEnvironment()
        .getHostAliases()
        .getOrDefault(getService().getServiceName(), new ArrayList<>())
        .stream()
        .map(o -> {
          try {
            return getObjectMapper().writeValueAsString(o);
          } catch (JsonProcessingException e) {
            throw new HalException(Problem.Severity.FATAL, "Invalid host alias format: " + e.getMessage(), e);
          }
        }).collect(Collectors.toList());

    if (hostAliases.isEmpty()) {
      hostAliases = null;
    }

    configSources.addAll(sidecarConfigs.stream()
        .filter(c -> StringUtils.isNotEmpty(c.getMountPath()))
        .map(c -> new ConfigSource().setMountPath(c.getMountPath())
          .setId(c.getName())
          .setType(ConfigSource.Type.emptyDir)
        ).collect(Collectors.toList()));

    Map<String, String> env = configSources.stream()
        .map(ConfigSource::getEnv)
        .map(Map::entrySet)
        .flatMap(Collection::stream)
        .collect(Collectors.toMap(
            Entry::getKey,
            Entry::getValue
        ));

    List<String> volumes = configSources.stream()
        .collect(Collectors.toMap(ConfigSource::getId, (i) -> i, (a, b) -> a))
        .values()
        .stream()
        .map(this::getVolumeYaml)
        .collect(Collectors.toList());

    volumes.addAll(settings.getKubernetes().getVolumes().stream()
        .collect(Collectors.toMap(ConfigSource::getId, (i) -> i, (a, b) -> a))
        .values()
        .stream()
        .map(this::getVolumeYaml)
        .collect(Collectors.toList()));

    volumes.addAll(sidecarConfigs.stream()
        .map(SidecarConfig::getConfigMapVolumeMounts)
        .flatMap(Collection::stream)
        .map(c -> new ConfigSource()
            .setMountPath(c.getMountPath())
            .setId(c.getConfigMapName())
            .setType(ConfigSource.Type.configMap)
        )
        .map(this::getVolumeYaml)
        .collect(Collectors.toList()));

    env.putAll(settings.getEnv());

    Integer targetSize = settings.getTargetSize();
    CustomSizing customSizing = details.getDeploymentConfiguration().getDeploymentEnvironment().getCustomSizing();
    if (customSizing != null) {
      Map componentSizing = customSizing.getOrDefault(getService().getServiceName(), new HashMap());
      targetSize = (Integer) componentSizing.getOrDefault("replicas", targetSize);
    }

    String primaryContainer = buildContainer(getService().getCanonicalName(), details, settings, configSources, env);
    List<String> sidecarContainers = getSidecars(runtimeSettings).stream()
        .map(SidecarService::getService)
        .map(s -> buildContainer(s.getCanonicalName(), details, runtimeSettings.getServiceSettings(s), configSources, env))
        .collect(Collectors.toList());

    sidecarContainers.addAll(sidecarConfigs.stream()
        .map(this::buildCustomSidecar)
        .collect(Collectors.toList()));

    List<String> containers = new ArrayList<>();
    containers.add(primaryContainer);
    containers.addAll(sidecarContainers);

    TemplatedResource podSpec = new JinjaJarResource("/kubernetes/manifests/podSpec.yml")
        .addBinding("containers", containers)
        .addBinding("initContainers", initContainers)
        .addBinding("hostAliases", hostAliases)
        .addBinding("imagePullSecrets", settings.getKubernetes().getImagePullSecrets())
        .addBinding("terminationGracePeriodSeconds", terminationGracePeriodSeconds())
        .addBinding("volumes", volumes);

    String version = makeValidLabel(details.getDeploymentConfiguration().getVersion());
    if (version.isEmpty()) {
      version = "unknown";
    }

    return new JinjaJarResource("/kubernetes/manifests/deployment.yml")
        .addBinding("name", getService().getCanonicalName())
        .addBinding("namespace", namespace)
        .addBinding("replicas", targetSize)
        .addBinding("version", version)
        .addBinding("podAnnotations", settings.getKubernetes().getPodAnnotations())
        .addBinding("podSpec", podSpec.toString())
        .toString();
  }

  default boolean characterAlphanumeric(String value, int index) {
    return Character.isAlphabetic(value.charAt(index)) || Character.isDigit(value.charAt(index));
  }

  default String makeValidLabel(String value) {
    value = value.replaceAll("[^A-Za-z0-9-_.]", "");
    while (!value.isEmpty() && !characterAlphanumeric(value, 0)) {
      value = value.substring(1);
    }

    while (!value.isEmpty() && !characterAlphanumeric(value, value.length() - 1)) {
      value = value.substring(0, value.length() - 1);
    }

    return value;
  }

  default String buildCustomSidecar(SidecarConfig config) {
    List<String> volumeMounts = new ArrayList<>();
    if (StringUtils.isNotEmpty(config.getMountPath())) {
      TemplatedResource volume = new JinjaJarResource("/kubernetes/manifests/volumeMount.yml");
      volume.addBinding("name", config.getName());
      volume.addBinding("mountPath", config.getMountPath());
      volumeMounts.add(volume.toString());
    }

    volumeMounts.addAll(config.getConfigMapVolumeMounts()
        .stream()
        .map(c -> {
          TemplatedResource volume = new JinjaJarResource("/kubernetes/manifests/volumeMount.yml");
          volume.addBinding("name", c.getConfigMapName());
          volume.addBinding("mountPath", c.getMountPath());
          return volume.toString();
        })
        .collect(Collectors.toList()));

    TemplatedResource container = new JinjaJarResource("/kubernetes/manifests/container.yml");
    if (config.getSecurityContext() != null) {
      TemplatedResource securityContext = new JinjaJarResource("/kubernetes/manifests/securityContext.yml");
      securityContext.addBinding("privileged", config.getSecurityContext().isPrivileged());
      container.addBinding("securityContext", securityContext.toString());
    }

    container.addBinding("name", config.getName());
    container.addBinding("imageId", config.getDockerImage());
    container.addBinding("port", null);
    container.addBinding("command", config.getCommand());
    container.addBinding("args", config.getArgs());
    container.addBinding("volumeMounts", volumeMounts);
    container.addBinding("probe", null);
    container.addBinding("lifecycle", null);
    container.addBinding("env", config.getEnv());
    container.addBinding("resources", null);

    return container.toString();
  }

  default String buildContainer(String name, AccountDeploymentDetails<KubernetesAccount> details, ServiceSettings settings, List<ConfigSource> configSources, Map<String, String> env) {
    List<String> volumeMounts = configSources.stream()
        .map(c -> {
          TemplatedResource volume = new JinjaJarResource("/kubernetes/manifests/volumeMount.yml");
          volume.addBinding("name", c.getId());
          volume.addBinding("mountPath", c.getMountPath());
          return volume.toString();
        }).collect(Collectors.toList());

    volumeMounts.addAll(settings.getKubernetes().getVolumes().stream()
      .map(c -> {
        TemplatedResource volume = new JinjaJarResource("/kubernetes/manifests/volumeMount.yml");
        volume.addBinding("name", c.getId());
        volume.addBinding("mountPath", c.getMountPath());
        return volume.toString();
      }).collect(Collectors.toList()));

    TemplatedResource probe;
    if (StringUtils.isNotEmpty(settings.getHealthEndpoint())) {
      probe = new JinjaJarResource("/kubernetes/manifests/execReadinessProbe.yml");
      probe.addBinding("command", getReadinessExecCommand(settings));
    } else {
      probe = new JinjaJarResource("/kubernetes/manifests/tcpSocketReadinessProbe.yml");
      probe.addBinding("port", settings.getPort());
    }

    String lifecycle = "{}";
    List<String> preStopCommand = getPreStopCommand(settings);
    if (!preStopCommand.isEmpty()) {
      TemplatedResource lifecycleResource = new JinjaJarResource("/kubernetes/manifests/lifecycle.yml");
      lifecycleResource.addBinding("command", getPreStopCommand(settings));
      lifecycle = lifecycleResource.toString();
    }

    CustomSizing customSizing = details.getDeploymentConfiguration().getDeploymentEnvironment().getCustomSizing();
    TemplatedResource resources = new JinjaJarResource("/kubernetes/manifests/resources.yml");
    if (customSizing != null) {
      // Look for container specific sizing otherwise fall back to service sizing
      Map componentSizing = customSizing.getOrDefault(name,
          customSizing.getOrDefault(getService().getServiceName(), new HashMap()));
      resources.addBinding("requests", componentSizing.getOrDefault("requests", new HashMap()));
      resources.addBinding("limits", componentSizing.getOrDefault("limits", new HashMap()));
    }

    TemplatedResource container = new JinjaJarResource("/kubernetes/manifests/container.yml");
    container.addBinding("name", name);
    container.addBinding("imageId", settings.getArtifactId());
    TemplatedResource port = new JinjaJarResource("/kubernetes/manifests/port.yml");
    port.addBinding("port", settings.getPort());
    container.addBinding("port", port.toString());
    container.addBinding("volumeMounts", volumeMounts);
    container.addBinding("probe", probe.toString());
    container.addBinding("lifecycle", lifecycle);
    container.addBinding("env", env);
    container.addBinding("resources", resources.toString());

    return container.toString();
  }

  default String getNamespace(ServiceSettings settings) {
    return settings.getLocation();
  }

  default List<ConfigSource> stageConfig(AccountDeploymentDetails<KubernetesAccount> details,
      GenerateService.ResolvedConfiguration resolvedConfiguration) {
    Map<String, Profile> profiles = resolvedConfiguration.getProfilesForService(getService().getType());
    String stagingPath = getSpinnakerStagingPath(details.getDeploymentName());
    SpinnakerRuntimeSettings runtimeSettings = resolvedConfiguration.getRuntimeSettings();

    Map<String, Set<Profile>> profilesByDirectory = new HashMap<>();
    List<String> requiredFiles = new ArrayList<>();
    List<ConfigSource> configSources = new ArrayList<>();
    String secretNamePrefix = getServiceName() + "-files";
    String namespace = getNamespace(resolvedConfiguration.getServiceSettings(getService()));
    KubernetesAccount account = details.getAccount();

    for (SidecarService sidecarService : getSidecars(runtimeSettings)) {
      for (Profile profile : sidecarService.getSidecarProfiles(resolvedConfiguration, getService())) {
        if (profile == null) {
          throw new HalException(Problem.Severity.FATAL, "Service " + sidecarService.getService().getCanonicalName() + " is required but was not supplied for deployment.");
        }

        profiles.put(profile.getName(), profile);
        requiredFiles.addAll(profile.getRequiredFiles());
      }
    }

    for (Entry<String, Profile> entry : profiles.entrySet()) {
      Profile profile = entry.getValue();
      String outputFile = profile.getOutputFile();
      String mountPoint = Paths.get(outputFile).getParent().toString();

      Set<Profile> profilesInDirectory = profilesByDirectory.getOrDefault(mountPoint, new HashSet<>());
      profilesInDirectory.add(profile);

      requiredFiles.addAll(profile.getRequiredFiles());
      profilesByDirectory.put(mountPoint, profilesInDirectory);
    }


    for (Entry<String, Set<Profile>> entry: profilesByDirectory.entrySet()) {
      Set<Profile> profilesInDirectory = entry.getValue();
      String mountPath = entry.getKey();
      List<SecretMountPair> files = profilesInDirectory.stream()
          .map(p -> {
            File input = new File(p.getStagedFile(stagingPath));
            File output = new File(p.getOutputFile());
            return new SecretMountPair(input, output);
          })
          .collect(Collectors.toList());

      Map<String, String> env = profilesInDirectory.stream()
          .map(Profile::getEnv)
          .map(Map::entrySet)
          .flatMap(Collection::stream)
          .collect(Collectors.toMap(
              Entry::getKey,
              Entry::getValue
          ));

      String name = KubernetesV2Utils.createSecret(account, namespace, getService().getCanonicalName(), secretNamePrefix, files);
      configSources.add(new ConfigSource()
          .setId(name)
          .setMountPath(mountPath)
          .setEnv(env)
      );
    }

    if (!requiredFiles.isEmpty()) {
      List<SecretMountPair> files = requiredFiles.stream()
          .map(File::new)
          .map(SecretMountPair::new)
          .collect(Collectors.toList());

      String name = KubernetesV2Utils.createSecret(account, namespace, getService().getCanonicalName(), secretNamePrefix, files);
      configSources.add(new ConfigSource()
          .setId(name)
          .setMountPath(files.get(0).getContents().getParent())
      );
    }

    return configSources;
  }

  default List<SidecarService> getSidecars(SpinnakerRuntimeSettings runtimeSettings) {
    SpinnakerMonitoringDaemonService monitoringService = getMonitoringDaemonService();
    ServiceSettings monitoringSettings = runtimeSettings.getServiceSettings(monitoringService);
    ServiceSettings thisSettings = runtimeSettings.getServiceSettings(getService());

    List<SidecarService> result = new ArrayList<>();
    if (monitoringSettings.getEnabled() && thisSettings.getMonitored()) {
      result.add(monitoringService);
    }

    return result;
  }

  default String getArtifactId(String deploymentName) {
    String artifactName = getArtifact().getName();
    String version = getArtifactService().getArtifactVersion(deploymentName, getArtifact());
    version = Versions.isLocal(version) ? Versions.fromLocal(version) : version;

    KubernetesImageDescription image = KubernetesImageDescription.builder()
        .registry(getDockerRegistry(deploymentName, getArtifact()))
        .repository(artifactName)
        .tag(version)
        .build();
    return KubernetesUtil.getImageId(image);
  }

  default String buildAddress(String namespace) {
    return Strings.join(".", getServiceName(), namespace);
  }

  default ServiceSettings buildServiceSettings(DeploymentConfiguration deploymentConfiguration) {
    KubernetesSharedServiceSettings kubernetesSharedServiceSettings = new KubernetesSharedServiceSettings(deploymentConfiguration);
    ServiceSettings settings = defaultServiceSettings(deploymentConfiguration);
    String location = kubernetesSharedServiceSettings.getDeployLocation();
    settings.setAddress(buildAddress(location))
        .setArtifactId(getArtifactId(deploymentConfiguration.getName()))
        .setLocation(location)
        .setEnabled(isEnabled(deploymentConfiguration));
    if (runsOnJvm()) {
      // Use half the available memory allocated to the container for the JVM heap
      settings.getEnv().put("JAVA_OPTS", "-XX:+UnlockExperimentalVMOptions -XX:+UseCGroupMemoryLimitForHeap -XX:MaxRAMFraction=2");
    }
    return settings;
  }

  default String connectCommand(AccountDeploymentDetails<KubernetesAccount> details,
      SpinnakerRuntimeSettings runtimeSettings) {
    ServiceSettings settings = runtimeSettings.getServiceSettings(getService());
    KubernetesAccount account = details.getAccount();
    String namespace = settings.getLocation();
    String name = getServiceName();
    int port = settings.getPort();

    String podNameCommand = String.join(" ", KubernetesV2Utils.kubectlPodServiceCommand(account,
        namespace,
        name));

    return String.join(" ", KubernetesV2Utils.kubectlConnectPodCommand(account,
        namespace,
        "$(" + podNameCommand + ")",
        port));
  }
}
