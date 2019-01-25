package com.hpe.cloudfoundryjenkins;

import com.gargoylesoftware.htmlunit.HttpMethod;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebRequest;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import jenkins.model.Jenkins;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import java.net.URL;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

public class Security876Test
{
    @ClassRule
    public static JenkinsRule j = new JenkinsRule();

    @Test
    @Ignore("some dependencies issues with spring version the one used by core is too old")
    public void rejected_as_no_access() throws Exception {

        j.jenkins.setCrumbIssuer(null);

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy());
        FreeStyleProject project = j.createFreeStyleProject();

        { // as an user with just read access, I may not be able to leak any credentials
            JenkinsRule.WebClient wc = j.createWebClient();
            wc.getOptions().setThrowExceptionOnFailingStatusCode( false );

            String testConnectionUrl = j.getURL() + "descriptorByName/" + CloudFoundryPushPublisher.class.getName() + "/testConnection";
            WebRequest request = new WebRequest( new URL( testConnectionUrl ), HttpMethod.POST );

            Page page = wc.getPage( request );
            // to avoid trouble, we always validate when the user has not the good permission
            assertThat( page.getWebResponse().getStatusCode(), equalTo( 403 ) );

        }

    }
}
