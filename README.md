Cloud Foundry Jenkins plugin
============================

This plugin can push apps to a Cloud Foundry platform at the end of a Jenkins build. You can either use the 
configuration of a manifest.yml file, or write your settings in the Jenkins build's configuration page.

**For usage information and changelog, see the
[Jenkins Wiki page](https://wiki.jenkins-ci.org/display/JENKINS/Cloud+Foundry+Plugin).**

**For reporting an issue, please use the
[Jenkins issue tracker](https://issues.jenkins-ci.org/browse/JENKINS/component/19824/).**

Installing:
-----------
Due to conflicts between the versions of Spring used by Jenkins and the CF Java client, this plugin uses a modified
version of the CF Java client with shaded libraries.

In order to avoid the use of a `mvn install-file` command on every new machine, this git repository contains a local
Maven repository in the `lib` subfolder, with the shaded library already installed. This allows building the plugin in
a single `mvn install` command.

The command that was used to install the library to the local Maven repository is (from the root of the project):

```
mvn org.apache.maven.plugins:maven-install-plugin:2.5.1:install-file \
-Dfile=cloudfoundry-client-lib-shaded-1.1.3.jar \
-DlocalRepositoryPath=lib
```

You do not need to use this command since it has already been done, and the shaded jar is now in the `lib` folder.

Debugging:
----------
This will launch a Jenkins instance for you with the plugin pre-installed. The Jenkins files will be stored in the
`work` folder.
The Jenkins instance will be accessible at http://localhost:8090/jenkins.

```
mvn hpi:run -Djetty.port=8090
```

You can also enable remote debugging at port 8000 by setting some Maven options before running the previous command:

```
export MAVEN_OPTS="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,address=8000,suspend=n"
```

Packaging:
----------

Prior to running the tests, you will need to install the test apps into your local
Maven repository. To do this, you simply run `mvn install` on each of the apps
in the `test-apps` folder.

If you already have a working Jenkins instance, use this command to create an .hpi file. You can then upload it to your
Jenkins instance. From the `cloudfoundry-jenkins-plugin` folder:

```
mvn clean install
```

By default, the integration tests expect a running Cloud Foundry platform available at `api.local.pcfdev.io`, with
username `user` and password `pass`. If you have a different Cloud Foundry platform and want to run the tests
against that platform instead, you will need to specify some arguments in your Maven command:

```
mvn test -Dcloudfoundry.target=<target fqdn> -Dcloudfoundry.username=<username> -Dcloudfoundry.password=<password> -Dcloudfoundry.org=<org> -Dcloudfoundry.space=<space>
```

The tests will remove all existing applications and services in that space.

Releasing:
----------

You must not have any unstaged changes.

After adding your jenkins-ci.org username and password in ~/.m2/settings.xml, do:

```
mvn org.apache.maven.plugins:maven-release-plugin:2.5:prepare org.apache.maven.plugins:maven-release-plugin:2.5:perform
```

The full artifact name and version is needed to avoid a bug.

Enter the release version, the release tag (default should be good) and the new version name (which can be changed
later, but must end with SNAPSHOT).

If at any point you have to start over, use `mvn release:clean` beforehand. You'll probably also need to delete the new
tag locally (and maybe on the remote) and reset to the latest commit.

The new version should appear [on this page](http://repo.jenkins-ci.org/releases/org/jenkins-ci/plugins/cloudfoundry/)
immediately, otherwise it means the release failed. If it worked, the new version will be available on Jenkins after
~12h. You will see the new version [on this page](http://updates.jenkins-ci.org/update-center.json) once it is available
to download.
