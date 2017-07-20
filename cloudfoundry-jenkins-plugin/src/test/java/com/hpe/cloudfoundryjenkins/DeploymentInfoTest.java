/**
 * © Copyright 2015 Hewlett Packard Enterprise Development LP
 * Copyright (c) ActiveState 2014 - ALL RIGHTS RESERVED.
 */

package com.hpe.cloudfoundryjenkins;

import hudson.FilePath;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.TaskListener;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.hpe.cloudfoundryjenkins.CloudFoundryPushPublisher.DescriptorImpl.*;
import static org.junit.Assert.*;

public class DeploymentInfoTest {

    private static final String PROJECT_VERSION = System.getProperty("project.version");

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void testReadManifestFileAllOptions() throws Exception {
        File manifestFile = new File(getClass().getResource("all-options-manifest.yml").toURI());
        FilePath manifestFilePath = new FilePath(manifestFile);
        ManifestReader manifestReader = new ManifestReader(manifestFilePath);
        Map<String, Object> appInfo = manifestReader.getApplicationInfo();
        DeploymentInfo deploymentInfo =
                new DeploymentInfo(System.out, appInfo, "jenkins-build-name", "domain-name", "");

        assertEquals("hello-java", deploymentInfo.getAppName());
        assertEquals(512, deploymentInfo.getMemory());
        assertEquals("testhost", deploymentInfo.getHostname());
        assertEquals(4, deploymentInfo.getInstances());
        assertEquals(42, deploymentInfo.getTimeout());
        assertEquals(true, deploymentInfo.isNoRoute());
        assertEquals("testdomain.local", deploymentInfo.getDomain());
        assertEquals("target" + File.separator + "hello-java-1.0.war", deploymentInfo.getAppPath());
        assertEquals("https://github.com/heroku/heroku-buildpack-hello", deploymentInfo.getBuildpack());
        assertEquals("customstack", deploymentInfo.getStack());
        assertEquals("echo Hello", deploymentInfo.getCommand());

        Map<String, String> expectedEnvs = new HashMap<String, String>();
        expectedEnvs.put("ENV_VAR_ONE", "value1");
        expectedEnvs.put("ENV_VAR_TWO", "value2");
        expectedEnvs.put("ENV_VAR_THREE", "value3");
        assertEquals(expectedEnvs, deploymentInfo.getEnvVars());

        List<String> expectedServices = new ArrayList<String>();
        expectedServices.add("service_name_one");
        expectedServices.add("service_name_two");
        expectedServices.add("service_name_three");
        assertEquals(expectedServices, deploymentInfo.getServicesNames());
    }

    @Test
    public void testReadManifestFileDefaultOptions() throws Exception {
        File manifestFile = new File(getClass().getResource("no-options-manifest.yml").toURI());
        FilePath manifestFilePath = new FilePath(manifestFile);
        ManifestReader manifestReader = new ManifestReader(manifestFilePath);
        Map<String, Object> appInfo = manifestReader.getApplicationInfo();
        DeploymentInfo deploymentInfo =
                new DeploymentInfo(System.out, appInfo, "jenkins-build-name", "domain-name", "");

        assertEquals("jenkins-build-name", deploymentInfo.getAppName());
        assertEquals(DEFAULT_MEMORY, deploymentInfo.getMemory());
        assertEquals("jenkins-build-name", deploymentInfo.getHostname());
        assertEquals(DEFAULT_INSTANCES, deploymentInfo.getInstances());
        assertEquals(DEFAULT_STACK, deploymentInfo.getStack());
        assertEquals(DEFAULT_TIMEOUT, deploymentInfo.getTimeout());
        assertEquals(false, deploymentInfo.isNoRoute());
        assertEquals("domain-name", deploymentInfo.getDomain());
        assertEquals("", deploymentInfo.getAppPath());
        assertNull(deploymentInfo.getBuildpack());
        assertNull(deploymentInfo.getCommand());

        assertTrue(deploymentInfo.getEnvVars().isEmpty());
        assertTrue(deploymentInfo.getServicesNames().isEmpty());
    }

    @Test
    public void testReadManifestFileMacroTokens() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        build.setDisplayName("test-build");
        TaskListener listener = j.createTaskListener();
        File manifestFile = new File(getClass().getResource("token-macro-manifest.yml").toURI());
        FilePath manifestFilePath = new FilePath(manifestFile);
        ManifestReader manifestReader = new ManifestReader(manifestFilePath);
        Map<String, Object> appInfo = manifestReader.getApplicationInfo();
        DeploymentInfo deploymentInfo =
                new DeploymentInfo(build, listener, System.out, appInfo, "jenkins-build-name", "domain-name", "");

        assertEquals("test-build", deploymentInfo.getAppName());
    }

    @Test
    public void testIgnoreUnknownEnvVarsFileMacroTokens() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        build.setDisplayName("test-build");
        TaskListener listener = j.createTaskListener();
        File manifestFile = new File(getClass().getResource("unknown-env-var-token-manifest.yml").toURI());
        FilePath manifestFilePath = new FilePath(manifestFile);
        ManifestReader manifestReader = new ManifestReader(manifestFilePath);
        Map<String, Object> appInfo = manifestReader.getApplicationInfo();
        DeploymentInfo deploymentInfo =
                new DeploymentInfo(build, listener, System.out, appInfo, "jenkins-build-name", "domain-name", "");

        assertEquals("test-build", deploymentInfo.getAppName());
        Map<String, String> expectedEnvs = new HashMap<String, String>();
        expectedEnvs.put("ENV_VAR_ONE", "$SOME_UNKNOWN_MACRO");
        assertEquals(expectedEnvs, deploymentInfo.getEnvVars());
    }

    @Test
    public void testOptionalJenkinsConfigAllOptions() throws Exception {
        List<CloudFoundryPushPublisher.EnvironmentVariable> envVars = new ArrayList<CloudFoundryPushPublisher.EnvironmentVariable>();
        envVars.add(new CloudFoundryPushPublisher.EnvironmentVariable("ENV_VAR_ONE", "value1"));
        envVars.add(new CloudFoundryPushPublisher.EnvironmentVariable("ENV_VAR_TWO", "value2"));
        envVars.add(new CloudFoundryPushPublisher.EnvironmentVariable("ENV_VAR_THREE", "value3"));
        List<CloudFoundryPushPublisher.ServiceName> services = new ArrayList<CloudFoundryPushPublisher.ServiceName>();
        services.add(new CloudFoundryPushPublisher.ServiceName("service_name_one"));
        services.add(new CloudFoundryPushPublisher.ServiceName("service_name_two"));
        services.add(new CloudFoundryPushPublisher.ServiceName("service_name_three"));
        CloudFoundryPushPublisher.ManifestChoice jenkinsManifest =
                new CloudFoundryPushPublisher.ManifestChoice("jenkinsConfig", null, "hello-java", 512, "testhost", 4, 42, true,
                        "hello-java-" + PROJECT_VERSION + ".war",
                        "https://github.com/heroku/heroku-buildpack-hello", "customstack",
                        "echo Hello", "testdomain.local", envVars, services);
        DeploymentInfo deploymentInfo =
                new DeploymentInfo(System.out, jenkinsManifest, "jenkins-build-name", "domain-name");

        assertEquals("hello-java", deploymentInfo.getAppName());
        assertEquals(512, deploymentInfo.getMemory());
        assertEquals("testhost", deploymentInfo.getHostname());
        assertEquals(4, deploymentInfo.getInstances());
        assertEquals(42, deploymentInfo.getTimeout());
        assertEquals(true, deploymentInfo.isNoRoute());
        assertEquals("testdomain.local", deploymentInfo.getDomain());
        assertEquals("hello-java-" + PROJECT_VERSION + ".war", deploymentInfo.getAppPath());
        assertEquals("https://github.com/heroku/heroku-buildpack-hello", deploymentInfo.getBuildpack());
        assertEquals("customstack", deploymentInfo.getStack());
        assertEquals("echo Hello", deploymentInfo.getCommand());

        Map<String, String> expectedEnvs = new HashMap<String, String>();
        expectedEnvs.put("ENV_VAR_ONE", "value1");
        expectedEnvs.put("ENV_VAR_TWO", "value2");
        expectedEnvs.put("ENV_VAR_THREE", "value3");
        assertEquals(expectedEnvs, deploymentInfo.getEnvVars());

        List<String> expectedServices = new ArrayList<String>();
        expectedServices.add("service_name_one");
        expectedServices.add("service_name_two");
        expectedServices.add("service_name_three");
        assertEquals(expectedServices, deploymentInfo.getServicesNames());
    }

    @Test
    public void testReadJenkinsConfigDefaultOptions() throws Exception {
        CloudFoundryPushPublisher.ManifestChoice jenkinsManifest =
                new CloudFoundryPushPublisher.ManifestChoice("jenkinsConfig", null, "", 0, "", 0, 0, false, "", "", "", "", "", null, null);
        DeploymentInfo deploymentInfo =
                new DeploymentInfo(System.out, jenkinsManifest, "jenkins-build-name", "domain-name");

        assertEquals("jenkins-build-name", deploymentInfo.getAppName());
        assertEquals(DEFAULT_MEMORY, deploymentInfo.getMemory());
        assertEquals("jenkins-build-name", deploymentInfo.getHostname());
        assertEquals(DEFAULT_INSTANCES, deploymentInfo.getInstances());
        assertEquals(DEFAULT_TIMEOUT, deploymentInfo.getTimeout());
        assertEquals(DEFAULT_STACK, deploymentInfo.getStack());
        assertEquals(false, deploymentInfo.isNoRoute());
        assertEquals("domain-name", deploymentInfo.getDomain());
        assertEquals("", deploymentInfo.getAppPath());
        assertNull(deploymentInfo.getBuildpack());
        assertNull(deploymentInfo.getCommand());

        assertTrue(deploymentInfo.getEnvVars().isEmpty());
        assertTrue(deploymentInfo.getServicesNames().isEmpty());
    }

    @Test
    public void testReadJenkinsConfigMacroTokens() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        build.setDisplayName("test-build");
        TaskListener listener = j.createTaskListener();
        CloudFoundryPushPublisher.ManifestChoice jenkinsManifest =
                new CloudFoundryPushPublisher.ManifestChoice("jenkinsConfig", null, "${BUILD_DISPLAY_NAME}",
                        0, "", 0, 0, false, "", "", "", "", "", null, null);
        DeploymentInfo deploymentInfo =
                new DeploymentInfo(build, listener, System.out, jenkinsManifest, "jenkins-build-name", "domain-name");

        assertEquals("test-build", deploymentInfo.getAppName());
    }
}
