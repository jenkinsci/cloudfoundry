f/**
 * Copyright (c) ActiveState 2014 - ALL RIGHTS RESERVED.
 */

package com.activestate.cloudfoundryjenkins;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.ProxyConfiguration;
import hudson.model.*;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import org.cloudfoundry.client.lib.*;
import org.cloudfoundry.client.lib.domain.*;
import org.cloudfoundry.client.lib.org.springframework.web.client.ResourceAccessException;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class CloudFoundryPushPublisher extends Recorder {

    private static final String DEFAULT_MANIFEST_PATH = "manifest.yml";
    private static final int TIMEOUT = 120;

    public String target;
    public String organization;
    public String cloudSpace;
    public String credentialsId;
    public boolean selfSigned;
    public String cutoverMethod;
    public List<Service> servicesToCreate;
    public ManifestChoice manifestChoice;

    private List<String> appURIs = new ArrayList<String>();

    /**
     * The constructor is databound from the Jenkins config page, which is defined in config.jelly.
     */
    @DataBoundConstructor
    public CloudFoundryPushPublisher(String target, String organization, String cloudSpace,
                                     String credentialsId, boolean selfSigned,
                                     String cutoverMethod, List<Service> servicesToCreate,
                                     ManifestChoice manifestChoice) {
        this.target = target;
        this.organization = organization;
        this.cloudSpace = cloudSpace;
        this.credentialsId = credentialsId;
        this.selfSigned = selfSigned;
        this.cutoverMethod = cutoverMethod;
        if (servicesToCreate == null) {
            this.servicesToCreate = new ArrayList<Service>();
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
     * This is the main method, which gets called when the plugin must run as part of a build.
     */
    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
        // We don't want to push if the build failed
        if (build.getResult().isWorseThan(Result.SUCCESS))
            return true;

        listener.getLogger().println("Cloud Foundry Plugin:");

        try {
            String jenkinsBuildName = build.getProject().getDisplayName();
            URL targetUrl = new URL(target);

            List<StandardUsernamePasswordCredentials> standardCredentials = CredentialsProvider.lookupCredentials(
                    StandardUsernamePasswordCredentials.class,
                    build.getProject(),
                    ACL.SYSTEM,
                    URIRequirementBuilder.fromUri(target).build());

            StandardUsernamePasswordCredentials credentials =
                    CredentialsMatchers.firstOrNull(standardCredentials, CredentialsMatchers.withId(credentialsId));

            if (credentials == null) {
                listener.getLogger().println("ERROR: No credentials have been given.");
                return false;
            }

            CloudCredentials cloudCredentials =
                    new CloudCredentials(credentials.getUsername(), Secret.toString(credentials.getPassword()));
            HttpProxyConfiguration proxyConfig = buildProxyConfiguration(targetUrl);

            CloudFoundryClient client = new CloudFoundryClient(cloudCredentials, targetUrl, organization, cloudSpace,
                    proxyConfig, selfSigned);
            client.login();

            String domain = client.getDefaultDomain().getName();


            // Create services before push
            List<CloudService> currentServicesList = client.getServices();
            List<String> currentServicesNames = new ArrayList<String>();
            for (CloudService currentService : currentServicesList) {
                currentServicesNames.add(currentService.getName());
            }

            for (Service service : servicesToCreate) {
                boolean createService = true;
                if (currentServicesNames.contains(service.name)) {
                    if (service.resetService) {
                        listener.getLogger().println("Service " + service.name + " already exists, resetting.");
                        client.deleteService(service.name);
                        listener.getLogger().println("Service deleted.");
                    } else {
                        createService = false;
                        listener.getLogger().println("Service " + service.name + " already exists, skipping creation.");
                    }
                }
                if (createService) {
                    listener.getLogger().println("Creating service " + service.name);
                    CloudService cloudService = new CloudService();
                    cloudService.setName(service.name);
                    cloudService.setLabel(service.type);
                    cloudService.setPlan(service.plan);
                    client.createService(cloudService);
                }
            }

            // Get all deployment info
            List<DeploymentInfo> allDeploymentInfo = new ArrayList<DeploymentInfo>();
            if (manifestChoice.value.equals("manifestFile")) {
                // Read manifest file
                FilePath manifestFilePath = new FilePath(build.getWorkspace(), manifestChoice.manifestFile);
                ManifestReader manifestReader = new ManifestReader(manifestFilePath);
                List<Map<String, Object>> appList = manifestReader.getApplicationList();
                for (Map<String, Object> appInfo : appList) {
                    allDeploymentInfo.add(
                            new DeploymentInfo(build, listener, listener.getLogger(),
                                    appInfo, jenkinsBuildName, domain, manifestChoice.manifestFile));
                }
            } else {
                // Read Jenkins configuration
                allDeploymentInfo.add(
                        new DeploymentInfo(build, listener, listener.getLogger(),
                                manifestChoice, jenkinsBuildName, domain));
            }

            boolean success = true;
            for (DeploymentInfo deploymentInfo : allDeploymentInfo) {
                boolean lastSuccess = processOneApp(client, deploymentInfo, build, listener);
                // If an app fails, the build status is failure, but we should still try pushing them
                success = success && lastSuccess;
            }
            return success;
        } catch (MalformedURLException e) {
            listener.getLogger().println("ERROR: The target URL is not valid: " + e.getMessage());
            return false;
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof UnknownHostException) {
                listener.getLogger().println("ERROR: Unknown host: " + e.getMessage());
            } else if (e.getCause() instanceof SSLPeerUnverifiedException) {
                listener.getLogger().println("ERROR: Certificate is not verified: " + e.getMessage());
            } else {
                listener.getLogger().println("ERROR: Unknown ResourceAccessException: " + e.getMessage());
            }
            return false;
        } catch (CloudFoundryException e) {
            if (e.getMessage().equals("403 Access token denied.")) {
                listener.getLogger().println("ERROR: Wrong username or password: " + e.getMessage());
            } else {
                listener.getLogger().println("ERROR: Unknown CloudFoundryException: " + e.getMessage());
                listener.getLogger().println("ERROR: Cloud Foundry error code: " + e.getCloudFoundryErrorCode());
                if (e.getDescription() != null) {
                    listener.getLogger().println("ERROR: " + e.getDescription());
                }
                e.printStackTrace(listener.getLogger());
            }
            return false;
        } catch (CloudOperationException e) {
            listener.getLogger().println("ERROR: Target returned an error: " + e.getMessage());
            return false;
        } catch (ManifestParsingException e) {
            listener.getLogger().println("ERROR: Could not parse manifest: " + e.getMessage());
            return false;
        } catch (MacroEvaluationException e) {
            listener.getLogger().println("ERROR: Could not parse token macro: " + e.getMessage());
            return false;
        } catch (IOException e) {
            listener.getLogger().println("ERROR: IOException: " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            listener.getLogger().println("ERROR: InterruptedException: " + e.getMessage());
            return false;
        } catch (Exception e) {
            e.printStackTrace(listener.getLogger());
            return false;
        }
    }

    private boolean processOneApp(CloudFoundryClient client, DeploymentInfo deploymentInfo, AbstractBuild build,
                                  BuildListener listener) throws IOException, InterruptedException {
        try {
            String appName = deploymentInfo.getAppName();
            String appURI = "https://" + deploymentInfo.getHostname() + "." + deploymentInfo.getDomain();
            addToAppURIs(appURI);

            listener.getLogger().println("Pushing " + appName + " app to " + target);

            // Create app depending on cutoverMethod parameter
            boolean createdNewApp = createApplicationIfNeeded(client, listener, deploymentInfo, appURI);

            // Unbind all routes if no-route parameter is set
            if (deploymentInfo.isNoRoute()) {
                client.updateApplicationUris(appName, new ArrayList<String>());
            }

            // Add environment variables
            if (!deploymentInfo.getEnvVars().isEmpty()) {
                client.updateApplicationEnv(appName, deploymentInfo.getEnvVars());
            }

            // Change number of instances
            if (deploymentInfo.getInstances() > 1) {
                client.updateApplicationInstances(appName, deploymentInfo.getInstances());
            }

            // Push files
            listener.getLogger().println("Pushing app bits.");
            pushAppBits(build, listener, deploymentInfo, client);

            // Start or restart application
            StartingInfo startingInfo;
            if (createdNewApp) {
                listener.getLogger().println("Starting application.");
                startingInfo = client.startApplication(appName);
            } else {
                listener.getLogger().println("Restarting application.");
                startingInfo = client.restartApplication(appName);
            }

            // Start printing the staging logs
            printStagingLogs(client, listener, startingInfo, appName);

            CloudApplication app = client.getApplication(appName);

            // Keep checking to see if the app is running
            int running = 0;
            int totalInstances = 0;
            for (int tries = 0; tries < TIMEOUT; tries++) {
                running = 0;
                InstancesInfo instancesInfo = client.getApplicationInstances(app);
                if (instancesInfo != null) {
                    List<InstanceInfo> listInstances = instancesInfo.getInstances();
                    totalInstances = listInstances.size();
                    for (InstanceInfo instance : listInstances) {
                        if (instance.getState() == InstanceState.RUNNING) {
                            running++;
                        }
                    }
                    if (running == totalInstances && totalInstances > 0) {
                        break;
                    }
                }
                Thread.sleep(1000);
            }

            String instanceGrammar = "instances";
            if (running == 1)
                instanceGrammar = "instance";
            listener.getLogger().println(running + " " + instanceGrammar + " running out of " + totalInstances);

            if (running > 0) {
                if (running != totalInstances) {
                    listener.getLogger().println("WARNING: Some instances of the application are not running.");
                }
                if (deploymentInfo.isNoRoute()) {
                    listener.getLogger().println("Application is now running. (No route)");
                } else {
                    listener.getLogger().println("Application is now running at " + appURI);
                }
                listener.getLogger().println("Cloud Foundry push successful.");
                return true;
            } else {
                listener.getLogger().println("ERROR: The application failed to start after " + TIMEOUT + " seconds.");
                listener.getLogger().println("Cloud Foundry push failed.");
                return false;
            }
        } catch (CloudFoundryException e) {
            listener.getLogger().println("ERROR: Unknown CloudFoundryException: " + e.getMessage());
            listener.getLogger().println("ERROR: Cloud Foundry error code: " + e.getCloudFoundryErrorCode());
            if (e.getDescription() != null) {
                listener.getLogger().println("ERROR: " + e.getDescription());
            }
            e.printStackTrace(listener.getLogger());
            return false;
        } catch (FileNotFoundException e) {
            listener.getLogger().println("ERROR: Could not find file: " + e.getMessage());
            return false;
        } catch (ZipException e) {
            listener.getLogger().println("ERROR: ZipException: " + e.getMessage());
            return false;
        }
    }

    private boolean createApplicationIfNeeded(CloudFoundryClient client, BuildListener listener,
                                              DeploymentInfo deploymentInfo, String appURI) {
        // Get app instance
        CloudApplication cloudApplication = client.getApplication(deploymentInfo.getAppName());
        String appTempName = deploymentInfo.getAppName();
        String appTempURI = appURI;

        if (cloudApplication != null) {
           switch (cutoverMethod.toLowerCase()) {
              case "redeploy":  
                 listener.getLogger().println("App already exists, redeploying.");
                 client.deleteApplication(deploymentInfo.getAppName());
                 listener.getLogger().println("App deleted.");
              break;
              case "reroute":  
                 // Alter app name and set temporary route to new app (blue/green)
                 listener.getLogger().println("App already exists, redeploying blue/green");
                 if (appName.endsWith("-green")) {
                    appTempName += "-blue";
                    appTempURI += "-blue";
                 }
                 else {
                    appTempName += "-green";
                    appTempURI += "-green";
                 }
              break;
              default: 
                 listener.getLogger().println("App already exists, skipping creation.");
                 return false;
              break;
           }
        }

        // Create new app
        Staging staging = new Staging(deploymentInfo.getCommand(), deploymentInfo.getBuildpack(),
              null, deploymentInfo.getTimeout());
        List<String> uris = new ArrayList<String>();
        // Pass an empty List as the uri list if no-route is set
        if (!deploymentInfo.isNoRoute()) {
           uris.add(appTempURI);
        }
        List<String> services = deploymentInfo.getServicesNames();
        client.createApplication(appTempName, staging, deploymentInfo.getMemory(), uris, services);
        listener.getLogger().println("App deployed");

        // Reroute new app and delete old one if blue/green rerouting selected
        if(!appURI.equals(appTempURI)) {
           int i=0;
           do {
              if(client.getApplication(appTempName).getState() == CloudApplication.AppState.STARTED) {
                 List<String> rerouteUris = new ArrayList<String>();
                 rerouteUris.add(appURI);
                 client.getApplication(appTempName).setUris(rerouteUris);
                 listener.getLogger().println("App rerouted");
                 client.deleteApplication(deploymentInfo.getAppName());
                 listener.getLogger().println("Old app deleted.");
                 break;
              }
              else {
                 Thread.sleep(1000);
                 listener.getLogger().println("App could not be rerouted");
              }
              i++;
           } while(i<10); 
	}
        return true;
    }

    private void pushAppBits(AbstractBuild build, BuildListener listener, DeploymentInfo deploymentInfo,
                             CloudFoundryClient client)
            throws IOException, InterruptedException, ZipException {
        FilePath appPath = new FilePath(build.getWorkspace(), deploymentInfo.getAppPath());

        if (appPath.getChannel() != Jenkins.MasterComputer.localChannel) {
            if (appPath.isDirectory()) {
                // The build is distributed, and a directory
                // We need to make a copy of the target directory on the master
                File tempAppFile = File.createTempFile("appFile", null); // This is on the master
                OutputStream outputStream = new FileOutputStream(tempAppFile);
                appPath.zip(outputStream);

                // We now have a zip file on the master, extract it into a directory
                ZipFile appZipFile = new ZipFile(tempAppFile);
                File tempOutputDirectory = new File(tempAppFile.getAbsolutePath().split("\\.")[0]);
                appZipFile.extractAll(tempOutputDirectory.getAbsolutePath());
                // appPath.zip() creates a top level directory that we want to remove
                File[] listFiles = tempOutputDirectory.listFiles();
                if (listFiles != null && listFiles.length == 1) {
                    tempOutputDirectory = listFiles[0];
                } else {
                    // This should never happen because appPath.zip() always makes a directory
                    throw new IllegalStateException("Unzipped output directory was empty.");
                }
                // We can now use tempOutputDirectory which is a copy of the target directory but on master
                client.uploadApplication(deploymentInfo.getAppName(), tempOutputDirectory);
                // Delete temporary files
                boolean deleted = tempAppFile.delete() && tempOutputDirectory.delete();
                if (!deleted) {
                    listener.getLogger().println("WARNING: Temporary files were not deleted successfully.");
                }

            } else {
                // If the target path is a single file, we can just use an InputStream
                // The CF client will make a temp file on the master from the InputStream
                client.uploadApplication(deploymentInfo.getAppName(), appPath.getName(), appPath.read());

            }
        } else {
            // If the build is not distributed, we can convert the FilePath to a File without problems
            File targetFile = new File(appPath.toURI());
            client.uploadApplication(deploymentInfo.getAppName(), targetFile);
        }
    }

    private void printStagingLogs(CloudFoundryClient client, BuildListener listener,
                                  StartingInfo startingInfo, String appName) {
        // First, try streamLogs()
        try {
            JenkinsApplicationLogListener logListener = new JenkinsApplicationLogListener(listener);
            client.streamLogs(appName, logListener);
        } catch (Exception e) {
            // In case of failure, try getStagingLogs()
            listener.getLogger().println("WARNING: Exception occurred trying to get staging logs via websocket. " +
                    "Switching to alternate method.");
            int offset = 0;
            String stagingLogs = client.getStagingLogs(startingInfo, offset);
            if (stagingLogs == null) {
                listener.getLogger().println("WARNING: Could not get staging logs with alternate method. " +
                        "Cannot display staging logs.");
            } else {
                while (stagingLogs != null) {
                    listener.getLogger().println(stagingLogs);
                    offset += stagingLogs.length();
                    stagingLogs = client.getStagingLogs(startingInfo, offset);
                }
            }
        }
    }

    private static HttpProxyConfiguration buildProxyConfiguration(URL targetURL) {
        ProxyConfiguration proxyConfig = Hudson.getInstance().proxy;
        if (proxyConfig == null) {
            return null;
        }

        String host = targetURL.getHost();
        for (Pattern p : proxyConfig.getNoProxyHostPatterns()) {
            if (p.matcher(host).matches()) {
                return null;
            }
        }

        return new HttpProxyConfiguration(proxyConfig.name, proxyConfig.port);
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    public List<String> getAppURIs() {
        return appURIs;
    }

    public void addToAppURIs(String appURI) {
        this.appURIs.add(appURI);
    }

    /**
     * This class contains the choice of using either a manifest file or the optional Jenkins configuration.
     * It also contains all the variables of either choice, which will be non-null only if their choice was selected.
     * It bothers me that a single class has these multiple uses, but everything is contained in the radioBlock tags
     * in config.jelly and must be databound to a single class. It doesn't seem like there is an alternative.
     */
    public static class ManifestChoice {
        // This should only be either "manifestFile" or "jenkinsConfig"
        public final String value;

        // Variable of the choice "manifestFile". Will be null if 'value' is "jenkinsConfig".
        public final String manifestFile;

        // Variables of the choice "jenkinsConfig". Will all be null (or 0 or false) if 'value' is "manifestFile".
        public final String appName;
        public final int memory;
        public final String hostname;
        public final int instances;
        public final int timeout;
        public final boolean noRoute;
        public final String appPath;
        public final String buildpack;
        public final String command;
        public final String domain;
        public final List<EnvironmentVariable> envVars;
        public final List<ServiceName> servicesNames;


        @DataBoundConstructor
        public ManifestChoice(String value, String manifestFile,
                              String appName, int memory, String hostname, int instances, int timeout,
                              boolean noRoute, String appPath, String buildpack, String command, String domain,
                              List<EnvironmentVariable> envVars, List<ServiceName> servicesNames) {
            if (value == null) {
                this.value = "manifestFile";
            } else {
                this.value = value;
            }
            if (manifestFile == null || manifestFile.isEmpty()) {
                this.manifestFile = DEFAULT_MANIFEST_PATH;
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
            this.command = command;
            this.domain = domain;
            this.envVars = envVars;
            this.servicesNames = servicesNames;
        }

        /**
         * Constructs a ManifestChoice with the default settings for using a manifest file.
         * This is mostly for easier unit tests.
         */
        public static ManifestChoice defaultManifestFileConfig() {
            return new ManifestChoice("manifestFile", DEFAULT_MANIFEST_PATH,
                    null, 0, null, 0, 0, false, null, null, null, null, null, null);
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
        public final boolean resetService;

        @DataBoundConstructor
        public Service(String name, String type, String plan, boolean resetService) {
            this.name = name;
            this.type = type;
            this.plan = plan;
            this.resetService = resetService;
        }
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        public static final int DEFAULT_MEMORY = 512;
        public static final int DEFAULT_INSTANCES = 1;
        public static final int DEFAULT_TIMEOUT = 60;

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
         */
        @SuppressWarnings("unused")
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context,
                                                     @QueryParameter("target") final String target) {
            StandardListBoxModel result = new StandardListBoxModel();
            result.withEmptySelection();
            result.withMatching(CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
                    CredentialsProvider.lookupCredentials(
                            StandardUsernameCredentials.class,
                            context,
                            ACL.SYSTEM,
                            URIRequirementBuilder.fromUri(target).build()
                    )
            );
            return result;
        }

        /**
         * This method is called when the "Test Connection" button is clicked on the Jenkins config page.
         */
        @SuppressWarnings("unused")
        public FormValidation doTestConnection(@AncestorInPath ItemGroup context,
                                               @QueryParameter("target") final String target,
                                               @QueryParameter("credentialsId") final String credentialsId,
                                               @QueryParameter("organization") final String organization,
                                               @QueryParameter("cloudSpace") final String cloudSpace,
                                               @QueryParameter("selfSigned") final boolean selfSigned) {

            try {
                URL targetUrl = new URL(target);
                List<StandardUsernamePasswordCredentials> standardCredentials = CredentialsProvider.lookupCredentials(
                        StandardUsernamePasswordCredentials.class,
                        context,
                        ACL.SYSTEM,
                        URIRequirementBuilder.fromUri(target).build());

                StandardUsernamePasswordCredentials credentials =
                        CredentialsMatchers.firstOrNull(standardCredentials, CredentialsMatchers.withId(credentialsId));

                CloudCredentials cloudCredentials =
                        new CloudCredentials(credentials.getUsername(), Secret.toString(credentials.getPassword()));
                HttpProxyConfiguration proxyConfig = buildProxyConfiguration(targetUrl);

                CloudFoundryClient client = new CloudFoundryClient(cloudCredentials, targetUrl, organization,
                        cloudSpace, proxyConfig, selfSigned);
                client.login();
                client.getCloudInfo();
                if (targetUrl.getHost().startsWith("api.")) {
                    return FormValidation.okWithMarkup("<b>Connection successful!</b>");
                } else {
                    return FormValidation.warning(
                            "Connection successful, but your target's hostname does not start with \"api.\".\n" +
                                    "Make sure it is the real API endpoint and not a redirection, " +
                                    "or it may cause some problems.");
                }
            } catch (MalformedURLException e) {
                return FormValidation.error("Malformed target URL");
            } catch (ResourceAccessException e) {
                if (e.getCause() instanceof UnknownHostException) {
                    return FormValidation.error("Unknown host");
                } else if (e.getCause() instanceof SSLPeerUnverifiedException) {
                    return FormValidation.error("Target's certificate is not verified " +
                            "(Add it to Java's keystore, or check the \"Allow self-signed\" box)");
                } else {
                    return FormValidation.error(e, "Unknown ResourceAccessException");
                }
            } catch (CloudFoundryException e) {
                if (e.getMessage().equals("404 Not Found")) {
                    return FormValidation.error("Could not find CF API info (Did you forget to add \"api.\"?)");
                } else if (e.getMessage().equals("403 Access token denied.")) {
                    return FormValidation.error("Wrong username or password");
                } else {
                    return FormValidation.error(e, "Unknown CloudFoundryException");
                }
            } catch (IllegalArgumentException e) {
                if (e.getMessage().contains("No matching organization and space found")) {
                    return FormValidation.error("Could not find Organization or Space");
                } else {
                    return FormValidation.error(e, "Unknown IllegalArgumentException");
                }
            } catch (Exception e) {
                return FormValidation.error(e, "Unknown Exception");
            }


        }

        @SuppressWarnings("unused")
        public FormValidation doCheckTarget(@QueryParameter String value) {
            if (!value.isEmpty()) {
                try {
                    URL targetUrl = new URL(value);
                } catch (MalformedURLException e) {
                    return FormValidation.error("Malformed URL");
                }
            }
            return FormValidation.validateRequired(value);
        }

        @SuppressWarnings("unused")
        public FormValidation doCheckCredentialsId(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

        @SuppressWarnings("unused")
        public FormValidation doCheckOrganization(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

        @SuppressWarnings("unused")
        public FormValidation doCheckCloudSpace(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

        @SuppressWarnings("unused")
        public FormValidation doCheckMemory(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }

        @SuppressWarnings("unused")
        public FormValidation doCheckInstances(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }

        @SuppressWarnings("unused")
        public FormValidation doCheckTimeout(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }

        @SuppressWarnings("unused")
        public FormValidation doCheckAppName(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

        @SuppressWarnings("unused")
        public FormValidation doCheckHostname(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }
    }

    /**
     * This method is called after a plugin upgrade, to convert an old configuration into a new one.
     * See: https://wiki.jenkins-ci.org/display/JENKINS/Hint+on+retaining+backward+compatibility
     */
    @SuppressWarnings("unused")
    private Object readResolve() {
        if (servicesToCreate == null) { // Introduced in 1.4
            this.servicesToCreate = new ArrayList<Service>();
        }
        return this;
    }
}
