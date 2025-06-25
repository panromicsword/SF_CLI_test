#!/usr/bin/env groovy
@Library(value = "jenkins-ci-library@acn_salesforce_kubernetes_pipelines", changelog=false)

import it.sky.dp.jenkins.salesforce.common.Configuration
import it.sky.dp.jenkins.salesforce.common.Utils
import it.sky.dp.jenkins.salesforce.process.SalesforcePRCreateProcess
import it.sky.dp.jenkins.slack.SlackInfo
import it.sky.dp.jenkins.slack.SlackNotifier

import static it.sky.dp.jenkins.salesforce.Constants.*

Configuration cfg = Configuration.getInstance()
cfg.setDsl(this)

SalesforcePRCreateProcess process = new SalesforcePRCreateProcess(this)
SlackNotifier slackNotifier
def repositoryCicd = "SFDC_Arcadia"

/* Parametri pipeline (NOME - TIPOLOGIA - VALORI DEFAULT/CHOICE )
Parametro         ;Tipo                   ;Valori                                                      ;Default          ;Descrizione
TARGET_BRANCH_NAME;String                 ;                                                            ;                 ;TARGET_BRANCH_NAME: the name of the target branch of the pull request
AVAILABLE_PR      ;Active Choices Reactive;Vedi script                                                 ;FALSE            ;AVAILABLE_PR: available pull request for target branch
TARGET_ENVIRONMENT;Choice                 ;"NONE,IT,ST,TST5,AM,UAT,ACNDEVOPS"                          ;NONE             ;TARGET_ENVIRONMENT: the target environment where the pull request will be validated
TEST_LEVEL        ;Choice                 ;"RunSpecifiedTests,NoTestRun,RunLocalTests,RunAllTestsInOrg";RunSpecifiedTests;TEST_LEVEL: the test level to use during the pull request validation
RELEASE_VERSION   ;String                 ;                                                            ;2021.03.04       ;RELEASE_VERSION: the name of the release in the SKY format (i.e. 2020.12.16)
*/

pipeline {
    agent { kubernetes(k8sAgent(cloud: 'deploy', podTemplate: 'salesforce-' + env.SALESFORCE_VERSION)) }
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
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)
                    }

                    cleanWs()
                }
            }
        }

        stage("Init variables") {
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
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
                                "${MAP_KEY_PR_DESCRIPTION}: ${params.PR_DESCRIPTION}\n" +
                                "${MAP_KEY_DRY_RUN}: ${params.DRY_RUN}\n" +
                                "${MAP_KEY_SKIP_INFO_COMMIT}: ${params.SKIP_INFO_COMMIT}\n" +
                                "BUILD_USER_ID: ${buildUser}\n" +
                                "------------------------\n" +
                                "<${env.RUN_DISPLAY_URL}|${MESSAGE_OPEN_BLUE_OCEAN}>"
                        echo("slackMessage-> ${slackMessage}")

                        //slackNotifier.notifyBuildStarted(slackMessage)

                        // set input variables to config map
                        cfg.addEntryToMap(MAP_KEY_SOURCE_BRANCH_NAME, params.SOURCE_BRANCH_NAME, true)
                        cfg.addEntryToMap(MAP_KEY_TARGET_BRANCH_NAME, params.TARGET_BRANCH_NAME, true)
                        cfg.addEntryToMap(MAP_KEY_TARGET_ENVIRONMENT, params.TARGET_ENVIRONMENT, true)
                        cfg.addEntryToMap(MAP_KEY_TEST_LEVEL, params.TEST_LEVEL, true)
                        cfg.addEntryToMap(MAP_KEY_SKIP_STORE, params.SKIP_STORE, true)
                        cfg.addEntryToMap(MAP_KEY_RELEASE_VERSION, params.RELEASE_VERSION, true)
                        cfg.addEntryToMap(MAP_KEY_SKIP_SCA, params.SKIP_SCA, true)
                        cfg.addEntryToMap(MAP_KEY_PR_DESCRIPTION, params.PR_DESCRIPTION, true)
                        cfg.addEntryToMap(MAP_KEY_DRY_RUN, params.DRY_RUN, true)

                        //added new input parameter
                        cfg.addEntryToMap(MAP_KEY_SKIP_INFO_COMMIT, params.SKIP_INFO_COMMIT, true)

                        if (TEST_RUN_NONE.equals(params.TEST_LEVEL)) {
                            slackNotifier.notifyBuildWarning(":warning: WARNING: No test run has been selected by the user")
                        }

                        process.initVariables()
                    }
                }
            }
        }

        stage("Checkout Source") {
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        process.checkoutSources()
                    }
                }
            }
        }

        stage('Check Status') {
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        process.checkStatus()
                    }
                }
            }
        }

        stage('Pre-Validate') {
            when {
                expression { currentBuild.currentResult == 'SUCCESS' }
            }
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        process.preValidate()
                    }
                }
            }
        }

        stage("Convert source") {
            when {
                expression { cfg.getMapValue("DELTA_FORCEAPP_EXISTS") && currentBuild.currentResult == 'SUCCESS' }
            }
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        process.convertSources()
                    }
                }
            }
        }

        stage("Pre-MP Check") {
            when {
                expression { cfg.getMapValue("DELTA_PRE_MANUAL_PROCEDURES_EXISTS") && currentBuild.currentResult == 'SUCCESS' }
            }
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        slackNotifier.notifyBuildWarning(":question: Please perform all manual procedures for the step PRE " + params.TARGET_ENVIRONMENT)

                        process.preMPCheck()
                    }
                }
            }
        }

        stage("Validate") {
            when {
                expression { cfg.getMapValue("DELTA_FORCEAPP_EXISTS") && currentBuild.currentResult == 'SUCCESS' }
            }
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        script {
                            cfg.setLastStage(env.STAGE_NAME)

                            process.validate()
                        }
                    }
                }
            }
        }

        stage("Quality") {
            when {
                expression { (cfg.getMapValue("DELTA_FORCEAPP_EXISTS") || cfg.getMapValue("VLOCITY_DELTA_EXISTS"))}
            }
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        process.quality()
                    }
                }
            }
        }

        stage("Store") {
            when {
                expression { !params.SKIP_STORE }
            }
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        process.storeArtifact()
                    }
                }
            }
        }

        stage("Create Pull Request") {
            when {
                expression { !params.DRY_RUN && currentBuild.currentResult == 'SUCCESS' }
            }
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        process.createPullRequest()
                    }
                }
            }
        }
    }

    post {
        always {
            echo '### FINALLY'
            echo "Configuration->\n\n" + cfg.toString() + "\n"
            script {
                process.updateElasticSearch()
            }
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