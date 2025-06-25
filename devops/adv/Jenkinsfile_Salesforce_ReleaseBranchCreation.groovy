#!/usr/bin/env groovy
@Library(value = "jenkins-ci-library@acn_salesforce_kubernetes_pipelines", changelog = false)

import it.sky.dp.jenkins.salesforce.common.Configuration
import it.sky.dp.jenkins.salesforce.common.Utils
import it.sky.dp.jenkins.salesforce.process.SalesforceReleaseBranchCreateProcess
import it.sky.dp.jenkins.slack.SlackInfo
import it.sky.dp.jenkins.slack.SlackNotifier

import static it.sky.dp.jenkins.salesforce.Constants.*

Configuration cfg = Configuration.getInstance()
cfg.setDsl(this)

SalesforceReleaseBranchCreateProcess process = new SalesforceReleaseBranchCreateProcess(this)
SlackNotifier slackNotifier

/* Parametri pipeline (NOME - TIPOLOGIA - VALORI DEFAULT/CHOICE )
Parametro         ;Tipo   ;Valori                                                    ;Default          ;Descrizione
SOURCE_BRANCH     ;String ;                                                          ;master           ;
TARGET_BRANCH     ;String ;                                                          ;                 ;
RELEASE_VERSION   ;String ;                                                          ;                 ;RELEASE_VERSION: the name of the release in the SKY format (i.e. 2020.12.16)

*/

pipeline {
    agent { kubernetes(k8sAgent(cloud: 'deploy', podTemplate: 'baseline')) }
    options {
        skipDefaultCheckout()
    }

    environment {
        PROJECT_NAME = 'adv_slsl'
        DOCKER_RUN_PARAMS = "-u root"
        SLACK_URL = "https://sky.slack.com/services/hooks/jenkins-ci/"
        SLACK_DEFAULT_CHANNEL = "ita-adv"
        PROJECT_URL = "https://github.com/sky-uk/adv_slsl/"
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

                    String slackChannel = "ita-salesforce"
                    SlackInfo slackInfo = new SlackInfo(SLACK_URL, slackChannel.toLowerCase(), slackChannel.toLowerCase())
                    slackNotifier = new SlackNotifier(this, env, currentBuild, slackInfo)

                    Utils util = new Utils(this)
                    String buildUser = util.getBuildUser()
                    cfg.addEntryToMap(MAP_KEY_BUILD_USER_ID, buildUser, true)

                    String slackMessage = ":rocket:\n" +
                            "Build parameters:\n" +
                            "------------------------\n" +
                            "${MAP_KEY_SOURCE_BRANCH_NAME}: ${params.SOURCE_BRANCH}\n" +
                            "${MAP_KEY_TARGET_BRANCH_NAME}: ${params.TARGET_BRANCH}\n" +
                            "${MAP_KEY_RELEASE_VERSION}: ${params.RELEASE_VERSION}\n" +
                            "BUILD_USER_ID: ${buildUser}\n"
                    echo("slackMessage-> ${slackMessage}")

                    slackNotifier.notifyBuildStarted(slackMessage)

                    // set input variables to config map
                    cfg.addEntryToMap(MAP_KEY_JOB_NAME, env.JOB_NAME, true)
                    cfg.addEntryToMap(MAP_KEY_SOURCE_BRANCH_NAME, params.SOURCE_BRANCH, true)
                    cfg.addEntryToMap(MAP_KEY_BRANCH_NAME, params.SOURCE_BRANCH, true)                 
                    cfg.addEntryToMap(MAP_KEY_TARGET_BRANCH_NAME, params.TARGET_BRANCH, true)
                    cfg.addEntryToMap(MAP_KEY_RELEASE_VERSION, params.RELEASE_VERSION, true)
                    cfg.addEntryToMap(MAP_KEY_WORKING_PATH, env.WORKSPACE, true)

                    process.initVariables()
                }
            }
        }

        stage("Checkout source") {
            steps {
                script {
                    cfg.setLastStage(env.STAGE_NAME)

                    process.checkoutSources()
                }
            }
        }

        stage("Create local branch") {
            steps {
                script {
                    cfg.setLastStage(env.STAGE_NAME)

                    process.createLocalBranch()
                }
            }
        }

        stage("Create release folder") {
            steps {
                script {
                    cfg.setLastStage(env.STAGE_NAME)

                    process.creationReleaseFolder()
                }
            }
        }

        stage("Commit and Push") {
            steps {
                script {
                    cfg.setLastStage(env.STAGE_NAME)

                    process.commitAndPush()
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
