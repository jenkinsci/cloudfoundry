/**
 * © Copyright 2015 Hewlett Packard Enterprise Development LP
 * Copyright (c) ActiveState 2014 - ALL RIGHTS RESERVED.
 */

package com.hpe.cloudfoundryjenkins;

import com.hpe.cloudfoundryjenkins.CloudFoundryPushPublisher.EnvironmentVariable;
import com.hpe.cloudfoundryjenkins.CloudFoundryPushPublisher.ManifestChoice;
import com.hpe.cloudfoundryjenkins.CloudFoundryPushPublisher.ServiceName;
import com.hpe.cloudfoundryjenkins.CloudFoundryPushPublisher.Service;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.ProxyConfiguration;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import org.apache.commons.io.FileUtils;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.cloudfoundry.client.CloudFoundryClient;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.jvnet.hudson.test.ExtractResourceSCM;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.WithTimeout;

import java.io.IOException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.cloudfoundry.client.v2.applications.DeleteApplicationRequest;
import org.cloudfoundry.client.v2.routes.DeleteRouteRequest;
import org.cloudfoundry.client.v2.servicebindings.DeleteServiceBindingRequest;
import org.cloudfoundry.client.v2.servicebindings.ListServiceBindingsRequest;
import org.cloudfoundry.client.v2.serviceinstances.DeleteServiceInstanceRequest;
import org.cloudfoundry.doppler.DopplerClient;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.routes.Level;
import org.cloudfoundry.operations.routes.ListRoutesRequest;
import org.cloudfoundry.operations.services.CreateServiceInstanceRequest;
import org.cloudfoundry.reactor.ConnectionContext;
import org.cloudfoundry.reactor.DefaultConnectionContext;
import org.cloudfoundry.reactor.TokenProvider;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.cloudfoundry.reactor.doppler.ReactorDopplerClient;
import org.cloudfoundry.reactor.tokenprovider.PasswordGrantTokenProvider;
import org.cloudfoundry.reactor.uaa.ReactorUaaClient;
import org.cloudfoundry.uaa.UaaClient;
import org.junit.AfterClass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;
import org.junit.ClassRule;
import reactor.core.publisher.Flux;

public class CloudFoundryPushPublisherTest {

    private static final String TEST_TARGET = System.getProperty("target");
    private static final String TEST_USERNAME = System.getProperty("username");
    private static final String TEST_PASSWORD = System.getProperty("password");
    private static final String TEST_ORG = System.getProperty("org");
    private static final String TEST_SPACE = System.getProperty("space");
    private static final String TEST_MYSQL_SERVICE_TYPE = System.getProperty("mysqlServiceType", "mysql");
    private static final String TEST_NONMYSQL_SERVICE_TYPE = System.getProperty("nonmysqlServiceType", "filesystem");
    private static final String TEST_NONMYSQL_SERVICE_PLAN = System.getProperty("nonmysqlServicePlan", "free-local-disk");
    private static final String TEST_SERVICE_PLAN = System.getProperty("servicePlan", "free");

    private static CloudFoundryClient client;

    private static CloudFoundryOperations cloudFoundryOperations;

    private static HttpClient httpClient;

    @ClassRule
    public static JenkinsRule j = new JenkinsRule();

    private static Optional<org.cloudfoundry.reactor.ProxyConfiguration> buildProxyConfiguration(URL targetURL) {
        ProxyConfiguration proxyConfig = j.getInstance().proxy;
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

        ConnectionContext connectionContext = DefaultConnectionContext.builder()
                .apiHost(targetUrl.getHost())
                .proxyConfiguration(buildProxyConfiguration(targetUrl))
                .skipSslValidation(true)
                .build();

        TokenProvider tokenProvider = PasswordGrantTokenProvider.builder()
            .username(TEST_USERNAME)
            .password(TEST_PASSWORD)
            .build();

        client = ReactorCloudFoundryClient.builder()
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

        cloudFoundryOperations = DefaultCloudFoundryOperations.builder()
            .cloudFoundryClient(client)
            .dopplerClient(dopplerClient)
            .uaaClient(uaaClient)
            .organization(TEST_ORG)
            .space(TEST_SPACE)
            .build();
    }

    @BeforeClass
    public static void setupHttpClient() throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
      SSLContextBuilder builder = new SSLContextBuilder();
      builder.loadTrustMaterial(null, new TrustSelfSignedStrategy());
      SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(
              builder.build());

      httpClient = HttpClients.custom().setSSLSocketFactory(sslsf).build();
    }

    @AfterClass
    public static void cleanCloudSpace() throws IOException {
        cloudFoundryOperations.routes()
            .list(ListRoutesRequest.builder().level(Level.SPACE).build())
            .map(route -> DeleteRouteRequest.builder().routeId(route.getId()).build())
            .flatMap(request -> client.routes().delete(request))
            .blockLast();

        client.serviceBindingsV2().list(ListServiceBindingsRequest.builder().build())
            .flatMapIterable(serviceBindingsResponse -> serviceBindingsResponse.getResources())
            .map(resource -> DeleteServiceBindingRequest.builder().serviceBindingId(resource.getMetadata().getId()).build())
            .flatMap(request -> client.serviceBindingsV2().delete(request))
            .blockLast();

        cloudFoundryOperations.applications()
            .list()
            .map(application -> DeleteApplicationRequest.builder().applicationId(application.getId()).build())
            .flatMap(request -> client.applicationsV2().delete(request))
            .blockLast();

        cloudFoundryOperations.services()
            .listInstances()
            .map(service -> DeleteServiceInstanceRequest.builder().serviceInstanceId(service.getId()).build())
            .flatMap(request -> client.serviceInstances().delete(request))
            .blockLast();
    }

    private static List<String> getAppURIs(String appName) {
      return cloudFoundryOperations.applications()
          .list()
          .filter(app -> app.getName().equals(appName))
          .map(app -> app.getUrls())
          .flatMap(Flux::fromIterable)
          .map(fqdn -> "https://" + fqdn)
          .collectList()
          .block();
    }

    @Before
    public void setupCredentialsAndCleanCloudSpace() throws IOException {
      cleanCloudSpace();
      CredentialsStore store = CredentialsProvider.lookupStores(j.getInstance()).iterator().next();
        store.addCredentials(Domain.global(),
                new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "testCredentialsId", "",
                        TEST_USERNAME, TEST_PASSWORD));
    }

    @Test
    public void testPerformSimplePushManifestFile() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("cloudfoundry-hello-java.zip")));

        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", true, false, 0, null, ManifestChoice.defaultManifestFileConfig());
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build did not display staging logs", log.contains("Downloaded app package"));
        List<String> appURIs = getAppURIs("hello-java");
        System.out.println("App URI : " + appURIs.get(0));
        String uri = appURIs.get(0);
        HttpResponse response = httpClient.execute(new HttpGet(uri));
        int statusCode = response.getStatusLine().getStatusCode();
        assertEquals("Get request did not respond 200 OK", 200, statusCode);
        String content = EntityUtils.toString(response.getEntity());
        System.out.println(content);
        assertTrue("App did not send back correct text", content.contains("Hello from"));
    }

    @Test
    public void testPerformSimplePushJenkinsConfig() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("cloudfoundry-hello-java.zip")));
        ManifestChoice manifest =
                new ManifestChoice("jenkinsConfig", null, "hello-java", "1g", "", null, null, null,
                        "hello-java-2.0.0.war", "", "", "", "",
                        new ArrayList<EnvironmentVariable>(), new ArrayList<ServiceName>());
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", true, false, 0, null, manifest);
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build did not display staging logs", log.contains("Downloaded app package"));

        System.out.println("App URI : " + getAppURIs("hello-java").get(0));
        String uri = getAppURIs("hello-java").get(0);
        HttpResponse response = httpClient.execute(new HttpGet(uri));
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
        project.setScm(new ExtractResourceSCM(getClass().getResource("cloudfoundry-hello-java.zip")));
        ManifestChoice manifest1 =
                new ManifestChoice("jenkinsConfig", null, "hello-java", "1g", "", null, null, null,
                        "hello-java-2.0.0.war", "", "", "", "",
                        new ArrayList<EnvironmentVariable>(), new ArrayList<ServiceName>());
        CloudFoundryPushPublisher cf1 = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", true, true, 0, null, manifest1);
        project.getPublishersList().add(cf1);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " 1 completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build 1 did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build 1 did not display staging logs", log.contains("Downloaded app package"));
        assertEquals((long) 1024, (long) cloudFoundryOperations.applications().get(GetApplicationRequest.builder().name("hello-java").build()).block().getMemoryLimit());

        project.getPublishersList().remove(cf1);

        ManifestChoice manifest2 =
                new ManifestChoice("jenkinsConfig", null, "hello-java", "1g", "", null, null, null,
                        "hello-java-2.0.0.war", "", "", "", "",
                        new ArrayList<EnvironmentVariable>(), new ArrayList<ServiceName>());
        CloudFoundryPushPublisher cf2 = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", true, true, 0, null, manifest2);
        project.getPublishersList().add(cf2);
        build = project.scheduleBuild2(0).get();

        log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build 2 did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build 2 did not display staging logs", log.contains("Downloaded app package"));
        assertEquals((long) 1024, (long) cloudFoundryOperations.applications().get(GetApplicationRequest.builder().name("hello-java").build()).block().getMemoryLimit());
    }

    @Test
    public void testPerformMultipleInstances() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("cloudfoundry-hello-java.zip")));
        ManifestChoice manifest =
                new ManifestChoice("jenkinsConfig", null, "hello-java", "1g", "", "4", null, null,
                        "hello-java-2.0.0.war", "", "", "", "",
                        new ArrayList<EnvironmentVariable>(), new ArrayList<ServiceName>());
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", true, false, 0, null, manifest);
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build did not display staging logs", log.contains("Downloaded app package"));
        assertTrue("Not the correct amount of instances", cloudFoundryOperations.applications().get(GetApplicationRequest.builder().name("hello-java").build()).block().getRunningInstances().equals(4));

        System.out.println("App URI : " + getAppURIs("hello-java").get(0));
        String uri = getAppURIs("hello-java").get(0);
        HttpResponse response = httpClient.execute(new HttpGet(uri));
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
        ManifestChoice manifest =
                new ManifestChoice("jenkinsConfig", null, "heroku-node-js-sample", "512", "", "1", "60", null, "",
                        "https://github.com/heroku/heroku-buildpack-nodejs", "", "", "",
                        new ArrayList<EnvironmentVariable>(), new ArrayList<ServiceName>());
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", true, false, 0, null, manifest);
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build did not display staging logs", log.contains("Downloading and installing node"));

        System.out.println("App URI : " + getAppURIs("heroku-node-js-sample").get(0));
        String uri = getAppURIs("heroku-node-js-sample").get(0);
        HttpResponse response = httpClient.execute(new HttpGet(uri));
        int statusCode = response.getStatusLine().getStatusCode();
        assertEquals("Get request did not respond 200 OK", 200, statusCode);
        String content = EntityUtils.toString(response.getEntity());
        System.out.println(content);
        assertTrue("App did not send back correct text", content.contains("Hello World!"));
    }

    @Test
    public void testPerformMultiAppManifest() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("cloudfoundry-multi-hello-java.zip")));
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", true, false, 0, null, ManifestChoice.defaultManifestFileConfig());
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build did not display staging logs", log.contains("Downloaded app package"));

        List<String> appUris = Stream.concat(getAppURIs("hello-java-1").stream(), getAppURIs("hello-java-2").stream()).collect(Collectors.toList());
        System.out.println("App URIs : " + appUris);

        String uri1 = appUris.get(0);
        HttpResponse response1 = httpClient.execute(new HttpGet(uri1));
        int statusCode1 = response1.getStatusLine().getStatusCode();
        assertEquals("Get request for hello-java-1 did not respond 200 OK", 200, statusCode1);
        String content1 = EntityUtils.toString(response1.getEntity());
        System.out.println(content1);
        assertTrue("hello-java-1 did not send back correct text", content1.contains("Hello from"));
        assertEquals((long) 1024, (long) cloudFoundryOperations.applications().get(GetApplicationRequest.builder().name("hello-java-1").build()).block().getMemoryLimit());
        String uri2 = appUris.get(1);
        HttpResponse response2 = httpClient.execute(new HttpGet(uri2));
        int statusCode2 = response2.getStatusLine().getStatusCode();
        assertEquals("Get request for hello-java-2 did not respond 200 OK", 200, statusCode2);
        String content2 = EntityUtils.toString(response2.getEntity());
        System.out.println(content2);
        assertTrue("hello-java-2 did not send back correct text", content2.contains("Hello from"));
        assertEquals((long) 1024, (long) cloudFoundryOperations.applications().get(GetApplicationRequest.builder().name("hello-java-2").build()).block().getMemoryLimit());
    }

    @Test
    public void testPerformCustomManifestFileLocation() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("cloudfoundry-hello-java-custom-manifest-location.zip")));

        ManifestChoice manifestChoice = new ManifestChoice("manifestFile", "manifest/manifest.yml",
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", true, false, 0, null, manifestChoice);
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build did not display staging logs", log.contains("Downloaded app package"));

        System.out.println("App URI : " + getAppURIs("hello-java").get(0));
        String uri = getAppURIs("hello-java").get(0);
        HttpResponse response = httpClient.execute(new HttpGet(uri));
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

        project.setScm(new ExtractResourceSCM(getClass().getResource("cloudfoundry-hello-java.zip")));
        ManifestChoice manifest =
                new ManifestChoice("jenkinsConfig", null, "hello-java", "1g", "", null, "1", null,
                        "hello-java-2.0.0.war", "", "", "", "",
                        new ArrayList<EnvironmentVariable>(), new ArrayList<ServiceName>());
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", true, false, 0, null, manifest);
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build succeeded where it should have failed", build.getResult().isWorseOrEqualTo(Result.FAILURE));
        assertTrue("Build did not display proper error message",
                log.contains("failed during start"));
    }

    @Test
    //TODO fix race condition.
    public void testPerformEnvVarsManifestFile() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("python-env.zip")));
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", true, false, 0, null, ManifestChoice.defaultManifestFileConfig());
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build did not display staging logs", log.contains("Downloaded app package"));

        System.out.println("App URI : " + getAppURIs("python-env").get(0));
        String uri = getAppURIs("python-env").get(0);
        HttpResponse response = httpClient.execute(new HttpGet(uri));
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
        cloudFoundryOperations.services().createInstance(CreateServiceInstanceRequest.builder()
            .serviceInstanceName("mysql_service1")
            .serviceName(TEST_MYSQL_SERVICE_TYPE)
            .planName(TEST_SERVICE_PLAN)
            .build()).block();

        cloudFoundryOperations.services().createInstance(CreateServiceInstanceRequest.builder()
            .serviceInstanceName("mysql_service2")
            .serviceName(TEST_MYSQL_SERVICE_TYPE)
            .planName(TEST_SERVICE_PLAN)
            .build()).block();

        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("python-env-services.zip")));
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", true, false, 0, null, ManifestChoice.defaultManifestFileConfig());
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build did not display staging logs", log.contains("Downloaded app package"));

        System.out.println("App URI : " + getAppURIs("python-env").get(0));
        String uri = getAppURIs("python-env").get(0);
        HttpResponse response = httpClient.execute(new HttpGet(uri));
        int statusCode = response.getStatusLine().getStatusCode();
        assertEquals("Get request did not respond 200 OK", 200, statusCode);
        String content = EntityUtils.toString(response.getEntity());
        System.out.println(content);
        assertTrue("App did not have mysql_service1 bound", content.contains("mysql_service1"));
        assertTrue("App did not have mysql_service2 bound", content.contains("mysql_service2"));
    }

    @Test
    public void testPerformCreateService() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("cloudfoundry-hello-spring-mysql.zip")));

        Service mysqlService = new Service("mysql-spring", TEST_MYSQL_SERVICE_TYPE, TEST_SERVICE_PLAN, true);
        List<Service> serviceList = new ArrayList<Service>();
        serviceList.add(mysqlService);

        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", true, false, 0, serviceList, ManifestChoice.defaultManifestFileConfig());
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build did not display staging logs", log.contains("Downloaded app package"));

        System.out.println("App URI : " + getAppURIs("hello-spring-mysql").get(0));
        String uri = getAppURIs("hello-spring-mysql").get(0);
        HttpResponse response = httpClient.execute(new HttpGet(uri));
        int statusCode = response.getStatusLine().getStatusCode();
        assertEquals("Get request did not respond 200 OK", 200, statusCode);
        String content = EntityUtils.toString(response.getEntity());
        System.out.println(content);
        assertTrue("App did not send back correct text",
                content.contains("State [id=1, stateCode=MA, name=Massachusetts]"));
    }

    @Test
    public void testPerformResetService() throws Exception {
        cloudFoundryOperations.services().createInstance(CreateServiceInstanceRequest.builder()
            .serviceInstanceName("mysql-spring")
            // Not the right type of service, must be reset for hello-mysql-spring to work
            .serviceName(TEST_NONMYSQL_SERVICE_TYPE)
            .planName(TEST_NONMYSQL_SERVICE_PLAN)
            .build()).block();

        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("cloudfoundry-hello-spring-mysql.zip")));

        Service mysqlService = new Service("mysql-spring", TEST_MYSQL_SERVICE_TYPE, TEST_SERVICE_PLAN, true);
        List<Service> serviceList = new ArrayList<Service>();
        serviceList.add(mysqlService);

        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", true, false, 0, serviceList, ManifestChoice.defaultManifestFileConfig());
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build did not display staging logs", log.contains("Downloaded app package"));

        System.out.println("App URI : " + getAppURIs("hello-spring-mysql").get(0));
        String uri = getAppURIs("hello-spring-mysql").get(0);
        HttpResponse response = httpClient.execute(new HttpGet(uri));
        int statusCode = response.getStatusLine().getStatusCode();
        assertEquals("Get request did not respond 200 OK", 200, statusCode);
        String content = EntityUtils.toString(response.getEntity());
        System.out.println(content);
        assertTrue("App did not send back correct text",
                content.contains("State [id=1, stateCode=MA, name=Massachusetts]"));
    }

    @Test
    public void testPerformNoRoute() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("cloudfoundry-hello-java.zip")));
        ManifestChoice manifest =
                new ManifestChoice("jenkinsConfig", null, "hello-java", "1g", "", null, null, "true",
                        "hello-java-2.0.0.war", "", "", "", "",
                        new ArrayList<EnvironmentVariable>(), new ArrayList<ServiceName>());
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "testCredentialsId", true, false, 0, null, manifest);
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String log = FileUtils.readFileToString(build.getLogFile());
        System.out.println(log);

        assertTrue("Build did not succeed", build.getResult().isBetterOrEqualTo(Result.SUCCESS));
        assertTrue("Build did not display staging logs", log.contains("Downloaded app package"));

        assertTrue("App has a routable URI.", getAppURIs("hello-java").isEmpty());
    }

    @Test
    public void testPerformUnknownHost() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("cloudfoundry-hello-java.zip")));
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher("https://does-not-exist.local",
                TEST_ORG, TEST_SPACE, "testCredentialsId", true, false, 0, null, null);
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String s = FileUtils.readFileToString(build.getLogFile());
        System.out.println(s);

        assertTrue("Build succeeded where it should have failed", build.getResult().isWorseOrEqualTo(Result.FAILURE));
        assertTrue("Build did not write error message", s.contains("java.net.UnknownHostException"));
    }

    @Test
    public void testPerformWrongCredentials() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.setScm(new ExtractResourceSCM(getClass().getResource("cloudfoundry-hello-java.zip")));

        CredentialsStore store = CredentialsProvider.lookupStores(j.getInstance()).iterator().next();
        store.addCredentials(Domain.global(),
                new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "wrongCredentialsId", "",
                        "wrongName", "wrongPass"));
        CloudFoundryPushPublisher cf = new CloudFoundryPushPublisher(TEST_TARGET, TEST_ORG, TEST_SPACE,
                "wrongCredentialsId", true, false, 0, null, ManifestChoice.defaultManifestFileConfig());
        project.getPublishersList().add(cf);
        FreeStyleBuild build = project.scheduleBuild2(0).get();
        System.out.println(build.getDisplayName() + " completed");

        String s = FileUtils.readFileToString(build.getLogFile());
        System.out.println(s);

        assertTrue("Build succeeded where it should have failed", build.getResult().isWorseOrEqualTo(Result.FAILURE));
        assertTrue("Build did not write error message", s.contains("unauthorized: Bad credentials"));
    }
}
