package it.sky.dp.jenkins.env
import groovy.json.JsonSlurperClassic
import groovy.json.JsonBuilder

class EnvironmentLoader {

    def steps
    def envFile
    def env

    EnvironmentLoader(steps, env, envFile) {
        this.steps = steps
        this.envFile = envFile
        this.env = env
    }

    public void load(){
        def envJson = steps.sh(returnStdout: true, script: "cat ${envFile}")
        Map envsMap =  new JsonSlurperClassic().parseText(envJson)
        envsMap.each{ k, v ->
          env."${k}" = "${v}"
        }
        steps.sh("printenv")    
    }

}
