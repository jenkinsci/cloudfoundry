/**
 * Copyright (c) ActiveState 2014 - ALL RIGHTS RESERVED.
 */

package com.activestate.cloudfoundryjenkins;

import com.activestate.cloudfoundryjenkins.CloudFoundryPushPublisher.EnvironmentVariable;
import com.activestate.cloudfoundryjenkins.CloudFoundryPushPublisher.ExistingAppHandler;
import com.activestate.cloudfoundryjenkins.CloudFoundryPushPublisher.ManifestChoice;
import com.activestate.cloudfoundryjenkins.CloudFoundryPushPublisher.ServiceName;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;

import org.apache.commons.io.FileUtils;
import org.apache.http.HttpResponse;
import org.apache.http.ParseException;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.fluent.Request;
import org.apache.http.util.EntityUtils;
import org.cloudfoundry.client.lib.CloudCredentials;
import org.cloudfoundry.client.lib.CloudFoundryClient;
import org.cloudfoundry.client.lib.domain.CloudService;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.ExtractResourceSCM;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.WithTimeout;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

public class CloudFoundryPushPublisherTest {

    private static final String TEST_TARGET = System.getProperty("target");
    private static final String TEST_USERNAME = System.getProperty("username");
    private static final String TEST_PASSWORD = System.getProperty("password");
    private static final String TEST_ORG = System.getProperty("org");
    private static final String TEST_SPACE = System.getProperty("space");

    private static CloudFoundryClient client;

    @Rule
    public JenkinsRule j = new JenkinsRule();


    @BeforeClass
    public static void initialiseClient() throws IOException {
        // Skip all tests of this class if no test CF platform is specified
        assumeNotNull(TEST_TARGET);

        String fullTarget = TEST_TARGET;
        if (!fullTarget.startsWith("https://")) {
            if (!fullTarget.startsWith("api.")) {
                fullTarget = "https://api." + fullTarget;
            } else {
                fullTarget = "https://" + fullTarget;
            }
        }
        URL targetUrl = new URL(fullTarget);

        CloudCredentials credentials = new CloudCredentials(TEST_USERNAME, TEST_PASSWORD);
        client = new CloudFoundryClient(credentials, targetUrl, TEST_ORG, TEST_SPACE);
        client.login();
    }

    @Before
    public void cleanCloudSpace() throws IOException {
        client.deleteAllApplications();
        client.deleteAllServices();

        CredentialsStore store = CredentialsProvider.lookupStores(j.getInstance()).iterator().next();
        store.addCredentials(Domain.global(),
                new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "testCredentialsId", "",
                        TEST_USERNAME, TEST_PASSWORD));
    }

    @Test
    public void testPerformSimplePushManifestFileWithDeafultExistingAppHandler() throws Exception {
        doSimplePushUsingManifestFile(ExistingAppHandler.getDefault());
    }
    
    @Test
    public void testPerformSimplePushManifestFileWithRecreateExistingAppHandler() throws Exception {
    		doSimplePushUsingManifestFile(ExistingAppHandler.getDefault());
        doSimplePushUsingManifestFile(new ExistingAppHandler(ExistingAppHandler.Choice.RECREATE.toString(), false));
    }
    
    @Test
    public void testPerformSimplePushManifestFileWithBGDeployExistingAppHandler() throws Exception {
        doSimplePushUsingManifestFile(new ExistingAppHandler(ExistingAppHandler.Choice.BGDEPLOY.toString(), false));
    }

	private void doSimplePushUsingManifestFile(ExistingAppHandler existingAppHandler) throws IOException,
			InterruptedException, ExecutionException, ClientProtocolException {
		FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("hello-java.zip")));

        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", false, existingAppHandler, ManifestChoice.defaultManifestFileConfig());
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build did not display staging logs", log.contains("Downloaded app package"));

        System.out.println("App URI : " + cf.getAppURIs().get(0));
        String uri = cf.getAppURIs().get(0);
        Request request = Request.Get(uri);
        HttpResponse response = request.execute().returnResponse();
        int statusCode = response.getStatusLine().getStatusCode();
        assertEquals("Get request did not respond 200 OK", 200, statusCode);
        String content = EntityUtils.toString(response.getEntity());
        System.out.println(content);
        assertTrue("App did not send back correct text", content.contains("Hello from"));
	}

    @Test
    public void testPerformSimplePushJenkinsConfig() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("hello-java.zip")));
        List<EnvironmentVariable> envVars = new ArrayList<EnvironmentVariable>();
        envVars.add(new EnvironmentVariable("SSH_CONNECTION", "Hub Port Ip None"));
        ManifestChoice manifest =
                new ManifestChoice("jenkinsConfig", null, "hello-java", 512, "", 0, 0, false,
                        "target/hello-java-1.0.war", "", "", "",
                        envVars, new ArrayList<ServiceName>());
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", false, ExistingAppHandler.getDefault(), manifest);
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build did not display staging logs", log.contains("Downloaded app package"));

        System.out.println("App URI : " + cf.getAppURIs().get(0));
        String uri = cf.getAppURIs().get(0);
        Request request = Request.Get(uri);
        HttpResponse response = request.execute().returnResponse();
        int statusCode = response.getStatusLine().getStatusCode();
        assertEquals("Get request did not respond 200 OK", 200, statusCode);
        String content = EntityUtils.toString(response.getEntity());
        System.out.println(content);
        assertTrue("App did not send back correct text", content.contains("Hello from"));
    }

    @Test
    @WithTimeout(300)
    public void testPerformResetIfExists() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("hello-java.zip")));
        List<EnvironmentVariable> envVars = new ArrayList<EnvironmentVariable>();
        envVars.add(new EnvironmentVariable("SSH_CONNECTION", "Hub Port Ip None"));
        ManifestChoice manifest1 =
                new ManifestChoice("jenkinsConfig", null, "hello-java", 512, "", 0, 0, false,
                        "target/hello-java-1.0.war", "", "", "",
                        envVars, new ArrayList<ServiceName>());
        CloudFoundryPushPublisher cf1 = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", false, new ExistingAppHandler("RECREATE", false), manifest1);
        project.getPublishersList().add(cf1);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " 1 completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build 1 did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build 1 did not display staging logs", log.contains("Downloaded app package"));
        assertEquals(512, client.getApplication("hello-java").getMemory());

        project.getPublishersList().remove(cf1);

        ManifestChoice manifest2 =
                new ManifestChoice("jenkinsConfig", null, "hello-java", 256, "", 0, 0, false,
                        "target/hello-java-1.0.war", "", "", "",
                        envVars, new ArrayList<ServiceName>());
  
        CloudFoundryPushPublisher cf2 = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", false, new ExistingAppHandler("RECREATE", false), manifest2);
        project.getPublishersList().add(cf2);
        build = project.scheduleBuild2(0).get();

        log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build 2 did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build 2 did not display staging logs", log.contains("Downloaded app package"));
        assertEquals(256, client.getApplication("hello-java").getMemory());
    }

    @Test
    public void testPerformMultipleInstances() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("hello-java.zip")));
        List<EnvironmentVariable> envVars = new ArrayList<EnvironmentVariable>();
        envVars.add(new EnvironmentVariable("SSH_CONNECTION", "Hub Port Ip None"));
        ManifestChoice manifest =
                new ManifestChoice("jenkinsConfig", null, "hello-java", 512, "", 3, 0, false,
                        "target/hello-java-1.0.war", "", "", "",
                        envVars, new ArrayList<ServiceName>());
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", false, ExistingAppHandler.getDefault(), manifest);
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build did not display staging logs", log.contains("Downloaded app package"));
        assertTrue("Not the correct amount of instances", log.contains("3 instances running out of 3"));

        System.out.println("App URI : " + cf.getAppURIs().get(0));
        String uri = cf.getAppURIs().get(0);
        Request request = Request.Get(uri);
        HttpResponse response = request.execute().returnResponse();
        int statusCode = response.getStatusLine().getStatusCode();
        assertEquals("Get request did not respond 200 OK", 200, statusCode);
        String content = EntityUtils.toString(response.getEntity());
        System.out.println(content);
        assertTrue("App did not send back correct text", content.contains("Hello from"));
    }

    @Test
    public void testPerformCustomBuildpack() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("heroku-node-js-sample.zip")));
        List<EnvironmentVariable> envVars = new ArrayList<EnvironmentVariable>();
        envVars.add(new EnvironmentVariable("SSH_CONNECTION", "Hub Port Ip None"));
        ManifestChoice manifest =
                new ManifestChoice("jenkinsConfig", null, "heroku-node-js-sample", 512, "", 1, 60, false, "",
                        "https://github.com/heroku/heroku-buildpack-nodejs", "", "",
                        envVars, new ArrayList<ServiceName>());
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", false, ExistingAppHandler.getDefault(), manifest);
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build did not display staging logs", log.contains("Downloading and installing node"));

        System.out.println("App URI : " + cf.getAppURIs().get(0));
        String uri = cf.getAppURIs().get(0);
        Request request = Request.Get(uri);
        HttpResponse response = request.execute().returnResponse();
        int statusCode = response.getStatusLine().getStatusCode();
        assertEquals("Get request did not respond 200 OK", 200, statusCode);
        String content = EntityUtils.toString(response.getEntity());
        System.out.println(content);
        assertTrue("App did not send back correct text", content.contains("Hello World!"));
    }

    @Test
    public void testPerformMultiAppManifest() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("multi-hello-java.zip")));
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", false, ExistingAppHandler.getDefault(), ManifestChoice.defaultManifestFileConfig());
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build did not display staging logs", log.contains("Downloaded app package"));

        List<String> appUris = cf.getAppURIs();
        System.out.println("App URIs : " + appUris);

        String uri1 = appUris.get(0);
        Request request1 = Request.Get(uri1);
        HttpResponse response1 = request1.execute().returnResponse();
        int statusCode1 = response1.getStatusLine().getStatusCode();
        assertEquals("Get request for hello-java-1 did not respond 200 OK", 200, statusCode1);
        String content1 = EntityUtils.toString(response1.getEntity());
        System.out.println(content1);
        assertTrue("hello-java-1 did not send back correct text", content1.contains("Hello from"));
        assertEquals(200, client.getApplication("hello-java-1").getMemory());
        String uri2 = appUris.get(1);
        Request request2 = Request.Get(uri2);
        HttpResponse response2 = request2.execute().returnResponse();
        int statusCode2 = response2.getStatusLine().getStatusCode();
        assertEquals("Get request for hello-java-2 did not respond 200 OK", 200, statusCode2);
        String content2 = EntityUtils.toString(response2.getEntity());
        System.out.println(content2);
        assertTrue("hello-java-2 did not send back correct text", content2.contains("Hello from"));
        assertEquals(300, client.getApplication("hello-java-2").getMemory());
    }

    @Test
    public void testPerformCustomManifestFileLocation() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("hello-java-custom-manifest-location.zip")));

        ManifestChoice manifestChoice = new ManifestChoice("manifestFile", "manifest/manifest.yml",
                null, 0, null, 0, 0, false, null, null, null, null, null, null);
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", false, ExistingAppHandler.getDefault(), manifestChoice);
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build did not display staging logs", log.contains("Downloaded app package"));

        System.out.println("App URI : " + cf.getAppURIs().get(0));
        String uri = cf.getAppURIs().get(0);
        Request request = Request.Get(uri);
        HttpResponse response = request.execute().returnResponse();
        int statusCode = response.getStatusLine().getStatusCode();
        assertEquals("Get request did not respond 200 OK", 200, statusCode);
        String content = EntityUtils.toString(response.getEntity());
        System.out.println(content);
        assertTrue("App did not send back correct text", content.contains("Hello from"));
    }

    // All the tests below are failure cases

    @Test
    @WithTimeout(300)
    public void testPerformCustomTimeout() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();

        project.setScm(new ExtractResourceSCM(getClass().getResource("hello-java.zip")));
        List<EnvironmentVariable> envVars = new ArrayList<EnvironmentVariable>();
        envVars.add(new EnvironmentVariable("SSH_CONNECTION", "Hub Port Ip None"));
        ManifestChoice manifest =
                new ManifestChoice("jenkinsConfig", null, "hello-java", 512, "", 0, 1, false,
                        "target/hello-java-1.0.war", "", "", "",
                        envVars, new ArrayList<ServiceName>());
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", false, ExistingAppHandler.getDefault(), manifest);
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build succeeded where it should have failed", build.getResult().isWorseOrEqualTo(Result.FAILURE));
        assertTrue("Build did not display staging logs", log.contains("Downloaded app package"));
        assertTrue("Build did not display proper error message",
                log.contains("ERROR: The application failed to start after"));
    }

    @Test 
    public void testPerformEnvVarsManifestFile() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("python-env.zip")));
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", false, ExistingAppHandler.getDefault(), ManifestChoice.defaultManifestFileConfig());
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build did not display staging logs", log.contains("Downloaded app package"));

        System.out.println("App URI : " + cf.getAppURIs().get(0));
        String uri = cf.getAppURIs().get(0);
        Request request = Request.Get(uri);
        HttpResponse response = request.execute().returnResponse();
        int statusCode = response.getStatusLine().getStatusCode();
        assertEquals("Get request did not respond 200 OK", 200, statusCode);
        String content = EntityUtils.toString(response.getEntity());
        System.out.println(content);
        assertTrue("App did not have correct ENV_VAR_ONE", content.contains("ENV_VAR_ONE: value1"));
        assertTrue("App did not have correct ENV_VAR_TWO", content.contains("ENV_VAR_TWO: value2"));
        assertTrue("App did not have correct ENV_VAR_THREE", content.contains("ENV_VAR_THREE: value3"));
    }

    @Test 
    public void testPerformServicesNamesManifestFile() throws Exception {
        CloudService service1 = new CloudService();
        service1.setName("mysql_service1");
        service1.setLabel("mysql");
        service1.setPlan("free");
        client.createService(service1);

        CloudService service2 = new CloudService();
        service2.setName("mysql_service2");
        service2.setLabel("mysql");
        service2.setPlan("free");
        client.createService(service2);

        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("python-env-services.zip")));
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", false, ExistingAppHandler.getDefault(), ManifestChoice.defaultManifestFileConfig());
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build did not display staging logs", log.contains("Downloaded app package"));

        System.out.println("App URI : " + cf.getAppURIs().get(0));
        String uri = cf.getAppURIs().get(0);
        Request request = Request.Get(uri);
        HttpResponse response = request.execute().returnResponse();
        int statusCode = response.getStatusLine().getStatusCode();
        assertEquals("Get request did not respond 200 OK", 200, statusCode);
        String content = EntityUtils.toString(response.getEntity());
        System.out.println(content);
        assertTrue("App did not have mysql_service1 bound", content.contains("mysql_service1"));
        assertTrue("App did not have mysql_service2 bound", content.contains("mysql_service2"));
    }

    @Test
    public void testPerformNoRoute() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("hello-java.zip")));
        List<EnvironmentVariable> envVars = new ArrayList<EnvironmentVariable>();
        envVars.add(new EnvironmentVariable("SSH_CONNECTION", "Hub Port Ip None"));
        ManifestChoice manifest =
                new ManifestChoice("jenkinsConfig", null, "hello-java", 512, "", 0, 0, true,
                        "target/hello-java-1.0.war", "", "", "",
                        envVars, new ArrayList<ServiceName>());
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", false, ExistingAppHandler.getDefault(), manifest);
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build did not display staging logs", log.contains("Downloaded app package"));

        System.out.println("App URI : " + cf.getAppURIs().get(0));
        String uri = cf.getAppURIs().get(0);
        Request request = Request.Get(uri);
        HttpResponse response = request.execute().returnResponse();
        int statusCode = response.getStatusLine().getStatusCode();
        assertEquals("Get request did not respond 404 Not Found", 404, statusCode);
    }

    @Test
    public void testPerformUnknownHost() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("hello-java.zip")));
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher("https://does-not-exist.local",
                TEST_ORG, TEST_SPACE, "testCredentialsId", false, ExistingAppHandler.getDefault(), null);
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String s = FileUtils.readFileToString(build.getLogFile());
        System.out.println(s);

        assertTrue("Build succeeded where it should have failed", build.getResult().isWorseOrEqualTo(Result.FAILURE));
        assertTrue("Build did not write error message", s.contains("ERROR: Unknown host"));
    }

    @Test
    public void testPerformWrongCredentials() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("hello-java.zip")));

        CredentialsStore store = CredentialsProvider.lookupStores(j.getInstance()).iterator().next();
        store.addCredentials(Domain.global(),
                new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "wrongCredentialsId", "",
                        "wrongName", "wrongPass"));
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "wrongCredentialsId", false, ExistingAppHandler.getDefault(), ManifestChoice.defaultManifestFileConfig());
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String s = FileUtils.readFileToString(build.getLogFile());
        System.out.println(s);

        assertTrue("Build succeeded where it should have failed", build.getResult().isWorseOrEqualTo(Result.FAILURE));
        assertTrue("Build did not write error message", s.contains("ERROR: Wrong username or password"));
    }
    
    private class BGRequester implements Runnable {
    		private String uri = null;
    		
    		private volatile int reqCount = 0;
    		private volatile int successResCount = 0;
    		private volatile int errorResCount = 0;
    		
    		private volatile boolean stop = false;
    		private  boolean doContentValidation = false;
    		private String  contentToValidate = null;
    		
    		public BGRequester(String uri) {
    			this.uri = uri;
    		}
		public void run() {
			
			while(!stop) {
				reqCount++;
				Request request = Request.Get(uri);
				try {
					HttpResponse response = request.execute().returnResponse();
					int statusCode = response.getStatusLine().getStatusCode();
					if(isSuccess(statusCode, validateContent(response)))
					{
						successResCount++;
					} else {
						errorResCount++;
					}
				    
				} catch (Exception e) {
					e.printStackTrace();
					errorResCount++;
					
				}
			}
			
			
		}
		private boolean isSuccess(int statusCode, boolean contentValidationSuccess) {
			return ((statusCode==200) && (contentValidationSuccess));
		}
		
		private boolean validateContent(HttpResponse response ) throws ParseException, IOException {
			if(!doContentValidation) {
				return true;
			}
			String content = EntityUtils.toString(response.getEntity());
			return content.contains(contentToValidate);
		}
		public int getReqCount() {
			return reqCount;
		}
		
		public int getSuccessResCount() {
			return successResCount;
		}
		
		public int getErrorResCount() {
			return errorResCount;
		}
		
		public void stop() {
			this.stop = true;
		}
	
		public void doContentValidation(boolean validate) {
			this.doContentValidation = validate;
		}
		
		public void setContentToValidate(String contentToValidate) {
			this.contentToValidate = contentToValidate;
		}
    	
    }
    
    @Test
	public void testPerformBGDeploy() throws Exception {

		FreeStyleProject project = j.createFreeStyleProject();
		project.setScm(new ExtractResourceSCM(getClass().getResource(
				"hello-java.zip")));
		EnvironmentVariable sshConnENVv1 = new EnvironmentVariable(
				"SSH_CONNECTION", "Hub Port 1.1.1.1 None");
		List<EnvironmentVariable> envVars = new ArrayList<EnvironmentVariable>();
		envVars.add(sshConnENVv1);

		ManifestChoice manifest = new ManifestChoice("jenkinsConfig", null,
				"hello-java", 512, "", 0, 0, false,
				"target/hello-java-1.0.war", "", "", "", envVars,
				new ArrayList<ServiceName>());
		CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(
				TEST_TARGET, TEST_ORG, TEST_SPACE, "testCredentialsId", false,
				new ExistingAppHandler(ExistingAppHandler.Choice.BGDEPLOY.toString(), false), manifest);

		project.getPublishersList().add(cf);

		FreeStyleBuild build = project.scheduleBuild2(0).get();
		System.out.println(build.getDisplayName() + " completed");
		String log = FileUtils.readFileToString(build.getLogFile());
		System.out.println(log);

		assertTrue("Blue Deployment Build did not succeed", build.getResult()
				.isBetterOrEqualTo(Result.SUCCESS));
		assertTrue("Blue Deployment Build did not display staging logs",
				log.contains("Downloaded app package"));

		// Verifying Orig Route to Orig App Is Correct
		System.out.println("Blue App URI : " + cf.getAppURIs().get(0));

		String uri = cf.getAppURIs().get(0);
		Request request = Request.Get(uri);
		HttpResponse response = request.execute().returnResponse();
		int statusCode = response.getStatusLine().getStatusCode();
		assertEquals("Blue App  Get request did not respond 200 OK", 200,
				statusCode);
		String content = EntityUtils.toString(response.getEntity());
		System.out.println(content);
		assertTrue("Blue App did not send back correct text",
				content.contains("1.1.1.1"));

		// Start Another Thread that continously queries app
		// This thread keeps a counter of # requests sent, success and failures
		// Now initiate another build, this time it will be a BG deployment
		BGRequester requester = new BGRequester(uri);
		Thread requesterThread = new Thread(requester);
		requesterThread.start();

		//Sleep for 5 seconds before doing another push
		Thread.sleep(5000);
		
		// Update Env Var
		envVars.clear();
		EnvironmentVariable sshConnENVv2 = new EnvironmentVariable(
				"SSH_CONNECTION", "Hub Port 2.2.2.2 None");
		envVars.add(sshConnENVv2);

		build = project.scheduleBuild2(0).get();
		System.out.println(build.getDisplayName() + " completed");
		log = FileUtils.readFileToString(build.getLogFile());
		System.out.println(log);
		assertTrue("Green Deployment Build did not succeed", build.getResult()
				.isBetterOrEqualTo(Result.SUCCESS));
		assertTrue("Green Deployment Build did not display staging logs",
				log.contains("Downloaded app package"));
		
		// Wait for 5 sec
		// Now stop the other thread and validate that there are no errors.
		Thread.sleep(5000);
		requester.stop();
		requesterThread.join();

		assertTrue (requester.getReqCount() > 0);
		assertTrue (requester.getSuccessResCount() > 0);
		assertTrue (requester.getErrorResCount() == 0);

		// Verifying New Route of Green Deployment does not exist
		System.out.println("Green App URI : " + cf.getAppURIs().get(1));
		String greenUri = cf.getAppURIs().get(1);
		request = Request.Get(greenUri);
		response = request.execute().returnResponse();
		statusCode = response.getStatusLine().getStatusCode();
		assertEquals("Green App Get request did not respond 404 Not Found",
				404, statusCode);
		
		requester = new BGRequester(uri);
		requester.setContentToValidate("2.2.2.2");
		requester.doContentValidation(true);
		Thread requesterThread1 = new Thread(requester);
		requesterThread1.start();
		Thread.sleep(10000);
		requester.stop();
		requesterThread1.join();

		assertTrue (requester.getReqCount() > 0);
		assertTrue (requester.getSuccessResCount() > 0);
		assertEquals ("There are error responses for the new version of the app.",0,requester.getErrorResCount());
		
		//TODO:  Additinal verifications for blue app not present.
		// Check for blueApp_old if the retainOrigApp setting is true
		

	}

}
