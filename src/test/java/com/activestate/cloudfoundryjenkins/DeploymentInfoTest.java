/**
 * Copyright (c) ActiveState 2014 - ALL RIGHTS RESERVED.
 */

package com.activestate.cloudfoundryjenkins;

import com.activestate.cloudfoundryjenkins.CloudFoundryPushPublisher.EnvironmentVariable;
import com.activestate.cloudfoundryjenkins.CloudFoundryPushPublisher.ManifestChoice;
import com.activestate.cloudfoundryjenkins.CloudFoundryPushPublisher.ServiceName;
import com.kenai.jffi.Array;

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
import java.util.UUID;

import static com.activestate.cloudfoundryjenkins.CloudFoundryPushPublisher.DescriptorImpl.*;
import static org.junit.Assert.*;

public class DeploymentInfoTest {

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
        
        UUID randomUUID = UUID.randomUUID();
        String newAppName = "hello-java-"+randomUUID.toString();
        String newHostName = "testhost-"+randomUUID.toString();
        deploymentInfo.setBGDeployment(true);
        deploymentInfo.setCreateNewApp(true);
        deploymentInfo.setAppName(newAppName);
        deploymentInfo.setHostname(newHostName);
        List<String> blueRoutes = new ArrayList<String>();
        blueRoutes.add("blue route");
        
        deploymentInfo.setBlueRoutes(blueRoutes);
        deploymentInfo.setBlueHostname("hello-java");
        deploymentInfo.setBlueAppName("hello-java");
        assertEquals(newAppName, deploymentInfo.getAppName());
        assertEquals(newHostName, deploymentInfo.getHostname());
        assertEquals("hello-java", deploymentInfo.getBlueHostname());
        assertEquals("hello-java", deploymentInfo.getBlueAppName());
        assertEquals("blue route", deploymentInfo.getBlueRoutes().get(0));
        assert(deploymentInfo.isBGDeployment());
        assert(deploymentInfo.isCreateNewApp());
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
    public void testOptionalJenkinsConfigAllOptions() throws Exception {
        List<EnvironmentVariable> envVars = new ArrayList<EnvironmentVariable>();
        envVars.add(new EnvironmentVariable("ENV_VAR_ONE", "value1"));
        envVars.add(new EnvironmentVariable("ENV_VAR_TWO", "value2"));
        envVars.add(new EnvironmentVariable("ENV_VAR_THREE", "value3"));
        List<ServiceName> services = new ArrayList<ServiceName>();
        services.add(new ServiceName("service_name_one"));
        services.add(new ServiceName("service_name_two"));
        services.add(new ServiceName("service_name_three"));
        ManifestChoice jenkinsManifest =
                new ManifestChoice("jenkinsConfig", null, "hello-java", 512, "testhost", 4, 42, true,
                        "target/hello-java-1.0.war",
                        "https://github.com/heroku/heroku-buildpack-hello",
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
        assertEquals("target" + File.separator + "hello-java-1.0.war", deploymentInfo.getAppPath());
        assertEquals("https://github.com/heroku/heroku-buildpack-hello", deploymentInfo.getBuildpack());
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
        ManifestChoice jenkinsManifest =
                new ManifestChoice("jenkinsConfig", null, "", 0, "", 0, 0, false, "", "", "", "", null, null);
        DeploymentInfo deploymentInfo =
                new DeploymentInfo(System.out, jenkinsManifest, "jenkins-build-name", "domain-name");

        assertEquals("jenkins-build-name", deploymentInfo.getAppName());
        assertEquals(DEFAULT_MEMORY, deploymentInfo.getMemory());
        assertEquals("jenkins-build-name", deploymentInfo.getHostname());
        assertEquals(DEFAULT_INSTANCES, deploymentInfo.getInstances());
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
    public void testReadJenkinsConfigMacroTokens() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        build.setDisplayName("test-build");
        TaskListener listener = j.createTaskListener();
        ManifestChoice jenkinsManifest =
                new ManifestChoice("jenkinsConfig", null, "${BUILD_DISPLAY_NAME}",
                        0, "", 0, 0, false, "", "", "", "", null, null);
        DeploymentInfo deploymentInfo =
                new DeploymentInfo(build, listener, System.out, jenkinsManifest, "jenkins-build-name", "domain-name");

        assertEquals("test-build", deploymentInfo.getAppName());
    }
}
