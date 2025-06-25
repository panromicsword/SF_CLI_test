# Jenkins Shared Library

This repository contains a collection of *.groovy files used as function libraries for our Jenkins CI Server. 

    .
    ├── README.md                        # This file
    ├── .gitignore                       # gitignore
    ├── src/it/sky/dp                    
    |   ├── jenkins                      # Jenkins shared library package 
    |       ├── annotations              # Monitoring annotations manager classes
    |       ├── env                      # Environment injector classes
    |       ├── jenkinsauthorizer        # Pipeline authorizer classes
    |       ├── slack                    # Slack utils classes
    |       ├── nexus                    # Nexus utils classes
    |       ├── swagger                  # Swagger utils classes

### Installation
Within Jenkins console go to Manage Jenkins » Configure System » Global Pipeline Libraries
![N|Installation](https://jenkins.io/doc/book/resources/pipeline/add-global-pipeline-libraries.png)
![N|Installation](https://jenkins.io/doc/book/resources/pipeline/configure-global-pipeline-library.png)



### ATTENTION!!
 - If the Load Implicit option is flagged so the changelog flag MUST be set to FALSE (as shown in the next example)otherwise each push in the jenkins-ci-library will trigger the job build!!
 - The SCM should be configured with Legacy SCM as workaround to the ISSUE https://issues.jenkins-ci.org/browse/JENKINS-39615

### Example of usage
```java
// N.B. IMPORTANT!!!!
// The changelog flag MUST be set to FALSE otherwise each push in the jenkins-ci-library will trigger the job build!!
@Library(value="jenkins-ci-library", changelog=false)
import it.sky.dp.jenkins.nexus.*;
import it.sky.dp.jenkins.slack.*;
node{
    def slackInfo = new SlackInfo("${SLACK_URL}", "${SLACK_TOKEN_CREDENTIAL_ID}", "${SLACK_CHANNEL}")
    def slackNotifier = new SlackNotifier(this, env, currentBuild, slackInfo)
    try{
        slackNotifier.notifyBuildStarted()    
        def nexusUploader = new NexusUploader(this, "${NEXUS_URL}",'nexus-repository-name')
        nexusUploader.uploadArtifact('it.sky.dp', 'sample', '1.0.0','dev', 'sample.zip', 'zip')
    }catch(Exception e){
        println(e.getMessage())
        currentBuild.result = "FAILURE"
        notifyBuildFailed("${currentBuild.duration}")
    }finally{
        notifyBuildSuccess("${currentBuild.duration}")
    }
}
```
### Other resources
https://jenkins.io/doc/book/pipeline/shared-libraries/
