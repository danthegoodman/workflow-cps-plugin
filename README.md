## Instructions

    mvn package -DskipTests
    
    scp target/workflow-cps.jar BUILD_SERVER:

## Overwriting plugin on build server:

    ssh BUILD_SERVER
    sudo -i
    cd ~jenkins/plugins/workflow-cps/WEB-INF/lib/
    mv workflow-cps.jar workflow-cps.jar.orig
    mv ~danny/workflow-cps.jar .
    chown jenkins:jenkins workflow-cps.jar
    service jenkins restart

## About

This branch disables the pipeline script sandbox for scripts running from a jenkinsfile.

See also: https://issues.jenkins-ci.org/browse/JENKINS-28178
