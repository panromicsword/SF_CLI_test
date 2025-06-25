#!/usr/bin/env groovy
@Library(value = "jenkins-ci-library@acn_salesforce_kubernetes_pipelines", changelog=false)

import it.sky.dp.jenkins.salesforce.common.Configuration
import it.sky.dp.jenkins.salesforce.common.Utils
import it.sky.dp.jenkins.salesforce.process.SalesforcePRAcceptanceProcess
import it.sky.dp.jenkins.slack.SlackInfo
import it.sky.dp.jenkins.slack.SlackNotifier

import static it.sky.dp.jenkins.salesforce.Constants.*

Configuration cfg = Configuration.getInstance()
cfg.setDsl(this)

SalesforcePRAcceptanceProcess process = new SalesforcePRAcceptanceProcess(this)
SlackNotifier slackNotifier

/* Parametri pipeline (NOME - TIPOLOGIA - VALORI DEFAULT/CHOICE )
Parametro         ;Tipo                   ;Valori                                                      ;Default          ;Descrizione
TARGET_BRANCH_NAME;String                 ;                                                            ;                 ;TARGET_BRANCH_NAME: the name of the target branch of the pull request
TARGET_ENVIRONMENT;Choice                 ;"NONE,IT,ST,TST5,AM,UAT,ACNDEVOPS"                          ;NONE             ;TARGET_ENVIRONMENT: the target environment where the pull request will be validated
TEST_LEVEL        ;Choice                 ;"RunSpecifiedTests,NoTestRun,RunLocalTests,RunAllTestsInOrg";RunSpecifiedTests;TEST_LEVEL: the test level to use during the pull request validation
RELEASE_VERSION   ;String                 ;                                                            ;2021.03.04       ;RELEASE_VERSION: the name of the release in the SKY format (i.e. 2020.12.16)
SOURCE_BRANCH_NAME;String                 ;                                                            ;                 ;SOURCE_BRANCH_NAME: the name of the target branch of the pull request (Optional)
SKIP_VALIDATE     ;Boolean                ;                                                            ;False            ;SKIP_VALIDATE: set 'true' to disable the validation step
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

                        cfg.addEntryToMap(MAP_KEY_TARGET_BRANCH_NAME, params.TARGET_BRANCH_NAME, true)
                        cfg.addEntryToMap(MAP_KEY_SOURCE_BRANCH_NAME, params.SOURCE_BRANCH_NAME, false)
                        process.preInitVariables()

                        String prMerge = cfg.getMapValue(MAP_KEY_AVAILABLE_PRS_MERGE)
                        String prNoMerge = cfg.getMapValue(MAP_KEY_AVAILABLE_PRS_NOMERGE)

                        String inputMessage = "Please select one or more pull requests between availables."
                        if (prNoMerge?.trim()) {
                            inputMessage += "\n\n Here listed pull requests not mergeable: " + prNoMerge
                        }

                        def availablePRs = ""
                        if (!params.SOURCE_BRANCH_NAME?.trim()) {
                            def inputParams = input message: inputMessage, ok: 'MERGE',
                                parameters: [
                                    extendedChoice(
                                        description: '',
                                        multiSelectDelimiter: ',',
                                        name: 'AVAILABLE_PR',
                                        quoteValue: false,
                                        saveJSONParameterToFile: false,
                                        type: 'PT_CHECKBOX',
                                        value: prMerge,
                                        visibleItemCount: 20
                                    )
                                ]
                            availablePRs = inputParams
                        } else {
                            availablePRs = prMerge
                        }
                        echo "---> " + availablePRs.toString()

                        Utils util = new Utils(this)
                        String buildUser = util.getBuildUser()
                        cfg.addEntryToMap(MAP_KEY_BUILD_USER_ID, buildUser, true)

                        String slackMessage = ":rocket:\n" +
                                "Build parameters:\n" +
                                "------------------------\n" +
                                "${MAP_KEY_BRANCH_NAME}: ${params.TARGET_BRANCH_NAME}\n" +
                                "${MAP_KEY_TARGET_BRANCH_NAME}: ${params.TARGET_BRANCH_NAME}\n" +
                                "${MAP_KEY_TARGET_ENVIRONMENT}: ${params.TARGET_ENVIRONMENT}\n" +
                                "${MAP_KEY_TEST_LEVEL}: ${params.TEST_LEVEL}\n" +
                                "${MAP_KEY_RELEASE_VERSION}: ${params.RELEASE_VERSION}\n" +
                                "${MAP_KEY_SOURCE_BRANCH_NAME}: ${params.SOURCE_BRANCH_NAME}\n" +
                                "${MAP_KEY_AVAILABLE_PRS}: ${availablePRs}\n" +
                                "${MAP_KEY_SKIP_VALIDATE}: ${params.SKIP_VALIDATE}\n" +
                                "BUILD_USER_ID: ${buildUser}\n" +
                                "------------------------\n" +
                                "<${env.RUN_DISPLAY_URL}|${MESSAGE_OPEN_BLUE_OCEAN}>"
                        echo("slackMessage-> ${slackMessage}")

                        slackNotifier.notifyBuildStarted(slackMessage)

                        // set input variables to config map
                        cfg.addEntryToMap(MAP_KEY_TARGET_ENVIRONMENT, params.TARGET_ENVIRONMENT, true)
                        cfg.addEntryToMap(MAP_KEY_TEST_LEVEL, params.TEST_LEVEL, true)
                        cfg.addEntryToMap(MAP_KEY_RELEASE_VERSION, params.RELEASE_VERSION, true)
                        cfg.addEntryToMap(MAP_KEY_AVAILABLE_PRS, availablePRs, true)
                        cfg.addEntryToMap(MAP_KEY_SKIP_VALIDATE, params.SKIP_VALIDATE, true)

                        if (params.SKIP_VALIDATE) {
                            slackNotifier.notifyBuildWarning(":warning: WARNING: Skip validation has been selected by the user")
                        } else {
                            if (TEST_RUN_NONE.equals(params.TEST_LEVEL)) {
                                slackNotifier.notifyBuildWarning(":warning: WARNING: No test run has been selected by the user")
                            }
                        }

                        process.initVariables()
                    }
                }
            }
        }

        stage("Checkout source") {
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        process.checkoutSources()
                    }
                }
            }
        }

        stage("Load Configs") {
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        process.loadConfigs()
                    }
                }
            }
        }

        stage("Merge Pull Request") {
            steps {
                container('salesforce-' + env.SALESFORCE_VERSION){
                    script {
                        cfg.setLastStage(env.STAGE_NAME)

                        process.mergePullRequest()
                    }
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
