 /*
  * © 2017 The original author or authors.
  */
package com.hpe.cloudfoundryjenkins;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.model.AbstractProject;
import hudson.model.Describable;
import hudson.model.ItemGroup;
import hudson.model.Queue;
import hudson.model.queue.Tasks;
import hudson.security.ACL;
import hudson.tasks.BuildStep;
import hudson.tasks.BuildStepDescriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.net.MalformedURLException;
import java.net.URL;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.QueryParameter;

/**
 * Extension point descriptor for cloudfoundry push.
 *
 * @author williamg
 * @author Steven Swor
 *
 * @param <T> the build step type
 */
public abstract class AbstractCloudFoundryPushDescriptor<T extends BuildStep & Describable<T>> extends BuildStepDescriptor<T> {

    /**
     * Default memory to allocate (512mb)
     */
    public static final int DEFAULT_MEMORY = 512;

    /**
     * Default number of instances to deploy (1).
     */
    public static final int DEFAULT_INSTANCES = 1;

    /**
     * Default application startup timeout (60 seconds).
     */
    public static final int DEFAULT_TIMEOUT = 60;

    /**
     * Default stack of the target.
     */
    public static final String DEFAULT_STACK = null; // null stack means it uses the default stack of the target

    /**
     * Creates a new descriptor.
     * @param clazz the specific class being described
     */
    protected AbstractCloudFoundryPushDescriptor(Class<? extends T> clazz) {
        super(clazz);
    }

    /**
     * Creates a new descriptor.
     */
    protected AbstractCloudFoundryPushDescriptor() {
    }

    @Override
    public boolean isApplicable(Class<? extends AbstractProject> jobType) {
        return true;
    }

    @Override
    public String getDisplayName() {
        return "Push to Cloud Foundry";
    }

    /**
     * This method is called to populate the credentials list on the Jenkins config page.
     * @param context the context
     * @param target the target
     * @return the credentials list box model
     */
    @SuppressWarnings(value = "unused")
    public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context, @QueryParameter(value = "target") final String target) {
        StandardListBoxModel result = new StandardListBoxModel();
        result.includeEmptyValue();
        result.includeMatchingAs(context instanceof Queue.Task ? Tasks.getAuthenticationOf((Queue.Task)context) : ACL.SYSTEM, context, StandardUsernamePasswordCredentials.class, URIRequirementBuilder.fromUri(target).build(), null);
        return result;
    }

    /**
     * This method is called when the "Test Connection" button is clicked on the Jenkins config page.
     * @param context the context
     * @param target the cloudfoundry target
     * @param credentialsId the ID of the jenkins credentials to use
     * @param organization the cloudfoundry organization
     * @param cloudSpace the cloudfoundry space
     * @param selfSigned {@code true} to ignore ssl validation errors
     * @return the validation result
     */
    @SuppressWarnings(value = "unused")
    public FormValidation doTestConnection(@AncestorInPath ItemGroup context, @QueryParameter(value = "target") final String target, @QueryParameter(value = "credentialsId") final String credentialsId, @QueryParameter(value = "organization") final String organization, @QueryParameter(value = "cloudSpace") final String cloudSpace, @QueryParameter(value = "selfSigned") final boolean selfSigned) {
        return CloudFoundryUtils.doTestConnection(context, target, credentialsId, organization, cloudSpace, selfSigned);
    }

    /**
     * Verifies the URL format of the cloudfoundry target
     * @param value the value to check
     * @return the validation result
     */
    @SuppressWarnings(value = {"unused", "ResultOfObjectAllocationIgnored"})
    public FormValidation doCheckTarget(@QueryParameter String value) {
        if (!value.isEmpty()) {
            try {
                if (value.startsWith("http://") || value.startsWith("https://")) {
                  new URL(value);
                } else {
                  new URL("https://" + value);
                }
            } catch (MalformedURLException e) {
                return FormValidation.error("Malformed URL");
            }
        }
        return FormValidation.validateRequired(value);
    }

    /**
     * Marks credentials as a required parameter
     * @param value the value
     * @return the validation
     */
    @SuppressWarnings(value = "unused")
    public FormValidation doCheckCredentialsId(@QueryParameter String value) {
        return FormValidation.validateRequired(value);
    }

    /**
     * Marks the organization as a required parameter
     * @param value the value
     * @return the validation
     */
    @SuppressWarnings(value = "unused")
    public FormValidation doCheckOrganization(@QueryParameter String value) {
        return FormValidation.validateRequired(value);
    }

    /**
     * Marks the cloud space as a required parameter
     * @param value the value
     * @return the validation
     */
    @SuppressWarnings(value = "unused")
    public FormValidation doCheckCloudSpace(@QueryParameter String value) {
        return FormValidation.validateRequired(value);
    }

    /**
     * Marks the plugin timeout as a required positive integer
     * @param value the value
     * @return the validation
     */
    @SuppressWarnings(value = "unused")
    public FormValidation doCheckPluginTimeout(@QueryParameter String value) {
        return FormValidation.validatePositiveInteger(value);
    }

    /**
     * Marks the memory allocation as a required positive integer
     * @param value the value
     * @return the validation
     */
    @SuppressWarnings(value = "unused")
    public FormValidation doCheckMemory(@QueryParameter String value) {
        return FormValidation.validatePositiveInteger(value);
    }

    /**
     * Marks the number of instances as a required positive integer
     * @param value the value
     * @return the validation
     */
    @SuppressWarnings(value = "unused")
    public FormValidation doCheckInstances(@QueryParameter String value) {
        return FormValidation.validatePositiveInteger(value);
    }

    /**
     * Marks the application timeout as a required positive integer
     * @param value the value
     * @return the validation
     */
    @SuppressWarnings(value = "unused")
    public FormValidation doCheckTimeout(@QueryParameter String value) {
        return FormValidation.validatePositiveInteger(value);
    }

    /**
     * Marks the app name as a required parameter
     * @param value the value
     * @return the validation
     */
    @SuppressWarnings(value = "unused")
    public FormValidation doCheckAppName(@QueryParameter String value) {
        return FormValidation.validateRequired(value);
    }

    /**
     * Marks the hostname as a required parameter
     * @param value the value
     * @return the validation
     */
    @SuppressWarnings(value = "unused")
    public FormValidation doCheckHostname(@QueryParameter String value) {
        return FormValidation.validateRequired(value);
    }

}
