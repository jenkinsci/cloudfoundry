/**
 * Copyright (c) ActiveState 2014 - ALL RIGHTS RESERVED.
 */

package com.activestate.cloudfoundryjenkins;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.ProxyConfiguration;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Hudson;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;

import org.cloudfoundry.client.lib.CloudCredentials;
import org.cloudfoundry.client.lib.CloudFoundryClient;
import org.cloudfoundry.client.lib.CloudFoundryException;
import org.cloudfoundry.client.lib.HttpProxyConfiguration;
import org.cloudfoundry.client.lib.StartingInfo;
import org.cloudfoundry.client.lib.domain.*;
import org.cloudfoundry.client.lib.org.springframework.web.client.ResourceAccessException;
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

    public final String target;
    public final String organization;
    public final String cloudSpace;
    public final String username;
    public final String password;
    public final boolean selfSigned;
    public final boolean resetIfExists;
    public final OptionalManifest optionalManifest;

    private List<String> appURIs = new ArrayList<String>();

    @DataBoundConstructor
    public CloudFoundryPushPublisher(String target, String organization, String cloudSpace,
                                     String username, String password, boolean selfSigned,
                                     boolean resetIfExists, OptionalManifest optionalManifest) {
        this.target = target;
        this.organization = organization;
        this.cloudSpace = cloudSpace;
        this.username = username;
        this.password = password;
        this.selfSigned = selfSigned;
        this.resetIfExists = resetIfExists;
        this.optionalManifest = optionalManifest;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
        // We don't want to push if the build failed
        if (build.getResult().isWorseThan(Result.SUCCESS))
            return true;

        try {
            String jenkinsBuildName = build.getProject().getDisplayName();

            URL targetUrl = new URL(target);
            String[] split = target.split("\\.", 2);
            String domain = split[split.length - 1];

            FilePath manifestFilePath = new FilePath(build.getWorkspace(), DEFAULT_MANIFEST_PATH);

            // Get all deployment info
            List<DeploymentInfo> allDeploymentInfo = new ArrayList<DeploymentInfo>();
            if (optionalManifest == null) {
                // Read manifest.yml
                ManifestReader manifestReader = new ManifestReader(manifestFilePath);
                List<Map<String, Object>> appList = manifestReader.getApplicationList();
                for (Map<String, Object> appInfo : appList) {
                    allDeploymentInfo.add(new DeploymentInfo(listener.getLogger(), appInfo, jenkinsBuildName, domain));
                }
            } else {
                // Read Jenkins configuration
                allDeploymentInfo.add(new DeploymentInfo(listener.getLogger(), optionalManifest, jenkinsBuildName, domain));
            }

            boolean success = true;
            for (DeploymentInfo deploymentInfo : allDeploymentInfo) {
                boolean lastSuccess = processOneApp(deploymentInfo, build, listener, targetUrl);
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
        } catch (ManifestParsingException e) {
            listener.getLogger().println("ERROR: Could not parse manifest: " + e.getMessage());
            return false;
        } catch (IOException e) {
            listener.getLogger().println("ERROR: IOException: " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            listener.getLogger().println("ERROR: InterruptedException: " + e.getMessage());
            return false;
        }
    }

    private boolean processOneApp(DeploymentInfo deploymentInfo, AbstractBuild build, BuildListener listener, URL targetUrl)
            throws IOException, InterruptedException {
        try {
            String appName = deploymentInfo.getAppName();
            String appURI = "https://" + deploymentInfo.getHostname() + "." + deploymentInfo.getDomain();
            addToAppURIs(appURI);

            CloudCredentials credentials = new CloudCredentials(username, password);
            HttpProxyConfiguration proxyConfig = buildProxyConfiguration(targetUrl);
            
            CloudFoundryClient client = new CloudFoundryClient(credentials, targetUrl, organization, cloudSpace,
            		proxyConfig, selfSigned);
            client.login();

            listener.getLogger().println("Pushing " + appName + " app to " + target);

            // Check if app already exists
            List<CloudApplication> existingApps = client.getApplications();
            boolean createNewApp = true;
            for (CloudApplication app : existingApps) {
                if (app.getName().equals(appName)) {
                    if (resetIfExists) {
                        listener.getLogger().println("App already exists, resetting.");
                        client.deleteApplication(appName);
                        listener.getLogger().println("App deleted.");
                    } else {
                        createNewApp = false;
                        listener.getLogger().println("App already exists, skipping creation.");
                    }
                    break;
                }
            }

            // This is where we would create services, if we decide to add that feature.
            // List<CloudService> cloudServices = deploymentInfo.getServices();
            // client.createService();

            // Create app if it doesn't exist
            if (createNewApp) {
                listener.getLogger().println("Creating new app.");
                Staging staging = new Staging(deploymentInfo.getCommand(), deploymentInfo.getBuildpack(),
                        null, deploymentInfo.getTimeout());
                List<String> uris = new ArrayList<String>();
                uris.add(appURI);
                List<String> services = deploymentInfo.getServicesNames();
                client.createApplication(appName, staging, deploymentInfo.getMemory(), uris, services);
            }

            // Delete route if no-route parameter
            if (deploymentInfo.isNoRoute()) {
                client.deleteRoute(deploymentInfo.getHostname(), deploymentInfo.getDomain());
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
            FilePath appPath = new FilePath(build.getWorkspace(), deploymentInfo.getAppPath());

            if (appPath.getChannel() != Jenkins.MasterComputer.localChannel) {
                if (appPath.isDirectory()) {
                    // The build is distributed, and a directory
                    // We need to make a copy of the target directory on the master
                    File appFile = File.createTempFile("appFile", null); // This is on the master
                    appFile.deleteOnExit();
                    OutputStream outputStream = new FileOutputStream(appFile);
                    appPath.zip(outputStream);

                    // We now have a zip file on the master, extract it into a directory
                    ZipFile appZipFile = new ZipFile(appFile);
                    File outputDirectory = new File(appFile.getAbsolutePath().split("\\.")[0]);
                    outputDirectory.deleteOnExit();
                    appZipFile.extractAll(outputDirectory.getAbsolutePath());
                    // appPath.zip() creates a top level directory that we want to remove
                    File[] listFiles = outputDirectory.listFiles();
                    if (listFiles != null && listFiles.length == 1) {
                        outputDirectory = listFiles[0];
                    } else {
                        // This should never happen because appPath.zip() always makes a directory
                        throw new IllegalStateException("Unzipped output directory was empty.");
                    }
                    // We can now use outputDirectory which is a copy of the target directory but on master
                    client.uploadApplication(appName, outputDirectory);

                } else {
                    // If the target path is a single file, we can just use an InputStream
                    // The CF client will make a temp file on the master from the InputStream
                    client.uploadApplication(appName, appPath.getName(), appPath.read());
                }
            } else {
                // If the build is not distributed, we can convert the FilePath to a File without problems
                File targetFile = new File(appPath.toURI());
                client.uploadApplication(appName, targetFile);
            }

            // Start or restart application
            StartingInfo startingInfo;
            if (createNewApp) {
                listener.getLogger().println("Starting application.");
                startingInfo = client.startApplication(appName);
            } else {
                listener.getLogger().println("Restarting application.");
                startingInfo = client.restartApplication(appName);
            }

            // Start printing the staging logs
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
            if (e.getMessage().equals("403 Access token denied.")) {
                listener.getLogger().println("ERROR: Wrong username or password: " + e.getMessage());
            } else {
                listener.getLogger().println("ERROR: Unknown CloudFoundryException: " + e.getMessage());
                listener.getLogger().println("ERROR: Cloud Foundry error code: " + e.getCloudFoundryErrorCode());
                if (e.getDescription() != null) {
                    listener.getLogger().println("ERROR: " + e.getDescription());
                }
            }
            return false;
        } catch (FileNotFoundException e) {
            listener.getLogger().println("ERROR: Could not find file: " + e.getMessage());
            return false;
        } catch (ZipException e) {
            listener.getLogger().println("ERROR: ZipException: " + e.getMessage());
            return false;
        }
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
    
    private static HttpProxyConfiguration buildProxyConfiguration(URL targetURL)
    {
    	ProxyConfiguration proxyConfig = Hudson.getInstance().proxy;
    	if (proxyConfig == null)
    		return null;
    	
    	String host = targetURL.getHost();
    	for (Pattern p : proxyConfig.getNoProxyHostPatterns()) {
    	    if (p.matcher(host).matches())
                return null;
		}
    	
    	return new HttpProxyConfiguration(proxyConfig.name, proxyConfig.port);
    }

    public static class OptionalManifest {
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
        public OptionalManifest(String appName, int memory, String hostname, int instances, int timeout,
                                boolean noRoute, String appPath, String buildpack, String command, String domain,
                                List<EnvironmentVariable> envVars, List<ServiceName> servicesNames) {
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

    public static class ServiceName {

        public final String name;

        @DataBoundConstructor
        public ServiceName(String name) {
            this.name = name;
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

        @SuppressWarnings("unused")
        public FormValidation doTestConnection(@QueryParameter("target") final String target,
                                               @QueryParameter("username") final String username,
                                               @QueryParameter("password") final String password,
                                               @QueryParameter("organization") final String organization,
                                               @QueryParameter("cloudSpace") final String cloudSpace,
                                               @QueryParameter("selfSigned") final boolean selfSigned) {

            try {
                URL targetUrl = new URL(target);
                CloudCredentials credentials = new CloudCredentials(username, password);
                HttpProxyConfiguration proxyConfig = buildProxyConfiguration(targetUrl);
                
                CloudFoundryClient client = new CloudFoundryClient(credentials, targetUrl, organization, cloudSpace,
                        proxyConfig, selfSigned);
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
        public FormValidation doCheckUsername(@QueryParameter String value) {
            return FormValidation.validateRequired(value);
        }

        @SuppressWarnings("unused")
        public FormValidation doCheckPassword(@QueryParameter String value) {
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
}
