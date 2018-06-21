 /*
  * © 2017 The original author or authors.
  */
package com.hpe.cloudfoundryjenkins;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Builder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Jenkins build step for CloudFoundry push.
 *
 * @author Steven Swor
 */
public class CloudFoundryPushBuilder extends Builder implements SimpleBuildStep {

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
    public boolean selfSigned = false;

    /**
     * Timeout for all cloudfoundry api calls.
     */
    public int pluginTimeout = CloudFoundryUtils.DEFAULT_PLUGIN_TIMEOUT;

    /**
     * Services to create before pushing.
     */
    public List<CloudFoundryPushPublisher.Service> servicesToCreate = new ArrayList<>();

    /**
     * Manifest to use.
     */
    public CloudFoundryPushPublisher.ManifestChoice manifestChoice = CloudFoundryPushPublisher.ManifestChoice.defaultManifestFileConfig();

    /**
     * Creates a new CloudFoundryPushBuilder.
     * @param target the cloudfoundry api target
     * @param organization the cloudfoundry organization
     * @param cloudSpace the cloudfoundry space
     * @param credentialsId the credentials to use
     */
    @DataBoundConstructor
    public CloudFoundryPushBuilder(String target, String organization, String cloudSpace,
                                   String credentialsId) {
        this.target = target;
        this.organization = organization;
        this.cloudSpace = cloudSpace;
        this.credentialsId = credentialsId;
    }

    /**
     * @return {@code true} if ssl validation errors should be ignored.
     */
    public boolean isSelfSigned() {
        return selfSigned;
    }

    /**
     * @param selfSigned {@code true} to ignore ssl validation errors
     */
    @DataBoundSetter
    public void setSelfSigned(boolean selfSigned) {
        this.selfSigned = selfSigned;
    }

    /**
     * @return the plugin timeout
     */
    public int getPluginTimeout() {
        return pluginTimeout;
    }

    /**
     * @param pluginTimeout the timeout for cloudfoundry api calls
     */
    @DataBoundSetter
    public void setPluginTimeout(int pluginTimeout) {
        if (pluginTimeout <= 0) {
            this.pluginTimeout = CloudFoundryUtils.DEFAULT_PLUGIN_TIMEOUT;
        } else {
            this.pluginTimeout = pluginTimeout;
        }
    }

    /**
     * @return the services to create before pushing
     */
    public List<CloudFoundryPushPublisher.Service> getServicesToCreate() {
        return servicesToCreate;
    }

    /**
     * @param servicesToCreate the services to create before pushing
     */
    @DataBoundSetter
    public void setServicesToCreate(List<CloudFoundryPushPublisher.Service> servicesToCreate) {
        if (servicesToCreate == null) {
            this.servicesToCreate = new ArrayList<>();
        } else {
            this.servicesToCreate = servicesToCreate;
        }
    }

    /**
     * @return the manifest to use
     */
    public CloudFoundryPushPublisher.ManifestChoice getManifestChoice() {
        return manifestChoice;
    }

    /**
     * @param manifestChoice the manifest to use
     */
    @DataBoundSetter
    public void setManifestChoice(CloudFoundryPushPublisher.ManifestChoice manifestChoice) {
        if (manifestChoice == null) {
            this.manifestChoice = CloudFoundryPushPublisher.ManifestChoice.defaultManifestFileConfig();
        } else {
            this.manifestChoice = manifestChoice;
        }
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        CloudFoundryPushTask task = new CloudFoundryPushTask(target, organization, cloudSpace, credentialsId, selfSigned, pluginTimeout, servicesToCreate, manifestChoice);
        return task.perform(build.getWorkspace(), build, launcher, listener);
    }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {
      CloudFoundryPushTask task = new CloudFoundryPushTask(target, organization, cloudSpace, credentialsId, selfSigned, pluginTimeout, servicesToCreate, manifestChoice);
      if (!task.perform(workspace, run, launcher, listener)) {
        throw new AbortException("CloudFoundry Push failed.");
      }
    }

    @Override
    public boolean prebuild(AbstractBuild<?, ?> build, BuildListener listener) {
      return true; // per SimpleBuildStep javadoc
    }

    @Override
    public Action getProjectAction(AbstractProject<?, ?> project) {
      return null; // per SimpleBuildStep javadoc
    }

    @Override
    public Collection<? extends Action> getProjectActions(AbstractProject<?, ?> project) {
      return Collections.emptySet(); // per SimpleBuildStep javadoc
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
      return BuildStepMonitor.NONE; // per SimpleBuildStep javadoc
    }

    /**
     * Step descriptor.
     */
    @Symbol({"pushToCloudFoundry", "cfPush"})
    @Extension
    public static final class DescriptorImpl extends AbstractCloudFoundryPushDescriptor<Builder> {
    }
}
