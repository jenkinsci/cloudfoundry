/*
 * © 2018 The original author or authors.
 */
package com.hpe.cloudfoundryjenkins;

import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.cloudfoundry.operations.applications.ApplicationManifest;
import org.cloudfoundry.operations.applications.ApplicationManifestUtils;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;

/**
 * Utility methods for dealing with manifests.
 *
 * @author Steven Swor
 */
public class ManifestUtils {

  public static List<ApplicationManifest> loadManifests(FilePath filesPath, CloudFoundryPushPublisher.ManifestChoice manifestChoice, boolean isOnSlave, Run run, FilePath workspace, TaskListener taskListener) throws IOException, InterruptedException, MacroEvaluationException {
    switch (manifestChoice.value) {
      case "manifestFile":
        return loadManifestFiles(filesPath, manifestChoice, run, workspace, taskListener);
      case "jenkinsConfig":
        return jenkinsConfig(filesPath, manifestChoice, isOnSlave);
      default:
        throw new IllegalArgumentException("manifest choice must be either 'manifestFile' or 'jenkinsConfig', but was " + manifestChoice.value);
    }
  }

  private static List<ApplicationManifest> loadManifestFiles(FilePath filesPath, CloudFoundryPushPublisher.ManifestChoice manifestChoice, Run run, FilePath workspace, TaskListener taskListener) throws IOException, InterruptedException, MacroEvaluationException {
    List<String> manifestContents = IOUtils.readLines(filesPath.read());
    StringBuilder sb = new StringBuilder();
    for (String line : manifestContents) {
      String tokenExpandedLine = TokenMacro.expandAll(run, workspace, taskListener, line);
      sb.append(tokenExpandedLine).append(System.getProperty("line.separator"));
    }
    FilePath tokenExpandedManifestFile = filesPath.getParent().createTextTempFile("cf-jenkins-plugin-generated-manifest", ".yml", sb.toString(), true);
    try {
      return ApplicationManifestUtils.read(Paths.get(tokenExpandedManifestFile.toURI()))
              .stream()
              .map(manifest -> fixManifest(filesPath, manifest))
              .collect(Collectors.toList());
    } finally {
      tokenExpandedManifestFile.delete();
    }
  }

  /**
   * Workarounds for any manifest issues should be added here.
   *
   * @param build the build
   * @param manifest the manifest
   * @return either the original manifest or a fixed-up version of the manifest
   */
  private static ApplicationManifest fixManifest(final FilePath filesPath, final ApplicationManifest manifest) {
    if (manifest.getPath() == null && (manifest.getDocker() == null || StringUtils.isEmpty(manifest.getDocker().getImage()))) {
      try {
        return ApplicationManifest.builder().from(manifest).path(Paths.get(filesPath.toURI())).build();
      } catch (IOException | InterruptedException e) {
        throw new RuntimeException(e);
      }
    } else {
      return manifest;
    }
  }

  private static List<ApplicationManifest> jenkinsConfig(FilePath filesPath, CloudFoundryPushPublisher.ManifestChoice manifestChoice, boolean isOnSlave) throws IOException, InterruptedException {
    ApplicationManifest.Builder manifestBuilder = ApplicationManifest.builder();
    manifestBuilder = !StringUtils.isBlank(manifestChoice.appName) ? manifestBuilder.name(manifestChoice.appName) : manifestBuilder;
    if (isOnSlave) {
      manifestBuilder = !StringUtils.isBlank(manifestChoice.appPath)
              ? manifestBuilder.path(Paths.get(Paths.get(filesPath.toURI()).toString()))
              : manifestBuilder.path(Paths.get(filesPath.toURI()));
    } else {
      manifestBuilder = !StringUtils.isBlank(manifestChoice.appPath)
              ? manifestBuilder.path(Paths.get(Paths.get(filesPath.toURI()).toString(), manifestChoice.appPath))
              : manifestBuilder.path(Paths.get(filesPath.toURI()));
    }
    manifestBuilder = !StringUtils.isBlank(manifestChoice.buildpack) ? manifestBuilder.buildpack(manifestChoice.buildpack) : manifestBuilder;
    manifestBuilder = !StringUtils.isBlank(manifestChoice.command) ? manifestBuilder.command(manifestChoice.command) : manifestBuilder;
    manifestBuilder = !StringUtils.isBlank(manifestChoice.domain) ? manifestBuilder.domain(manifestChoice.domain) : manifestBuilder;
    manifestBuilder = !CollectionUtils.isEmpty(manifestChoice.envVars) ? manifestBuilder.environmentVariables(manifestChoice.envVars.stream().collect(Collectors.toMap(envVar -> envVar.key, envVar -> envVar.value))) : manifestBuilder;
    manifestBuilder = !StringUtils.isBlank(manifestChoice.hostname) ? manifestBuilder.host(manifestChoice.hostname) : manifestBuilder;
    manifestBuilder = manifestChoice.instances > 0 ? manifestBuilder.instances(manifestChoice.instances) : manifestBuilder;
    manifestBuilder = manifestChoice.memory > 0 ? manifestBuilder.memory(manifestChoice.memory) : manifestBuilder;
    manifestBuilder = manifestBuilder.noRoute(manifestChoice.noRoute);
    manifestBuilder = !CollectionUtils.isEmpty(manifestChoice.servicesNames) ? manifestBuilder.services(manifestChoice.servicesNames.stream().map(serviceName -> serviceName.name).collect(Collectors.toList())) : manifestBuilder;
    manifestBuilder = !StringUtils.isBlank(manifestChoice.stack) ? manifestBuilder.stack(manifestChoice.stack) : manifestBuilder;
    manifestBuilder = manifestChoice.timeout > 0 ? manifestBuilder.timeout(manifestChoice.timeout) : manifestBuilder;
    return Collections.singletonList(manifestBuilder.build());
  }
}
