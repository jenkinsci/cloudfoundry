/**
 * © Copyright 2015 Hewlett Packard Enterprise Development LP
 * Copyright (c) ActiveState 2014 - ALL RIGHTS RESERVED.
 */

package com.hpe.cloudfoundryjenkins;

import hudson.FilePath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ManifestReaderTest {

    private static final String PROJECT_VERSION = System.getProperty("project.version");

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Test
    public void testGetApplicationInfo() throws Exception {
        File manifestFile = new File(getClass().getResource("hello-java-manifest.yml").toURI());
        FilePath manifestFilePath = new FilePath(manifestFile);
        ManifestReader reader = new ManifestReader(manifestFilePath);
        Map<String, Object> result = reader.getApplicationInfo();
        assertEquals("hello-java", result.get("name"));
        assertEquals("512M", result.get("memory"));
        assertEquals("target" + File.separator + "hello-java-1.0.war", result.get("path"));
    }

    @Test
    public void testGetApplicationInfoEnvVars() throws Exception {
        File manifestFile = new File(getClass().getResource("env-vars-manifest.yml").toURI());
        FilePath manifestFilePath = new FilePath(manifestFile);
        ManifestReader reader = new ManifestReader(manifestFilePath);
        Map<String, Object> result = reader.getApplicationInfo();
        assertEquals("hello-java", result.get("name"));
        assertEquals("512M", result.get("memory"));
        assertEquals("target" + File.separator + "hello-java-1.0.war", result.get("path"));
        @SuppressWarnings("unchecked")
        Map<String, String> envVars = (Map<String, String>) result.get("env");
        assertEquals("value1", envVars.get("ENV_VAR_ONE"));
        assertEquals("value2", envVars.get("ENV_VAR_TWO"));
        assertEquals("value3", envVars.get("ENV_VAR_THREE"));
    }

    @Test
    public void testGetApplicationInfoServicesNames() throws Exception {
        File manifestFile = new File(getClass().getResource("services-names-manifest.yml").toURI());
        FilePath manifestFilePath = new FilePath(manifestFile);
        ManifestReader reader = new ManifestReader(manifestFilePath);
        Map<String, Object> result = reader.getApplicationInfo();
        assertEquals("hello-java", result.get("name"));
        assertEquals("512M", result.get("memory"));
        assertEquals("target" + File.separator + "hello-java-1.0.war", result.get("path"));
        @SuppressWarnings("unchecked")
        List<String> servicesNames = (List<String>) result.get("services");
        assertTrue(servicesNames.contains("service1"));
        assertTrue(servicesNames.contains("service2"));
        assertTrue(servicesNames.contains("service3"));
    }

    @Test
    public void testGetApplicationInfoMalformedYML() throws Exception {
        exception.expect(ManifestParsingException.class);
        exception.expectMessage("Malformed YAML file");
        File manifestFile = new File(getClass().getResource("malformed-manifest.yml").toURI());
        FilePath manifestFilePath = new FilePath(manifestFile);
        new ManifestReader(manifestFilePath);
    }

    @Test
    public void testGetApplicationInfoNotAMap() throws Exception {
        exception.expect(ManifestParsingException.class);
        exception.expectMessage("Could not parse the manifest file into a map");
        File manifestFile = new File(getClass().getResource("not-a-map-manifest.yml").toURI());
        FilePath manifestFilePath = new FilePath(manifestFile);
        new ManifestReader(manifestFilePath);
    }

    @Test
    public void testGetApplicationInfoNoApplicationBlock() throws Exception {
        exception.expect(ManifestParsingException.class);
        exception.expectMessage("Manifest file does not start with an 'applications' block");
        File manifestFile = new File(getClass().getResource("no-application-block-manifest.yml").toURI());
        FilePath manifestFilePath = new FilePath(manifestFile);
        new ManifestReader(manifestFilePath);
    }

    @Test
    public void testGetApplicationInfoWrongAppName() throws Exception {
        exception.expect(ManifestParsingException.class);
        exception.expectMessage("Manifest file does not contain an app named goodbye-java");
        File manifestFile = new File(getClass().getResource("hello-java-manifest.yml").toURI());
        FilePath manifestFilePath = new FilePath(manifestFile);
        ManifestReader reader = new ManifestReader(manifestFilePath);
        reader.getApplicationInfo("goodbye-java");
    }
}
