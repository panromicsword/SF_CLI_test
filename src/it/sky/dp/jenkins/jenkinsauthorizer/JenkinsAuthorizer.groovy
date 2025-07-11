package it.sky.dp.jenkins.jenkinsauthorizer

import hudson.*
import hudson.model.*
import hudson.security.*
import com.michelin.cio.hudson.plugins.rolestrategy.*

class JenkinsAuthorizer {
  
  def steps
  def env
  
  JenkinsAuthorizer(steps, env){
    this.steps = steps
    this.env = env
  }

  def getAuthorizedTargetEnvsForLoggedUser(List devAllowedEnvs, List devOpsAllowedEnvs){
    def List result = []
    def jenkinsInstance = Jenkins.getInstance()
    def authStrategy = Hudson.instance.getAuthorizationStrategy()
    if(authStrategy instanceof RoleBasedAuthorizationStrategy){
        def currentUser = Jenkins.getInstance().getUser(Jenkins.getInstance().getItemByFullName(env.JOB_BASE_NAME, Job.class).getBuildByNumber(env.BUILD_ID as int).getCause(Cause.UserIdCause)?.getUserId())?.getId() ?: 'Jenkins'
        RoleBasedAuthorizationStrategy roleAuthStrategy = (RoleBasedAuthorizationStrategy) authStrategy
        RoleMap myRoleMap = roleAuthStrategy.getRoleMap(RoleBasedAuthorizationStrategy.PROJECT);
        
        def sids = myRoleMap.getSidsForRole("devops")
    
        if(sids?.contains(currentUser)) {
            devOpsAllowedEnvs.each {
              env -> result.add(env)
            }
        } else {
            devAllowedEnvs.each {
              env -> result.add(env)
            }
        }
    }
    steps.echo("authorized target env for logged user are: \n" + result.join("\n"))
    return result
  }

}
