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
