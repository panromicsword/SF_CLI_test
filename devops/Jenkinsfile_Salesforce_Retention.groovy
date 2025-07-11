#!/usr/bin/env groovy
@Library(value = "jenkins-ci-library@acn_salesforce_kubernetes_pipelines", changelog=false)

import it.sky.dp.jenkins.salesforce.common.Configuration
import it.sky.dp.jenkins.salesforce.common.Utils
import it.sky.dp.jenkins.salesforce.process.SalesforceRetentionProcess
import it.sky.dp.jenkins.slack.SlackInfo
import it.sky.dp.jenkins.slack.SlackNotifier

import static it.sky.dp.jenkins.salesforce.Constants.*

Configuration cfg = Configuration.getInstance()
cfg.setDsl(this)

SalesforceRetentionProcess process = new SalesforceRetentionProcess(this)
SlackNotifier slackNotifier

/* Parametri pipeline (NOME - TIPOLOGIA - VALORI DEFAULT/CHOICE )
Parametro           ;Tipo                   ;Valori                                                      ;Default          ;Descrizione
DRY_RUN             ;boolean                                                                                               ;DRY_RUN: if set to “true” the job don’t ask for the confirmation and consequently don’t execute the cleanup actions
BRANCHES_TO_EXCLUDE ;multi-line String                                                                                     ;BRANCHES_TO_EXCLUDE: contains the list of branches to exclude (1 per line)
TAGS_TO_EXCLUDE     ;multi-line String                                                                                     ;TAGS_TO_EXCLUDE: contains the list of tags to exclude (1 per line)
*/

pipeline {
    agent { kubernetes(k8sAgent(cloud: 'deploy', podTemplate: 'baseline')) }
    options {
        skipDefaultCheckout()
    }

    environment {
        PROJECT_NAME = 'SFDC_Arcadia'
        DOCKER_RUN_PARAMS = "-u root"
        SLACK_URL = "https://sky.slack.com/services/hooks/jenkins-ci/"
        SLACK_DEFAULT_CHANNEL = "ita-salesforce"
        PROJECT_URL = " https://github.com/sky-uk/SFDC_Arcadia/"
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
                            "${MAP_KEY_DRY_RUN}: ${params.DRY_RUN}\n" +
                            "${MAP_KEY_BRANCHES_TO_EXCLUDE}: ${params.BRANCHES_TO_EXCLUDE}\n" +
                            "${MAP_KEY_TAGS_TO_EXCLUDE}: ${params.TAGS_TO_EXCLUDE}\n" +
                            "BUILD_USER_ID: ${buildUser}\n" +
                            "------------------------\n" +
                            "<${env.RUN_DISPLAY_URL}|${MESSAGE_OPEN_BLUE_OCEAN}>"
                    echo("slackMessage-> ${slackMessage}")

                    slackNotifier.notifyBuildStarted(slackMessage)

                    // set input variables to config map
                    cfg.addEntryToMap(MAP_KEY_BRANCHES_TO_EXCLUDE, params.BRANCHES_TO_EXCLUDE, false)
                    cfg.addEntryToMap(MAP_KEY_TAGS_TO_EXCLUDE, params.TAGS_TO_EXCLUDE, false)
                    cfg.addEntryToMap(MAP_KEY_DRY_RUN, params.DRY_RUN, true)

                    if (params.DRY_RUN) {
                        slackNotifier.notifyBuildWarning(":warning: WARNING: DRY_RUN set to true. the job doesn’t ask for the confirmation and consequently doesn’t execute the cleanup actions")
                    }

                    process.initVariables()
                }
            }
        }

        stage("Checkout Source") {
            steps {
                script {
                    cfg.setLastStage(env.STAGE_NAME)

                    process.checkoutSources()
                }
            }
        }

        stage('Collections elements') {
            steps {
                script {
                    cfg.setLastStage(env.STAGE_NAME)

                    process.collectionsElements()
                }
            }
        }

        stage('Confirm deletion') {
            when {
                expression { !params.DRY_RUN && currentBuild.currentResult == 'SUCCESS' }
            }
            steps {
                script {
                    cfg.setLastStage(env.STAGE_NAME)

                    process.confirmDeletion()
                }
            }
        }

        stage("Deleting elements") {
            when {
                expression { !params.DRY_RUN && currentBuild.currentResult == 'SUCCESS' }
            }
            steps {
                script {
                    cfg.setLastStage(env.STAGE_NAME)

                    process.deleteElements()
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
