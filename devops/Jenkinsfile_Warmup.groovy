#!/usr/bin/env groovy
@Library(value = "jenkins-ci-library@acn_salesforce_kubernetes_pipelines", changelog=false)

import it.sky.dp.jenkins.salesforce.common.Configuration
import it.sky.dp.jenkins.salesforce.common.Utils
import it.sky.dp.jenkins.email.EmailNotification

import it.sky.dp.jenkins.salesforce.process.SalesforceWarmupProcess

import static it.sky.dp.jenkins.salesforce.Constants.*

Configuration cfg = Configuration.getInstance()
cfg.setDsl(this)
def userInputActivate="NO"
def userInputDeactivate="NO"

SalesforceWarmupProcess process = new SalesforceWarmupProcess(this)
EmailNotification emailNotification

pipeline {
    agent { kubernetes(k8sAgent(cloud: 'deploy', podTemplate: 'warmup-salesforce')) }

    environment {
        PROJECT_NAME = 'WARMUP_SKY'
        PROJECT_URL = " https://github.com/sky-uk/SFDC_Arcadia/"
    }

    stages {
        stage("Cleanup workspace") {
            steps {
                container('warmup-salesforce'){
                    script {
                        def stage = cfg.setLastStage(env.STAGE_NAME)
                        emailNotification = new EmailNotification(this, env, currentBuild)
                    }
                    cleanWs()
                }
            }
        }
        
        stage ("Ask for PDC aol activation") {
            steps {
                container('warmup-salesforce'){               
                    script {
                        cfg.setLastStage(env.STAGE_NAME)
                        def env = params.TARGET_ENVIRONMENT.toUpperCase()

                        //emailNotification.emailAlertUser(cfg.getMapValue(MAP_KEY_SENDER),cfg.getMapValue(MAP_KEY_RECIPIENT))
                    
                        userInputActivate = input(
                                            message: 'Starting pipeline in env: ' + env + ' - Has manual activation of PDC aol been executed ?',
                                            parameters: [
                                                    [$class: 'ChoiceParameterDefinition',
                                                    choices: ['NO','YES'].join('\n'),
                                                    name: 'input',
                                                    description: 'Select box option']
                                            ])       

                        if( userInputActivate == "YES"){
                            cfg.setMapValue(MAP_KEY_ACTIVATE_AOL,userInputActivate)
                        }                       
                    }
                }
            }
        }


        stage("Init variables") {
            when {
                expression { cfg.getMapValue("MAP_KEY_ACTIVATE_AOL")=="YES" }
            }
            steps {
                container('warmup-salesforce'){
                    script {
                        def stage = cfg.setLastStage(env.STAGE_NAME)
                        Utils util = new Utils(this)
                        String buildUser = util.getBuildUser()
                        cfg.addEntryToMap(MAP_KEY_BUILD_USER_ID, buildUser, true)
                        
                        /*
                        String slackMessage =  "Build parameters:\n" +
                                "------------------------\n" +
                                "${MAP_KEY_BRANCH_NAME}: ${params.BRANCH_NAME}\n" +
                                "${MAP_KEY_TARGET_ENVIRONMENT}: ${params.TARGET_ENVIRONMENT}\n" +
                                "BUILD_USER_ID: ${buildUser}\n" +
                                "------------------------\n" +
                                "<${env.RUN_DISPLAY_URL}|${MESSAGE_OPEN_BLUE_OCEAN}>"
                        echo("slackMessage-> ${slackMessage}")
                        */

                        // set input variables to config map
                        cfg.addEntryToMap(MAP_KEY_BRANCH_NAME, params.BRANCH_NAME, true)
                        cfg.addEntryToMap(MAP_KEY_TARGET_ENVIRONMENT, params.TARGET_ENVIRONMENT, true)
                        cfg.addEntryToMap(MAP_KEY_TYPE_EXECUTION, params.TYPE_EXECUTION, true)
                        cfg.addEntryToMap(MAP_KEY_CLEANCACHEDAPIRESPONSEFULL, params.CLEAN_CACHED_API_RESPONSE_FULL, true)
                        cfg.addEntryToMap(MAP_KEY_CLEANCACHEDAPIRESPONSEPARTIAL, params.CLEAN_CACHED_API_RESPONSE_PARTIAL, true)
                        cfg.addEntryToMap(MAP_KEY_ACTIVEMAINTENANCEMODE, params.ACTIVE_MAINTENANCE_MODE, true)
                        cfg.addEntryToMap(MAP_KEY_APEXINVOKE, params.APEX_INVOKE, true)
                        cfg.addEntryToMap(MAP_KEY_CACHEORCHESTRATOR, params.CACHE_ORCHESTRATOR, true)
                        cfg.addEntryToMap(MAP_KEY_DEACTIVATEMAINTENANCEMODE, params.DEACTIVE_MAINTENANCE_MODE, true)
                        cfg.addEntryToMap(MAP_KEY_FLUSHCACHEAWS, params.FLUSH_CACHE_AWS, true)
                        cfg.addEntryToMap(MAP_KEY_WARMUPWSCBB, params.WARMUP_WSC_BB, true)
                        cfg.addEntryToMap(MAP_KEY_WARMUPTOPSTRATEGY, params.WARMUP_TOP_STRATEGY, true) //no partial
                        cfg.addEntryToMap(MAP_KEY_WARMUPLLAMA, params.WARMUP_LLAMA, true)   //no partial                   
                        cfg.addEntryToMap(MAP_KEY_MOTORINO3P, params.MOTORINO_3P, true) //no partial  
                        cfg.addEntryToMap(MAP_KEY_WARMUWSC, params.WARMUP_WSC, true)   //no partial  
                        cfg.addEntryToMap(MAP_KEY_WARMUNSPI, params.WARMUP_NSPI, true)   //no partial  
                        cfg.addEntryToMap(MAP_KEY_ACTIVATE_AOL, userInputActivate, true)                
                        cfg.addEntryToMap(MAP_KEY_DEACTIVATE_AOL, userInputDeactivate, true)                
                        
                        process.initVariables()
                        
                    }
                }
            }
        }
    

      stage("Checkout source") {
            when {
                expression { cfg.getMapValue("MAP_KEY_ACTIVATE_AOL")=="YES" }
            }
            steps {
                container('warmup-salesforce'){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)
                        process.checkoutSources()
                       
                    }
                }
            }
        }

        //DOCKER IMAGE VIENE PUSHATA DAL TEAM DI INFRA E PUO' ESSERE DIRETTAMENTE UTILIZZATA 
 /* 
        stage("Clean CachedAPIResponse & Active Maintenance Mode") {
            parallel {
                    stage("Clean CachedAPIResponse") {
                        steps {
                               echo 'Clean CachedAPIResponse' 
                        }
                    }
                    stage("Active Maintenance Mode") {
                        steps {
                        echo 'Active Maintenance Mode' 
                        }
                    }
            }
        }

           stage ("Apex Invoke") {
            steps {
                 echo 'Apex Invoke' 
            }
        }
         stage ("Run Cache Orchestrator") {
            steps {
                 echo 'Run Cache Orchestrator' 
            }
        }

        stage ("Deactivate Maintenance Mode") {
            steps {
                echo 'Deactivate Maintenance Mode' 
            }
        }


        stage("Flush Cache AWS & Warmup") {
            parallel {
                stage("Flush Cache AWS") {
                    steps {
                        echo 'Flush Cache AWS' 
                    }
                }
                stage("Warmup WSC BB") {
                    steps {
                        echo 'Warmup Top Strategy'
                    }
                }
                stage("Warmup Top Strategy") {
                    steps {
                        echo "Warmup Top Strategy"
                    }
                }
            }
        }
        */
        stage ("Ask for PDC aol deactivation") {
            steps {
                container('warmup-salesforce'){               
                    script {
                        cfg.setLastStage(env.STAGE_NAME)
                        def env = params.TARGET_ENVIRONMENT.toUpperCase()

                        //emailNotification.emailAlertUser(cfg.getMapValue(MAP_KEY_SENDER),cfg.getMapValue(MAP_KEY_RECIPIENT))
                    
                        userInputDeactivate = input(
                                            message: 'Pipeline in env: ' + env + ' - Has manual deactivation of PDC aol been executed ?',
                                            parameters: [
                                                    [$class: 'ChoiceParameterDefinition',
                                                    choices: ['NO','YES'].join('\n'),
                                                    name: 'input',
                                                    description: 'Select box option']
                                            ])       

                        if( userInputDeactivate == "YES"){
                            cfg.setMapValue(MAP_KEY_DEACTIVATE_AOL,userInputDeactivate)
                        }                       
                    }
                }
            }
        }

        stage ("Run Motorino 3p & Warmup WSC") {
            parallel {
          /*      stage("Warmup Llama") {
                    steps {
                        echo 'Warmup Llama'
                    }
                }
            */  stage("Run Motorino 3p") {
                    when {
                        expression { cfg.getMapValue("MAP_KEY_MOTORINO3P")==true && currentBuild.currentResult == 'SUCCESS' && cfg.getMapValue("MAP_KEY_DEACTIVATE_AOL")=="YES" }
                    }     
                    steps {
                        container('warmup-salesforce'){
                            script {
                                cfg.setLastStage(env.STAGE_NAME)
                                echo "MOTORINO"
                                echo "Versione Newman:"
                                sh("newman -v")
                                echo "---------------"
                                process.motorino3p()

                            }
                        }
                    }
                }
            /*    stage("Warmup WSC") {
                    steps {
                        echo 'Warmup WSC'
                    }
                }
                stage("Warmup NSPI") {
                    steps {
                        echo 'Warmup NSPI'
                    }
                }*/
            }
        }

    }

    post {
        always {
            echo '### FINALLY'
            echo "Configuration->\n\n" + cfg.toString() + "\n"
            /*script {
               // process.updateElasticSearch()
            }*/
        }
        success {
            echo '### SUCCESS'
            script {
                //TO DO: Per ogni step aggiungere controllo che MAP_KEY_RECIPIENT non sia null o concordare con il cliente un destinatario di default
               
               emailNotification.emailSuccess(cfg.getMapValue(MAP_KEY_SENDER),cfg.getMapValue(MAP_KEY_RECIPIENT))
            }
        }
        unstable {
            echo '### UNSTABLE'
            script {
               emailNotification.emailWarning(cfg.getMapValue(MAP_KEY_SENDER),cfg.getMapValue(MAP_KEY_RECIPIENT))
            }
        }
        failure {
            echo '### FAILURE'
            script {
              
                emailNotification.emailFailed(cfg.getMapValue(MAP_KEY_SENDER),cfg.getMapValue(MAP_KEY_RECIPIENT))
            }
        }
        aborted {
            echo '### ABORTED'
            script {
                emailNotification.emailFailed(cfg.getMapValue(MAP_KEY_SENDER),cfg.getMapValue(MAP_KEY_RECIPIENT))
            }
        }
    }
}
