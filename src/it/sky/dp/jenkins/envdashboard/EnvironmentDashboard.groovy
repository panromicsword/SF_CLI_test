package it.sky.dp.jenkins.envdashboard

class EnvironmentDashboard {
  def steps
  private String jobName
  private String targetEnv
  private String projectName
  private String projectVersion

  EnvironmentDashboard(steps, env, projectName, targetEnv, projectVersion){
    this.steps          = steps
    this.jobName        = env.JOB_NAME
    this.projectName    = projectName
    this.targetEnv      = targetEnv
    this.projectVersion = projectVersion
  }

  def update(){
    steps.build(job: 'EnvironmentDashboardUpdate', parameters: [steps.string(name: 'TARGET_ENV', value: "${targetEnv}"), steps.string(name: 'PROJECT_NAME', value: "${projectName}"), steps.string(name: 'PROJECT_VERSION', value: "${projectVersion}"), steps.string(name: 'SOURCE_JOB', value: "${jobName}")], propagate: false, wait: false)
  }

}
