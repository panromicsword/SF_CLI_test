## JenkinsAuthorizer

### Example

~~~~
import it.sky.dp.jenkins.jenkinsauthorizer.*

node{

    List devopsAlloweds = ["dev", "test", "stage", "prod"]
    List devAlloweds = ["dev", "test", "stage"]    
    result = new JenkinsAuthorizer(this, env).getAuthorizedTargetEnvsForLoggedUser(devAlloweds, devopsAlloweds)
    if(!result.contains("${TARGET_ENV}")){
        currentBuild.result = 'ABORTED'
        error('Missing privileges')
    }

}
~~~~

