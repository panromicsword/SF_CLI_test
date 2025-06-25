#!/usr/bin/env groovy
@Library(value = "jenkins-ci-library@acn_salesforce_kubernetes_pipelines", changelog=false)

import it.sky.dp.jenkins.salesforce.common.Configuration
import it.sky.dp.jenkins.salesforce.common.Utils
import it.sky.dp.jenkins.salesforce.process.SalesforceBranchAlignmentProcess
import it.sky.dp.jenkins.slack.SlackInfo
import it.sky.dp.jenkins.slack.SlackNotifier

import static it.sky.dp.jenkins.salesforce.Constants.*

Configuration cfg = Configuration.getInstance()
cfg.setDsl(this)

SalesforceBranchAlignmentProcess process = new SalesforceBranchAlignmentProcess(this)
SlackNotifier slackNotifier

/* Parametri pipeline (NOME - TIPOLOGIA - VALORI DEFAULT/CHOICE )
Parametro            ;Tipo                   ;Valori                                                      ;Default          ;Descrizione
BRANCH_CONFIGURATION ; multi-line            ;                                                            ;                 ;
*/

pipeline {
    agent { kubernetes(k8sAgent(cloud: 'deploy', podTemplate: 'salesforce-' + env.SALESFORCE_VERSION)) }
    options { 
        skipDefaultCheckout()
    }

    environment {
        PROJECT_NAME = 'BBH'
        DOCKER_RUN_PARAMS = "-u root"
        SLACK_URL = "https://sky.slack.com/services/hooks/jenkins-ci/"
        SLACK_DEFAULT_CHANNEL = "ita-bbh"
        PROJECT_URL = "https://github.com/sky-uk/BBH/"
    }

    stages {
        stage("Cleanup workspace") {
            steps {
    
                script {
                    cfg.setLastStage(env.STAGE_NAME)
                }

                cleanWs()
            }
        }

        stage("Init variables") {
            steps {
                script {
                    cfg.setLastStage(env.STAGE_NAME)

                    String slackChannel = SLACK_DEFAULT_CHANNEL

                    SlackInfo slackInfo = new SlackInfo(SLACK_URL, slackChannel.toLowerCase(), slackChannel.toLowerCase())
                    slackNotifier = new SlackNotifier(this, env, currentBuild, slackInfo)

                    Utils util = new Utils(this)
                    String buildUser = util.getBuildUser()
                    cfg.addEntryToMap(MAP_KEY_BUILD_USER_ID, buildUser, true)

                    String slackMessage = ":rocket:\n" +
                            "Build parameters:\n" +
                            "------------------------\n" +
                            "${MAP_KEY_BRANCH_CONFIGURATION}: ${params.BRANCH_CONFIGURATION}\n" +
                            "BUILD_USER_ID: ${buildUser}\n" +
                            "------------------------\n" +
                            "<${env.RUN_DISPLAY_URL}|${MESSAGE_OPEN_BLUE_OCEAN}>"
                    echo("slackMessage-> ${slackMessage}")

                    slackNotifier.notifyBuildStarted(slackMessage)

                    // set input variables to config map
                    cfg.addEntryToMap(MAP_KEY_BRANCH_CONFIGURATION, params.BRANCH_CONFIGURATION, true)

                    process.initVariables()
                }
            }
        }

        stage('Align branches') {
            steps {
                script {
                    cfg.setLastStage(env.STAGE_NAME)

                    process.alignBranches()
            
                }
            }
        }
    }

    post {
        always {
            echo '### FINALLY'
            echo "Configuration->\n\n" + cfg.toString() + "\n"
        }
        success {
            echo '### SUCCESS'
            script {
                slackNotifier.notifyBuildSuccess(process.getSuccessSlackMessage())
            }
        }
        unstable {
            echo '### UNSTABLE'
            script {
                slackNotifier.notifyBuildWarning(process.getUnstableSlackMessage())
            }
        }
        failure {
            echo '### FAILURE'
            script {
                slackNotifier.notifyBuildFailed(process.getFailureSlackMessage())
            }
        }
        aborted {
            echo '### ABORTED'
            script {
                slackNotifier.notifyBuildFailed(process.getAbortSlackMessage())
            }
        }
    }
}
