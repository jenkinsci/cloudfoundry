/**
 * © Copyright 2015 Hewlett Packard Enterprise Development LP
 * Copyright (c) ActiveState 2014 - ALL RIGHTS RESERVED.
 */

package com.hpe.cloudfoundryjenkins;

import com.hpe.cloudfoundryjenkins.CloudFoundryPushPublisher.DescriptorImpl;
import com.hpe.cloudfoundryjenkins.CloudFoundryPushPublisher.EnvironmentVariable;
import com.hpe.cloudfoundryjenkins.CloudFoundryPushPublisher.ManifestChoice;
import com.hpe.cloudfoundryjenkins.CloudFoundryPushPublisher.ServiceName;
import hudson.model.AbstractBuild;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Contains all deployment info of a single application.
 * The class is in charge of default values, of expanding token macros,
 * and of reading from the Jenkins config if needed.
 */
public class DeploymentInfo {

    private String appName;
    private int memory;
    private String hostname;
    private int instances;
    private int timeout;
    private boolean noRoute;
    private String appPath;
    private String buildpack;
    private String command;
    private String domain;
    private String stack;

    private Map<String, String> envVars = new HashMap<String, String>();
    private List<String> servicesNames = new ArrayList<String>();

    /**
     * Constructor for reading the manifest.yml file.
     * Takes an appInfo Map that is created from a ManifestReader.
     */
    public DeploymentInfo(AbstractBuild build, TaskListener listener, PrintStream logger, Map<String, Object> appInfo,
                          String jenkinsBuildName, String defaultDomain, String manifestPath)
            throws IOException, ManifestParsingException, InterruptedException, MacroEvaluationException {

        readManifestFile(logger, appInfo, jenkinsBuildName, defaultDomain, manifestPath);
        expandTokenMacros(build, listener);
    }

    /**
     * Constructor for reading the optional Jenkins config.
     */
    public DeploymentInfo(AbstractBuild build, TaskListener listener, PrintStream logger,
                          ManifestChoice optionalJenkinsConfig, String jenkinsBuildName, String defaultDomain)
            throws IOException, ManifestParsingException, InterruptedException, MacroEvaluationException {

        readOptionalJenkinsConfig(logger, optionalJenkinsConfig, jenkinsBuildName, defaultDomain);
        expandTokenMacros(build, listener);
    }

    /**
     * Constructor for reading the manifest.yml file. (Without token expansion)
     */
    public DeploymentInfo(PrintStream logger, Map<String, Object> appInfo,
                          String jenkinsBuildName, String defaultDomain, String manifestPath)
            throws IOException, ManifestParsingException, InterruptedException, MacroEvaluationException {

        readManifestFile(logger, appInfo, jenkinsBuildName, defaultDomain, manifestPath);
    }

    /**
     * Constructor for reading the optional Jenkins config. (Without token expansion)
     */
    public DeploymentInfo(PrintStream logger, ManifestChoice optionalJenkinsConfig,
                          String jenkinsBuildName, String defaultDomain)
            throws IOException, ManifestParsingException, InterruptedException, MacroEvaluationException {

        readOptionalJenkinsConfig(logger, optionalJenkinsConfig, jenkinsBuildName, defaultDomain);
    }

    private void readManifestFile(PrintStream logger, Map<String, Object> manifestJson,
                                  String jenkinsBuildName, String defaultDomain, String manifestPath) {

        // Important optional attributes, we should warn in case they are missing
        if (manifestJson == null) {
            manifestJson = new HashMap<String, Object>();
        }

        appName = (String) manifestJson.get("name");
        if (appName == null) {
            logger.println("WARNING: No application name. Using Jenkins build name: " + jenkinsBuildName);
            appName = jenkinsBuildName;
        }

        int memory;
        Object mem = manifestJson.get("memory");
        if (mem == null) {
            logger.println("WARNING: No manifest value for memory. Using default value: " +
                    DescriptorImpl.DEFAULT_MEMORY);
            memory = DescriptorImpl.DEFAULT_MEMORY;
        } else {
            // The YAML parser from ManifestReader might make the memory value an Integer or a String
            // depending on whether or not there is a unit at the end of the value
            if (mem instanceof Integer) {
                memory = (Integer) mem;
            } else {
                String memString = (String) mem;
                memString = memString.toLowerCase();
                if (memString.endsWith("m")) {
                    memory = Integer.parseInt(memString.substring(0, memString.length() - 1));
                } else if (memString.endsWith("mb")) {
                    memory = Integer.parseInt(memString.substring(0, memString.length() - 2));
                } else if (memString.endsWith("g")) {
                    memory = Integer.parseInt(memString.substring(0, memString.length() - 1));
                    memory = memory * 1024;
                } else if (memString.endsWith("gb")) {
                    memory = Integer.parseInt(memString.substring(0, memString.length() - 2));
                    memory = memory * 1024;
                } else {
                    logger.println("WARNING: Unknown unit for memory in manifest. Using default value: " +
                            DescriptorImpl.DEFAULT_MEMORY);
                    memory = DescriptorImpl.DEFAULT_MEMORY;
                }
            }
        }
        this.memory = memory;

        hostname = (String) manifestJson.get("host");
        if (hostname == null) {
            logger.println("WARNING: No manifest value for hostname. Using app name: " + appName);
            hostname = appName;
        }

        // Non-important optional attributes, no need to warn
        Integer instances = (Integer) manifestJson.get("instances");
        if (instances == null) {
            instances = DescriptorImpl.DEFAULT_INSTANCES;
        }
        this.instances = instances;

        Integer timeout = (Integer) manifestJson.get("timeout");
        if (timeout == null) {
            timeout = DescriptorImpl.DEFAULT_TIMEOUT;
        }
        this.timeout = timeout;

        String stack = (String) manifestJson.get("stack");
        if (stack == null) {
            stack = DescriptorImpl.DEFAULT_STACK;
        }
        this.stack = stack;

        Boolean noRoute = (Boolean) manifestJson.get("no-route");
        if (noRoute == null) {
            noRoute = false;
        }
        this.noRoute = noRoute;

        String domain = (String) manifestJson.get("domain");
        if (domain == null) {
            domain = defaultDomain;
        }
        this.domain = domain;

        String appPath = (String) manifestJson.get("path");
        if (appPath == null) {
            appPath = "";
        }
        // The path in manifest.yml is from the relative position of the manifest.yml file
        // We need to transform it into a path relative to the workspace
        Path sourcePath = Paths.get(manifestPath);
        sourcePath = sourcePath.getParent();
        if (sourcePath == null) {
            sourcePath = Paths.get(".");
        }
        Path targetPath = Paths.get(appPath);
        this.appPath = sourcePath.resolve(targetPath).normalize().toString();

        // Optional attributes with no defaults, it's ok if those are null
        this.buildpack = (String) manifestJson.get("buildpack");
        this.command = (String) manifestJson.get("command");

        // Env vars and services
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> envVarsSuppressed = (Map<String, String>) manifestJson.get("env");
            if (envVarsSuppressed != null) {
                this.envVars = envVarsSuppressed;
            }
        } catch (ClassCastException e) {
            logger.println("WARNING: Could not parse env vars into a map. Ignoring env vars.");
        }

        try {
            @SuppressWarnings("unchecked")
            List<String> servicesSuppressed = (List<String>) manifestJson.get("services");
            if (servicesSuppressed != null) {
                this.servicesNames = servicesSuppressed;
            }
        } catch (ClassCastException e) {
            logger.println("WARNING: Could not parse services into a list. Ignoring services.");
        }
    }

    private void readOptionalJenkinsConfig(PrintStream logger, ManifestChoice jenkinsConfig,
                                           String jenkinsBuildName, String defaultDomain) {

        this.appName = jenkinsConfig.appName;
        if (appName.equals("")) {
            logger.println("WARNING: No application name. Using Jenkins build name: " + jenkinsBuildName);
            appName = jenkinsBuildName;
        }
        this.memory = jenkinsConfig.memory;
        if (memory == 0) {
            logger.println("WARNING: Missing value for memory. Using default value: " + DescriptorImpl.DEFAULT_MEMORY);
            memory = DescriptorImpl.DEFAULT_MEMORY;
        }
        this.hostname = jenkinsConfig.hostname;
        if (hostname.equals("")) {
            logger.println("WARNING: Missing value for hostname. Using app name: " + appName);
            hostname = appName;
        }

        this.instances = jenkinsConfig.instances;
        if (instances == 0) {
            instances = DescriptorImpl.DEFAULT_INSTANCES;
        }

        this.timeout = jenkinsConfig.timeout;
        if (timeout == 0) {
            timeout = DescriptorImpl.DEFAULT_TIMEOUT;
        }

        this.stack = jenkinsConfig.stack;
        if (stack.equals("")) {
            stack = DescriptorImpl.DEFAULT_STACK;
        }

        // noRoute's default value is already false, which is acceptable
        this.noRoute = jenkinsConfig.noRoute;

        this.domain = jenkinsConfig.domain;
        if (domain.equals("")) {
            domain = defaultDomain;
        }

        // These must be null, not just empty string
        this.buildpack = jenkinsConfig.buildpack;
        if (buildpack.equals("")) {
            buildpack = null;
        }
        this.command = jenkinsConfig.command;
        if (command.equals("")) {
            command = null;
        }

        // Paths.get("").normalize() causes a bug in some versions of Java
        // Replace "" with "." to avoid the bug
        String tempAppPath = jenkinsConfig.appPath;
        if (tempAppPath.isEmpty()) {
            tempAppPath = ".";
        }
        this.appPath = Paths.get(tempAppPath).normalize().toString();

        List<EnvironmentVariable> manifestEnvVars = jenkinsConfig.envVars;
        if (manifestEnvVars != null) {
            for (EnvironmentVariable var : manifestEnvVars) {
                this.envVars.put(var.key, var.value);
            }
        }

        List<ServiceName> manifestServicesNames = jenkinsConfig.servicesNames;
        if (manifestServicesNames != null) {
            for (ServiceName service : manifestServicesNames) {
                this.servicesNames.add(service.name);
            }
        }
    }

    private void expandTokenMacros(AbstractBuild build, TaskListener listener)
            throws InterruptedException, MacroEvaluationException, IOException {

        this.appName = TokenMacro.expandAll(build, listener, this.appName);
        this.hostname = TokenMacro.expandAll(build, listener, this.hostname);
        this.appPath = TokenMacro.expandAll(build, listener, this.appPath);
        this.buildpack = TokenMacro.expandAll(build, listener, this.buildpack);
        this.command = TokenMacro.expandAll(build, listener, this.command);
        this.domain = TokenMacro.expandAll(build, listener, this.domain);

        Map<String, String> expandedEnvVars = new HashMap<String, String>();
        for (Map.Entry<String, String> entry : this.envVars.entrySet()) {
            try {
                String expandedEnvVarName = TokenMacro.expandAll(build, listener, entry.getKey());
                String expandedEnvVarValue = TokenMacro.expandAll(build, listener, entry.getValue());
                expandedEnvVars.put(expandedEnvVarName, expandedEnvVarValue);
            } catch (MacroEvaluationException e) {
                // If a token exists but isn't recognized, then it's probably an environment variable
                // meant for the CF target, so leave it alone.
                expandedEnvVars.put(entry.getKey(), entry.getValue());
            }
        }
        this.envVars = expandedEnvVars;

        List<String> expandedServicesNames = new ArrayList<String>();
        for (String serviceName : this.servicesNames) {
            String expandedServiceName = TokenMacro.expandAll(build, listener, serviceName);
            expandedServicesNames.add(expandedServiceName);
        }
        this.servicesNames = expandedServicesNames;
    }

    public String getAppName() {
        return appName;
    }

    public int getMemory() {
        return memory;
    }

    public String getHostname() {
        return hostname;
    }

    public int getInstances() {
        return instances;
    }

    public String getStack() {
        return stack;
    }

    public int getTimeout() {
        return timeout;
    }

    public boolean isNoRoute() {
        return noRoute;
    }

    public String getAppPath() {
        return appPath;
    }

    public String getBuildpack() {
        return buildpack;
    }

    public String getCommand() {
        return command;
    }

    public String getDomain() {
        return domain;
    }

    public Map<String, String> getEnvVars() {
        return envVars;
    }

    public List<String> getServicesNames() {
        return servicesNames;
    }

}
