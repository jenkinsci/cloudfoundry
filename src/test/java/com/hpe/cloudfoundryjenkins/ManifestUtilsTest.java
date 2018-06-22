/*
 * © 2018 The original author or authors.
 */
package com.hpe.cloudfoundryjenkins;

import com.hpe.cloudfoundryjenkins.CloudFoundryPushPublisher.ManifestChoice;
import hudson.FilePath;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.TaskListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import org.apache.commons.io.IOUtils;
import org.cloudfoundry.operations.applications.ApplicationManifest;
import static org.junit.Assert.assertEquals;
import org.junit.ClassRule;
import org.junit.Ignore;
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
    
    FilePath manifestPath = new FilePath(f);
   
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
  @Ignore("not implemented yet")
  public void testTokenExpansionInJenkinsConfig() throws Exception {
  }
}
