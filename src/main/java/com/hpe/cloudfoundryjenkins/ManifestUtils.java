/*
 * © 2018 The original author or authors.
 */
package com.hpe.cloudfoundryjenkins;

import com.hpe.cloudfoundryjenkins.CloudFoundryPushPublisher.EnvironmentVariable;
import com.hpe.cloudfoundryjenkins.CloudFoundryPushPublisher.ServiceName;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        return jenkinsConfig(filesPath, manifestChoice, isOnSlave, run, workspace, taskListener);
      default:
        throw new IllegalArgumentException("manifest choice must be either 'manifestFile' or 'jenkinsConfig', but was " + manifestChoice.value);
    }
  }

  private static List<ApplicationManifest> loadManifestFiles(FilePath filesPath, CloudFoundryPushPublisher.ManifestChoice manifestChoice, Run run, FilePath workspace, TaskListener taskListener) throws IOException, InterruptedException, MacroEvaluationException {
    List<String> manifestContents = IOUtils.readLines(new FilePath(filesPath, manifestChoice.getManifestFile()).read());
    StringBuilder sb = new StringBuilder();
    for (String line : manifestContents) {
      String tokenExpandedLine = TokenMacro.expandAll(run, workspace, taskListener, line);
      sb.append(tokenExpandedLine).append(System.lineSeparator());
    }
    FilePath actualSourceManifestFilePath = filesPath;
    if (manifestChoice.getManifestFile().contains(File.separator)) {
      int pos = manifestChoice.getManifestFile().lastIndexOf(File.separator);
      actualSourceManifestFilePath = new FilePath(actualSourceManifestFilePath, manifestChoice.getManifestFile().substring(0, pos));
    }
    FilePath tokenExpandedManifestFile = actualSourceManifestFilePath.createTextTempFile("cf-jenkins-plugin-generated-manifest", ".yml", sb.toString(), true);
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

  private static List<ApplicationManifest> jenkinsConfig(FilePath filesPath, CloudFoundryPushPublisher.ManifestChoice manifestChoice, boolean isOnSlave, final Run run, final FilePath workspace, final TaskListener taskListener) throws IOException, InterruptedException, MacroEvaluationException {
    ApplicationManifest.Builder manifestBuilder = ApplicationManifest.builder();
    manifestBuilder = !StringUtils.isBlank(manifestChoice.appName) ? manifestBuilder.name(TokenMacro.expandAll(run, workspace, taskListener, manifestChoice.appName)) : manifestBuilder;
    if (isOnSlave) {
      manifestBuilder = !StringUtils.isBlank(manifestChoice.appPath)
              ? manifestBuilder.path(Paths.get(Paths.get(filesPath.toURI()).toString()))
              : manifestBuilder.path(Paths.get(filesPath.toURI()));
    } else {
      manifestBuilder = !StringUtils.isBlank(manifestChoice.appPath)
              ? manifestBuilder.path(Paths.get(Paths.get(filesPath.toURI()).toString(), TokenMacro.expandAll(run, workspace, taskListener, manifestChoice.appPath)))
              : manifestBuilder.path(Paths.get(filesPath.toURI()));
    }
    manifestBuilder = !StringUtils.isBlank(manifestChoice.buildpack) ? manifestBuilder.buildpack(TokenMacro.expandAll(run, workspace, taskListener, manifestChoice.buildpack)) : manifestBuilder;
    manifestBuilder = !StringUtils.isBlank(manifestChoice.command) ? manifestBuilder.command(TokenMacro.expandAll(run, workspace, taskListener, manifestChoice.command)) : manifestBuilder;
    manifestBuilder = !StringUtils.isBlank(manifestChoice.domain) ? manifestBuilder.domain(TokenMacro.expandAll(run, workspace, taskListener, manifestChoice.domain)) : manifestBuilder;
    if (!CollectionUtils.isEmpty(manifestChoice.envVars)) {
      Map<String, Object> tokenMacroExpandedEnvVars = new HashMap<>(manifestChoice.envVars.size());
      for (EnvironmentVariable envVar : manifestChoice.getEnvVars()) {
        String key = TokenMacro.expandAll(run, workspace, taskListener, envVar.key);
        String value = TokenMacro.expandAll(run, workspace, taskListener, envVar.value);
        tokenMacroExpandedEnvVars.put(key, value);
      }
      manifestBuilder = manifestBuilder.environmentVariables(tokenMacroExpandedEnvVars);
    }
    manifestBuilder = !StringUtils.isBlank(manifestChoice.hostname) ? manifestBuilder.host(TokenMacro.expandAll(run, workspace, taskListener, manifestChoice.hostname)) : manifestBuilder;
    if (!StringUtils.isBlank(manifestChoice.instances)) {
      String instances = TokenMacro.expandAll(run, workspace, taskListener, manifestChoice.instances);
      manifestBuilder = manifestBuilder.instances(Integer.valueOf(instances));
    }
    if (!StringUtils.isBlank(manifestChoice.memory)) {
      String memory = TokenMacro.expandAll(run, workspace, taskListener, manifestChoice.memory);
      Integer memoryMb = asMemoryInteger(memory);
      manifestBuilder = manifestBuilder.memory(memoryMb);
    }
    if (!StringUtils.isBlank(manifestChoice.noRoute)) {
      String s = TokenMacro.expandAll(run, workspace, taskListener, manifestChoice.noRoute);
      Boolean noRoute = Boolean.parseBoolean(s);
      manifestBuilder = manifestBuilder.noRoute(noRoute);
    }
    if (!CollectionUtils.isEmpty(manifestChoice.servicesNames)) {
      List<String> servicesNames = new ArrayList<String>(manifestChoice.servicesNames.size());
      for (ServiceName serviceName : manifestChoice.servicesNames) {
        servicesNames.add(TokenMacro.expandAll(run, workspace, taskListener, serviceName.name));
      }
      manifestBuilder = manifestBuilder.services(servicesNames);
    }
    manifestBuilder = !StringUtils.isBlank(manifestChoice.stack) ? manifestBuilder.stack(TokenMacro.expandAll(run, workspace, taskListener, manifestChoice.stack)) : manifestBuilder;
    if (!StringUtils.isBlank(manifestChoice.timeout)) {
      String timeout = TokenMacro.expandAll(run, workspace, taskListener, manifestChoice.timeout);
      manifestBuilder = manifestBuilder.timeout(Integer.valueOf(timeout));
    }
    return Collections.singletonList(manifestBuilder.build());
  }

  private static final int GIBI = 1024;

  public static int asMemoryInteger(final String text) {
    if (text.toUpperCase().endsWith("G")) {
      return Integer.parseInt(text.substring(0, text.length() - 1)) * GIBI;
    } else if (text.toUpperCase().endsWith("GB")) {
      return Integer.parseInt(text.substring(0, text.length() - 2)) * GIBI;
    } else if (text.toUpperCase().endsWith("M")) {
      return Integer.parseInt(text.substring(0, text.length() - 1));
    } else if (text.toUpperCase().endsWith("MB")) {
      return Integer.parseInt(text.substring(0, text.length() - 2));
    } else {
      return Integer.parseInt(text); // assume MB.
    }
  }
}
