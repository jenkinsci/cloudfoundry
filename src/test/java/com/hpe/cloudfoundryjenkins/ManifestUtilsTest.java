/*
 * © 2018 The original author or authors.
 */
package com.hpe.cloudfoundryjenkins;

import com.google.common.collect.Lists;
import com.hpe.cloudfoundryjenkins.CloudFoundryPushPublisher.EnvironmentVariable;
import com.hpe.cloudfoundryjenkins.CloudFoundryPushPublisher.ManifestChoice;
import com.hpe.cloudfoundryjenkins.CloudFoundryPushPublisher.ServiceName;
import hudson.FilePath;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.TaskListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.io.IOUtils;
import org.cloudfoundry.operations.applications.ApplicationManifest;
import org.jenkinsci.plugins.envinject.EnvInjectBuilder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Tests for {@link ManifestUtils}.
 *
 * @author Steven Swor
 */
public class ManifestUtilsTest {

  @Rule
  public TemporaryFolder tempFolder = new TemporaryFolder();

  @ClassRule
  public static JenkinsRule jenkinsRule = new JenkinsRule();

  @Test
  @Issue("JENKINS-31208")
  public void testTokenExpansionWhenReadingManifestsFromFilesystem() throws Exception {
    File folder = tempFolder.newFolder();
    File f = new File(folder, "manifest.yml");
    InputStream input = getClass().getResourceAsStream("token-macro-manifest.yml");
    OutputStream output = new FileOutputStream(f);
    IOUtils.copy(input, output);

    FilePath manifestPath = new FilePath(folder);

    FreeStyleProject project = jenkinsRule.createFreeStyleProject();
    FreeStyleBuild build = project.scheduleBuild2(0).get();
    build.setDisplayName("JENKINS-31208");
    List<ApplicationManifest> actual = ManifestUtils.loadManifests(manifestPath, ManifestChoice.defaultManifestFileConfig(), false, build, build.getWorkspace(), TaskListener.NULL);

    assertEquals(1, actual.size());
    ApplicationManifest manifest = actual.get(0);
    assertEquals("JENKINS-31208", manifest.getName());
  }

  @Test
  @Issue("JENKINS-31208")
  public void testTokenExpansionInJenkinsConfig() throws Exception {
    FreeStyleProject project = jenkinsRule.createFreeStyleProject("JENKINS-31208");
    String propertiesContent = new StringBuilder()
            .append("ENV_VAR_NAME = FOO").append(System.getProperty("line.separator"))
            .append("ENV_VAR_VALUE = BAR").append(System.getProperty("line.separator"))
            .append("SERVICE_NAME = mysql").append(System.getProperty("line.separator"))
            .append("MEMORY = 1g").append(System.getProperty("line.separator"))
            .append("HOSTNAME = foo.bar.com").append(System.getProperty("line.separator"))
            .append("INSTANCES = 3").append(System.getProperty("line.separator"))
            .append("TIMEOUT = 300").append(System.getProperty("line.separator"))
            .append("NOROUTE = false").append(System.getProperty("line.separator"))
            .append("BUILDPACK = java-buildpack").append(System.getProperty("line.separator"))
            .append("STACK = pancakes").append(System.getProperty("line.separator"))
            .append("COMMAND = tail -f /dev/null").append(System.getProperty("line.separator"))
            .append("DOMAIN = bar.com").append(System.getProperty("line.separator"))
            .toString();
    project.getBuildersList().add(new EnvInjectBuilder(null, propertiesContent));
    FreeStyleBuild build = project.scheduleBuild2(0).get();

    List<EnvironmentVariable> envVars = Lists.newArrayList(new EnvironmentVariable("${ENV_VAR_NAME}", "${ENV_VAR_VALUE}"));
    List<ServiceName> servicesNames = Lists.newArrayList(new ServiceName("${SERVICE_NAME}"));
    ManifestChoice manifestChoice = new ManifestChoice("jenkinsConfig", null, "${JOB_NAME}", "${MEMORY}", "${HOSTNAME}", "${INSTANCES}", "${TIMEOUT}", "${NOROUTE}", "${WORKSPACE}", "${BUILDPACK}", "${STACK}", "${COMMAND}", "${DOMAIN}", envVars, servicesNames);

    List<ApplicationManifest> manifests = ManifestUtils.loadManifests(project.getSomeWorkspace(), manifestChoice, true, build, build.getWorkspace(), TaskListener.NULL);

    assertEquals(1, manifests.size());
    ApplicationManifest manifest = manifests.get(0);
    assertEquals("JENKINS-31208", manifest.getName());
    assertEquals(Integer.valueOf(1024), manifest.getMemory());
    assertEquals("foo.bar.com", manifest.getHosts().get(0));
    assertEquals(Integer.valueOf(3), manifest.getInstances());
    assertEquals(Integer.valueOf(300), manifest.getTimeout());
    assertFalse(manifest.getNoRoute());
    assertEquals(Paths.get(build.getWorkspace().toURI()), manifest.getPath());
    assertEquals("java-buildpack", manifest.getBuildpack());
    assertEquals("pancakes", manifest.getStack());
    assertEquals("tail -f /dev/null", manifest.getCommand());
    assertEquals("bar.com", manifest.getDomains().get(0));
    Map<String, Object> expectedEnvVars = Stream.of(new AbstractMap.SimpleEntry<>("FOO", "BAR")).collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
    assertEquals(expectedEnvVars, manifest.getEnvironmentVariables());
    assertEquals("mysql", manifest.getServices().get(0));
  }
}
