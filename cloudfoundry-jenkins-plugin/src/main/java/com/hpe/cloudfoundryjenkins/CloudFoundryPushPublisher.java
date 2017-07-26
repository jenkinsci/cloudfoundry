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

import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;

import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.ClientV2Exception;
import org.cloudfoundry.client.v2.info.GetInfoRequest;
import org.cloudfoundry.doppler.DopplerClient;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationManifest;
import org.cloudfoundry.operations.applications.ApplicationManifestUtils;
import org.cloudfoundry.operations.applications.LogsRequest;
import org.cloudfoundry.operations.applications.PushApplicationManifestRequest;
import org.cloudfoundry.operations.routes.ListRoutesRequest;
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
        Result result = build.getResult();
        if (result != null && result.isWorseThan(Result.SUCCESS))
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

            FilePath masterPath = pathOnMaster(build.getWorkspace());
            if (!masterPath.equals(build.getWorkspace())) {
              masterPath = transferArtifactsToMaster(masterPath, build.getWorkspace(), manifestChoice, listener);
            }

            List<ApplicationManifest> manifests = toManifests(masterPath, manifestChoice);
            for(final ApplicationManifest manifest : manifests) {
              cloudFoundryOperations.applications().pushManifest(PushApplicationManifestRequest.builder().manifest(manifest).build())
                  .timeout(Duration.ofSeconds(pluginTimeout))
                  .doOnError(e -> e.printStackTrace(listener.getLogger()))
                  .block();
              if (manifest.getNoRoute() == null || !manifest.getNoRoute().booleanValue()) {
                cloudFoundryOperations.routes().list(ListRoutesRequest.builder().build())
                  .timeout(Duration.ofSeconds(pluginTimeout))
                  .filter(route -> route.getApplications().contains(manifest.getName()))
                  .map(route -> new StringBuilder("https://").append(route.getHost()).append(".").append(route.getDomain()).append(route.getPath()))
                  .map(StringBuilder::toString)
                  .doOnNext(this::addToAppURIs)
                  .blockLast();
              }
              printStagingLogs(cloudFoundryOperations, listener, manifest.getName());
            }
            if (!masterPath.equals(build.getWorkspace())) {
              masterPath.deleteRecursive();
            }
            return true;
        } catch (MalformedURLException e) {
            listener.getLogger().println("ERROR: The target URL is not valid: " + e.getMessage());
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

    private static List<ApplicationManifest> toManifests(FilePath filesPath, ManifestChoice manifestChoice) throws IOException, InterruptedException {
      switch(manifestChoice.value) {
        case "manifestFile":
          return manifestFile(filesPath, manifestChoice);
        case "jenkinsConfig":
          return jenkinsConfig(filesPath, manifestChoice);
        default:
          throw new IllegalArgumentException("manifest choice must be either 'manifestFile' or 'jenkinsConfig', but was " + manifestChoice.value);
      }
    }

    private static List<ApplicationManifest> manifestFile(FilePath filesPath, ManifestChoice manifestChoice) throws IOException, InterruptedException {
      return ApplicationManifestUtils.read(Paths.get(new FilePath(filesPath, manifestChoice.manifestFile).toURI()))
          .stream()
          .map(manifest -> fixManifest(filesPath, manifest))
          .collect(Collectors.toList());
    }

    /**
     * Workarounds for any manifest issues should be added here.
     * @param build the build
     * @param manifest the manifest
     * @return either the original manifest or a fixed-up version of the manifest
     */
    private static ApplicationManifest fixManifest(final FilePath filesPath, final ApplicationManifest manifest) {
      if (manifest.getPath()==null && StringUtils.isEmpty(manifest.getDockerImage())) {
        try {
          return ApplicationManifest.builder().from(manifest).path(Paths.get(filesPath.toURI())).build();
        } catch(IOException | InterruptedException e) {
          throw new RuntimeException(e);
        }
      } else {
        return manifest;
      }
    }

    private static FilePath pathOnMaster(final FilePath path) throws IOException, InterruptedException {
      if (path.getChannel() != FilePath.localChannel) {
        // The build is distributed
        // We need to make a copy of the target file/directory on the master
        File tempFile = Files.createTempDirectory("appDir").toFile();
        tempFile.deleteOnExit();
        return new FilePath(tempFile);
      }else {
        return path;
      }
    }

    private FilePath transferArtifactsToMaster(FilePath masterPath, FilePath workspacePath, ManifestChoice manifestChoice, BuildListener listener) throws IOException, InterruptedException {
      FilePath results = masterPath;
      if (masterPath != workspacePath) {
        listener.getLogger().println("INFO: Looks like we are on a distributed system... Transferring build artifacts from the slave to the master.");
        // only transfer artifacts if we aren't on the master
        FilePath appPath = new FilePath(workspacePath, manifestChoice.appPath == null ? "" : manifestChoice.appPath);
        // The build is distributed, and a directory
        // We need to make a copy of the target directory on the master
        FilePath zipFilePath = new FilePath(masterPath, "appFile");
        try(OutputStream outputStream = new FileOutputStream(Paths.get(zipFilePath.toURI()).toFile())) {
          listener.getLogger().println(String.format("INFO: Transferring from %s to %s", appPath.getRemote(), masterPath.getRemote()));
          appPath.zip(outputStream);
        }
        zipFilePath.unzip(masterPath);
        try {
          zipFilePath.delete();
        } catch(IOException ex) {
          listener.getLogger().println("WARNING: temporary files were not deleted successfully.");
        }

        // appPath.zip() creates a top level directory that we want to remove
        File[] listFiles = new File(masterPath.toURI()).listFiles();
        if (listFiles != null && listFiles.length == 1) {
            results = new FilePath(listFiles[0]);
        } else {
            // This should never happen because appPath.zip() always makes a directory
            throw new IllegalStateException("Unzipped output directory was empty.");
        }
      }
      return results;
    }

    private static List<ApplicationManifest> jenkinsConfig(FilePath filesPath, ManifestChoice manifestChoice) throws IOException, InterruptedException {
      ApplicationManifest.Builder manifestBuilder = ApplicationManifest.builder();
      manifestBuilder = !StringUtils.isBlank(manifestChoice.appName) ? manifestBuilder.name(manifestChoice.appName) : manifestBuilder;
      manifestBuilder = !StringUtils.isBlank(manifestChoice.appPath) ? manifestBuilder.path(Paths.get(Paths.get(filesPath.toURI()).toString(), manifestChoice.appPath)) : manifestBuilder.path(Paths.get(filesPath.toURI()));
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

    private void printStagingLogs(CloudFoundryOperations cloudFoundryOperations,
                                  final BuildListener listener, String appName) {
      cloudFoundryOperations.applications().logs(LogsRequest.builder().name(appName).recent(Boolean.TRUE).build())
        .timeout(Duration.ofSeconds(pluginTimeout))
        .doOnNext(applicationLog -> listener.getLogger().println(applicationLog.getMessage()))
        .blockLast();

    }

    private static Optional<org.cloudfoundry.reactor.ProxyConfiguration> buildProxyConfiguration(URL targetURL) {
        Hudson hudson = Hudson.getInstance();
        if (hudson == null) return Optional.empty();

        ProxyConfiguration proxyConfig = hudson.proxy;
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

                PasswordGrantTokenProvider.Builder tokenProviderBuilder = PasswordGrantTokenProvider.builder();
                if (credentials != null) {
                  tokenProviderBuilder = tokenProviderBuilder.username(credentials.getUsername())
                    .password(Secret.toString(credentials.getPassword()));
                }
                TokenProvider tokenProvider = tokenProviderBuilder.build();

                CloudFoundryClient client = ReactorCloudFoundryClient.builder()
                    .connectionContext(connectionContext)
                    .tokenProvider(tokenProvider)
                    .build();

                client.info().get(GetInfoRequest.builder().build())
                    .timeout(Duration.ofSeconds(DEFAULT_PLUGIN_TIMEOUT))
                    .block();
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
                    new URL("https://" + value);
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

    public boolean isResetIfExists() {
        return resetIfExists;
    }
}
