/**
 * © Copyright 2015 Hewlett Packard Enterprise Development LP
 * Copyright (c) ActiveState 2014 - ALL RIGHTS RESERVED.
 * © 2017 The original author or authors.
 */
package com.hpe.cloudfoundryjenkins;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import org.kohsuke.stapler.DataBoundConstructor;
import java.util.ArrayList;
import java.util.List;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Post-build action for pushing to CloudFoundry.
 *
 * @author williamg
 * @author Steven Swor
 */
public class CloudFoundryPushPublisher extends Recorder {

  /**
   * The cloudfoundry api target.
   */
  public String target;

  /**
   * The cloudfoundry organization.
   */
  public String organization;

  /**
   * The cloudfoundry space.
   */
  public String cloudSpace;

  /**
   * The jenkins credentials id for cloudfoundry.
   */
  public String credentialsId;

  /**
   * Whether to ignore ssl validation errors.
   */
  public boolean selfSigned;

  /**
   * Whether to reset the app if it already existed.
   * <i>Note:</i> Since version 2.0 of the plugin, this setting has no effect.
   */
  public boolean resetIfExists;

  /**
   * Timeout for all cloudfoundry api calls.
   */
  public int pluginTimeout;

  /**
   * Services to create before pushing.
   */
  public List<Service> servicesToCreate;

  /**
   * Manifest to use.
   */
  public ManifestChoice manifestChoice;

  /**
   * The constructor is databound from the Jenkins config page, which is defined
   * in config.jelly.
   *
   * @param target the cloudfoundry api target
   * @param organization the cloudfoundry organization
   * @param cloudSpace the cloudfoundry space
   * @param credentialsId the credentials to use
   * @param selfSigned {@code true} to ignore ssl validation errors
   * @param resetIfExists {@code true} to reset the app if it already exists
   * (<i>Note:</i> since version 2.0 of the plugin, this setting has no effect).
   * @param pluginTimeout the timeout for cloudfoundry api calls
   * @param servicesToCreate services to create before pushing
   * @param manifestChoice the manifest to use
   */
  @DataBoundConstructor
  public CloudFoundryPushPublisher(String target, String organization, String cloudSpace,
          String credentialsId, boolean selfSigned,
          boolean resetIfExists, int pluginTimeout, List<Service> servicesToCreate,
          ManifestChoice manifestChoice) {
    this.target = target;
    this.organization = organization;
    this.cloudSpace = cloudSpace;
    this.credentialsId = credentialsId;
    this.selfSigned = selfSigned;
    this.resetIfExists = resetIfExists;
    if (pluginTimeout == 0) {
      this.pluginTimeout = CloudFoundryUtils.DEFAULT_PLUGIN_TIMEOUT;
    } else {
      this.pluginTimeout = pluginTimeout;
    }
    if (servicesToCreate == null) {
      this.servicesToCreate = new ArrayList<>();
    } else {
      this.servicesToCreate = servicesToCreate;
    }
    if (manifestChoice == null) {
      this.manifestChoice = ManifestChoice.defaultManifestFileConfig();
    } else {
      this.manifestChoice = manifestChoice;
    }
  }

  /**
   * This is the main method, which gets called when the plugin must run as part
   * of a build.
   *
   * @param build {@inheritDoc}
   * @param launcher {@inheritDoc}
   * @param listener {@inheritDoc}
   * @return {@inheritDoc}
   */
  @Override
  public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
    // We don't want to push if the build failed
    Result result = build.getResult();
    if (result != null && result.isWorseThan(Result.SUCCESS)) {
      return true;
    }

    CloudFoundryPushTask task = new CloudFoundryPushTask(target, organization, cloudSpace, credentialsId, selfSigned, pluginTimeout, servicesToCreate, manifestChoice);
    return task.perform(build.getWorkspace(), build, launcher, listener);
  }

  /**
   * Gets the required monitor service (NONE).
   *
   * @return the required monitor service (NONE)
   */
  @Override
  public BuildStepMonitor getRequiredMonitorService() {
    return BuildStepMonitor.NONE;
  }

  /**
   * This class contains the choice of using either a manifest file or the
   * optional Jenkins configuration. It also contains all the variables of
   * either choice, which will be non-null only if their choice was selected. It
   * bothers me that a single class has these multiple uses, but everything is
   * contained in the radioBlock tags in config.jelly and must be databound to a
   * single class. It doesn't seem like there is an alternative.
   */
  public static class ManifestChoice {
    // This should only be either "manifestFile" or "jenkinsConfig"

    public String value = "manifestFile";

    // Variable of the choice "manifestFile". Will be null if 'value' is "jenkinsConfig".
    public String manifestFile = CloudFoundryUtils.DEFAULT_MANIFEST_PATH;

    // Variables of the choice "jenkinsConfig". Will all be null (or 0 or false) if 'value' is "manifestFile".
    public String appName;
    public String memory;
    public String hostname;
    public String instances;
    public String timeout;
    public String noRoute;
    public String appPath;
    public String buildpack;
    public String stack;
    public String command;
    public String domain;
    public List<EnvironmentVariable> envVars = new ArrayList<>();
    public List<ServiceName> servicesNames = new ArrayList<>();

    public ManifestChoice(String value, String manifestFile,
            String appName, String memory, String hostname, String instances, String timeout, String noRoute,
            String appPath, String buildpack, String stack, String command, String domain,
            List<EnvironmentVariable> envVars, List<ServiceName> servicesNames) {
      if (value == null) {
        this.value = "manifestFile";
      } else {
        this.value = value;
      }
      if (manifestFile == null || manifestFile.isEmpty()) {
        this.manifestFile = CloudFoundryUtils.DEFAULT_MANIFEST_PATH;
      } else {
        this.manifestFile = manifestFile;
      }

      this.appName = appName;
      this.memory = memory;
      this.hostname = hostname;
      this.instances = instances;
      this.timeout = timeout;
      this.noRoute = noRoute;
      this.appPath = appPath;
      this.buildpack = buildpack;
      this.stack = stack;
      this.command = command;
      this.domain = domain;
      this.envVars = envVars;
      this.servicesNames = servicesNames;
    }

    @DataBoundConstructor
    public ManifestChoice() {
    }

    public String getValue() {
      return value;
    }

    @DataBoundSetter
    public void setValue(String value) {
      this.value = value;
    }

    public String getManifestFile() {
      return manifestFile;
    }

    @DataBoundSetter
    public void setManifestFile(String manifestFile) {
      this.manifestFile = manifestFile;
    }

    public String getAppName() {
      return appName;
    }

    @DataBoundSetter
    public void setAppName(String appName) {
      this.appName = appName;
    }

    public String getMemory() {
      return memory;
    }

    @DataBoundSetter
    public void setMemory(String memory) {
      this.memory = memory;
    }

    public String getHostname() {
      return hostname;
    }

    @DataBoundSetter
    public void setHostname(String hostname) {
      this.hostname = hostname;
    }

    public String getInstances() {
      return instances;
    }

    @DataBoundSetter
    public void setInstances(String instances) {
      this.instances = instances;
    }

    public String getTimeout() {
      return timeout;
    }

    @DataBoundSetter
    public void setTimeout(String timeout) {
      this.timeout = timeout;
    }

    public String isNoRoute() {
      return noRoute;
    }

    @DataBoundSetter
    public void setNoRoute(String noRoute) {
      this.noRoute = noRoute;
    }

    public String getAppPath() {
      return appPath;
    }

    @DataBoundSetter
    public void setAppPath(String appPath) {
      this.appPath = appPath;
    }

    public String getBuildpack() {
      return buildpack;
    }

    @DataBoundSetter
    public void setBuildpack(String buildpack) {
      this.buildpack = buildpack;
    }

    public String getStack() {
      return stack;
    }

    @DataBoundSetter
    public void setStack(String stack) {
      this.stack = stack;
    }

    public String getCommand() {
      return command;
    }

    @DataBoundSetter
    public void setCommand(String command) {
      this.command = command;
    }

    public String getDomain() {
      return domain;
    }

    @DataBoundSetter
    public void setDomain(String domain) {
      this.domain = domain;
    }

    public List<EnvironmentVariable> getEnvVars() {
      return envVars;
    }

    @DataBoundSetter
    public void setEnvVars(List<EnvironmentVariable> envVars) {
      this.envVars = envVars;
    }

    public List<ServiceName> getServicesNames() {
      return servicesNames;
    }

    @DataBoundSetter
    public void setServicesNames(List<ServiceName> servicesNames) {
      this.servicesNames = servicesNames;
    }

    /**
     * Constructs a ManifestChoice with the default settings for using a
     * manifest file. This is mostly for easier unit tests.
     *
     * @return the default manifest
     */
    public static ManifestChoice defaultManifestFileConfig() {
      return new ManifestChoice();
    }
  }

  public static class EnvironmentVariable {

    public final String key;
    public final String value;

    @DataBoundConstructor
    public EnvironmentVariable(String key, String value) {
      this.key = key;
      this.value = value;
    }
  }

  // This class is for services to bind to the app. We only get the name of the service.
  public static class ServiceName {

    public final String name;

    @DataBoundConstructor
    public ServiceName(String name) {
      this.name = name;
    }
  }

  // This class is for services to create. We need name, type and plan for this.
  public static class Service {

    public final String name;
    public final String type;
    public final String plan;
    public boolean resetService;

    @DataBoundConstructor
    public Service(String name, String type, String plan) {
      this.name = name;
      this.type = type;
      this.plan = plan;
    }

    public Service(String name, String type, String plan, boolean resetService) {
      this(name, type, plan);
      this.resetService = resetService;
    }

    @DataBoundSetter
    public void setResetService(boolean resetService) {
      this.resetService = resetService;
    }

    public boolean isResetService() {
      return resetService;
    }
  }

  @Extension
  public static final class DescriptorImpl extends AbstractCloudFoundryPushDescriptor<Publisher> {
  }

  /**
   * This method is called after a plugin upgrade, to convert an old
   * configuration into a new one. See:
   * https://wiki.jenkins-ci.org/display/JENKINS/Hint+on+retaining+backward+compatibility
   */
  @SuppressWarnings("unused")
  private Object readResolve() {
    if (servicesToCreate == null) { // Introduced in 1.4
      this.servicesToCreate = new ArrayList<>();
    }
    if (pluginTimeout == 0) { // Introduced in 1.5
      this.pluginTimeout = CloudFoundryUtils.DEFAULT_PLUGIN_TIMEOUT;
    }
    return this;
  }

  public boolean isResetIfExists() {
    return resetIfExists;
  }
}
