#!/usr/bin/env groovy
@Library(value = "jenkins-ci-library@acn_salesforce_kubernetes_pipelines", changelog = false)

import it.sky.dp.jenkins.salesforce.common.Configuration
import it.sky.dp.jenkins.salesforce.common.Utils
import it.sky.dp.jenkins.salesforce.process.SalesforceValidateProdSchedulerProcess
import it.sky.dp.jenkins.slack.SlackInfo
import it.sky.dp.jenkins.slack.SlackNotifier

import static it.sky.dp.jenkins.salesforce.Constants.*

Configuration cfg = Configuration.getInstance()
cfg.setDsl(this)

SalesforceValidateProdSchedulerProcess process = new SalesforceValidateProdSchedulerProcess(this)
SlackNotifier slackNotifier

/* Parametri pipeline (NOME - TIPOLOGIA - VALORI DEFAULT/CHOICE )
Parametro         ;Tipo   ;Valori;Default          ;Descrizione
SOURCE_BRANCH_NAME;String ;      ;FULL_2021.03.04  ;SOURCE_BRANCH_NAME: the name of the source branch of the pull request
TARGET_BRANCH_NAME;String ;      ;master           ;TARGET_BRANCH_NAME: the name of the target branch of the pull request
TARGET_ENVIRONMENT;String ;      ;PRODRYRUN        ;TARGET_ENVIRONMENT: the target environment where the pull request will be validated
TEST_LEVEL        ;String ;      ;RunSpecifiedTests;TEST_LEVEL: the test level to use during the pull request validation
RELEASE_VERSION   ;String ;      ;2021.03.04       ;RELEASE_VERSION: the name of the release in the SKY format (i.e. 2020.12.16)
SKIP_SCA          ;Boolean;      ;TRUE             ;SKIP_SCA: set 'true' to skip static code analysis
SKIP_STORE        ;Boolean;      ;TRUE             ;SKIP_STORE: set 'true' to skip zip package creation on Nexus
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
        PROJECT_URL = "https://github.com/sky-uk/adv_slsl"
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
                    if (!"NONE".equals(params.TARGET_ENVIRONMENT)) {
                        slackChannel += "-" + params.TARGET_ENVIRONMENT
                    }
                    SlackInfo slackInfo = new SlackInfo(SLACK_URL, slackChannel.toLowerCase(), slackChannel.toLowerCase())
                    slackNotifier = new SlackNotifier(this, env, currentBuild, slackInfo)

                    Utils util = new Utils(this)
                    String buildUser = util.getBuildUser()
                    cfg.addEntryToMap(MAP_KEY_BUILD_USER_ID, buildUser, true)

                    String slackMessage = ":rocket:\n" +
                            "Build parameters:\n" +
                            "------------------------\n" +
                            "${MAP_KEY_SOURCE_BRANCH_NAME}: ${params.SOURCE_BRANCH_NAME}\n" +
                            "${MAP_KEY_TARGET_BRANCH_NAME}: ${params.TARGET_BRANCH_NAME}\n" +
                            "${MAP_KEY_TARGET_ENVIRONMENT}: ${params.TARGET_ENVIRONMENT}\n" +
                            "${MAP_KEY_TEST_LEVEL}: ${params.TEST_LEVEL}\n" +
                            "${MAP_KEY_SKIP_STORE}: ${params.SKIP_STORE}\n" +
                            "${MAP_KEY_RELEASE_VERSION}: ${params.RELEASE_VERSION}\n" +
                            "${MAP_KEY_SKIP_SCA}: ${params.SKIP_SCA}\n" +
                            "BUILD_USER_ID: ${buildUser}\n" +
                            "------------------------\n" +
                            "<${env.RUN_DISPLAY_URL}|${MESSAGE_OPEN_BLUE_OCEAN}>"
                    echo("slackMessage-> ${slackMessage}")

                    slackNotifier.notifyBuildStarted(slackMessage)

                    // set input variables to config map
                    cfg.addEntryToMap(MAP_KEY_SOURCE_BRANCH_NAME, params.SOURCE_BRANCH_NAME, true)
                    cfg.addEntryToMap(MAP_KEY_TARGET_BRANCH_NAME, params.TARGET_BRANCH_NAME, true)
                    cfg.addEntryToMap(MAP_KEY_TARGET_ENVIRONMENT, params.TARGET_ENVIRONMENT, true)
                    cfg.addEntryToMap(MAP_KEY_TEST_LEVEL, params.TEST_LEVEL, true)
                    cfg.addEntryToMap(MAP_KEY_SKIP_STORE, params.SKIP_STORE, true)
                    cfg.addEntryToMap(MAP_KEY_RELEASE_VERSION, params.RELEASE_VERSION, true)
                    cfg.addEntryToMap(MAP_KEY_SKIP_SCA, params.SKIP_SCA, true)

                    if (TEST_RUN_NONE.equals(params.TEST_LEVEL)) {
                        slackNotifier.notifyBuildWarning(":warning: WARNING: No test run has been selected by the user")
                    }

                    process.initVariables()
                } 
            }
        }

        stage("Schedule job") {
            script {
                cfg.setLastStage(env.STAGE_NAME)

                build wait: false, job: 'ita-salesforce-PR-Creation_PROD_CM',
                    parameters: [
                        string(name: 'SOURCE_BRANCH_NAME', value: params.SOURCE_BRANCH_NAME),
                        string(name: 'TARGET_BRANCH_NAME', value: params.TARGET_BRANCH_NAME),
                        string(name: 'TARGET_ENVIRONMENT', value: params.TARGET_ENVIRONMENT),
                        string(name: 'TEST_LEVEL', value: params.TEST_LEVEL),
                        string(name: 'RELEASE_VERSION', value: params.RELEASE_VERSION),
                        booleanParam(name: 'SKIP_STORE', value: params.SKIP_STORE),
                        booleanParam(name: 'SKIP_SCA', value: params.SKIP_SCA),
                        text(name: 'PR_DESCRIPTION', value: 'dummy'),
                        booleanParam(name: 'DRY_RUN', value: true)
                    ]
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
