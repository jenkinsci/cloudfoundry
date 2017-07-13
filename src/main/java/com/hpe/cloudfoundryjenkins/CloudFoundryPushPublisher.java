/**
 * © Copyright 2015 Hewlett Packard Enterprise Development LP
 * Copyright (c) ActiveState 2014 - ALL RIGHTS RESERVED.
 */

package com.hpe.cloudfoundryjenkins;

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

import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.ClientV2Exception;
import org.cloudfoundry.client.v2.info.GetInfoRequest;
import org.cloudfoundry.doppler.DopplerClient;
import org.cloudfoundry.doppler.LogMessage;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.applications.LogsRequest;
import org.cloudfoundry.operations.applications.PushApplicationRequest;
import org.cloudfoundry.operations.applications.RestartApplicationRequest;
import org.cloudfoundry.operations.applications.ScaleApplicationRequest;
import org.cloudfoundry.operations.applications.SetEnvironmentVariableApplicationRequest;
import org.cloudfoundry.operations.applications.StartApplicationRequest;
import org.cloudfoundry.operations.routes.ListRoutesRequest;
import org.cloudfoundry.operations.routes.UnmapRouteRequest;
import org.cloudfoundry.operations.services.BindServiceInstanceRequest;
import org.cloudfoundry.operations.services.CreateServiceInstanceRequest;
import org.cloudfoundry.operations.services.DeleteServiceInstanceRequest;
import org.cloudfoundry.operations.services.ServiceInstanceSummary;
import org.cloudfoundry.reactor.ConnectionContext;
import org.cloudfoundry.reactor.DefaultConnectionContext;
import org.cloudfoundry.reactor.TokenProvider;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.cloudfoundry.reactor.doppler.ReactorDopplerClient;
import org.cloudfoundry.reactor.tokenprovider.PasswordGrantTokenProvider;
import org.cloudfoundry.reactor.uaa.ReactorUaaClient;
import org.cloudfoundry.uaa.UaaClient;

import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;

public class CloudFoundryPushPublisher extends Recorder {

    private static final String DEFAULT_MANIFEST_PATH = "manifest.yml";
    private static final int DEFAULT_PLUGIN_TIMEOUT = 120;

    public String target;
    public String organization;
    public String cloudSpace;
    public String credentialsId;
    public boolean selfSigned;
    public boolean resetIfExists;
    public int pluginTimeout;
    public List<Service> servicesToCreate;
    public ManifestChoice manifestChoice;

    private List<String> appURIs = new ArrayList<String>();

    /**
     * The constructor is databound from the Jenkins config page, which is defined in config.jelly.
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
            this.pluginTimeout = DEFAULT_PLUGIN_TIMEOUT;
        } else {
            this.pluginTimeout = pluginTimeout;
        }
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
            URL targetUrl = new URL("https://" + target);

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

            // TODO: move this into a CloudFoundryOperations factory method and
            // share it with doTestConnection.
            ConnectionContext connectionContext = DefaultConnectionContext.builder()
                .apiHost(target)
                .proxyConfiguration(buildProxyConfiguration(targetUrl))
                .skipSslValidation(selfSigned)
                .build();

            TokenProvider tokenProvider = PasswordGrantTokenProvider.builder()
                .username(credentials.getUsername())
                .password(Secret.toString(credentials.getPassword()))
                .build();

            CloudFoundryClient client = ReactorCloudFoundryClient.builder()
                .connectionContext(connectionContext)
                .tokenProvider(tokenProvider)
                .build();

            DopplerClient dopplerClient = ReactorDopplerClient.builder()
                .connectionContext(connectionContext)
                .tokenProvider(tokenProvider)
                .build();

            UaaClient uaaClient = ReactorUaaClient.builder()
                .connectionContext(connectionContext)
                .tokenProvider(tokenProvider)
                .build();

            CloudFoundryOperations cloudFoundryOperations = DefaultCloudFoundryOperations.builder()
                .cloudFoundryClient(client)
                .dopplerClient(dopplerClient)
                .uaaClient(uaaClient)
                .organization(organization)
                .space(cloudSpace)
                .build();

            String domain = cloudFoundryOperations.domains().list().blockFirst().getName();

            // Create services before push
            Flux<ServiceInstanceSummary> currentServicesList = cloudFoundryOperations.services().listInstances();
            List<String> currentServicesNames = currentServicesList.map(service -> service.getName()).collectList().block();

            for (Service service : servicesToCreate) {
                boolean createService = true;
                if (currentServicesNames.contains(service.name)) {
                    if (service.resetService) {
                        listener.getLogger().println("Service " + service.name + " already exists, resetting.");
                        cloudFoundryOperations.services().deleteInstance(DeleteServiceInstanceRequest.builder().name(service.name).build()).block();
                        listener.getLogger().println("Service deleted.");
                    } else {
                        createService = false;
                        listener.getLogger().println("Service " + service.name + " already exists, skipping creation.");
                    }
                }
                if (createService) {
                    listener.getLogger().println("Creating service " + service.name);
                    cloudFoundryOperations.services().createInstance(CreateServiceInstanceRequest.builder()
                        .serviceName(service.type)
                        .serviceInstanceName(service.name)
                        .planName(service.plan)
                        .build()).block();
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
                boolean lastSuccess = processOneApp(cloudFoundryOperations, deploymentInfo, build, listener);
                // If an app fails, the build status is failure, but we should still try pushing them
                success = success && lastSuccess;
            }
            return success;
        } catch (MalformedURLException e) {
            listener.getLogger().println("ERROR: The target URL is not valid: " + e.getMessage());
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

    private boolean processOneApp(CloudFoundryOperations cloudFoundryOperations, DeploymentInfo deploymentInfo, AbstractBuild build,
                                  BuildListener listener) throws IOException, InterruptedException  {
        String appName = deploymentInfo.getAppName();
        String appURI = null;

        listener.getLogger().println("Pushing " + appName + " app to " + target);

        // Create app if it doesn't already exist, or if resetIfExists parameter is true
        boolean createdNewApp = createApplicationIfNeeded(cloudFoundryOperations, listener, deploymentInfo, appName, build);

        // Unbind all routes if no-route parameter is set
        if (deploymentInfo.isNoRoute()) {
            cloudFoundryOperations.routes().unmap(UnmapRouteRequest.builder()
                .applicationName(appName)
                .build()).block();
        } else {
          appURI = cloudFoundryOperations.routes().list(ListRoutesRequest.builder().build())
              .filter(route -> route.getApplications().contains(appName))
              .map(route -> new StringBuilder("https://").append(route.getHost()).append(".").append(route.getDomain()).append(route.getPath()))
              .map(sb -> sb.toString())
              .blockFirst();
          if (appURI != null) {
            addToAppURIs(appURI);
          }
        }

        // Add environment variables
        if (!deploymentInfo.getEnvVars().isEmpty()) {
            Flux.fromStream(deploymentInfo.getEnvVars().entrySet().stream())
                .map(e -> SetEnvironmentVariableApplicationRequest.builder().name(appName).variableName(e.getKey()).variableValue(e.getValue()).build())
                .map(request -> cloudFoundryOperations.applications().setEnvironmentVariable(request))
                .blockLast();
        }

        // Change number of instances
        if (deploymentInfo.getInstances() > 1) {
          cloudFoundryOperations.applications().scale(ScaleApplicationRequest.builder().name(appName).instances(deploymentInfo.getInstances()).build()).block();
        }

        // Start or restart application
        if (createdNewApp) {
            listener.getLogger().println("Starting application.");
            cloudFoundryOperations.applications().start(StartApplicationRequest.builder().name(appName).build()).block();
        } else {
            listener.getLogger().println("Restarting application.");
            cloudFoundryOperations.applications().restart(RestartApplicationRequest.builder().name(appName).build()).block();
        }

        // Start printing the staging logs
        printStagingLogs(cloudFoundryOperations, listener, appName);

        // Keep checking to see if the app is running
        int running = 0;
        int totalInstances = 0;
        for (int tries = 0; tries < pluginTimeout; tries++) {
            running = 0;
            ApplicationDetail app = cloudFoundryOperations.applications().get(GetApplicationRequest.builder().name(appName).build()).block();
            totalInstances = app.getInstances();
            running = app.getRunningInstances();
            if (running == totalInstances && totalInstances > 0) {
                break;
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
            listener.getLogger().println(
                    "ERROR: The application failed to start after " + pluginTimeout + " seconds.");
            listener.getLogger().println("Cloud Foundry push failed.");
            return false;
        }
    }

    private boolean createApplicationIfNeeded(CloudFoundryOperations cloudFoundryOperations, BuildListener listener,
                                              DeploymentInfo deploymentInfo, String appName, AbstractBuild build) throws IOException, InterruptedException {

        // Check if app already exists
        boolean applicationExists = cloudFoundryOperations.applications().list()
            .any(app -> app.getName().equals(appName))
            .block();

        listener.getLogger().println(applicationExists ? "Updating existing app." : "Creating new app.");

        cloudFoundryOperations.applications().push(PushApplicationRequest.builder()
            .name(appName)
            .command(deploymentInfo.getCommand())
            .buildpack(deploymentInfo.getBuildpack())
            .stack(deploymentInfo.getStack())
            .timeout(deploymentInfo.getTimeout())
            .memory(deploymentInfo.getMemory())
            .noRoute(deploymentInfo.isNoRoute())
            .routePath(null) // TODO add routePath to DeploymentInfo
            .randomRoute(!deploymentInfo.isNoRoute()) // TODO add randomRoute to DeploymentInfo
            .path(Paths.get(new FilePath(build.getWorkspace(), deploymentInfo.getAppPath()).toURI()))
            .build()).block();

        if (deploymentInfo.getServicesNames() != null && !deploymentInfo.getServicesNames().isEmpty()) {
          listener.getLogger().println("Binding services to app.");
          Flux.fromStream(deploymentInfo.getServicesNames().stream())
              .map(serviceName -> BindServiceInstanceRequest.builder().applicationName(appName).serviceInstanceName(serviceName).build())
              .flatMap(request -> cloudFoundryOperations.services().bind(request))
              .blockLast();
        }
        return !applicationExists;
    }

    private void printStagingLogs(CloudFoundryOperations cloudFoundryOperations,
                                  final BuildListener listener, String appName) {
       cloudFoundryOperations.applications().logs(LogsRequest.builder().name(appName).recent(Boolean.TRUE).build())
                .filter(logMessage -> logMessage.getSourceType().startsWith("STG") || logMessage.getSourceType().startsWith("CELL"))
           .subscribeWith(new BaseSubscriber<LogMessage>(){
          @Override
          protected void hookOnNext(LogMessage applicationLog) {
            /*
             * We are only interested in the staging logs, and per
             * https://docs.cloudfoundry.org/devguide/deploy-apps/streaming-logs.html#stg
             * "After the droplet has been uploaded, STG messages end and CELL messages begin",
             * so once we see the first CELL message we know we're done with the STG ones.
             */
            if (applicationLog.getSourceType().startsWith("STG")) {
              listener.getLogger().println(applicationLog.getMessage());
            } else if (applicationLog.getSourceType().startsWith("CELL")) {
              onComplete();
            }
          }
        });
    }

    private static Optional<org.cloudfoundry.reactor.ProxyConfiguration> buildProxyConfiguration(URL targetURL) {
        ProxyConfiguration proxyConfig = Hudson.getInstance().proxy;
        if (proxyConfig == null) {
            return Optional.empty();
        }

        String host = targetURL.getHost();
        for (Pattern p : proxyConfig.getNoProxyHostPatterns()) {
            if (p.matcher(host).matches()) {
                return Optional.empty();
            }
        }

        return Optional.of(org.cloudfoundry.reactor.ProxyConfiguration.builder()
            .host(proxyConfig.name)
            .port(proxyConfig.port)
            .build());
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
        public final String stack;
        public final String command;
        public final String domain;
        public final List<EnvironmentVariable> envVars;
        public final List<ServiceName> servicesNames;


        @DataBoundConstructor
        public ManifestChoice(String value, String manifestFile,
                              String appName, int memory, String hostname, int instances, int timeout, boolean noRoute,
                              String appPath, String buildpack, String stack, String command, String domain,
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
            this.stack = stack;
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
                    null, 0, null, 0, 0, false, null, null, null, null, null, null, null);
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
        public static final String DEFAULT_STACK = null; // null stack means it uses the default stack of the target

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
                URL targetUrl = new URL("https://" + target);
                List<StandardUsernamePasswordCredentials> standardCredentials = CredentialsProvider.lookupCredentials(
                        StandardUsernamePasswordCredentials.class,
                        context,
                        ACL.SYSTEM,
                        URIRequirementBuilder.fromUri(target).build());

                StandardUsernamePasswordCredentials credentials =
                        CredentialsMatchers.firstOrNull(standardCredentials, CredentialsMatchers.withId(credentialsId));
                // TODO: move this into a CloudFoundryOperations factory method and
                // share it with perform.
                ConnectionContext connectionContext = DefaultConnectionContext.builder()
                    .apiHost(target)
                    .proxyConfiguration(buildProxyConfiguration(targetUrl))
                    .skipSslValidation(selfSigned)
                    .build();

                TokenProvider tokenProvider = PasswordGrantTokenProvider.builder()
                    .username(credentials.getUsername())
                    .password(Secret.toString(credentials.getPassword()))
                    .build();

                CloudFoundryClient client = ReactorCloudFoundryClient.builder()
                    .connectionContext(connectionContext)
                    .tokenProvider(tokenProvider)
                    .build();

                client.info().get(GetInfoRequest.builder().build()).block();
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
            } catch(RuntimeException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof ClientV2Exception) {
                  return FormValidation.error("Client error. Code=%i, Error Code=%s, Description=%s", ((ClientV2Exception)e).getCode(), ((ClientV2Exception)e).getErrorCode(), ((ClientV2Exception)e).getDescription());
                } else if (cause instanceof UnknownHostException) {
                  return FormValidation.error("Unknown host");
                } else if (cause instanceof SSLPeerUnverifiedException) {
                  return FormValidation.error("Target's certificate is not verified " +
                            "(Add it to Java's keystore, or check the \"Allow self-signed\" box)");
                } else {
                  return FormValidation.error(e, "Unknown error");
                }
            } catch (Exception e) {
                return FormValidation.error(e, "Unknown Exception");
            }
        }

        @SuppressWarnings("unused")
        public FormValidation doCheckTarget(@QueryParameter String value) {
            if (!value.isEmpty()) {
                try {
                    URL targetUrl = new URL("https://" + value);
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
        public FormValidation doCheckPluginTimeout(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
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
        if (pluginTimeout == 0) { // Introduced in 1.5
            this.pluginTimeout = DEFAULT_PLUGIN_TIMEOUT;
        }
        return this;
    }
}
